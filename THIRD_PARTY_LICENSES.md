# Third Party Licenses (Summary)

## Amethyst-Android
- Source: https://github.com/AngelAuraMC/Amethyst-Android
- Local reused package: `app/src/main/java/net/kdt/pojavlaunch/**`
- License: GNU Lesser General Public License v3.0 (LGPL-3.0)
- Usage: JavaSE launch bridge integration, JNI bridge, component assets.

## GL4ES
- Source lineage: packaged via Amethyst-Android renderer stack.
- Local usage: `libgl4es_114.so`
- License: MIT.

## MobileGlues
- Source lineage: packaged via Amethyst-Android renderer stack.
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

Note: This file is a practical attribution summary for this minimal prototype.
For redistribution/commercial compliance, audit all transitive dependencies and include full license texts as required.
