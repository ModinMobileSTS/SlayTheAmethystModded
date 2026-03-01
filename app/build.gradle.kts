import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Sync
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val generatedRuntimeAssetsDir = layout.buildDirectory.dir("generated/runtime-assets")
val appVersionName = "0.0.6"

android {
    namespace = "io.stamethyst"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.stamethyst"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = appVersionName

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

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

val installBootBridgeJar by tasks.registering(Copy::class) {
    val dep = project(":boot-bridge").tasks.named<Jar>("jar")
    dependsOn(dep)
    from(dep.flatMap { it.archiveFile })
    into(generatedRuntimeAssetsDir.map { it.dir("components/boot_bridge") })
}

val patchProjectPaths = listOf(
    ":patches:gdx-patch",
    ":patches:downfall-fbo-patch",
    ":patches:basemod-fbo-patch",
    ":patches:basemod-glow-fbo-compat"
)
val runtimePackZip = rootProject.layout.projectDirectory.file("runtime-pack/jre8-pojav.zip")
val stsPackageName = "io.stamethyst"
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
    "crash_highlights.txt"
)

data class CommandExecResult(
    val exitCode: Int,
    val stdout: ByteArray,
    val stderr: ByteArray,
    val timedOut: Boolean
)

fun ByteArray.asUtf8Text(): String = toString(Charsets.UTF_8).trim()

fun Project.runCommandWithTimeout(
    command: List<String>,
    timeoutSeconds: Long
): CommandExecResult {
    val process = ProcessBuilder(command)
        .redirectErrorStream(false)
        .start()
    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()
    val outThread = thread(start = true, isDaemon = true, name = "sts-adb-stdout") {
        process.inputStream.use { input ->
            input.copyTo(stdout)
        }
    }
    val errThread = thread(start = true, isDaemon = true, name = "sts-adb-stderr") {
        process.errorStream.use { input ->
            input.copyTo(stderr)
        }
    }

    val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    if (!finished) {
        process.destroy()
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
        }
    }
    outThread.join(2_000)
    errThread.join(2_000)

    return CommandExecResult(
        exitCode = if (finished) process.exitValue() else -1,
        stdout = stdout.toByteArray(),
        stderr = stderr.toByteArray(),
        timedOut = !finished
    )
}

fun Project.connectedAdbSerials(): List<String> {
    val result = runCommandWithTimeout(adbCommand("devices"), timeoutSeconds = 10)
    if (result.timedOut) {
        throw GradleException("adb devices timed out after 10s. Check adb/server status.")
    }
    if (result.exitCode != 0) {
        val stderrText = result.stderr.asUtf8Text()
        throw GradleException(
            "adb devices failed (exit=${result.exitCode}). " +
                if (stderrText.isNotEmpty()) stderrText else "No stderr output."
        )
    }

    val deviceLine = Regex("^([^\\s]+)\\s+device(?:\\s+.*)?$")
    return result.stdout.asUtf8Text()
        .lineSequence()
        .map { it.trim() }
        .mapNotNull { line ->
            val match = deviceLine.find(line) ?: return@mapNotNull null
            match.groupValues[1]
        }
        .toList()
}

fun Project.requireAdbTargetDevice() {
    val serial = (findProperty("deviceSerial")?.toString() ?: "").trim()
    if (serial.startsWith("$") || (serial.startsWith("%") && serial.endsWith("%"))) {
        throw GradleException(
            "Invalid -PdeviceSerial value '$serial' (looks like an unexpanded shell variable). " +
                "Use a concrete adb serial, e.g. -PdeviceSerial=1b430ecc."
        )
    }

    val serials = connectedAdbSerials()
    if (serials.isEmpty()) {
        throw GradleException("No adb device is online. Connect a device and ensure 'adb devices' shows status 'device'.")
    }

    if (serial.isNotEmpty() && serial !in serials) {
        throw GradleException(
            "Requested deviceSerial '$serial' is not online. Available: ${serials.joinToString(", ")}"
        )
    }

    if (serial.isEmpty() && serials.size > 1) {
        throw GradleException(
            "Multiple adb devices are online (${serials.joinToString(", ")}). " +
                "Please set -PdeviceSerial=<serial>."
        )
    }
}

fun Project.resolveAdbExecutable(): File {
    val localPropertiesFile = rootProject.file("local.properties")
    val localProperties = Properties()
    if (localPropertiesFile.isFile) {
        localPropertiesFile.inputStream().use { input ->
            localProperties.load(input)
        }
    }

    val sdkDir = sequenceOf(
        localProperties.getProperty("sdk.dir")?.trim(),
        System.getenv("ANDROID_SDK_ROOT")?.trim(),
        System.getenv("ANDROID_HOME")?.trim()
    ).firstOrNull { !it.isNullOrEmpty() }
        ?: throw GradleException(
            "Android SDK not found. Set sdk.dir in local.properties, ANDROID_SDK_ROOT, or ANDROID_HOME."
        )

    val adbName = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        "adb.exe"
    } else {
        "adb"
    }
    val adbExecutable = File(File(sdkDir), "platform-tools/$adbName")
    if (!adbExecutable.isFile) {
        throw GradleException("adb not found: ${adbExecutable.absolutePath}")
    }
    return adbExecutable
}

fun Project.adbCommand(vararg args: String): List<String> {
    val command = ArrayList<String>()
    command.add(resolveAdbExecutable().absolutePath)
    val serial = (findProperty("deviceSerial")?.toString() ?: "").trim()
    if (serial.isNotEmpty()) {
        command.add("-s")
        command.add(serial)
    }
    command.addAll(args)
    return command
}

val installPatchJars by tasks.registering(Sync::class) {
    val patchJarTasks = patchProjectPaths.map { projectPath ->
        project(projectPath).tasks.named<Jar>("jar")
    }
    dependsOn(patchJarTasks)
    patchProjectPaths.forEach { projectPath ->
        from(project(projectPath).tasks.named<Jar>("jar").flatMap { it.archiveFile })
    }
    into(generatedRuntimeAssetsDir.map { it.dir("components/gdx_patch") })
}

val gdxVideoNativeAssetFiles = listOf(
    rootProject.layout.projectDirectory.file("runtime-pack/gdx_video_natives/libgdx-video-desktoparm64.so"),
    rootProject.layout.projectDirectory.file("runtime-pack/gdx_video_natives/libgdx-video-desktoparm.so")
)

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
    from(zipTree(runtimePackZip))
    into(generatedRuntimeAssetsDir.map { it.dir("components/jre") })
}

tasks.named("preBuild").configure {
    dependsOn(installBootBridgeJar)
    dependsOn(installPatchJars)
    dependsOn(installGdxVideoNatives)
    dependsOn(installRuntimePackAssets)
}

tasks.register("stsStart") {
    group = "debug"
    description = "Start SlayTheAmethyst on a connected Android device."
    doLast {
        project.requireAdbTargetDevice()
        val launchMode = (findProperty("launchMode")?.toString() ?: "mts_basemod").trim()
        if (!supportedLaunchModes.contains(launchMode)) {
            throw GradleException(
                "Unsupported launchMode: $launchMode. Supported: ${supportedLaunchModes.joinToString(", ")}"
            )
        }
        val forceJvmCrash = (findProperty("forceJvmCrash")?.toString() ?: "false").trim()
            .lowercase(Locale.US) == "true"

        val startArgs = ArrayList<String>()
        startArgs.addAll(
            listOf(
                "shell",
                "am",
                "start",
                "-n",
                "$stsPackageName/.LauncherActivity",
                "--es",
                "io.stamethyst.debug_launch_mode",
                launchMode
            )
        )
        if (forceJvmCrash) {
            startArgs.addAll(listOf("--ez", "io.stamethyst.debug_force_jvm_crash", "true"))
        }

        val result = project.runCommandWithTimeout(
            project.adbCommand(*startArgs.toTypedArray()),
            timeoutSeconds = 30
        )
        if (result.timedOut) {
            throw GradleException("stsStart timed out after 30s.")
        }
        if (result.exitCode != 0) {
            val stderrText = result.stderr.asUtf8Text()
            throw GradleException(
                "stsStart failed (exit=${result.exitCode}). " +
                    if (stderrText.isNotEmpty()) stderrText else "No stderr output."
            )
        }
    }
}

tasks.register("stsStop") {
    group = "debug"
    description = "Force stop SlayTheAmethyst on a connected Android device."
    doLast {
        project.requireAdbTargetDevice()
        val result = project.runCommandWithTimeout(
            project.adbCommand(
                "shell",
                "am",
                "force-stop",
                stsPackageName
            ),
            timeoutSeconds = 20
        )
        if (result.timedOut) {
            throw GradleException("stsStop timed out after 20s.")
        }
        if (result.exitCode != 0) {
            val stderrText = result.stderr.asUtf8Text()
            throw GradleException(
                "stsStop failed (exit=${result.exitCode}). " +
                    if (stderrText.isNotEmpty()) stderrText else "No stderr output."
            )
        }
    }
}

tasks.register("stsPullLogs") {
    group = "debug"
    description = "Export SlayTheAmethyst runtime logs from device to a local directory."
    doLast {
        project.requireAdbTargetDevice()
        val logsDirProp = (findProperty("logsDir")?.toString() ?: "").trim()
        val outputDir = if (logsDirProp.isNotEmpty()) {
            file(logsDirProp)
        } else {
            val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(LocalDateTime.now())
            layout.buildDirectory.dir("sts-logs/$timestamp").get().asFile
        }
        outputDir.mkdirs()

        val pulled = ArrayList<String>()
        val missing = ArrayList<String>()

        fun pullRunAsFile(remotePath: String, localName: String): Boolean {
            val result = project.runCommandWithTimeout(
                project.adbCommand(
                    "exec-out",
                    "run-as",
                    stsPackageName,
                    "sh",
                    "-c",
                    "cat $remotePath 2>/dev/null"
                ),
                timeoutSeconds = 20
            )
            val bytes = result.stdout
            if (!result.timedOut && result.exitCode == 0 && bytes.isNotEmpty()) {
                File(outputDir, localName).writeBytes(bytes)
                pulled.add(localName)
                return true
            }
            if (result.timedOut) {
                logger.lifecycle("Timed out pulling $localName after 20s")
            }
            return false
        }

        fun listRunAsFiles(globPattern: String): List<String>? {
            val listResult = project.runCommandWithTimeout(
                project.adbCommand(
                    "exec-out",
                    "run-as",
                    stsPackageName,
                    "sh",
                    "-c",
                    "for f in $globPattern; do [ -f \"${'$'}f\" ] && echo \"${'$'}f\"; done"
                ),
                timeoutSeconds = 20
            )
            if (listResult.timedOut) {
                logger.lifecycle("Timed out listing files for pattern '$globPattern' after 20s")
                return null
            }
            if (listResult.exitCode != 0) {
                logger.lifecycle(
                    "Failed listing files for pattern '$globPattern' (exit=${listResult.exitCode})"
                )
                return null
            }
            return listResult.stdout.asUtf8Text()
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
        }

        for (name in stsLogFiles) {
            logger.lifecycle("Pulling STS log: $name")
            if (!pullRunAsFile("files/sts/$name", name)) {
                missing.add(name)
            }
        }

        logger.lifecycle("Scanning STS crash dumps (hs_err_pid*.log)")
        val hsErrPaths = listRunAsFiles("files/sts/hs_err_pid*.log")
        if (hsErrPaths != null) {
            if (hsErrPaths.isEmpty()) {
                logger.lifecycle("No hs_err_pid*.log found on device.")
            } else {
                for (remotePath in hsErrPaths) {
                    val name = remotePath.substringAfterLast('/')
                    logger.lifecycle("Pulling STS crash dump: $name")
                    if (!pullRunAsFile(remotePath, name)) {
                        logger.lifecycle("Failed or empty crash dump: $name")
                    }
                }
            }
        }

        logger.lifecycle("Scanning STS pid snapshots (logcat_pid_*_snapshot.txt)")
        val pidSnapshotPaths = listRunAsFiles("files/sts/logcat_pid_*_snapshot.txt")
        if (pidSnapshotPaths != null) {
            if (pidSnapshotPaths.isEmpty()) {
                logger.lifecycle("No logcat_pid_*_snapshot.txt found on device.")
            } else {
                for (remotePath in pidSnapshotPaths) {
                    val name = remotePath.substringAfterLast('/')
                    logger.lifecycle("Pulling STS pid snapshot: $name")
                    if (!pullRunAsFile(remotePath, name)) {
                        logger.lifecycle("Failed or empty pid snapshot: $name")
                    }
                }
            }
        }

        fun pullLogcatToFile(fileName: String, vararg args: String) {
            logger.lifecycle("Pulling Android logcat -> $fileName")
            val command = ArrayList<String>()
            command.add("logcat")
            command.addAll(args)
            val result = project.runCommandWithTimeout(
                project.adbCommand(*command.toTypedArray()),
                timeoutSeconds = 20
            )
            if (!result.timedOut && result.stdout.isNotEmpty()) {
                File(outputDir, fileName).writeBytes(result.stdout)
                pulled.add(fileName)
            } else if (result.timedOut) {
                logger.lifecycle("Timed out pulling $fileName after 20s")
            } else {
                logger.lifecycle("Failed or empty logcat capture: $fileName")
            }
        }
        pullLogcatToFile("logcat.txt", "-d", "-b", "all", "-v", "threadtime", "-t", "12000")
        pullLogcatToFile("logcat_crash.txt", "-d", "-b", "crash", "-v", "threadtime", "-t", "12000")
        pullLogcatToFile("logcat_events.txt", "-d", "-b", "events", "-v", "threadtime", "-t", "12000")

        logger.lifecycle("SlayTheAmethyst logs exported to: ${outputDir.absolutePath}")
        logger.lifecycle("Pulled: ${pulled.joinToString(", ").ifBlank { "(none)" }}")
        if (missing.isNotEmpty()) {
            logger.lifecycle("Missing/empty on device: ${missing.joinToString(", ")}")
        }
    }
}
