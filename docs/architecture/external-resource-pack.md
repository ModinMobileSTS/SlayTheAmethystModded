# External Runtime Resource Pack

The launcher uses one resource-pack installation path for all APK variants.

## APK variants

- Slim APKs download `resources.zip` from the configured fixed URL when no valid local generation of `resourcePack.version` exists.
- Full APKs may embed an archive at `assets/resource-pack/resources.zip` for offline first install, then install it through the same path as a download.
- The APK variant and the app version are not part of the installed resource identity. An app update reuses the local generation as long as `resourcePack.version` is unchanged.
- The hosted zip is published from the resource repository, not from this app's CI release.

Repair from Settings reinstalls through the same install path without deleting the active generation first. A failed repair leaves the previous generation in place.

`packageExternalResources` may write `resources.zip.sha256` next to a locally packaged archive. That sidecar is only for publishing a new hosted zip. Do not pin `resourcePack.sha256` to a per-build local hash, or slim installs would reject the fixed download URL.

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
