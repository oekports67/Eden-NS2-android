// SPDX-FileCopyrightText: Copyright 2025 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package org.yuzu.yuzu_emu.utils

import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.view.Surface
import java.io.File
import java.io.IOException
import org.yuzu.yuzu_emu.NativeLibrary
import org.yuzu.yuzu_emu.YuzuApplication
import org.yuzu.yuzu_emu.features.settings.model.StringSetting
import java.io.FileNotFoundException
import java.util.zip.ZipException
import java.util.zip.ZipFile

object GpuDriverHelper {
    private const val META_JSON_FILENAME = "meta.json"
    private var fileRedirectionPath: String? = null
    var driverInstallationPath: String? = null
    internal var hookLibPath: String? = null

    val driverStoragePath get() = DirectoryInitialization.userDirectory!! + "/gpu_drivers/"

    fun initializeFreedrenoConfigEarly() {
        NativeFreedrenoConfig.setFreedrenoBasePath(YuzuApplication.appContext.cacheDir.absolutePath)
        NativeFreedrenoConfig.initializeFreedrenoConfig()
        NativeFreedrenoConfig.reloadFreedrenoConfig()
    }

    @Synchronized
    fun initializeDriverParameters() {
        val context = YuzuApplication.appContext
        // Driver binaries must live in private application storage. Use private storage for
        // Vulkan debug redirection too: external storage can be unavailable at startup.
        fileRedirectionPath = File(context.filesDir, "gpu/vk_file_redirect").absolutePath + "/"
        driverInstallationPath = File(context.filesDir, "gpu_driver").absolutePath + "/"
        hookLibPath = context.applicationInfo.nativeLibraryDir + "/"
        initializeDirectories()
        NativeFreedrenoConfig.reloadFreedrenoConfig()

        val installed = installedCustomDriverData
        val libraryName = installed.libraryName.orEmpty()
        if (libraryName.isEmpty() && File(driverInstallationPath, META_JSON_FILENAME).exists()) {
            Log.warning("Installed GPU driver is incomplete; falling back to system driver")
        }
        NativeLibrary.initializeGpuDriver(
            hookLibPath,
            driverInstallationPath,
            libraryName,
            fileRedirectionPath
        )
    }

    fun getDrivers(): MutableList<Pair<String, GpuDriverMetadata>> {
        val driverZips = File(driverStoragePath).listFiles()
        val drivers: MutableList<Pair<String, GpuDriverMetadata>> =
            driverZips
                ?.filter { it.isFile && it.extension.equals("zip", ignoreCase = true) }
                ?.mapNotNull {
                    val metadata = getMetadataFromZip(it)
                    metadata.name?.let { _ -> Pair(it.path, metadata) }
                }
                ?.sortedByDescending { it: Pair<String, GpuDriverMetadata> -> it.second.name }
                ?.distinct()
                ?.toMutableList() ?: mutableListOf()
        return drivers
    }

    @Synchronized
    fun installDefaultDriver() {
        val installation = File(requireNotNull(driverInstallationPath))
        if (installation.exists() && !installation.deleteRecursively()) {
            Log.warning("Unable to remove installed GPU driver: $installation")
        }
        initializeDriverParameters()
    }

    fun copyDriverToInternalStorage(driverUri: Uri): Boolean {
        initializeDirectories()
        val copiedFile =
            FileUtil.copyUriToInternalStorage(driverUri, driverStoragePath) ?: return false
        val metadata = getMetadataFromZip(copiedFile)
        val valid = isUsableMetadata(metadata)
        if (!valid) copiedFile.delete()
        return valid
    }

    /** Extract and validate the next driver before replacing the active driver. */
    private fun installDriverArchive(driver: File): Boolean {
        val active = File(requireNotNull(driverInstallationPath))
        val staging = File(YuzuApplication.appContext.filesDir, "gpu_driver_staging")
        val backup = File(YuzuApplication.appContext.filesDir, "gpu_driver_backup")
        val metadata = getMetadataFromZip(driver)
        if (!isUsableMetadata(metadata)) return false

        if (staging.exists() && !staging.deleteRecursively()) return false
        if (!staging.mkdirs()) return false
        try {
            FileUtil.unzipToInternalStorage(driver.path, staging)
            val extractedMetadata = GpuDriverMetadata(File(staging, META_JSON_FILENAME))
            if (!isUsableMetadata(extractedMetadata) || extractedMetadata != metadata) {
                Log.warning("GPU driver archive has missing or inconsistent metadata")
                return false
            }
            val library = File(staging, requireNotNull(extractedMetadata.libraryName))
            if (!library.isFile || library.length() == 0L ||
                !library.canonicalPath.startsWith(staging.canonicalPath + File.separator)
            ) {
                Log.warning("GPU driver archive is missing its Vulkan library")
                return false
            }

            if (backup.exists() && !backup.deleteRecursively()) return false
            val hadOldDriver = active.exists()
            if (hadOldDriver && !active.renameTo(backup)) return false
            if (!staging.renameTo(active)) {
                if (hadOldDriver && !backup.renameTo(active)) {
                    Log.error("Could not restore previous GPU driver after installation failed")
                }
                return false
            }
            if (hadOldDriver && !backup.deleteRecursively()) {
                Log.warning("Could not clean up GPU driver backup: $backup")
            }
            initializeDriverParameters()
            return true
        } catch (e: IOException) {
            Log.warning("GPU driver installation failed: ${e.message}")
        } catch (e: SecurityException) {
            Log.warning("Unsafe GPU driver archive rejected: ${e.message}")
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
        return false
    }

    @Synchronized
    fun installCustomDriver(driverUri: Uri): Boolean {
        initializeDirectories()
        val copiedFile =
            FileUtil.copyUriToInternalStorage(driverUri, driverStoragePath) ?: return false
        val installed = installDriverArchive(copiedFile)
        if (!installed) copiedFile.delete()
        return installed
    }

    @Synchronized
    fun installCustomDriver(driver: File): Boolean {
        initializeDirectories()
        return installDriverArchive(driver)
    }

    private fun isSafeLibraryName(name: String): Boolean =
        name.isNotBlank() && name.endsWith(".so") && !name.startsWith("/") &&
            !name.contains("\\") && name.split('/').all { it.isNotEmpty() && it != "." && it != ".." }

    private fun isUsableMetadata(metadata: GpuDriverMetadata): Boolean =
        !metadata.name.isNullOrBlank() &&
            metadata.minApi <= Build.VERSION.SDK_INT &&
            metadata.libraryName?.let(::isSafeLibraryName) == true

    /**
     * Takes in a zip file and reads the meta.json file for presentation to the UI
     *
     * @param driver Zip containing driver and meta.json file
     * @return A non-null [GpuDriverMetadata] instance that may have null members
     */
    fun getMetadataFromZip(driver: File): GpuDriverMetadata {
        if (!driver.exists()) {
            return GpuDriverMetadata()
        }

        try {
            ZipFile(driver).use { zf ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.isDirectory && entry.name == META_JSON_FILENAME) {
                        zf.getInputStream(entry).use {
                            return GpuDriverMetadata(it, entry.size)
                        }
                    }
                }
            }
        } catch (_: ZipException) {
        } catch (_: FileNotFoundException) {
        } catch (_: IOException) {
        }
        return GpuDriverMetadata()
    }

    external fun supportsCustomDriverLoading(): Boolean

    external fun getSystemDriverInfo(
        surface: Surface = Surface(SurfaceTexture(true)),
        hookLibPath: String = GpuDriverHelper.hookLibPath!!
    ): Array<String>?

    external fun getGpuModel(
        surface: Surface = Surface(SurfaceTexture(true)),
        hookLibPath: String
    ): String?

    fun isAdrenoGpu(): Boolean {
        return try {
            supportsCustomDriverLoading()
        } catch (e: Exception) {
            false
        }
    }

    // Parse the custom driver metadata to retrieve the name.
    val installedCustomDriverData: GpuDriverMetadata
        get() {
            val directory = File(requireNotNull(driverInstallationPath))
            val metadata = GpuDriverMetadata(File(directory, META_JSON_FILENAME))
            val libraryName = metadata.libraryName
            return if (isUsableMetadata(metadata) && libraryName != null &&
                File(directory, libraryName).isFile
            ) metadata else GpuDriverMetadata()
        }

    val customDriverSettingData: GpuDriverMetadata
        get() = getMetadataFromZip(File(StringSetting.DRIVER_PATH.getString()))

    fun initializeDirectories() {
        for (directory in listOf(
            File(requireNotNull(fileRedirectionPath)),
            File(requireNotNull(driverInstallationPath)),
            File(driverStoragePath)
        )) {
            if ((!directory.isDirectory && !directory.mkdirs()) || !directory.isDirectory) {
                throw IOException("Failed to create GPU driver directory: $directory")
            }
        }
    }

    /**
     * Checks if a driver zip with the given filename is already present and valid in the
     * internal driver storage directory. Validation requires a readable meta.json with a name.
     */
    fun isDriverZipInstalledByName(fileName: String): Boolean {
        // Normalize separators in case upstream sent a path
        val baseName = fileName.substringAfterLast('/')
            .substringAfterLast('\\')
        val candidate = File("$driverStoragePath$baseName")
        if (!candidate.exists() || candidate.length() == 0L) return false
        val metadata = getMetadataFromZip(candidate)
        return isUsableMetadata(metadata)
    }
}
