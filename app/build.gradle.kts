import com.android.build.api.artifact.SingleArtifact
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
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
val callbackBridgeTemplatesDir = rootProject.layout.projectDirectory.dir("gradle/callback-bridge/templates")
val callbackBridgeBaseJar = rootProject.layout.projectDirectory.file("gradle/callback-bridge/lwjgl-glfw-classes-base.jar")
val generatedAndroidCallbackBridgeDir: Provider<Directory> =
    layout.buildDirectory.dir("generated/source/callbackBridge/android")
val generatedJvmCallbackBridgeSourceDir: Provider<Directory> =
    layout.buildDirectory.dir("generated/source/callbackBridge/jvm")
val generatedJvmCallbackBridgeClassesDir: Provider<Directory> =
    layout.buildDirectory.dir("generated/classes/callbackBridge/jvm")
val generatedJvmCallbackBridgeJarContentsDir: Provider<Directory> =
    layout.buildDirectory.dir("generated/tmp/callbackBridge/jar-contents")
val packagedLwjglBridgeJarDir: Provider<Directory> =
    layout.buildDirectory.dir("generated/callbackBridgeRuntimeJar")
val generatedLwjglBridgeAssetDir: Provider<Directory> =
    generatedRuntimeAssetsDir.map { it.dir("components/lwjgl3") }
val localPropertiesFile = rootProject.layout.projectDirectory.file("local.properties").asFile
val localProperties = Properties().apply {
    if (localPropertiesFile.isFile) {
        localPropertiesFile.reader(StandardCharsets.UTF_8).use(::load)
    }
}
val releaseStoreFilePath = readReleaseSigningProperty("RELEASE_STORE_FILE", "release.storeFile")
val releaseStorePassword = readReleaseSigningProperty("RELEASE_STORE_PASSWORD", "release.storePassword")
val releaseKeyAlias = readReleaseSigningProperty("RELEASE_KEY_ALIAS", "release.keyAlias")
val releaseKeyPassword = readReleaseSigningProperty("RELEASE_KEY_PASSWORD", "release.keyPassword")
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
    logger.warn(
        "Release signing configuration missing; falling back to the debug signing config " +
            "for local release tasks."
    )
}

fun readLocalProperty(name: String): String =
    localProperties.getProperty(name)?.trim().orEmpty()

fun readReleaseSigningProperty(envName: String, gradlePropertyName: String): String =
    providers.environmentVariable(envName).orNull?.trim().orEmpty()
        .ifEmpty { readGradleProperty(gradlePropertyName, readLocalProperty(gradlePropertyName)) }

fun renderCallbackBridge(target: CallbackBridgeTarget): String {
    val templateFile = when (target) {
        CallbackBridgeTarget.ANDROID -> callbackBridgeTemplatesDir.file("android/CallbackBridge.java.tmpl").asFile
        CallbackBridgeTarget.JVM -> callbackBridgeTemplatesDir.file("jvm/CallbackBridge.java.tmpl").asFile
    }
    return CallbackBridgeCodegen.renderTemplate(
        templateFile.readText(StandardCharsets.UTF_8),
        target
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
            abiFilters += listOf("arm64-v8a")
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
            java.srcDir(generatedAndroidCallbackBridgeDir)
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
        jniLibs.pickFirsts += setOf(
            "**/libbytehook.so",
            "**/libc++_shared.so"
        )
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
                outputFileName = "SlayTheAmethyst-stable-$appVersionName.apk"
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
    implementation(platform(libs.okhttpBom))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.haze)
    implementation(libs.haze.materials)
    implementation(libs.okhttp)
    implementation(libs.reorderable)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.tukaani.xz)
    implementation(libs.apache.commons.compress)
    implementation(libs.bytedance.bytehook)
    implementation(libs.ow2.asm)
    implementation(libs.ow2.asm.tree)
    testImplementation(platform(libs.okhttpBom))
    testImplementation("org.json:json:20240303")
    testImplementation(libs.junit4)
    testImplementation(libs.mockwebserver3)
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
val bundledModProjectPaths = listOf(
    ":mods:amethyst-runtime-compat"
)
val log4jRuntimeComponents by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isVisible = false
}
val adb: String = androidComponents.sdkComponents.adb.get().asFile.absolutePath
val runtimePackZip: RegularFile = rootProject.layout.projectDirectory.file("runtime-pack/jre8-pojav.zip")
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

val generateAndroidCallbackBridgeSource by tasks.registering {
    val templateFile = callbackBridgeTemplatesDir.file("android/CallbackBridge.java.tmpl")
    inputs.file(templateFile)
    inputs.property("callbackBridgeContractHash", CallbackBridgeCodegen.contractHash)
    outputs.dir(generatedAndroidCallbackBridgeDir)
    doLast {
        val outputDir = generatedAndroidCallbackBridgeDir.get().asFile.resolve("org/lwjgl/glfw")
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }
        File(outputDir, "CallbackBridge.java").writeText(
            renderCallbackBridge(CallbackBridgeTarget.ANDROID),
            StandardCharsets.UTF_8
        )
    }
}

val generateJvmCallbackBridgeSource by tasks.registering {
    val templateFile = callbackBridgeTemplatesDir.file("jvm/CallbackBridge.java.tmpl")
    inputs.file(templateFile)
    inputs.property("callbackBridgeContractHash", CallbackBridgeCodegen.contractHash)
    outputs.dir(generatedJvmCallbackBridgeSourceDir)
    doLast {
        val outputDir = generatedJvmCallbackBridgeSourceDir.get().asFile.resolve("org/lwjgl/glfw")
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }
        File(outputDir, "CallbackBridge.java").writeText(
            renderCallbackBridge(CallbackBridgeTarget.JVM),
            StandardCharsets.UTF_8
        )
    }
}

val compileJvmCallbackBridge by tasks.registering(JavaCompile::class) {
    dependsOn(generateJvmCallbackBridgeSource)
    source(generatedJvmCallbackBridgeSourceDir)
    destinationDirectory.set(generatedJvmCallbackBridgeClassesDir)
    sourceCompatibility = JavaVersion.VERSION_1_8.toString()
    targetCompatibility = JavaVersion.VERSION_1_8.toString()
    options.release.set(8)
    classpath = files()
}

val prepareJvmCallbackBridgeJarContents by tasks.registering(Sync::class) {
    dependsOn(compileJvmCallbackBridge)
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    from(zipTree(callbackBridgeBaseJar))
    from(generatedJvmCallbackBridgeClassesDir)
    into(generatedJvmCallbackBridgeJarContentsDir)
}

val packageLwjglCallbackBridgeJar by tasks.registering(Zip::class) {
    dependsOn(prepareJvmCallbackBridgeJarContents)
    from(generatedJvmCallbackBridgeJarContentsDir)
    destinationDirectory.set(packagedLwjglBridgeJarDir)
    archiveFileName.set("lwjgl-glfw-classes.jar")
    archiveExtension.set("jar")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val generateLwjglBridgeVersion by tasks.registering {
    val androidTemplate = callbackBridgeTemplatesDir.file("android/CallbackBridge.java.tmpl")
    val jvmTemplate = callbackBridgeTemplatesDir.file("jvm/CallbackBridge.java.tmpl")
    val outputFile = generatedLwjglBridgeAssetDir.map { it.file("version") }
    inputs.file(callbackBridgeBaseJar)
    inputs.file(androidTemplate)
    inputs.file(jvmTemplate)
    inputs.property("callbackBridgeContractHash", CallbackBridgeCodegen.contractHash)
    outputs.file(outputFile)
    doLast {
        val targetFile = outputFile.get().asFile
        val parent = targetFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        targetFile.writeText(
            CallbackBridgeCodegen.fingerprint(
                CallbackBridgeCodegen.contractHash,
                androidTemplate.asFile.readText(StandardCharsets.UTF_8),
                jvmTemplate.asFile.readText(StandardCharsets.UTF_8),
                callbackBridgeBaseJar.asFile.length().toString(),
                callbackBridgeBaseJar.asFile.lastModified().toString()
            ),
            StandardCharsets.UTF_8
        )
    }
}

val installLwjglBridgeAssets by tasks.registering(Sync::class) {
    dependsOn(packageLwjglCallbackBridgeJar)
    dependsOn(generateLwjglBridgeVersion)
    from(packagedLwjglBridgeJarDir) {
        include("lwjgl-glfw-classes.jar")
    }
    from(generateLwjglBridgeVersion)
    into(generatedLwjglBridgeAssetDir)
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

val installBundledModJars by tasks.registering(Sync::class) {
    val bundledModJarTaskPaths = bundledModProjectPaths.map { projectPath -> "$projectPath:jar" }
    dependsOn(bundledModJarTaskPaths)
    bundledModProjectPaths.forEach { projectPath ->
        from(project(projectPath).layout.buildDirectory.dir("libs")) {
            include("*.jar")
        }
    }
    into(generatedRuntimeAssetsDir.map { it.dir("components/mods") })
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
        exclude("bin-arm.tar.xz", "bin-x86.tar.xz", "bin-x86_64.tar.xz")
    }
    into(generatedRuntimeAssetsDir.map { it.dir("components/jre") })
}

tasks.preBuild.configure {
    dependsOn(generateAndroidCallbackBridgeSource)
    dependsOn(installBootBridgeJar)
    dependsOn(installLwjglBridgeAssets)
    dependsOn(installPatchJars)
    dependsOn(installBundledModJars)
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
            for (abi in listOf("arm64-v8a")) {
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

    data class PulledEntry(
        val entryName: String,
        val content: ByteArray
    )

    fun runAdbCommand(args: List<String>): AdbCommandResult {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val execResult = project.exec {
            val adbCommand = mutableListOf(adb)
            if (deviceSerial.isNotEmpty()) {
                adbCommand.addAll(listOf("-s", deviceSerial))
            }
            adbCommand.addAll(args)
            commandLine(adbCommand)
            isIgnoreExitValue = true
            standardOutput = stdout
            errorOutput = stderr
        }
        return AdbCommandResult(
            exitCode = execResult.exitValue,
            stdout = stdout.toString(StandardCharsets.UTF_8)
        )
    }

    fun runAdbBinaryCommand(args: List<String>): ByteArray {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        project.exec {
            val adbCommand = mutableListOf(adb)
            if (deviceSerial.isNotEmpty()) {
                adbCommand.addAll(listOf("-s", deviceSerial))
            }
            adbCommand.addAll(args)
            commandLine(adbCommand)
            isIgnoreExitValue = true
            standardOutput = stdout
            errorOutput = stderr
        }
        return stdout.toByteArray()
    }

    fun runDeviceCommand(accessMode: RemoteFileAccessMode, command: List<String>): AdbCommandResult {
        val deviceCommand = when (accessMode) {
            RemoteFileAccessMode.SHELL -> listOf("shell") + command
            RemoteFileAccessMode.RUN_AS -> listOf("shell", "run-as", packageName) + command
        }
        return runAdbCommand(deviceCommand)
    }

    fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    fun runDeviceScript(accessMode: RemoteFileAccessMode, script: String): AdbCommandResult {
        return runDeviceCommand(accessMode, listOf("sh", "-c", script))
    }

    fun devicePathReadable(remotePath: String, accessMode: RemoteFileAccessMode): Boolean {
        return runDeviceScript(
            accessMode,
            "ls ${shellQuote(remotePath)} >/dev/null 2>&1"
        ).exitCode == 0
    }

    fun resolveDeviceStsPaths(): DeviceStsPaths {
        val candidates = listOf(
            DeviceStsPaths(
                stsRoot = "/sdcard/Android/data/$packageName/files/sts",
                accessMode = RemoteFileAccessMode.SHELL
            ),
            DeviceStsPaths(
                stsRoot = "/storage/emulated/0/Android/data/$packageName/files/sts",
                accessMode = RemoteFileAccessMode.SHELL
            ),
            DeviceStsPaths(
                stsRoot = "files/sts",
                accessMode = RemoteFileAccessMode.RUN_AS
            )
        )

        for (candidate in candidates) {
            if (devicePathReadable(candidate.stsRoot, candidate.accessMode)) {
                return candidate
            }
        }

        return candidates.first()
    }

    fun resolveRemotePath(paths: DeviceStsPaths, relativePath: String): String {
        val trimmed = relativePath.trimStart('/')
        return if (trimmed.isEmpty()) paths.stsRoot else "${paths.stsRoot}/$trimmed"
    }

    fun remoteFileExists(paths: DeviceStsPaths, relativePath: String): Boolean {
        return devicePathReadable(
            resolveRemotePath(paths, relativePath),
            paths.accessMode
        )
    }

    fun readRemoteFile(paths: DeviceStsPaths, relativePath: String): ByteArray? {
        val remotePath = resolveRemotePath(paths, relativePath)
        return when (paths.accessMode) {
            RemoteFileAccessMode.SHELL -> {
                if (!remoteFileExists(paths, relativePath)) {
                    return null
                }
                val tempFile = File(temporaryDir, relativePath.replace('/', '_'))
                if (tempFile.exists()) {
                    tempFile.delete()
                }
                val pullResult = runAdbCommand(
                    listOf("pull", remotePath, tempFile.absolutePath)
                )
                if (pullResult.exitCode != 0 || !tempFile.isFile) {
                    tempFile.delete()
                    null
                } else {
                    tempFile.readBytes().also {
                        tempFile.delete()
                    }
                }
            }

            RemoteFileAccessMode.RUN_AS -> {
                val bytes = runAdbBinaryCommand(
                    listOf(
                        "exec-out",
                        "run-as",
                        packageName,
                        "sh",
                        "-c",
                        "cat ${shellQuote(remotePath)}"
                    )
                )
                bytes.takeIf { it.isNotEmpty() || remoteFileExists(paths, relativePath) }
            }
        }
    }

    fun listRemoteFileNames(paths: DeviceStsPaths, relativePath: String): List<String> {
        val remotePath = resolveRemotePath(paths, relativePath)
        val result = when (paths.accessMode) {
            RemoteFileAccessMode.SHELL -> runAdbCommand(listOf("shell", "ls", remotePath))
            RemoteFileAccessMode.RUN_AS ->
                runAdbCommand(listOf("shell", "run-as", packageName, "ls", remotePath))
        }
        if (result.exitCode != 0) {
            return emptyList()
        }
        return result.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }

    fun listArchivedJvmLogNames(paths: DeviceStsPaths): List<String> {
        return listRemoteFileNames(paths, "jvm_logs")
            .filter { it.matches(Regex("""jvm_log_.*\.log""")) }
            .sortedDescending()
    }

    fun listHistogramNames(paths: DeviceStsPaths): List<String> {
        return listRemoteFileNames(paths, "jvm_histograms")
            .filter { it.endsWith(".txt", ignoreCase = true) }
            .sortedDescending()
            .take(6)
    }

    fun listLogcatCaptureNames(paths: DeviceStsPaths): List<String> {
        return listRemoteFileNames(paths, "logcat")
            .filter { name ->
                name.endsWith(".log", ignoreCase = true) ||
                    name.contains(".log.", ignoreCase = true)
            }
            .sorted()
    }

    fun normalizeInfoValue(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        return if (trimmed.isEmpty()) "unknown" else trimmed
    }

    fun readDeviceProp(key: String): String {
        return normalizeInfoValue(
            runAdbCommand(listOf("shell", "getprop", key)).stdout
        )
    }

    fun readPackageVersionInfo(): Pair<String, String> {
        val dump = runAdbCommand(listOf("shell", "dumpsys", "package", packageName)).stdout
        val versionName = dump.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("versionName=") }
            ?.substringAfter("versionName=")
            ?.trim()
        val versionCode = dump.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("versionCode=") }
            ?.substringAfter("versionCode=")
            ?.substringBefore(' ')
            ?.trim()
        return normalizeInfoValue(versionName) to normalizeInfoValue(versionCode)
    }

    fun buildJvmLogDeviceInfo(): String {
        val (versionName, versionCode) = readPackageVersionInfo()
        return buildString {
            append("launcher.package=").append(packageName).append('\n')
            append("launcher.versionName=").append(versionName).append('\n')
            append("launcher.versionCode=").append(versionCode).append('\n')
            append("device.manufacturer=").append(readDeviceProp("ro.product.manufacturer")).append('\n')
            append("device.brand=").append(readDeviceProp("ro.product.brand")).append('\n')
            append("device.model=").append(readDeviceProp("ro.product.model")).append('\n')
            append("device.device=").append(readDeviceProp("ro.product.device")).append('\n')
            append("device.product=").append(readDeviceProp("ro.product.name")).append('\n')
            append("device.hardware=").append(readDeviceProp("ro.hardware")).append('\n')
            append("android.release=").append(readDeviceProp("ro.build.version.release")).append('\n')
            append("android.sdkInt=").append(normalizeInfoValue(runAdbCommand(listOf("shell", "getprop", "ro.build.version.sdk")).stdout)).append('\n')
            append("android.securityPatch=").append(readDeviceProp("ro.build.version.security_patch")).append('\n')
            append("device.abis=").append(readDeviceProp("ro.product.cpu.abilist")).append('\n')
            append("device.fingerprint=").append(readDeviceProp("ro.build.fingerprint")).append('\n')
        }
    }

    fun buildHistogramSummary(histogramFiles: List<PulledEntry>): String {
        if (histogramFiles.isEmpty()) {
            return "No JVM histogram dumps captured yet.\n"
        }

        val latest = histogramFiles.first()
        val lines = String(latest.content, StandardCharsets.UTF_8).lineSequence().toList()
        val header = linkedMapOf<String, String>()
        val topClasses = ArrayList<String>(12)
        var inBody = false
        for (rawLine in lines) {
            val line = rawLine.trimEnd()
            if (!inBody) {
                if (line.isBlank()) {
                    inBody = true
                    continue
                }
                val separatorIndex = line.indexOf('=')
                if (separatorIndex > 0 && separatorIndex < line.length - 1) {
                    val key = line.substring(0, separatorIndex).trim()
                    val value = line.substring(separatorIndex + 1).trim()
                    if (key.isNotEmpty()) {
                        header[key] = value
                    }
                }
                continue
            }
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("num") || trimmed.startsWith("-")) {
                continue
            }
            topClasses.add(trimmed)
            if (topClasses.size >= 12) {
                break
            }
        }

        return buildString {
            append("Histogram files captured: ").append(histogramFiles.size).append('\n')
            append("Latest file: ").append(latest.entryName.substringAfterLast('/')).append('\n')
            header.forEach { (key, value) ->
                append(key).append('=').append(value).append('\n')
            }
            append('\n')
            append("Top classes from latest dump:\n")
            if (topClasses.isEmpty()) {
                append("(no class rows parsed)\n")
            } else {
                topClasses.forEach { row -> append(row).append('\n') }
            }
        }
    }

    fun buildJvmLogExportFileName(): String {
        val formatter = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        return "sts-jvm-logs-export-${formatter.format(Date())}.zip"
    }

    fun textEntry(entryName: String, content: String): PulledEntry {
        return PulledEntry(
            entryName = entryName,
            content = content.toByteArray(StandardCharsets.UTF_8)
        )
    }

    doLast {
        val outputDir = if (logsDir.isNotEmpty()) {
            file(logsDir)
        } else {
            layout.buildDirectory.dir("sts-logs").get().asFile
        }
        outputDir.mkdirs()

        val deviceStsPaths = resolveDeviceStsPaths()
        logger.lifecycle(
            "Resolved device stsRoot: ${deviceStsPaths.stsRoot} (${deviceStsPaths.accessMode.name.lowercase(Locale.US)})"
        )

        val pulledEntries = mutableListOf<PulledEntry>()
        val histogramEntries = mutableListOf<PulledEntry>()
        var exportedCount = 0

        pulledEntries.add(
            textEntry(
                entryName = "sts/jvm_logs/device_info.txt",
                content = buildJvmLogDeviceInfo()
            )
        )

        val latestExists = remoteFileExists(deviceStsPaths, "latest.log")
        if (latestExists) {
            logger.lifecycle("Pulling shared JVM log: latest.log")
            readRemoteFile(deviceStsPaths, "latest.log")?.let { content ->
                pulledEntries.add(
                    PulledEntry(
                        entryName = "sts/jvm_logs/latest.log",
                        content = content
                    )
                )
                exportedCount++
            }
        } else {
            logger.lifecycle("latest.log not found on device.")
        }

        val optionalLogPaths = listOf(
            "boot_bridge_events.log",
            "jvm_gc.log",
            "jvm_heap_snapshot.txt",
            "last_signal_dump.txt"
        )
        optionalLogPaths.forEach { relativePath ->
            if (!remoteFileExists(deviceStsPaths, relativePath)) {
                logger.lifecycle("$relativePath not found on device.")
                return@forEach
            }
            logger.lifecycle("Pulling shared JVM log: $relativePath")
            readRemoteFile(deviceStsPaths, relativePath)?.let { content ->
                if (content.isEmpty()) {
                    return@let
                }
                pulledEntries.add(
                    PulledEntry(
                        entryName = "sts/jvm_logs/${relativePath.substringAfterLast('/')}",
                        content = content
                    )
                )
                exportedCount++
            }
        }

        val logcatNames = listLogcatCaptureNames(deviceStsPaths)
        if (logcatNames.isEmpty()) {
            logger.lifecycle("No logcat capture files found on device.")
        }
        logcatNames.forEach { name ->
            logger.lifecycle("Pulling logcat capture: $name")
            readRemoteFile(deviceStsPaths, "logcat/$name")?.let { content ->
                pulledEntries.add(
                    PulledEntry(
                        entryName = "sts/logcat/$name",
                        content = content
                    )
                )
                exportedCount++
            }
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
            readRemoteFile(deviceStsPaths, "jvm_logs/$name")?.let { content ->
                pulledEntries.add(
                    PulledEntry(
                        entryName = "sts/jvm_logs/$name",
                        content = content
                    )
                )
                exportedCount++
            }
        }

        val histogramNames = listHistogramNames(deviceStsPaths)
        histogramNames.forEach { name ->
            if (!remoteFileExists(deviceStsPaths, "jvm_histograms/$name")) {
                return@forEach
            }
            logger.lifecycle("Pulling JVM histogram: $name")
            readRemoteFile(deviceStsPaths, "jvm_histograms/$name")?.let { content ->
                if (content.isEmpty()) {
                    return@let
                }
                histogramEntries.add(
                    PulledEntry(
                        entryName = "sts/jvm_histograms/$name",
                        content = content
                    )
                )
                exportedCount++
            }
        }

        pulledEntries.add(
            textEntry(
                entryName = "sts/jvm_histograms/summary.txt",
                content = buildHistogramSummary(histogramEntries)
            )
        )

        val archiveFile = File(outputDir, buildJvmLogExportFileName())
        FileOutputStream(archiveFile, false).use { output ->
            ZipOutputStream(output).use { zipOutput ->
                for (pulled in pulledEntries) {
                    val entry = ZipEntry(pulled.entryName)
                    zipOutput.putNextEntry(entry)
                    zipOutput.write(pulled.content)
                    zipOutput.closeEntry()
                }
                for (pulled in histogramEntries) {
                    val entry = ZipEntry(pulled.entryName)
                    zipOutput.putNextEntry(entry)
                    zipOutput.write(pulled.content)
                    zipOutput.closeEntry()
                }
                if (exportedCount <= 0) {
                    val entry = ZipEntry("sts/README.txt")
                    zipOutput.putNextEntry(entry)
                    val message = "No diagnostic logs found.\n" +
                        "Expected files under:\n" +
                        "- ${deviceStsPaths.stsRoot}\n"
                    zipOutput.write(message.toByteArray(StandardCharsets.UTF_8))
                    zipOutput.closeEntry()
                }
            }
        }

        logger.lifecycle("SlayTheAmethyst JVM logs exported to: ${archiveFile.absolutePath}")
        if (exportedCount <= 0) {
            logger.lifecycle("No diagnostic logs found on device; wrote README.txt into archive.")
        } else {
            val exportedNames = (pulledEntries + histogramEntries)
                .map { it.entryName.removePrefix("sts/") }
            logger.lifecycle("Pulled: ${exportedNames.joinToString(", ")}")
        }
    }
}
