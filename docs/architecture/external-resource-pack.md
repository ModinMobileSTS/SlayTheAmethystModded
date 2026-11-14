# External Runtime Resource Pack

The launcher uses one resource-pack installation path for all APK variants.

## APK variants

- Slim APKs download `resources.zip` from the configured fixed URL when no valid local generation of `resourcePack.version` exists.
- Full APKs may embed an archive at `assets/resource-pack/resources.zip` for offline first install, then install it through the same path as a download.
- The APK variant and the app version are not part of the installed resource identity. An app update reuses the local generation as long as `resourcePack.version` is unchanged.
- The hosted zip is published from the resource repository, not from this app's CI release.

Repair from Settings reinstalls through the same install path without deleting the active generation first. A failed repair leaves the previous generation in place.

`packageExternalResources` may write `resources.zip.sha256` next to a locally packaged archive. That sidecar is only for publishing a new hosted zip. Do not pin `resourcePack.sha256` to a per-build local hash, or slim installs would reject the fixed download URL.

## APK-owned JNI callback contract

`org.lwjgl.glfw.CallbackBridge` is generated from the shared Android/JVM contract
and shipped as `assets/components/callback_bridge/callback-bridge.jar` in **every**
APK variant. It contains only the JVM bridge class (a few KB); the bulk LWJGL and
OpenAL bindings remain external. `packageJvmCallbackBridgeJar` produces this small
JAR; `packageLwjglRuntimeJar` produces the external runtime without that class.
GDX and floating-tools compile against both artifacts.

`ComponentInstaller` reads the callback JAR directly from `context.assets`, never
through the resource-pack override provider, and installs it at
`files/callback_bridge/callback-bridge.jar`. Its content is compared with the APK
on the component-current check, so missing, stale or damaged copies trigger
replacement even when the external resource version is unchanged. Installation
uses the same staged file replacement as other components; a failed copy retains
the previous file. A component update also invalidates the MTS startup cache.

Both vanilla and MTS launch classpaths put the APK callback JAR first. MTS's cold
and cached class loaders delegate `org.lwjgl` to this parent classpath. Existing
`resources-v1.7` packs may still contain an older `CallbackBridge`; that copy is
shadowed and the immutable resource generation is not modified. This fixes the
new-APK/old-pack failure where missing audio methods caused native registration
to fail and disabled background mute/recovery commands. Updating the APK is
sufficient; existing users do not need to redownload the resource pack.

Newly generated external packs omit `CallbackBridge` and require an APK with this
split. Publish such a pack under a new resource version rather than replacing the
existing hosted `resources-v1.7` archive used by older APKs.

Offline verification:

- `:app:testDebugUnitTest --tests io.stamethyst.backend.launch.ApkCallbackBridgeTest`
  covers old-resource class loading, artifact boundaries, stale/missing/corrupt
  installation and failed-copy preservation.
- `:boot-bridge:test --tests io.stamethyst.bridge.ApkCallbackBridgeClassLoaderTest`
  exercises both the actual cold MTS class loader and the cached class loader on
  Java 8, with a duplicate legacy bridge in the child URL list.
- Every `assemble<Variant>` runs `verify<Variant>CallbackBridgePackaging`, checking
  the actual APK contains the exact current bridge JAR and does not bundle the
  bulk LWJGL JAR as a common asset. These checks do not establish audible behavior
  on Android hardware.

## Repository layout

The persistent repository is rooted at `storageRoot/external_resources`:

```text
external_resources/
  active.properties
  state.properties
  generations/<sha256-pack-id>/
    assets/
    lib/arm64-v8a/
    manifest.properties
    .resource-pack-installed
  staging/
  quarantine/
```

Only the generation named by `active.properties` is exposed to runtime consumers. A generation is immutable after activation. Downloads, extraction, and metadata writes happen in staging; the active pointer is replaced only after complete validation.

## Migration

The pre-generation locations are migration inputs only:

- `storageRoot/external_resources/current`
- `filesDir/external_resources/current`
- older layouts with `assets/` and `lib/` directly below the resource root

Legacy content must have the expected old install marker and all required files before it is copied into a new generation. Invalid or unrecognized legacy content is quarantined and never exposed to runtime reads.
A failed copy or other I/O error during migration does not quarantine the source; recovery can retry it later.

## Diagnostics

Resource-pack state is written to `sts/resource_pack/state.txt` in diagnostics archives. The rotating diagnostics log also records preparation, source selection, migration, and failure events.

## Loading artwork

The full-screen art loading page defaults to the new poster pair in
`app/src/main/assets/boot-art/`. These small assets stay embedded in both APK
variants (outside the externalized `ui/**` directory), so an existing resource
pack does not need to be downloaded again after an app update.

The original `ui/boot_dark.png` / `ui/boot_bright.png` pair remains in the resource
pack. Select either pair under **Settings → Appearance → Custom loading image →
Built-in loading artwork**. Both the loading screen and settings previews use the
same persisted selection. Custom images still take priority; resetting custom
images keeps the selected built-in pair, while resetting all launcher settings
restores the new pair.
