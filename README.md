# SlayTheAmethyst (Minimal)

Minimal Android launcher that boots Slay the Spire (`desktop-1.0.jar`) with:
- LibGDX desktop runtime bridge
- Amethyst/Pojav JavaSE launch chain
- arm64-v8a only

## Scope
- Vanilla STS startup to main menu
- External keyboard/mouse first
- No ModTheSpire in this phase

## Required Inputs
1. Runtime pack zip at `runtime-pack/jre8-pojav.zip`
   - must contain: `universal.tar.xz`, `bin-aarch64.tar.xz`, `version`
   - optional override: set env `STS_JRE8_PACK=/absolute/path/to/jre8-pojav.zip`
2. STS desktop jar imported from app UI
   - stored as `files/sts/desktop-1.0.jar`

## Build
```bash
./gradlew :app:assembleDebug
```

If `runtime-pack/jre8-pojav.zip` is missing, build fails with a clear error.

## App Flow
1. Open app.
2. Tap `Import desktop-1.0.jar`.
3. Tap `Launch Slay the Spire`.
4. App installs runtime/components and starts `com.megacrit.cardcrawl.desktop.DesktopLauncher`.

## Storage Paths
- STS jar: `files/sts/desktop-1.0.jar`
- Runtime: `files/runtimes/Internal/...`
- LWJGL classes: `files/lwjgl3/lwjgl-glfw-classes.jar`
- Log file: `files/sts/latestlog.txt`

## Credits
This repository reuses parts of Amethyst-Android/PojavLauncher native and Java bridge code.
See `NOTICE` and `THIRD_PARTY_LICENSES.md`.
