// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.DataInputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.yuzu.yuzu_emu.getPublicFilesDir

/**
 * Android Storage Access Framework directory import for loose ExeFS/RomFS dumps.
 *
 * A SAF document Uri is NOT a filesystem directory. The native RealVfsFile cannot
 * discover its siblings (rtld, optional main.npdm, sdk, romfs) from an individual content Uri.
 * Stage the selected tree under app-controlled storage and launch the real
 * absolute path to exefs/main, so the existing desktop directory loader works.
 *
 * The original user files are read-only and are never removed or changed.
 */
object Ns2FolderImport {
    data class ImportedGame(val mainPath: String, val romfsIncluded: Boolean)

    /** Must run on an IO dispatcher, not on Android's main thread. */
    suspend fun import(context: Context, selectedUri: Uri): ImportedGame {
        val resolver = context.contentResolver
        val picked = DocumentFile.fromTreeUri(context, selectedUri)
            ?: throw IOException("The selected folder could not be opened")
        if (!picked.isDirectory) throw IOException("Select a folder, not a file")

        // Accept <game>/exefs/main, or an exefs folder selected directly.
        val exefsSource = if (picked.findFile("main")?.isFile == true) {
            picked
        } else {
            picked.findFile("exefs")
        }
        if (exefsSource == null || !exefsSource.isDirectory ||
            exefsSource.findFile("main")?.isFile != true
        ) {
            throw IOException("exefs/main not found. Select the game folder or exefs")
        }

        // An incomplete/incorrect 'main' must be rejected before loading native code.
        val mainSource = exefsSource.findFile("main")!!
        val magic = ByteArray(4)
        resolver.openInputStream(mainSource.uri)?.use { input ->
            try {
                DataInputStream(input).readFully(magic)
            } catch (_: java.io.EOFException) {
                throw IOException("exefs/main is truncated")
            }
            if (!magic.contentEquals("NSO0".toByteArray(Charsets.US_ASCII))) {
                throw IOException("exefs/main is not a valid NSO file (missing NSO0 magic)")
            }
        } ?: throw IOException("Unable to read exefs/main (check Android folder permissions)")

        // The folder may be many gigabytes: intentionally use app-owned external files
        // if available (same partition as user dumps) instead of tiny cacheDir.
        val base = File(context.getPublicFilesDir(), "ns2_imports")
        if (!base.isDirectory && !base.mkdirs()) throw IOException("Cannot create import directory")
        val key = MessageDigest.getInstance("SHA-256")
            .digest(selectedUri.toString().toByteArray(Charsets.UTF_8))
            .take(10).joinToString("") { "%02x".format(it) }
        val dest = File(base, key)
        val ready = File(dest, ".ns2_import_complete")
        val destMain = File(dest, "exefs/main")
        // main.npdm is optional for experimental NS2 ExeFS imports.
        if (ready.isFile && destMain.isFile) {
            return ImportedGame(destMain.absolutePath, File(dest, "romfs").exists() ||
                dest.listFiles()?.any { it.extension.equals("romfs", true) } == true)
        }

        // Remove only interrupted imports for this same selected tree.
        base.listFiles()?.filter { it.name.startsWith(".$key-") &&
            it.name.endsWith(".partial") }?.forEach { it.deleteRecursively() }
        val temp = File(base, ".$key-${System.nanoTime()}.partial")
        if (!temp.mkdirs()) throw IOException("Cannot prepare the import folder")
        try {
            copyDirectory(context, exefsSource, File(temp, "exefs"), 0)
            currentCoroutineContext().ensureActive()

            // If user chose game root, copy the RomFS sibling too. The directory
            // loader reads it from the parent of exefs. If they chose exefs itself,
            // the Android SAF grant does not imply permission to read its parent.
            val isExeFsSelection = picked.uri == exefsSource.uri
            val romfsSource = if (!isExeFsSelection) picked.findFile("romfs") else null
            if (romfsSource != null) {
                if (romfsSource.isDirectory) {
                    copyDirectory(context, romfsSource, File(temp, "romfs"), 0)
                } else if (romfsSource.isFile) {
                    copyFile(context, romfsSource, File(temp, "romfs.romfs"))
                }
            }
            if (!isExeFsSelection) {
                // ReadRomFS also supports loose *.romfs files. NACP/icons may be
                // at the game's root rather than next to main.
                picked.listFiles().filter { it.isFile &&
                    (it.name?.endsWith(".romfs", ignoreCase = true) == true ||
                     it.name?.endsWith(".nacp", ignoreCase = true) == true ||
                     it.name?.startsWith("icon_", ignoreCase = true) == true)
                }.forEach { file ->
                    currentCoroutineContext().ensureActive()
                    val name = safeName(file.name)
                    copyFile(context, file, File(temp, name))
                }
            }
            if (!File(temp, "exefs/main").isFile) {
                throw IOException("ExeFS main could not be copied")
            }
            File(temp, ".ns2_import_complete").writeText("Eden NS2 SAF import\n")
            // All files are visible together only after the complete copy.
            if (dest.exists() && !dest.deleteRecursively()) {
                throw IOException("Cannot replace the previous import; close the running game")
            }
            if (!temp.renameTo(dest)) throw IOException("Cannot finalize the imported folder")
            return ImportedGame(File(dest, "exefs/main").absolutePath,
                File(dest, "romfs").exists() ||
                    dest.listFiles()?.any { it.extension.equals("romfs", true) } == true)
        } finally {
            if (temp.exists()) temp.deleteRecursively()
        }
    }

    private fun safeName(name: String?): String {
        if (name.isNullOrBlank() || name == "." || name == ".." ||
            name.contains('/') || name.contains('\\') || name.contains('\u0000')) {
            throw IOException("Unsafe or invalid name inside selected folder")
        }
        return name
    }

    private suspend fun copyDirectory(context: Context, source: DocumentFile,
                                      target: File, depth: Int) {
        currentCoroutineContext().ensureActive()
        if (depth > 64) throw IOException("Too many nested folders in the game dump")
        if (!target.isDirectory && !target.mkdirs()) throw IOException("Cannot create $target")
        for (child in source.listFiles()) {
            currentCoroutineContext().ensureActive()
            val output = File(target, safeName(child.name))
            if (child.isDirectory) {
                copyDirectory(context, child, output, depth + 1)
            } else if (child.isFile) {
                copyFile(context, child, output)
            }
        }
    }

    private suspend fun copyFile(context: Context, source: DocumentFile, target: File) {
        currentCoroutineContext().ensureActive()
        val resolver = context.contentResolver
        val input = resolver.openInputStream(source.uri)
            ?: throw IOException("Cannot read ${source.name}. Select a readable game folder")
        input.use { stream ->
            target.outputStream().use { output ->
                val buffer = ByteArray(1024 * 256)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
        }
    }
}
