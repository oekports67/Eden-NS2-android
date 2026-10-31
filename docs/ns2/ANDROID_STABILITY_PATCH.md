# Eden-NS2: Android stability hardening (2026-10-31)

This is a defensive Android source patch, **not a claim of full Switch 2 Android compatibility**.
The files were reviewed against the supplied `eden_log.txt.old(1).txt` and the bundled code.

## What the supplied crash log actually shows

- Android 36, Qualcomm SM8635 / Adreno 735, PurpleVK-public 26.2.99 (reported as Vulkan 1.4.359).
- Sonic Mania starts and renders. Missing Vulkan extensions and generic Qualcomm workarounds in that session are not a crash diagnosis.
- Later, a *different* launch selects an item displayed as `main`. The NSO loader reports `Module main loaded at 0x0` and Dynarmic reports `Unhandled SIGSEGV` shortly afterwards.
- On desktop, the existing loose-ExeFS loader expects `main.npdm` and the companion ExeFS modules to be available through the containing directory. Selecting only `main` via a single-file Android URI can conceal that directory from VFS. Without metadata, there is no valid process entry point (address 0). **This is an observed problem; it is not proof of what causes every Android crash.**
- `Core::IsSwitch2Program()` also reads `main.npdm` through the containing directory before selecting the 12 GiB memory mode. Android cannot reliably autodetect NS2 if that directory is inaccessible.

## Changes

1. `DirectoryInitialization` and `YuzuApplication`: prefer the writable Android external app-specific files directory; fall back to private app data if external storage is absent; never silently proceed after directory setup fails.
2. `GpuDriverHelper`: keep the live custom Vulkan driver while unpacking and validating a replacement in a private staging directory; validate `meta.json`, API level and Vulkan `.so` path; reject malformed and unsafe archives; restore a prior driver on rename failure; use a private writable Vulkan redirect directory. Partial extractions can no longer replace a valid installation.
3. `DriverViewModel`: do not leave the launch gate pending when driver installation fails; select the system driver after a failed installation.
4. `FileUtil`: sanitize imported filenames, create the destination directory, and safely handle denied content URI permissions.
5. Android native Vulkan initialization: log custom/system load failures and fall back to `libvulkan.so` when the adrenotools hooks fail.
6. The standalone NSO loader now returns an informative loader error **before** mapping a module at address 0. It cannot make an incomplete loose ExeFS executable.

## Current limitation and practical workaround

A standalone ExeFS `main` file is **not a game container**. A complete game package or a future Android SAF ExeFS-folder mount is needed. Where the Android file picker exposes only `main` and not adjacent `main.npdm`, `rtld`, `sdk` and related modules, the NS2 game still will **not** boot with this patch, but should fail cleanly instead of dereferencing invalid memory.

If running a full ExeFS dump, retain the directory and use an import path that preserves access to its children. The desktop path works because the VFS sees that directory; copying only `main` to the phone is insufficient. Never remove app data without backing up saves and keys.

For the first comparative test, use the system Vulkan driver and default graphics settings; then try one verified compatible custom driver. Preserve the entire latest log, and collect an Android `logcat` native backtrace/tombstone after a native crash.

## Build and test status

These files are source changes. Android APK build, hardware Vulkan compatibility testing, and an on-device NS2 boot verification were **not possible** in the current environment (Android SDK/NDK absent). Rebuild with the repository's Android workflow (`.ci/android/build.sh -t standard -b Release -DENABLE_WERROR=OFF` using the toolchains from `.github/workflows/android-build.yml`), then test startup, storage availability, valid/invalid drivers, and full-package loading.

The repository's `docs/policies/AI.md` requires appropriate disclosure and human review before upstream submission. This patch was produced with AI assistance and has not been submitted upstream.
