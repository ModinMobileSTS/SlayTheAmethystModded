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
import java.util.zip.ZipFile
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
val releaseStoreFilePath = providers.environmentVariable("RELEASE_STORE_FILE").orNull?.trim().orEmpty()
val releaseStorePassword = providers.environmentVariable("RELEASE_STORE_PASSWORD").orNull?.trim().orEmpty()
val releaseKeyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").orNull?.trim().orEmpty()
val releaseKeyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD").orNull?.trim().orEmpty()
val hasReleaseSigning = releaseStoreFilePath.isNotEmpty() &&
    releaseStorePassword.isNotEmpty() &&
    releaseKeyAlias.isNotEmpty() &&
    releaseKeyPassword.isNotEmpty()
val isReleaseTaskRequested = gradle.startParameter.taskNames.any { taskName ->
    taskName.contains("Release", ignoreCase = true)
}

if (hasReleaseSigning && !File(releaseStoreFilePath).isFile) {
    throw GradleException("RELEASE_STORE_FILE does not exist: $releaseStoreFilePath")
}
if (isReleaseTaskRequested && !hasReleaseSigning) {
    throw GradleException(
        "Missing release signing env vars. Required: " +
            "RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD"
    )
}

android {
    namespace = "io.stamethyst"
    compileSdk = 36

    defaultConfig {
        applicationId = packageName
        minSdk = 26
        targetSdk = 33
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "FEEDBACK_BASE_URL", "\"http://1315061624-boxfc2p5fb.ap-guangzhou.tencentscf.com\"")
        buildConfigField("String", "FEEDBACK_ENDPOINT", "\"http://1315061624-boxfc2p5fb.ap-guangzhou.tencentscf.com/api/sts-feedback\"")
        buildConfigField("String", "FEEDBACK_API_KEY", feedbackApiKey.toBuildConfigStringLiteral())
        buildConfigField("String", "FEEDBACK_GITHUB_OWNER", "\"ModinMobileSTS\"")
        buildConfigField("String", "FEEDBACK_GITHUB_REPO", "\"SlayTheAmethystModded\"")

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

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFilePath)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
        buildConfig = true
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
    implementation(libs.reorderable)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.tukaani.xz)
    implementation(libs.apache.commons.compress)
    implementation(libs.bytedance.bytehook)
    testImplementation("org.json:json:20240303")
    testImplementation(libs.junit4)
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
val log4jRuntimeComponents by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isVisible = false
}
val adb: String = androidComponents.sdkComponents.adb.get().asFile.absolutePath
val runtimePackZip: RegularFile = rootProject.layout.projectDirectory.file("runtime-pack/jre8-pojav.zip")
val gdxVideoNativeAssetFiles = listOf(
    rootProject.layout.projectDirectory.file("runtime-pack/gdx_video_natives/libgdx-video-desktoparm64.so"),
    rootProject.layout.projectDirectory.file("runtime-pack/gdx_video_natives/libgdx-video-desktoparm.so")
)
val supportedLaunchModes = setOf("mts", "vanilla")
val stsJvmLogExportMaxSlots = 5

enum class RemoteFileAccessMode {
    SHELL,
    RUN_AS
}

data class DeviceStsPaths(
    val stsRoot: String,
    val accessMode: RemoteFileAccessMode
)

data class AdbCommandResult(
    val exitCode: Int,
    val stdout: String
)

val rawLaunchMode: String = readGradleProperty("launchMode", "mts")
val launchMode: String = when (rawLaunchMode) {
    "mts_basemod" -> "mts"
    else -> rawLaunchMode
}
val forceJvmCrash: String = readGradleProperty("forceJvmCrash", "false")
val deviceSerial: String = readGradleProperty("deviceSerial")
val logsDir: String = readGradleProperty("logsDir")
val rendererLibsSource: String = readGradleProperty("rendererLibsSource")
val feedbackApiKey: String = readGradleProperty("feedback.apiKey")
require(launchMode in supportedLaunchModes) {
    "Unsupported launchMode: $launchMode. Supported: ${supportedLaunchModes.joinToString(", ")}"
}

val rendererBackendImportLibraries = listOf(
    "libc++_shared.so",
    "libEGL_mesa.so",
    "libglapi.so",
    "libglxshim.so",
    "liblinkerhook.so",
    "libmobileglues.so",
    "libspirv-cross-c-shared.so",
    "libzink_dri.so",
    "libcutils.so",
    "libvulkan_freedreno.so",
    "libVkLayer_khronos_timeline_semaphore.so",
    "libOSMesa.so"
)

dependencies {
    add(log4jRuntimeComponents.name, libs.log4j.api)
    add(log4jRuntimeComponents.name, libs.log4j.core)
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

val installLog4jRuntimeAssets by tasks.registering(Sync::class) {
    from(log4jRuntimeComponents) {
        include("log4j-api-*.jar")
        rename { "log4j-api.jar" }
    }
    from(log4jRuntimeComponents) {
        include("log4j-core-*.jar")
        rename { "log4j-core.jar" }
    }
    into(generatedRuntimeAssetsDir.map { it.dir("components/log4j_runtime") })
    doLast {
        val outputDir = generatedRuntimeAssetsDir.get().dir("components/log4j_runtime").asFile
        val requiredJars = listOf(
            File(outputDir, "log4j-api.jar"),
            File(outputDir, "log4j-core.jar")
        )
        requiredJars.forEach { jarFile ->
            if (!jarFile.isFile || jarFile.length() <= 0L) {
                throw GradleException("Missing packaged Log4j runtime asset: ${jarFile.absolutePath}")
            }
        }
    }
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
    dependsOn(installLog4jRuntimeAssets)
    dependsOn(installRuntimePackAssets)
}

val importRendererBackendLibs by tasks.registering {
    group = "dev"
    description = "Import backend-native libraries from a renderer APK or an outer archive containing one."

    fun extractApk(source: File, tempDir: File): File {
        if (source.extension.equals("apk", ignoreCase = true)) {
            return source
        }
        ZipFile(source).use { zip ->
            val apkEntry = zip.entries().asSequence()
                .firstOrNull { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
                ?: throw GradleException("No .apk found inside ${source.absolutePath}")
            val extractedApk = File(tempDir, apkEntry.name.substringAfterLast('/'))
            extractedApk.parentFile?.mkdirs()
            zip.getInputStream(apkEntry).use { input ->
                FileOutputStream(extractedApk, false).use { output ->
                    input.copyTo(output)
                }
            }
            return extractedApk
        }
    }

    doLast {
        if (rendererLibsSource.isBlank()) {
            throw GradleException("Missing -PrendererLibsSource=<apk-or-zip-path>")
        }
        val source = file(rendererLibsSource)
        if (!source.isFile) {
            throw GradleException("Renderer backend source does not exist: ${source.absolutePath}")
        }

        val tempDir = layout.buildDirectory.dir("tmp/importRendererBackendLibs").get().asFile
        tempDir.mkdirs()
        val apkFile = extractApk(source, tempDir)
        ZipFile(apkFile).use { apkZip ->
            for (abi in listOf("arm64-v8a", "armeabi-v7a")) {
                val targetDir = file("src/main/jniLibs/$abi")
                targetDir.mkdirs()
                for (libraryName in rendererBackendImportLibraries) {
                    val entry = apkZip.getEntry("lib/$abi/$libraryName") ?: continue
                    val targetFile = File(targetDir, libraryName)
                    if (targetFile.exists()) {
                        logger.lifecycle("Skip existing renderer lib: ${targetFile.absolutePath}")
                        continue
                    }
                    apkZip.getInputStream(entry).use { input ->
                        FileOutputStream(targetFile, false).use { output ->
                            input.copyTo(output)
                        }
                    }
                    logger.lifecycle("Imported renderer lib: ${targetFile.absolutePath}")
                }
            }

            val angleLicenseEntry = apkZip.getEntry("assets/licenses/ANGLE_LICENSE")
            if (angleLicenseEntry != null) {
                val licenseFile = file("src/main/assets/licenses/ANGLE_LICENSE")
                if (!licenseFile.exists()) {
                    licenseFile.parentFile?.mkdirs()
                    apkZip.getInputStream(angleLicenseEntry).use { input ->
                        FileOutputStream(licenseFile, false).use { output ->
                            input.copyTo(output)
                        }
                    }
                    logger.lifecycle("Imported ANGLE license: ${licenseFile.absolutePath}")
                } else {
                    logger.lifecycle("Skip existing ANGLE license: ${licenseFile.absolutePath}")
                }
            }
        }
    }
}

private fun String?.toBuildConfigStringLiteral(): String {
    val value = this ?: ""
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
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

    fun runAdbCommand(args: List<String>): AdbCommandResult {
        val output = providers.exec {
            val command = mutableListOf(adb)
            if (deviceSerial.isNotEmpty()) {
                command.addAll(listOf("-s", deviceSerial))
            }
            command.addAll(args)
            commandLine(command)
            isIgnoreExitValue = true
        }
        return AdbCommandResult(
            exitCode = output.result.get().exitValue,
            stdout = output.standardOutput.asText.get()
        )
    }

    fun runDeviceCommand(
        accessMode: RemoteFileAccessMode,
        shellArgs: List<String>,
        runAsArgs: List<String> = shellArgs
    ): AdbCommandResult {
        val command = when (accessMode) {
            RemoteFileAccessMode.SHELL -> listOf("shell") + shellArgs
            RemoteFileAccessMode.RUN_AS -> listOf("shell", "run-as", packageName) + runAsArgs
        }
        return runAdbCommand(command)
    }

    fun devicePathExists(
        remotePath: String,
        accessMode: RemoteFileAccessMode
    ): Boolean {
        return runDeviceCommand(
            accessMode = accessMode,
            shellArgs = listOf("ls", remotePath)
        ).exitCode == 0
    }

    fun resolveDeviceStsPaths(): DeviceStsPaths {
        val externalFilesCandidates = linkedSetOf(
            "/sdcard/Android/data/$packageName/files",
            "/storage/emulated/0/Android/data/$packageName/files"
        )

        for (externalFilesDir in externalFilesCandidates) {
            if (devicePathExists(externalFilesDir, RemoteFileAccessMode.SHELL)) {
                return DeviceStsPaths(
                    stsRoot = "$externalFilesDir/sts",
                    accessMode = RemoteFileAccessMode.SHELL
                )
            }
        }

        if (devicePathExists("files/sts", RemoteFileAccessMode.RUN_AS)) {
            return DeviceStsPaths(
                stsRoot = "files/sts",
                accessMode = RemoteFileAccessMode.RUN_AS
            )
        }

        return DeviceStsPaths(
            stsRoot = "/sdcard/Android/data/$packageName/files/sts",
            accessMode = RemoteFileAccessMode.SHELL
        )
    }

    fun resolveRemotePath(paths: DeviceStsPaths, relativePath: String): String {
        val trimmed = relativePath.trimStart('/')
        return if (paths.accessMode == RemoteFileAccessMode.RUN_AS) {
            "${paths.stsRoot}/$trimmed"
        } else {
            "${paths.stsRoot}/$trimmed"
        }
    }

    fun remoteFileExists(paths: DeviceStsPaths, relativePath: String): Boolean {
        return devicePathExists(
            resolveRemotePath(paths, relativePath),
            paths.accessMode
        )
    }

    fun readRemoteFile(paths: DeviceStsPaths, relativePath: String): String {
        val remotePath = resolveRemotePath(paths, relativePath)
        val result = runDeviceCommand(
            accessMode = paths.accessMode,
            shellArgs = listOf("cat", remotePath)
        )
        return if (result.exitCode == 0) result.stdout else ""
    }

    fun listArchivedJvmLogNames(paths: DeviceStsPaths): List<String> {
        val logsDirPath = resolveRemotePath(paths, "jvm_logs")
        val result = runDeviceCommand(
            accessMode = paths.accessMode,
            shellArgs = listOf("ls", logsDirPath)
        )
        if (result.exitCode != 0) {
            return emptyList()
        }
        return result.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.matches(Regex("""jvm_log_.*\.log""")) }
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
        val deviceStsPaths = resolveDeviceStsPaths()
        logger.lifecycle(
            "Resolved device stsRoot: ${deviceStsPaths.stsRoot} (${deviceStsPaths.accessMode.name.lowercase(Locale.US)})"
        )

        val latestExists = remoteFileExists(deviceStsPaths, "latest.log")
        if (latestExists) {
            logger.lifecycle("Pulling shared JVM log: latest.log")
            pulledLogs.add(
                PulledLog(
                    fileName = "latest.log",
                    content = readRemoteFile(deviceStsPaths, "latest.log")
                )
            )
        } else {
            logger.lifecycle("latest.log not found on device.")
        }

        if (remoteFileExists(deviceStsPaths, "boot_bridge_events.log")) {
            logger.lifecycle("Pulling shared JVM log: boot_bridge_events.log")
            pulledLogs.add(
                PulledLog(
                    fileName = "boot_bridge_events.log",
                    content = readRemoteFile(deviceStsPaths, "boot_bridge_events.log")
                )
            )
        } else {
            logger.lifecycle("boot_bridge_events.log not found on device.")
        }

        val archivedLimit = if (latestExists) {
            stsJvmLogExportMaxSlots - 1
        } else {
            stsJvmLogExportMaxSlots
        }
        val archivedNames = listArchivedJvmLogNames(deviceStsPaths).take(archivedLimit)
        if (archivedNames.isEmpty()) {
            logger.lifecycle("No archived jvm_log_*.log found on device.")
        }
        for (name in archivedNames) {
            if (!remoteFileExists(deviceStsPaths, "jvm_logs/$name")) {
                continue
            }
            logger.lifecycle("Pulling shared JVM log: $name")
            pulledLogs.add(
                PulledLog(
                    fileName = name,
                    content = readRemoteFile(deviceStsPaths, "jvm_logs/$name")
                )
            )
        }

        val archiveFile = File(outputDir, buildJvmLogExportFileName())
        FileOutputStream(archiveFile, false).use { output ->
            ZipOutputStream(output).use { zipOutput ->
                if (pulledLogs.isEmpty()) {
                    val entry = ZipEntry("sts/jvm_logs/README.txt")
                    zipOutput.putNextEntry(entry)
                    val message = "No JVM logs found.\n" +
                        "Expected files:\n" +
                        "- ${resolveRemotePath(deviceStsPaths, "latest.log")}\n" +
                        "- ${resolveRemotePath(deviceStsPaths, "jvm_logs")}\n"
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
