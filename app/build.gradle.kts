import com.android.build.api.artifact.SingleArtifact
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Sync
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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
val stsLogFiles = listOf(
    "latestlog.txt",
    "jvm_output.log",
    "boot_bridge_events.log",
    "enabled_mods.txt",
    "last_crash_report.txt",
    "last_exit_info.txt",
    "last_exit_trace.txt",
    "last_signal_stack.txt",
    "logcat_snapshot.txt",
    "logcat_crash_snapshot.txt",
    "logcat_events_snapshot.txt",
    "crash_highlights.txt",
)

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
    description = "Export SlayTheAmethyst runtime logs from device to a local directory."

    data class PatternSpec(
        val globPattern: String,
        val scanMessage: String,
        val emptyMessage: String,
        val itemLabel: String
    )

    val pulled = mutableListOf<String>()
    val missing = mutableListOf<String>()
    fun listFiles(globPattern: String): List<String> {
        val bashScript = "for f in $globPattern; do [ -f \"${'$'}f\" ] && echo \"${'$'}f\"; done"
        val command = adbCommand("exec-out run-as $packageName sh -c '$bashScript'")
        return runCommand(command)
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    fun pullByPattern(spec: PatternSpec, pullFile: (String, String) -> Boolean) {
        logger.lifecycle(spec.scanMessage)
        val remotePaths = listFiles(spec.globPattern)
        if (remotePaths.isEmpty()) {
            logger.lifecycle(spec.emptyMessage)
            return
        }
        for (remotePath in remotePaths) {
            val name = remotePath.substringAfterLast('/')
            logger.lifecycle("Pulling STS ${spec.itemLabel}: $name")
            if (!pullFile(remotePath, name)) {
                logger.lifecycle("Failed or empty ${spec.itemLabel}: $name")
            }
        }
    }

    val patternSpecs = listOf(
        PatternSpec(
            globPattern = "files/sts/hs_err_pid*.log",
            scanMessage = "Scanning STS crash dumps (hs_err_pid*.log)",
            emptyMessage = "No hs_err_pid*.log found on device.",
            itemLabel = "crash dump"
        ),
        PatternSpec(
            globPattern = "files/sts/logcat_pid_*_snapshot.txt",
            scanMessage = "Scanning STS pid snapshots (logcat_pid_*_snapshot.txt)",
            emptyMessage = "No logcat_pid_*_snapshot.txt found on device.",
            itemLabel = "pid snapshot"
        )
    )
    val logcatSpecs = listOf(
        "logcat.txt" to "logcat -d -b all -v threadtime -t 12000",
        "logcat_crash.txt" to "logcat -d -b crash -v threadtime -t 12000",
        "logcat_events.txt" to "logcat -d -b events -v threadtime -t 12000"
    )

    doLast {
        val outputDir = if (logsDir.isNotEmpty()) {
            file(logsDir)
        } else {
            val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(LocalDateTime.now())
            layout.buildDirectory.dir("sts-logs/$timestamp").get().asFile
        }
        outputDir.mkdirs()

        fun pullToFile(localName: String, command: String, emptyMessage: String): Boolean {
            val content = runCommand(command)
            if (content.isEmpty()) {
                logger.lifecycle(emptyMessage)
                return false
            }
            File(outputDir, localName).writeText(content)
            pulled.add(localName)
            return true
        }

        fun pullFile(remotePath: String, localName: String): Boolean {
            return pullToFile(
                localName = localName,
                command = adbCommand("exec-out run-as $packageName sh -c 'cat $remotePath 2>/dev/null'"),
                emptyMessage = "Failed or empty file: $localName"
            )
        }

        for (name in stsLogFiles) {
            logger.lifecycle("Pulling STS log: $name")
            if (!pullFile("files/sts/$name", name)) {
                missing.add(name)
            }
        }

        patternSpecs.forEach { spec -> pullByPattern(spec, ::pullFile) }
        logcatSpecs.forEach { (fileName, args) ->
            logger.lifecycle("Pulling Android logcat -> $fileName")
            pullToFile(
                localName = fileName,
                command = adbCommand(args),
                emptyMessage = "Failed or empty logcat capture: $fileName"
            )
        }

        logger.lifecycle("SlayTheAmethyst logs exported to: ${outputDir.absolutePath}")
        logger.lifecycle("Pulled: ${pulled.joinToString(", ")}")
        if (missing.isNotEmpty()) {
            logger.warn("Missing/empty on device: ${missing.joinToString(", ")}")
        }
    }
}
