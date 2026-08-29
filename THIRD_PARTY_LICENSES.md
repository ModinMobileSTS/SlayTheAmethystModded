# Third Party Licenses (Summary)

## Amethyst-Android
- Source: https://github.com/AngelAuraMC/Amethyst-Android
- Local reused package: `app/src/main/java/net/kdt/pojavlaunch/**`
- License: GNU Lesser General Public License v3.0 (LGPL-3.0)
- Usage: JavaSE launch bridge integration, JNI bridge, component assets.

## EasyTier (Desktop Companion, Bundled Runtime)
- Source: https://github.com/EasyTier/EasyTier
- Release: `v2.6.4`, Windows x86_64 archive
- License: GNU Lesser General Public License v3.0 (LGPL-3.0)
- Usage: `desktop-companion` embeds the official Windows runtime files
  (`easytier-core.exe`, `Packet.dll`, `wintun.dll`, and `WinDivert64.sys`) as
  build assets, extracts them to the user's runtime directory, and invokes the
  core as a separate process. The upstream files are not modified.
- Embedded binary SHA-256:
  `da7eb2d24b5416f3d3407636949e964a0750e3f9dc53a828cb6799a57ead445d`
- Source archive:
  https://github.com/EasyTier/EasyTier/releases/download/v2.6.4/easytier-windows-x86_64-v2.6.4.zip
- Redistribution requirement: Keep the complete LGPL notice, upstream
  copyright/source information, and permit replacement of the extracted
  EasyTier binary.

## GL4ES
- Source lineage: packaged via Amethyst-Android renderer stack.
- Local usage: `libgl4es_114.so`
- License: MIT.

## MobileGlues
- Source lineage: packaged via Amethyst-Android renderer stack.
- Bundled version: `V1.3.4`
- Upstream release: `https://github.com/MobileGL-Dev/MobileGlues-release/releases/tag/V1.3.4`
- Local usage: `libmobileglues.so`
- License: LGPL-2.1.

## Mesa / Zink / Kopper Runtime Components
- Source lineage: packaged via Amethyst-Android renderer stack, plus `libOSMesa.so` for `arm64-v8a` sourced from `Vera-Firefly/android-mesa-build` release artifacts.
- Local usage: `libEGL_mesa.so`, `libglapi.so`, `libglxshim.so`, `libOSMesa.so`, `libzink_dri.so`, `libspirv-cross-c-shared.so`, `libvulkan_freedreno.so`, `libVkLayer_khronos_timeline_semaphore.so`, `libcutils.so`
- License: MIT-style / component-specific upstream terms. Audit bundled binaries before redistribution.

## ANGLE
- Source lineage: packaged via Amethyst-Android renderer stack.
- Local license text: `app/src/main/assets/licenses/ANGLE_LICENSE`
- License: As declared in bundled ANGLE notice.

## OpenJDK Launcher Bridge Snippets
- Usage: `jre_launcher.c` lineage and related launch glue.
- License: As declared in file headers (GPLv2 + Classpath Exception where applicable).

## GLFW Keycode Definitions
- Source lineage: GLFW public header constants.
- License: zlib/libpng style per GLFW notice in upstream keycode source.

## ByteHook
- Dependency: `com.bytedance:bytehook`
- Usage: native exit/chmod hook integration.
- License: Refer to artifact/license metadata.

## Apache Commons Compress
- Dependency: `org.apache.commons:commons-compress`
- License: Apache License 2.0.

## XZ for Java
- Dependency: `org.tukaani:xz`
- License: Refer to upstream project terms.

## Material Line Icons
- Source: https://github.com/cyberalien/line-md
- Local usage: Workshop market static download/status icon shapes in `app/src/main/res/drawable/ic_workshop_*.xml`.
- License: MIT.

## Lottie Android / Compose Runtime
- Dependency: `com.airbnb.android:lottie-compose`
- Source: https://github.com/airbnb/lottie-android
- Local usage: Renders the Workshop market animated download status icons from `app/src/main/res/raw/workshop_useanimations_*.json`.
- License: Apache License 2.0.

## Stream Chat Android SDK
- Dependency: `io.getstream:stream-chat-android-compose`
- Source: https://github.com/GetStream/stream-chat-android
- Local usage: Compose chat UI building blocks for the in-app feedback conversation view.
- License: Stream License.

## useAnimations Animated Icons
- Source: https://useanimations.com/
- Package source: https://github.com/useAnimations/react-useanimations
- Local usage: Workshop market animated download, loading, and checkmark icon assets at `app/src/main/res/raw/workshop_useanimations_*.json`.
- Local modifications: The original Lottie JSON assets are rendered with the launcher's current icon tint at runtime.
- License: Creative Commons Attribution 4.0 International (CC BY 4.0) with useAnimations attribution required by upstream.

Note: This file is a practical attribution summary for this minimal prototype.
For redistribution/commercial compliance, audit all transitive dependencies and include full license texts as required.
