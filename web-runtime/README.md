# Download-only GeckoView runtime

This module is **not linked to any launcher variant**. It builds the external code/resource/native
container uploaded to CDN. Users download and unpack the ZIP; they do not install its APK container.

Build: `./gradlew :web-runtime:bundleWebRuntime`

Artifacts: `build/outputs/web-dependency/` (ZIP, SHA-256, JSON metadata, paired build properties).

See [`docs/geckoview-engine.md`](../docs/geckoview-engine.md) for CDN configuration,
loader/security details, Android 9+ requirements and the loopback boot-overlay bridge.

Keep the version and checksum in `runtime.properties` paired with the exact published ZIP.
Rebuilds may produce a different hash. Publish a new version when changing runtime code.
