# Android NS2: two-folder ExeFS / RomFS launch

The Android games screen includes **NS2 / ExeFS + RomFS**.

1. Tap it and choose the **ExeFS** directory (containing `main`, optionally `rtld`, `sdk`, `subsdk*`, etc.). Alternatively choose the game's root directory containing `exefs/`.
2. The Android folder picker opens **again**. Choose the **RomFS** directory, which may be located anywhere on the device. A game root with `romfs/` also works. If RomFS is missing, cancel the second picker, then explicitly choose **Skip RomFS**.
3. A scan/copy/verification progress dialog appears. The two trees are staged together under `Android/data/dev.eden.eden_emulator/files/ns2_imports/` using a temporary `.partial` directory. The sources are not changed. On success the emulator launches the staged `exefs/main` with sibling `romfs/` (or a loose `.romfs` image).

- `main.npdm` is **not required** by this Android importer. The header of `exefs/main` must be `NSO0`; there is no requirement for a `.nso` filename extension.
- A single loose `main` chosen from the ordinary file picker is not enough: please use the NS2 folder flow.
- If file size and modification timestamps are supplied by the Android document provider, unchanged completed imports are reused on the next selection; imports with missing metadata are copied again to avoid silently reusing outdated data.
- Large RomFS folders require enough space for a **copy** in the app data directory. The current native loader is not wired to read the raw SAF directory directly. Selecting a separate folder does not eliminate storage requirements.
- On cancellation/error, temporary partial imports are removed. An existing completed import is left intact until a replacement is successfully prepared.
- Filesystem access fixes do **not** establish full Switch 2 emulation compatibility; CPU/GPU or OS service support can still prevent games from booting.

## Build / verification

Source files were statically checked for consistency, and the ZIP archive passed CRC checks. The environment does not have a complete Android build toolchain/Gradle distribution, so **APK compilation and execution on a device were not verified**.
