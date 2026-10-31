// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later
package org.yuzu.yuzu_emu.utils

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.yuzu.yuzu_emu.getPublicFilesDir

/**
 * Faster SAF import of extracted NS2 games, designed for 200,000+ loose files.
 * Uses bulk directory queries, bounded concurrent copying and throttled progress.
 * Does not alter input; stages atomically; cancellation cleans partial output.
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
        val scannedFiles: Int = 0,
        val scannedFolders: Int = 0,
        val bytesPerSecond: Long = 0,
    )
    data class ImportedGame(
        val mainPath: String,
        val romfsIncluded: Boolean,
        val reused: Boolean = false,
    )

    // Same version/fingerprint as old importer, allowing safe reuse of complete cache.
    private const val MARKER_VERSION = "EDEN_NS2_IMPORT_V3_DUAL_TREE"
    private const val MAX_DEPTH = 64
    private const val WORKERS = 4
    private const val BUFFER_SIZE = 512 * 1024
    private const val SCAN_REPORT_MS = 500L
    private const val COPY_REPORT_MS = 350L
    private val PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )

    private data class Entry(
        val id: String, val name: String, val mime: String,
        val size: Long, val modified: Long,
    ) {
        val isDir: Boolean get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
    }
    private data class Src(
        val tree: Uri, val docId: String, val path: String,
        val size: Long, val modified: Long,
    )
    private data class Dir(val id: String, val path: String, val depth: Int)
    private data class Selection(val tree: Uri, val id: String)
    private class Plan {
        val dirs = linkedSetOf<String>()
        val files = ArrayList<Src>()
        private val paths = HashSet<String>()
        var totalBytes = 0L
        var metadataComplete = true
        var romfsIncluded = false

        fun add(tree: Uri, e: Entry, path: String) {
            if (!paths.add(path)) throw IOException("Duplicate source path: $path")
            if (e.size <= 0L || e.modified <= 0L) metadataComplete = false
            files.add(Src(tree, e.id, path, e.size, e.modified))
            if (e.size > 0L) {
                if (totalBytes > Long.MAX_VALUE - e.size) throw IOException("Game is too large")
                totalBytes += e.size
            }
            if (path.startsWith("romfs/") || path.endsWith(".romfs", ignoreCase = true)) {
                romfsIncluded = true
            }
        }
    }

    /** One bulk ContentResolver query per folder; no DocumentFile object per child. */
    private suspend fun children(
        context: Context, tree: Uri, folderId: String,
        action: suspend (Entry) -> Unit,
    ) {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId)
        val cursor = context.contentResolver.query(uri, PROJECTION, null, null, null)
            ?: throw IOException("Cannot list folder: SAF provider returned no cursor")
        cursor.use { c ->
            val idCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val typeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val timeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            if (idCol < 0 || nameCol < 0 || typeCol < 0) {
                throw IOException("SAF document provider lacks required metadata")
            }
            val job = currentCoroutineContext()
            var n = 0
            while (c.moveToNext()) {
                if (++n % 256 == 0) job.ensureActive()
                val id = c.getString(idCol) ?: throw IOException("Missing SAF document ID")
                val name = safeName(c.getString(nameCol))
                val mime = c.getString(typeCol) ?: throw IOException("Missing SAF MIME type")
                val size = if (sizeCol >= 0 && !c.isNull(sizeCol)) c.getLong(sizeCol) else 0L
                val mtime = if (timeCol >= 0 && !c.isNull(timeCol)) c.getLong(timeCol) else 0L
                action(Entry(id, name, mime, size, mtime))
            }
        }
    }

    private suspend fun named(
        context: Context, root: Selection, vararg wanted: String,
    ): Map<String, Entry> {
        val result = HashMap<String, Entry>()
        val names = wanted.toSet()
        children(context, root.tree, root.id) { e -> if (e.name in names) result[e.name] = e }
        return result
    }

    private fun folderName(context: Context, root: Selection): String? {
        val uri = DocumentsContract.buildDocumentUriUsingTree(root.tree, root.id)
        return context.contentResolver.query(
            uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
        )?.use { c ->
            val col = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (col >= 0 && c.moveToFirst()) c.getString(col) else null
        }
    }

    private suspend fun scan(
        context: Context, tree: Uri, rootId: String, prefix: String, plan: Plan,
        fileCount: AtomicInteger, dirCount: AtomicInteger,
        report: suspend (Boolean) -> Unit,
    ) {
        val queue = ArrayDeque<Dir>()
        queue.addLast(Dir(rootId, prefix, 0))
        while (!queue.isEmpty()) {
            currentCoroutineContext().ensureActive()
            val folder = queue.removeFirst()
            if (folder.depth > MAX_DEPTH) throw IOException("Too many nested directories")
            if (!plan.dirs.add(folder.path)) throw IOException("Duplicate directory: ${folder.path}")
            dirCount.incrementAndGet()
            children(context, tree, folder.id) { e ->
                val path = when {
                    folder.path == "romfs" && !e.isDir && e.name == "romfs" -> "romfs.romfs"
                    folder.path == "romfs" && !e.isDir &&
                        e.name.endsWith(".romfs", true) -> e.name
                    else -> "${folder.path}/${e.name}"
                }
                if (e.isDir) queue.addLast(Dir(e.id, path, folder.depth + 1))
                else {
                    plan.add(tree, e, path)
                    if (fileCount.incrementAndGet() % 128 == 0) report(false)
                }
            }
            report(false)
        }
    }

    suspend fun import(
        context: Context,
        selectedExeFsUri: Uri,
        selectedRomFsUri: Uri? = null,
        onProgress: suspend (ImportProgress) -> Unit = {},
    ): ImportedGame {
        currentCoroutineContext().ensureActive()
        val plan = Plan()
        val filesFound = AtomicInteger(0)
        val dirsFound = AtomicInteger(0)
        var lastScanReport = 0L
        suspend fun reportScan(force: Boolean) {
            val now = SystemClock.elapsedRealtime()
            if (!force && now - lastScanReport < SCAN_REPORT_MS) return
            lastScanReport = now
            onProgress(ImportProgress(
                Phase.SCANNING, scannedFiles = filesFound.get(), scannedFolders = dirsFound.get()
            ))
        }
        reportScan(true)

        val selection = Selection(selectedExeFsUri, DocumentsContract.getTreeDocumentId(selectedExeFsUri))
        val exRoot = named(context, selection, "main", "exefs")
        val exeId = if (exRoot["main"]?.isDir == false) selection.id
            else exRoot["exefs"]?.takeIf { it.isDir }?.id
                ?: throw IOException("exefs/main not found: select ExeFS or game root")
        val main = named(context, Selection(selectedExeFsUri, exeId), "main")["main"]
            ?.takeIf { !it.isDir }
            ?: throw IOException("exefs/main not found")
        val mainUri = DocumentsContract.buildDocumentUriUsingTree(selectedExeFsUri, main.id)
        val magic = ByteArray(4)
        context.contentResolver.openInputStream(mainUri)?.use { input ->
            try { DataInputStream(input).readFully(magic) }
            catch (_: java.io.EOFException) { throw IOException("exefs/main is truncated") }
        } ?: throw IOException("Cannot read exefs/main")
        if (!magic.contentEquals("NSO0".toByteArray(Charsets.US_ASCII))) {
            throw IOException("exefs/main is not NSO0")
        }
        scan(context, selectedExeFsUri, exeId, "exefs", plan, filesFound, dirsFound, ::reportScan)

        val rom = when {
            selectedRomFsUri != null -> Selection(
                selectedRomFsUri, DocumentsContract.getTreeDocumentId(selectedRomFsUri))
            exeId != selection.id -> selection
            else -> null
        }
        if (rom != null) {
            val romName = folderName(context, rom)
            if (romName.equals("exefs", ignoreCase = true)) {
                throw IOException("ExeFS was selected again; choose RomFS")
            }
            // When RomFS is the chosen folder itself, avoid an extra full
            // 200k-file directory listing merely to look for named children.
            val meta = if (romName.equals("romfs", ignoreCase = true)) emptyMap()
                       else named(context, rom, "main", "exefs", "romfs")
            if (meta["main"]?.isDir == false) {
                throw IOException("ExeFS was selected again; choose RomFS")
            }
            val nested = meta["romfs"]
            val gameRoot = meta["exefs"]?.isDir == true
            val romId = when {
                nested?.isDir == true -> nested.id
                gameRoot -> null
                else -> rom.id
            }
            if (nested != null && !nested.isDir && nested.name == "romfs" && romId != rom.id) {
                plan.add(rom.tree, nested, "romfs.romfs")
                filesFound.incrementAndGet()
            }
            if (romId != null && !(rom.tree == selectedExeFsUri && romId == exeId)) {
                scan(context, rom.tree, romId, "romfs", plan, filesFound, dirsFound, ::reportScan)
            }
            // Root metadata files accompanying a nested romfs/ or a game root.
            if (romId != rom.id) {
                children(context, rom.tree, rom.id) { e ->
                    if (!e.isDir && e.id != nested?.id &&
                        (e.name.endsWith(".romfs", true) ||
                         e.name.endsWith(".nacp", true) ||
                         e.name.startsWith("icon_", true))) {
                        plan.add(rom.tree, e, e.name)
                        filesFound.incrementAndGet()
                    }
                }
            }
            if (selectedRomFsUri != null && gameRoot && romId == null && !plan.romfsIncluded) {
                throw IOException("No RomFS found in selected game folder")
            }
        }
        reportScan(true)
        if (selectedRomFsUri != null && !plan.romfsIncluded) {
            throw IOException("Selected RomFS folder contains no readable files")
        }
        if (plan.files.none { it.path == "exefs/main" }) throw IOException("Missing exefs/main")

        val digest = MessageDigest.getInstance("SHA-256")
        fun hash(text: String) { digest.update(text.toByteArray(Charsets.UTF_8)) }
        hash("$MARKER_VERSION\nExeFS:$selectedExeFsUri\nRomFS:${selectedRomFsUri ?: "auto"}\n")
        plan.dirs.sorted().forEach { hash("D:$it\n") }
        plan.files.sortedBy { it.path }.forEach { hash("F:${it.path}:${it.size}:${it.modified}\n") }
        val fingerprint = digest.digest().joinToString("") { "%02x".format(it) }
        val base = File(context.getPublicFilesDir(), "ns2_imports")
        if (!base.isDirectory && !base.mkdirs()) throw IOException("Cannot create import directory")
        val key = MessageDigest.getInstance("SHA-256")
            .digest("$selectedExeFsUri\n${selectedRomFsUri ?: "auto"}".toByteArray(Charsets.UTF_8))
            .take(10).joinToString("") { "%02x".format(it) }
        val dest = File(base, key)
        val marker = File(dest, ".ns2_import_complete")
        val mainDest = File(dest, "exefs/main")
        if (plan.metadataComplete && marker.isFile && mainDest.isFile &&
            marker.readText() == "$MARKER_VERSION\n$fingerprint\n" && verifyFiles(dest, plan)) {
            onProgress(ImportProgress(Phase.CACHED, plan.files.size, plan.files.size,
                plan.totalBytes, plan.totalBytes, percentage = 100))
            return ImportedGame(mainDest.absolutePath, plan.romfsIncluded, reused = true)
        }
        if (base.usableSpace > 0 && plan.totalBytes > base.usableSpace - 64L * 1024 * 1024) {
            throw IOException("Insufficient space: need at least ${plan.totalBytes / 1048576} MiB")
        }
        // Old unfinished staging folders are never accepted as imports.
        base.listFiles()?.filter {
            it.name.startsWith(".$key-") && it.name.endsWith(".partial")
        }?.forEach { it.deleteRecursively() }
        val stage = File(base, ".$key-${System.nanoTime()}.partial")
        if (!stage.mkdirs()) throw IOException("Cannot create staging directory")

        val sizes = LongArray(plan.files.size)
        val copiedBytes = AtomicLong(0)
        val copiedFiles = AtomicInteger(0)
        val lastCopyReport = AtomicLong(0)
        val progressMutex = Mutex()
        val startedAt = SystemClock.elapsedRealtime()
        suspend fun reportCopy(force: Boolean = false, file: String = "") {
            val now = SystemClock.elapsedRealtime()
            if (!force) {
                val before = lastCopyReport.get()
                if (now - before < COPY_REPORT_MS ||
                    !lastCopyReport.compareAndSet(before, now)) return
            } else lastCopyReport.set(now)
            progressMutex.withLock {
                val bytes = copiedBytes.get()
                val percent = if (plan.metadataComplete && plan.totalBytes > 0) {
                    ((bytes.coerceAtMost(plan.totalBytes) * 99) / plan.totalBytes).toInt()
                } else null
                val elapsed = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1)
                onProgress(ImportProgress(
                    Phase.COPYING, copiedFiles.get(), plan.files.size,
                    bytes, plan.totalBytes, file, percent,
                    filesFound.get(), dirsFound.get(), bytes * 1000L / elapsed,
                ))
            }
        }
        try {
            for (dir in plan.dirs) {
                currentCoroutineContext().ensureActive()
                val output = File(stage, dir)
                if (!output.isDirectory && !output.mkdirs()) {
                    throw IOException("Cannot create directory $dir")
                }
            }
            reportCopy(true)
            // Four bounded workers hide SAF open() latency for small files.
            // A shared buffer PER worker avoids allocating one for each file.
            coroutineScope {
                val jobs = Channel<Int>(capacity = 64)
                val workers = List(WORKERS) {
                    launch(Dispatchers.IO) {
                        val buffer = ByteArray(BUFFER_SIZE)
                        for (index in jobs) {
                            currentCoroutineContext().ensureActive()
                            val src = plan.files[index]
                            val sourceUri = DocumentsContract.buildDocumentUriUsingTree(src.tree, src.docId)
                            val input = context.contentResolver.openInputStream(sourceUri)
                                ?: throw IOException("Cannot read ${src.path}")
                            val output = File(stage, src.path)
                            var n = 0L
                            input.use { stream ->
                                output.outputStream().use { out ->
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        val count = stream.read(buffer)
                                        if (count == -1) break
                                        if (count == 0) continue
                                        out.write(buffer, 0, count)
                                        n += count
                                        copiedBytes.addAndGet(count.toLong())
                                        reportCopy(file = src.path)
                                    }
                                }
                            }
                            if (src.size > 0 && src.size != n) {
                                throw IOException("Source file changed during copy: ${src.path}")
                            }
                            if (output.length() != n) throw IOException("Incomplete copy: ${src.path}")
                            sizes[index] = n
                            copiedFiles.incrementAndGet()
                            reportCopy(file = src.path)
                        }
                    }
                }
                try {
                    for (i in plan.files.indices) jobs.send(i)
                } finally { jobs.close() }
                workers.joinAll()
            }
            onProgress(ImportProgress(Phase.VERIFYING, copiedFiles.get(), plan.files.size,
                copiedBytes.get(), plan.totalBytes))
            for (i in plan.files.indices) {
                if (i % 256 == 0) currentCoroutineContext().ensureActive()
                val e = plan.files[i]
                val f = File(stage, e.path)
                if (!f.isFile || f.length() != sizes[i]) {
                    throw IOException("Imported file missing or truncated: ${e.path}")
                }
            }
            File(stage, "exefs/main").inputStream().use { input ->
                val header = ByteArray(4)
                DataInputStream(input).readFully(header)
                if (!header.contentEquals(magic)) throw IOException("Copied NSO header mismatch")
            }
            File(stage, ".ns2_import_complete").writeText("$MARKER_VERSION\n$fingerprint\n")
            val backup = File(base, ".$key-${System.nanoTime()}.backup")
            val previous = dest.exists()
            if (previous && !dest.renameTo(backup)) {
                throw IOException("Cannot replace previous import; close running game")
            }
            if (!stage.renameTo(dest)) {
                if (previous) backup.renameTo(dest)
                throw IOException("Cannot finalize import")
            }
            if (previous) backup.deleteRecursively()
            onProgress(ImportProgress(Phase.COMPLETE, copiedFiles.get(), plan.files.size,
                copiedBytes.get(), plan.totalBytes, percentage = 100))
            return ImportedGame(File(dest, "exefs/main").absolutePath, plan.romfsIncluded)
        } finally {
            if (stage.exists()) stage.deleteRecursively()
        }
    }

    private suspend fun verifyFiles(base: File, plan: Plan): Boolean {
        for ((i, entry) in plan.files.withIndex()) {
            if (i % 256 == 0) currentCoroutineContext().ensureActive()
            val file = File(base, entry.path)
            if (!file.isFile || file.length() != entry.size) return false
        }
        return true
    }

    private fun safeName(name: String?): String {
        if (name.isNullOrBlank() || name == "." || name == ".." ||
            name.contains('/') || name.contains('\\') || name.contains('\u0000')) {
            throw IOException("Invalid file or directory name")
        }
        return name
    }
}
