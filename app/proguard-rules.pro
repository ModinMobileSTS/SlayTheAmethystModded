-dontwarn org.apache.**
-dontwarn javax.annotation.**

# Native glue is resolved by JNI symbols and by bundled JVM runtime classes.
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class org.lwjgl.glfw.CallbackBridge { *; }
-keep class net.kdt.pojavlaunch.AWTInputBridge { *; }
-keep class net.kdt.pojavlaunch.CriticalNativeTest { *; }
-keep class net.kdt.pojavlaunch.ExitActivity { *; }
-keep class net.kdt.pojavlaunch.MainActivity { *; }
-keep class net.kdt.pojavlaunch.Tools$SDL { *; }
-keep class net.kdt.pojavlaunch.utils.JREUtils { *; }

# Keep serialized model names stable for saved launcher/cloud state.
-keep class io.stamethyst.navigation.Route { *; }
-keep class io.stamethyst.navigation.Route$* { *; }
-keep class io.stamethyst.backend.steamcloud.SteamCloudRootKind { *; }
-keep class io.stamethyst.backend.steamcloud.SteamCloudManifestEntry { *; }
-keep class io.stamethyst.backend.steamcloud.SteamCloudManifestSnapshot { *; }
-keep class io.stamethyst.backend.steamcloud.SteamCloudLocalFileSnapshotEntry { *; }
-keep class io.stamethyst.backend.steamcloud.SteamCloudSyncBaseline { *; }

# JavaSteam/protobuf internals use generated classes and reflective callbacks.
-keep class in.dragonbra.javasteam.** { *; }
-keep class com.google.protobuf.** { *; }
