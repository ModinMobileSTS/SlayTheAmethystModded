# Download-only Godot engine prototype

Independent container, **not installed** and **never an `:app` dependency**. It
contains Godot 4.7.2 stable's release Android AAR, arm64 libraries, resources and a
small host adapter. Gameplay lives in a separate Android-exported PCK.

`PrototypeHost` drives the official direct `Godot`/`GodotHost` lifecycle APIs and
registers a `SlingPrototype` plugin for script readiness and save acknowledgement.
`EngineContext` supplies the container's resources and a stable prototype-only
save directory. No Godot engine patches are applied.

Android's official release template rejects `--main-pack` pointing to a normal
external file when path overrides are disabled. The launcher therefore derives a
code-free ZIP containing only the verified PCK at `assets/game.pck`, mounts it in
the private engine AssetManager, and passes `res://game.pck`. There remain exactly
two downloads; the derived mount costs one additional PCK-sized local copy.

The host Activity owns the prototype save directory. `EngineContext.getFilesDir()`
delegates to that host unchanged: GodotIO prefers the Activity while the storage
scope identifier uses the engine context, so different paths break write access.

Build: `./gradlew :godot-runtime:assembleRelease`. The unsigned output is a
hash-pinned download container, not an installable companion application.
The matching Godot export templates must already be installed; they are not
downloaded or committed by this module. Preserve the engine and third-party
licenses when distributing it.

See [the prototype guide](../docs/godot-minigame-prototypes.md) for the two fixed
URLs, payload checks, debug entry and required device acceptance. Compilation
alone does not validate Android dynamic loading or the loading-overlay design.
