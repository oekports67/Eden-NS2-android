# GitHub Actions: audio_core source fix

The old `.gitignore` rule `out/` excludes ALL nested directories named `out`,
including the required `src/audio_core/out/` source files.
It is now `/out/` so only the root build-output directory is ignored.

Before committing, verify that these 4 files appear in the Git changes:

- `src/audio_core/out/audio_out.cpp`
- `src/audio_core/out/audio_out.h`
- `src/audio_core/out/audio_out_system.cpp`
- `src/audio_core/out/audio_out_system.h`

Run from the source root after extracting the archive:

```sh
git add .gitignore src/audio_core/out/
git add -A
git status --short
git commit -m "Fix Android build: track audio_core/out source files"
git push
```

If your existing repository still ignores the files, use:

```sh
git add -f src/audio_core/out/audio_out.cpp src/audio_core/out/audio_out.h src/audio_core/out/audio_out_system.cpp src/audio_core/out/audio_out_system.h
```

GitHub Actions should be rerun after pushing. This only fixes the demonstrated
CMake missing-source problem, not untested later build or runtime problems.
