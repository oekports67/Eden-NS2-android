# Eden-NS2 Android: selective fork merge

Base: `Eden_NS2_Android_GitHub_Build_Fixed.zip` (your last patched build).
Donor: `Eden-NS2-main (1).zip` (other fork, preserving its original code as reference).

## Ported from donor / built on
- SAF directory enumerator bridge: native C++ -> JNI NativeLibrary -> Kotlin FileUtil.
- Content URI VFS listings, subdirectories and file lookup from donor, with JNI guard and null-safe cursor.
- Document parent URI resolution (bounded to selected SAF tree) and JNI local-reference cleanup for repeated SAF queries.
- Existing ExeFS-folder picker, optional NPDM, staging/copy workflow and driver safety remain.

## Corrections found during merge
- A root-level `*.romfs` copied during import was not searched by ReadRomFS; now searched.
- Loose `exefs/main` without NPDM is now recognized for *experimental* 12 GiB Switch 2 memory layout.
- Precreate app-owned cache/shader, load, tas, config, nand, sdmc folders before native access.

## Important limits
- The *main-only* mode is experimental: valid NPDM is needed to reproduce actual process permissions and extended ACID metadata.
- A missing or unreadable RomFS cannot be replaced by code; the game data must be supplied.
- A different fork reaching a black screen does not prove it rendered game frames.
- No Android device / full SDK + NDK were available here: APK and runtime behavior not verified.
- Altering guest 12 GiB layout does not guarantee Android host has address space/RAM for all allocations.
- Ported donor GPU device-memory continuity-map invalidation (`device_memory_manager.h/.inc`) to avoid stale GPU page runs after remapping.
- Preserved your newer GPU/ASTC and shader fixes rather than overwriting them wholesale with donor code.

Use GitHub Actions workflow `.github/workflows/android-build.yml` to compile/test.
