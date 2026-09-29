-dontwarn org.apache.**
-dontwarn javax.annotation.**
# LangChain4j ships a JDK 11 HttpClient client; Android has no java.net.http.
-dontwarn java.net.http.**

# ECJ is bundled for on-device patch compilation. It is invoked through its batch compiler API,
# and it probes optional JDK 9+ packages that ART does not ship.
-keep class org.eclipse.jdt.** { *; }
-keep class javax.lang.model.SourceVersion { *; }
-dontwarn org.eclipse.jdt.**
-dontwarn javax.tools.**
-dontwarn javax.lang.model.**
-dontwarn javax.annotation.processing.**
-dontwarn com.sun.tools.**

# CFR is bundled for on-device class decompilation and drives its pipeline through the public
# CfrDriver API, which R8 can follow; only its optional integrations need suppressing.
-dontwarn org.benf.cfr.**

# Native glue is resolved by JNI symbols and by bundled JVM runtime classes.
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class org.lwjgl.glfw.CallbackBridge { *; }
-keep class io.stamethyst.backend.workshop.AndroidZstdBridge { *; }
-keep class com.easytier.jni.EasyTierJNI { *; }
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
-keep class top.apricityx.workshop.steam.proto.** { *; }

# LangChain4j OpenAI request/response DTOs are serialized by Jackson through
# field annotations. R8 full mode cannot infer those reflective accesses, and
# obfuscating or removing the fields changes the wire names or leaves Jackson
# with no properties (notably StreamOptions.includeUsage).
-keep class dev.langchain4j.model.openai.internal.** { *; }
