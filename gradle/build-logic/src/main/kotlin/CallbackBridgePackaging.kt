import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import java.io.File
import java.util.zip.ZipFile

/** Verify the deliverable, not just the codegen output: slim APKs also need the JVM class. */
fun Project.registerCallbackBridgePackagingVerification() {
    val generatedJar = layout.buildDirectory.file("generated/apkCallbackBridgeJar/callback-bridge.jar")
    val runtimeJar = layout.buildDirectory.file("generated/lwjglRuntimeJar/lwjgl-glfw-classes.jar")
    val legacyJar = rootProject.layout.projectDirectory.file("gradle/callback-bridge/lwjgl-glfw-classes-base.jar")
    tasks.withType<Test>().configureEach {
        dependsOn("packageJvmCallbackBridgeJar", "packageLwjglRuntimeJar")
        inputs.files(generatedJar, runtimeJar, legacyJar).withPropertyName("callbackBridgeRuntime")
        systemProperty("sts.callbackBridgeJar", generatedJar.get().asFile.absolutePath)
        systemProperty("sts.lwjglRuntimeJar", runtimeJar.get().asFile.absolutePath)
        systemProperty("sts.legacyLwjglJar", legacyJar.asFile.absolutePath)
    }
    extensions.getByType<ApplicationAndroidComponentsExtension>().onVariants { variant ->
        val capitalized = variant.name.replaceFirstChar { it.uppercaseChar() }
        val verify = tasks.register("verify${capitalized}CallbackBridgePackaging") {
            val apks = variant.artifacts.get(SingleArtifact.APK)
            dependsOn("packageJvmCallbackBridgeJar")
            inputs.dir(apks)
            inputs.file(generatedJar)
            doLast {
                val expected = generatedJar.get().asFile.readBytes()
                val files = apks.get().asFile.walkTopDown().filter { it.extension == "apk" }.toList()
                check(files.isNotEmpty()) { "No APK found for callback bridge verification" }
                files.forEach { apk ->
                    verifyCallbackBridgeApk(apk, expected)
                    logger.lifecycle("Verified APK-owned JVM callback bridge: ${apk.name} (${expected.size} bytes)")
                }
            }
        }
        tasks.matching { it.name == "assemble$capitalized" }.configureEach { dependsOn(verify) }
    }
}

private fun verifyCallbackBridgeApk(apk: File, expected: ByteArray) {
    ZipFile(apk).use { archive ->
        val path = "assets/components/callback_bridge/callback-bridge.jar"
        val entry = checkNotNull(archive.getEntry(path)) { "Missing APK-owned callback bridge in $apk" }
        check(archive.getInputStream(entry).use { it.readBytes() }.contentEquals(expected)) {
            "APK contains a stale JVM callback bridge: $apk"
        }
        check(archive.getEntry("assets/components/lwjgl3/lwjgl-glfw-classes.jar") == null) {
            "Bulk LWJGL runtime must stay in the external resource pack: $apk"
        }
    }
}
