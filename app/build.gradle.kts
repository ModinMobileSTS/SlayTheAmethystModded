import com.android.build.api.artifact.SingleArtifact
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Sync
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val packageName = readGradleProperty("application.id")
val appVersionName = readGradleProperty("application.version.name")
val appVersionCode = readGradleProperty("application.version.code").toInt()
val generatedRuntimeAssetsDir: Provider<Directory> = layout.buildDirectory.dir("generated/runtime-assets")

android {
    namespace = "io.stamethyst"
    compileSdk = 36

    defaultConfig {
        applicationId = packageName
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        @Suppress("UnstableApiUsage")
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(generatedRuntimeAssetsDir)
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = true
        jniLibs.pickFirsts += setOf("**/libbytehook.so")
    }

    buildFeatures {
        compose = true
        prefab = true
    }

    applicationVariants.all {
        outputs.all {
            @Suppress("DEPRECATION")
            if (this is com.android.build.gradle.api.ApkVariantOutput) {
                outputFileName = "SlayTheAmethyst-dev-$appVersionName.APK"
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_1_8
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.tukaani.xz)
    implementation(libs.apache.commons.compress)
    implementation(libs.bytedance.bytehook)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

androidComponents.onVariants { variant ->
    val assembleTaskName = "assemble${variant.name.replaceFirstChar { it.uppercaseChar() }}"
    tasks.matching { it.name == assembleTaskName }.configureEach {
        doLast {
            val apkDir = variant.artifacts.get(SingleArtifact.APK).get().asFile
            logger.lifecycle("APK output directory: ${apkDir.absolutePath}")
        }
    }
}

val patchProjectPaths = listOf(
    ":patches:gdx-patch"
)
val adb: String = androidComponents.sdkComponents.adb.get().asFile.absolutePath
val runtimePackZip: RegularFile = rootProject.layout.projectDirectory.file("runtime-pack/jre8-pojav.zip")
val gdxVideoNativeAssetFiles = listOf(
    rootProject.layout.projectDirectory.file("runtime-pack/gdx_video_natives/libgdx-video-desktoparm64.so"),
    rootProject.layout.projectDirectory.file("runtime-pack/gdx_video_natives/libgdx-video-desktoparm.so")
)
val supportedLaunchModes = setOf("mts_basemod", "vanilla")
val stsJvmLogExportMaxSlots = 5

val launchMode: String = readGradleProperty("launchMode", "mts_basemod")
val forceJvmCrash: String = readGradleProperty("forceJvmCrash", "false")
val deviceSerial: String = readGradleProperty("deviceSerial")
val logsDir: String = readGradleProperty("logsDir")
require(launchMode in supportedLaunchModes) {
    "Unsupported launchMode: $launchMode. Supported: ${supportedLaunchModes.joinToString(", ")}"
}

private fun adbCommand(
    args: String
): String = buildString {
    append(adb)
    if (deviceSerial.isNotEmpty()) {
        append(" -s $deviceSerial")
    }
    append(" $args")
}

val installBootBridgeJar by tasks.registering(Copy::class) {
    dependsOn(":boot-bridge:jar")
    from(project(":boot-bridge").layout.buildDirectory.file("libs/boot-bridge.jar"))
    into(generatedRuntimeAssetsDir.map { it.dir("components/boot_bridge") })
}

val installPatchJars by tasks.registering(Sync::class) {
    val patchJarTaskPaths = patchProjectPaths.map { projectPath -> "$projectPath:jar" }
    dependsOn(patchJarTaskPaths)
    patchProjectPaths.forEach { projectPath ->
        from(project(projectPath).layout.buildDirectory.dir("libs")) {
            include("*.jar")
        }
    }
    into(generatedRuntimeAssetsDir.map { it.dir("components/gdx_patch") })
}

val installGdxVideoNatives by tasks.registering(Sync::class) {
    doFirst {
        gdxVideoNativeAssetFiles.forEach { nativeFile ->
            if (!nativeFile.asFile.isFile) {
                throw GradleException("Missing gdx-video native asset: ${nativeFile.asFile.absolutePath}")
            }
        }
    }
    from(gdxVideoNativeAssetFiles)
    into(generatedRuntimeAssetsDir.map { it.dir("components/gdx_video_natives") })
}

val installRuntimePackAssets by tasks.registering(Sync::class) {
    doFirst {
        val runtimePackFile = runtimePackZip.asFile
        if (!runtimePackFile.isFile) {
            throw GradleException(
                "Missing runtime pack zip: ${runtimePackFile.absolutePath}. " +
                    "Expected runtime-pack/jre8-pojav.zip."
            )
        }
    }
    from(zipTree(runtimePackZip)) {
        exclude("bin-x86.tar.xz", "bin-x86_64.tar.xz")
    }
    into(generatedRuntimeAssetsDir.map { it.dir("components/jre") })
}

tasks.preBuild.configure {
    dependsOn(installBootBridgeJar)
    dependsOn(installPatchJars)
    dependsOn(installGdxVideoNatives)
    dependsOn(installRuntimePackAssets)
}

val stsStart by tasks.registering(Exec::class) {
    group = "debug"
    description = "Start SlayTheAmethyst on a connected Android device."
    val command = buildString {
        append("shell am start")
        append(" -n $(pm resolve-activity --components $packageName)")
        append(" --es io.stamethyst.debug_launch_mode $launchMode")
        append(" --ez io.stamethyst.debug_force_jvm_crash $forceJvmCrash")
    }
    commandLine(adbCommand(command).split(" "))
    isIgnoreExitValue = true
}

val stsStop by tasks.registering(Exec::class) {
    group = "debug"
    description = "Force stop SlayTheAmethyst on a connected Android device."
    commandLine(adb, "shell", "am", "force-stop", packageName)
    isIgnoreExitValue = true
}

val stsPullLogs by tasks.registering {
    group = "debug"
    description = "Export the same JVM log bundle as Settings > Share Logs."

    data class PulledLog(
        val fileName: String,
        val content: String
    )

    fun runAdbCommand(args: List<String>): String {
        val output = providers.exec {
            val command = mutableListOf(adb)
            if (deviceSerial.isNotEmpty()) {
                command.addAll(listOf("-s", deviceSerial))
            }
            command.addAll(args)
            commandLine(command)
            isIgnoreExitValue = true
        }
        if (output.result.get().exitValue != 0) {
            return ""
        }
        return output.standardOutput.asText.get()
    }

    fun remoteFileExists(remotePath: String): Boolean {
        val checkScript = "[ -f $remotePath ] && echo 1"
        return runAdbCommand(
            listOf("exec-out", "run-as", packageName, "sh", "-c", checkScript)
        ).trim() == "1"
    }

    fun readRemoteFile(remotePath: String): String {
        return runAdbCommand(
            listOf(
                "exec-out",
                "run-as",
                packageName,
                "sh",
                "-c",
                "cat $remotePath 2>/dev/null"
            )
        )
    }

    fun listArchivedJvmLogNames(): List<String> {
        val listScript = "for f in files/sts/jvm_logs/jvm_log_*.log; do [ -f \"${'$'}f\" ] && echo \"${'$'}{f##*/}\"; done"
        return runAdbCommand(
            listOf("exec-out", "run-as", packageName, "sh", "-c", listScript)
        ).lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedDescending()
            .toList()
    }

    fun buildJvmLogExportFileName(): String {
        val formatter = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        return "sts-jvm-logs-export-${formatter.format(Date())}.zip"
    }

    doLast {
        val outputDir = if (logsDir.isNotEmpty()) {
            file(logsDir)
        } else {
            layout.buildDirectory.dir("sts-logs").get().asFile
        }
        outputDir.mkdirs()

        val pulledLogs = mutableListOf<PulledLog>()

        val latestRemotePath = "files/sts/latest.log"
        val latestExists = remoteFileExists(latestRemotePath)
        if (latestExists) {
            logger.lifecycle("Pulling shared JVM log: latest.log")
            pulledLogs.add(
                PulledLog(
                    fileName = "latest.log",
                    content = readRemoteFile(latestRemotePath)
                )
            )
        } else {
            logger.lifecycle("latest.log not found on device.")
        }

        val archivedLimit = if (latestExists) {
            stsJvmLogExportMaxSlots - 1
        } else {
            stsJvmLogExportMaxSlots
        }
        val archivedNames = listArchivedJvmLogNames().take(archivedLimit)
        if (archivedNames.isEmpty()) {
            logger.lifecycle("No archived jvm_log_*.log found on device.")
        }
        for (name in archivedNames) {
            val remotePath = "files/sts/jvm_logs/$name"
            if (!remoteFileExists(remotePath)) {
                continue
            }
            logger.lifecycle("Pulling shared JVM log: $name")
            pulledLogs.add(PulledLog(fileName = name, content = readRemoteFile(remotePath)))
        }

        val archiveFile = File(outputDir, buildJvmLogExportFileName())
        FileOutputStream(archiveFile, false).use { output ->
            ZipOutputStream(output).use { zipOutput ->
                if (pulledLogs.isEmpty()) {
                    val entry = ZipEntry("sts/jvm_logs/README.txt")
                    zipOutput.putNextEntry(entry)
                    val message = "No JVM logs found.\n" +
                        "Expected files:\n" +
                        "- /data/user/0/$packageName/files/sts/latest.log\n" +
                        "- /data/user/0/$packageName/files/sts/jvm_logs\n"
                    zipOutput.write(message.toByteArray(StandardCharsets.UTF_8))
                    zipOutput.closeEntry()
                } else {
                    for (pulled in pulledLogs) {
                        val entry = ZipEntry("sts/jvm_logs/${pulled.fileName}")
                        zipOutput.putNextEntry(entry)
                        zipOutput.write(pulled.content.toByteArray(StandardCharsets.UTF_8))
                        zipOutput.closeEntry()
                    }
                }
            }
        }

        logger.lifecycle("SlayTheAmethyst JVM logs exported to: ${archiveFile.absolutePath}")
        if (pulledLogs.isEmpty()) {
            logger.lifecycle("No JVM logs found on device; wrote README.txt into archive.")
        } else {
            logger.lifecycle("Pulled: ${pulledLogs.joinToString(", ") { it.fileName }}")
        }
    }
}
