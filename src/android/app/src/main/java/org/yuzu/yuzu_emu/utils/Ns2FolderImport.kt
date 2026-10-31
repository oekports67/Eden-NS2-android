// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.documentfile.provider.DocumentFile
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.yuzu.yuzu_emu.getPublicFilesDir

/**
 * Stage a selected SAF ExeFS + optional RomFS tree in app-controlled storage.
 *
 * A content:// document has no reliable POSIX parent directory, so passing only
 * exefs/main to the native RealVfsFile loses the neighbouring RomFS and NSOs.
 * The original source remains untouched. An interrupted import is never reused.
 */
object Ns2FolderImport {
    enum class Phase { SCANNING, COPYING, VERIFYING, CACHED, COMPLETE }

    data class ImportProgress(
        val phase: Phase,
        val filesCopied: Int = 0,
        val totalFiles: Int = 0,
        val bytesCopied: Long = 0,
        val totalBytes: Long = 0,
        val currentFile: String = "",
        val percentage: Int? = null,
    )

    data class ImportedGame(
        val mainPath: String,
        val romfsIncluded: Boolean,
        val reused: Boolean = false,
    )

    private const val MARKER_VERSION = "EDEN_NS2_IMPORT_V3_DUAL_TREE"
    private const val BUFFER_SIZE = 256 * 1024
    private const val MAX_DEPTH = 64

    private data class SourceFile(val source: DocumentFile, val path: String, val size: Long,
                                  val modified: Long)

    private class Plan {
        val folders = linkedSetOf<String>()
        val files = arrayListOf<SourceFile>()
        var romfsIncluded = false
        var completeMetadata = true
        var totalBytes = 0L
    }

    /** Call on Dispatchers.IO. Progress callbacks may suspend (e.g. switch to Main). */
    suspend fun import(
        context: Context,
        selectedExeFsUri: Uri,
        selectedRomFsUri: Uri? = null,
        onProgress: suspend (ImportProgress) -> Unit = {},
    ): ImportedGame {
        currentCoroutineContext().ensureActive()
        onProgress(ImportProgress(Phase.SCANNING))
        val picked = DocumentFile.fromTreeUri(context, selectedExeFsUri)
            ?: throw IOException("The selected folder could not be opened")
        if (!picked.isDirectory) throw IOException("Select a folder, not a file")

        val exefs = if (picked.findFile("main")?.isFile == true) picked
                    else picked.findFile("exefs")
        if (exefs == null || !exefs.isDirectory) {
            throw IOException("exefs/main not found. Select the game root or the exefs folder")
        }
        val main = exefs.findFile("main")?.takeIf { it.isFile }
            ?: throw IOException("exefs/main not found in the selected folder")
        val magic = ByteArray(4)
        context.contentResolver.openInputStream(main.uri)?.use { stream ->
            try {
                DataInputStream(stream).readFully(magic)
            } catch (_: java.io.EOFException) {
                throw IOException("exefs/main is truncated")
            }
        } ?: throw IOException("Cannot read exefs/main. Check access to the selected folder")
        if (!magic.contentEquals("NSO0".toByteArray(Charsets.US_ASCII))) {
            throw IOException("exefs/main is not an NSO (missing NSO0 header)")
        }

        val plan = Plan()
        suspend fun scan(dir: DocumentFile, relative: String, depth: Int) {
            currentCoroutineContext().ensureActive()
            if (depth > MAX_DEPTH) throw IOException("Too many nested directories")
            plan.folders.add(relative)
            for (child in dir.listFiles()) {
                currentCoroutineContext().ensureActive()
                val name = safeName(child.name)
                // A RomFS image is a file, not a loose RomFS directory: the
                // native loader expects it next to exefs/, not inside romfs/.
                val path = if (relative == "romfs" && child.isFile &&
                    name.endsWith(".romfs", ignoreCase = true)) name
                    else "$relative/$name"
                if (child.isDirectory) {
                    scan(child, path, depth + 1)
                } else if (child.isFile) {
                    addFile(plan, child, path)
                } else {
                    throw IOException("Cannot read a file in $relative")
                }
            }
        }

        scan(exefs, "exefs", 0)
        // The second SAF picker can point to a RomFS directory that is completely
        // separate from the ExeFS tree. Only the selected trees are read.
        // Also accept the historical single-root layout when RomFS was skipped.
        val romfsSelection = if (selectedRomFsUri != null) {
            DocumentFile.fromTreeUri(context, selectedRomFsUri)
                ?: throw IOException("The RomFS folder could not be opened")
        } else if (picked.uri != exefs.uri) picked else null
        if (romfsSelection != null) {
            if (!romfsSelection.isDirectory) throw IOException("Select a RomFS folder")
            if (romfsSelection.findFile("main")?.isFile == true ||
                romfsSelection.name.equals("exefs", ignoreCase = true)) {
                throw IOException("You selected ExeFS again. Choose the RomFS folder")
            }
            val nested = romfsSelection.findFile("romfs")
            val selectedGameRoot = romfsSelection.findFile("exefs")?.isDirectory == true
            // Selecting a game root with a separate *.romfs image is valid,
            // but the whole root must never be copied into romfs/ (that would
            // recursively copy the ExeFS tree as game data).
            val romfsDir = when {
                nested?.isDirectory == true -> nested
                selectedGameRoot -> null
                else -> romfsSelection
            }
            if (nested?.isFile == true && nested.name == "romfs") {
                addFile(plan, nested, "romfs.romfs")
            }
            if (romfsDir != null && romfsDir.uri != exefs.uri) {
                scan(romfsDir, "romfs", 0)
            }
            // *.romfs, NACP and icon files can live at the selected root.
            // Avoid duplicating entries already found in the RomFS directory.
            for (entry in romfsSelection.listFiles()) {
                currentCoroutineContext().ensureActive()
                if (!entry.isFile || entry.uri == nested?.uri) continue
                val name = safeName(entry.name)
                if (name.endsWith(".romfs", ignoreCase = true) ||
                    name.endsWith(".nacp", ignoreCase = true) ||
                    name.startsWith("icon_", ignoreCase = true)) {
                    if (plan.files.none { it.source.uri == entry.uri }) {
                        addFile(plan, entry, name)
                    }
                }
            }
            if (selectedRomFsUri != null && selectedGameRoot && romfsDir == null &&
                plan.files.none { it.path.endsWith(".romfs", ignoreCase = true) }) {
                throw IOException("No RomFS found in the selected game folder")
            }
        }
        plan.romfsIncluded = plan.files.any {
            it.path.startsWith("romfs/") || it.path.endsWith(".romfs", ignoreCase = true)
        }
        if (selectedRomFsUri != null && !plan.romfsIncluded) {
            throw IOException("Selected RomFS folder contains no readable files")
        }
        if (plan.files.none { it.path == "exefs/main" }) {
            throw IOException("Cannot locate exefs/main")
        }

        // Stable fingerprint includes all paths, file metadata and the chosen tree.
        // SAF providers sometimes report size/mtime as 0. In that case do not reuse
        // an old import, because we cannot establish that the source is unchanged.
        val digest = MessageDigest.getInstance("SHA-256")
        fun hash(value: String) = digest.update(value.toByteArray(Charsets.UTF_8))
        hash("$MARKER_VERSION\nExeFS:$selectedExeFsUri\nRomFS:${selectedRomFsUri ?: "auto"}\n")
        plan.folders.sorted().forEach { hash("D:$it\n") }
        plan.files.sortedBy { it.path }.forEach {
            hash("F:${it.path}:${it.size}:${it.modified}\n")
        }
        val fingerprint = digest.digest().joinToString("") { "%02x".format(it) }
        val base = File(context.getPublicFilesDir(), "ns2_imports")
        if (!base.isDirectory && !base.mkdirs()) throw IOException("Cannot create import directory")
        val key = MessageDigest.getInstance("SHA-256")
            .digest("$selectedExeFsUri\n${selectedRomFsUri ?: "auto"}".toByteArray(Charsets.UTF_8))
            .take(10).joinToString("") { "%02x".format(it) }
        val dest = File(base, key)
        val mainDest = File(dest, "exefs/main")
        val marker = File(dest, ".ns2_import_complete")

        if (plan.completeMetadata && marker.isFile && mainDest.isFile &&
            marker.readText() == "$MARKER_VERSION\n$fingerprint\n" && verifyFiles(dest, plan)) {
            onProgress(ImportProgress(Phase.CACHED, plan.files.size, plan.files.size,
                                      plan.totalBytes, plan.totalBytes, percentage = 100))
            return ImportedGame(mainDest.absolutePath, plan.romfsIncluded, reused = true)
        }

        val available = base.usableSpace
        // This checks the stated file sizes. Providers reporting unknown sizes may
        // still exhaust storage; IO errors are surfaced instead of an incomplete boot.
        if (available > 0 && plan.totalBytes > available - 64L * 1024 * 1024) {
            throw IOException("Not enough free space to import game (need at least " +
                              "${plan.totalBytes / (1024 * 1024)} MiB)")
        }

        // Earlier failed attempts must not be recognized as completed imports.
        base.listFiles()?.filter {
            it.name.startsWith(".$key-") && it.name.endsWith(".partial")
        }?.forEach { it.deleteRecursively() }
        val temp = File(base, ".$key-${System.nanoTime()}.partial")
        if (!temp.mkdirs()) throw IOException("Cannot create temporary import folder")
        val expectedSizes = HashMap<String, Long>()
        var copiedBytes = 0L
        var copiedFiles = 0
        var lastUpdate = 0L
        val sizeBasedProgress = plan.completeMetadata && plan.totalBytes > 0L

        suspend fun progress(force: Boolean, file: String = "") {
            val now = SystemClock.elapsedRealtime()
            if (!force && now - lastUpdate < 180L) return
            lastUpdate = now
            val percentage = if (sizeBasedProgress) {
                // 100% only after all output files have been verified.
                ((copiedBytes.coerceAtMost(plan.totalBytes) * 99) / plan.totalBytes).toInt()
            } else null
            onProgress(ImportProgress(Phase.COPYING, copiedFiles, plan.files.size,
                                      copiedBytes, plan.totalBytes, file, percentage))
        }

        try {
            for (folder in plan.folders) {
                currentCoroutineContext().ensureActive()
                if (!File(temp, folder).isDirectory && !File(temp, folder).mkdirs()) {
                    throw IOException("Cannot create $folder in import directory")
                }
            }
            progress(force = true)
            for (entry in plan.files) {
                currentCoroutineContext().ensureActive()
                val target = File(temp, entry.path)
                val input = context.contentResolver.openInputStream(entry.source.uri)
                    ?: throw IOException("Cannot read ${entry.path} from selected folder")
                var written = 0L
                input.use { stream ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = stream.read(buffer)
                            if (count == -1) break
                            if (count == 0) continue
                            output.write(buffer, 0, count)
                            written += count
                            copiedBytes += count
                            progress(force = false, file = entry.path)
                        }
                    }
                }
                if (entry.size > 0 && entry.size != written) {
                    throw IOException("File size changed while importing ${entry.path}")
                }
                if (target.length() != written) throw IOException("Incomplete copy: ${entry.path}")
                expectedSizes[entry.path] = written
                copiedFiles++
                progress(force = true, file = entry.path)
            }
            onProgress(ImportProgress(Phase.VERIFYING, copiedFiles, plan.files.size,
                                      copiedBytes, plan.totalBytes))
            for ((relative, size) in expectedSizes) {
                currentCoroutineContext().ensureActive()
                val output = File(temp, relative)
                if (!output.isFile || output.length() != size) {
                    throw IOException("Imported file missing or truncated: $relative")
                }
            }
            // Verify the imported main header, not just the original source header.
            File(temp, "exefs/main").inputStream().use { stream ->
                val header = ByteArray(4)
                DataInputStream(stream).readFully(header)
                if (!header.contentEquals(magic)) throw IOException("Copied NSO header mismatch")
            }
            File(temp, ".ns2_import_complete").writeText("$MARKER_VERSION\n$fingerprint\n")

            // Atomic replacement with rollback if final rename fails. Avoid losing
            // the previous complete import when a new import cannot be installed.
            val backup = File(base, ".$key-${System.nanoTime()}.backup")
            val hadPrevious = dest.exists()
            if (hadPrevious && !dest.renameTo(backup)) {
                throw IOException("Cannot replace previous import. Close running games")
            }
            if (!temp.renameTo(dest)) {
                if (hadPrevious) backup.renameTo(dest)
                throw IOException("Cannot finalize imported game folder")
            }
            if (hadPrevious) backup.deleteRecursively()
            onProgress(ImportProgress(Phase.COMPLETE, copiedFiles, plan.files.size,
                                      copiedBytes, plan.totalBytes, percentage = 100))
            return ImportedGame(File(dest, "exefs/main").absolutePath, plan.romfsIncluded)
        } finally {
            if (temp.exists()) temp.deleteRecursively()
        }
    }

    private fun addFile(plan: Plan, source: DocumentFile, path: String) {
        val size = source.length()
        val modified = source.lastModified()
        if (size <= 0 || modified <= 0) plan.completeMetadata = false
        plan.files.add(SourceFile(source, path, size, modified))
        if (size > 0) {
            if (Long.MAX_VALUE - plan.totalBytes < size) {
                throw IOException("The selected game is too large")
            }
            plan.totalBytes += size
        }
    }

    private suspend fun verifyFiles(folder: File, plan: Plan): Boolean {
        for (entry in plan.files) {
            currentCoroutineContext().ensureActive()
            val target = File(folder, entry.path)
            if (!target.isFile || target.length() != entry.size) return false
        }
        return true
    }

    private fun safeName(name: String?): String {
        if (name.isNullOrBlank() || name == "." || name == ".." ||
            name.contains('/') || name.contains('\\') || name.contains('\u0000')) {
            throw IOException("Invalid file or directory name inside game folder")
        }
        return name
    }
}
