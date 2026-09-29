import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Properties
import java.util.zip.ZipFile
import org.gradle.api.tasks.PathSensitivity

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    id("io.stamethyst.android-app-build")
}

// Incremental dex outputs can occasionally go stale while the Java compilation output is intact.
// Fail the debug build instead of producing an APK that crashes before Application.onCreate.
val verifyDebugApkEntrypoints = tasks.register("verifyDebugApkEntrypoints") {
    dependsOn("packageDebug")
    val apk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")
    inputs.file(apk)
    doLast {
        val requiredClasses = listOf(
            "Lio/stamethyst/StsApplication;",
            "Lio/stamethyst/LauncherActivity;",
        )
        ZipFile(apk.get().asFile).use { archive ->
            val classes = archive.entries().asSequence()
                .filter { it.name.matches(Regex("classes[0-9]*\\.dex")) }
                .flatMap { entry ->
                    val bytes = archive.getInputStream(entry).use { it.readBytes() }
                    val dex = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    val stringIdsOffset = dex.getInt(0x3c)
                    val typeIdsOffset = dex.getInt(0x44)
                    val classDefsSize = dex.getInt(0x60)
                    val classDefsOffset = dex.getInt(0x64)
                    (0 until classDefsSize).asSequence().map { index ->
                        val typeIndex = dex.getInt(classDefsOffset + index * 32)
                        val stringIndex = dex.getInt(typeIdsOffset + typeIndex * 4)
                        var offset = dex.getInt(stringIdsOffset + stringIndex * 4)
                        while (bytes[offset++].toInt() and 0x80 != 0) Unit // skip ULEB128 length
                        val start = offset
                        while (bytes[offset] != 0.toByte()) offset++
                        String(bytes, start, offset - start, Charsets.UTF_8)
                    }.toList().asSequence()
                }.toSet()
            val missing = requiredClasses.filterNot { it in classes }
            check(missing.isEmpty()) {
                "Debug APK is missing startup classes: ${missing.joinToString()}. " +
                    "Rebuild with :app:assembleDebug --rerun-tasks before installing."
            }
        }
    }
}
tasks.matching { it.name == "assembleDebug" }.configureEach {
    dependsOn(verifyDebugApkEntrypoints)
}

dependencies {
    implementation(libs.androidx.games.frame.pacing)
}

val packageName = readGradleProperty("application.id")
val appVersionName = readGradleProperty("application.version.name")
val appVersionCode = readGradleProperty("application.version.code").toInt()
val feedbackApiKey = readGradleProperty("feedback.apiKey")
val swappyFramePacingEnabled =
    readGradleProperty("swappyEnabled", "true").toBooleanStrictOrNull() ?: true

val localProperties = Properties().apply {
    val file = rootProject.layout.projectDirectory.file("local.properties").asFile
    if (file.isFile) {
        file.reader(StandardCharsets.UTF_8).use(::load)
    }
}

fun readLocalProperty(name: String): String =
    localProperties.getProperty(name)?.trim().orEmpty()

fun readReleaseSigningProperty(envName: String, gradlePropertyName: String): String =
    providers.environmentVariable(envName).orNull?.trim().orEmpty()
        .ifEmpty { readGradleProperty(gradlePropertyName, readLocalProperty(gradlePropertyName)) }

data class SigningMaterial(
    val storeFile: File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String
)

// The keystore and its credentials live together under build-deps/<kind>-signature/ so a
// fresh clone only needs one directory per variant. Environment variables and Gradle
// properties still win for release builds so CI keeps using its stored secrets.
fun readSignatureDirectory(kind: String): SigningMaterial? {
    val directory = rootProject.layout.projectDirectory.dir("build-deps/${kind}-signature").asFile
    if (!directory.isDirectory) {
        return null
    }

    val properties = Properties().apply {
        val file = File(directory, "signing.properties")
        if (file.isFile) {
            file.reader(StandardCharsets.UTF_8).use(::load)
        }
    }

    fun value(name: String, default: String = ""): String =
        properties.getProperty(name)?.trim().orEmpty().ifEmpty { default }

    val storeFile = File(directory, value("storeFile", "keystore.jks"))
    val storePassword = value("storePassword")
    val keyAlias = value("keyAlias")
    if (!storeFile.isFile || storePassword.isEmpty() || keyAlias.isEmpty()) {
        return null
    }
    return SigningMaterial(
        storeFile = storeFile,
        storePassword = storePassword,
        keyAlias = keyAlias,
        keyPassword = value("keyPassword").ifEmpty { storePassword }
    )
}

fun String?.toBuildConfigStringLiteral(): String =
    "\"" + (this ?: "").replace("\\", "\\\\").replace("\"", "\\\"") + "\""

fun Iterable<String>.toBuildConfigStringArrayLiteral(): String =
    joinToString(
        prefix = "new String[]{",
        postfix = "}"
    ) { value ->
        value.toBuildConfigStringLiteral()
    }

fun File.normalizedBuildPath(): String =
    absolutePath.replace('\\', '/').lowercase()

val releaseSignature = readSignatureDirectory("release")
val debugSignature = readSignatureDirectory("debug")
val releaseStoreFilePath = readReleaseSigningProperty("RELEASE_STORE_FILE", "release.storeFile")
    .ifEmpty { releaseSignature?.storeFile?.absolutePath.orEmpty() }
val releaseStorePassword = readReleaseSigningProperty("RELEASE_STORE_PASSWORD", "release.storePassword")
    .ifEmpty { releaseSignature?.storePassword.orEmpty() }
val releaseKeyAlias = readReleaseSigningProperty("RELEASE_KEY_ALIAS", "release.keyAlias")
    .ifEmpty { releaseSignature?.keyAlias.orEmpty() }
val releaseKeyPassword = readReleaseSigningProperty("RELEASE_KEY_PASSWORD", "release.keyPassword")
    .ifEmpty { releaseSignature?.keyPassword.orEmpty() }
val defaultResourcePackDownloadUrl =
    "https://github.com/ModinMobileSTS/SlayTheAmethystResource/releases/download/v1.6/resources.zip"
val defaultResourcePackDownloadFallbackUrls = listOf(
    "https://gitee.com/apricityx/SlayTheAmethystResource/releases/download/v1.6/resources.zip"
)
val defaultCloudControlConfigUrl =
    "https://github.com/ModinMobileSTS/SlayTheAmethystResource/releases/download/Resource/cloud-control.json"
val configuredResourcePackDownloadUrl = readGradleProperty(
    "resourcePack.downloadUrl",
    readLocalProperty("resourcePack.downloadUrl").ifEmpty { defaultResourcePackDownloadUrl }
)
val resourcePackDownloadUrls = buildList {
    add(configuredResourcePackDownloadUrl)
    addAll(defaultResourcePackDownloadFallbackUrls)
}.map(String::trim)
    .filter(String::isNotEmpty)
    .distinct()
val resourcePackDownloadUrl = resourcePackDownloadUrls.firstOrNull().orEmpty()
// Hosted pack identity. Keep this aligned with the archive at the download URLs;
// bumping it forces every player to re-download.
val resourcePackVersion = readGradleProperty(
    "resourcePack.version",
    readLocalProperty("resourcePack.version").ifEmpty { "resources-v1.6" }
)
val resourcePackSha256 = readGradleProperty(
    "resourcePack.sha256",
    readLocalProperty("resourcePack.sha256")
)
val cloudControlConfigUrl = readGradleProperty(
    "cloudControl.configUrl",
    readLocalProperty("cloudControl.configUrl").ifEmpty { defaultCloudControlConfigUrl }
)
val hasReleaseSigning = listOf(
    releaseStoreFilePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all(String::isNotEmpty)
// Signing config used when no release keystore is configured: the shared repository debug
// keystore when present, otherwise AGP's built-in debug config.
val fallbackSigningConfigName = if (debugSignature != null) "sharedDebug" else "debug"
val isReleaseTaskRequested = gradle.startParameter.taskNames.any { taskName ->
    taskName.contains("Release", ignoreCase = true)
}

if (hasReleaseSigning && !File(releaseStoreFilePath).isFile) {
    throw GradleException(
        "Release keystore does not exist: $releaseStoreFilePath. " +
            "Place it under build-deps/release-signature/ or set RELEASE_STORE_FILE."
    )
}
if (isReleaseTaskRequested && !hasReleaseSigning) {
    logger.warn(
        "Release signing configuration missing; falling back to the debug signing config " +
            "for local release tasks. Provide build-deps/release-signature/ or RELEASE_STORE_* env vars."
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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "FEEDBACK_BASE_URL", "\"http://1315061624-boxfc2p5fb.ap-guangzhou.tencentscf.com\"")
        buildConfigField("String", "FEEDBACK_ENDPOINT", "\"http://1315061624-boxfc2p5fb.ap-guangzhou.tencentscf.com/api/sts-feedback\"")
        buildConfigField("String", "FEEDBACK_API_KEY", feedbackApiKey.toBuildConfigStringLiteral())
        buildConfigField("String", "FEEDBACK_GITHUB_OWNER", "\"ModinMobileSTS\"")
        buildConfigField("String", "FEEDBACK_GITHUB_REPO", "\"SlayTheAmethystModded\"")
        buildConfigField("String", "RESOURCE_PACK_DOWNLOAD_URL", resourcePackDownloadUrl.toBuildConfigStringLiteral())
        buildConfigField("String[]", "RESOURCE_PACK_DOWNLOAD_URLS", resourcePackDownloadUrls.toBuildConfigStringArrayLiteral())
        buildConfigField("String", "RESOURCE_PACK_VERSION", resourcePackVersion.toBuildConfigStringLiteral())
        buildConfigField("String", "RESOURCE_PACK_SHA256", resourcePackSha256.toBuildConfigStringLiteral())
        buildConfigField("String", "CLOUD_CONTROL_CONFIG_URL", cloudControlConfigUrl.toBuildConfigStringLiteral())
        buildConfigField("boolean", "SWAPPY_FRAME_PACING_ENABLED", swappyFramePacingEnabled.toString())

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
        // A repository-local debug keystore so every checkout shares one debug signature.
        // Left untouched when build-deps/debug-signature/ is absent.
        debugSignature?.let { signature ->
            create("sharedDebug") {
                storeFile = signature.storeFile
                storePassword = signature.storePassword
                keyAlias = signature.keyAlias
                keyPassword = signature.keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName(fallbackSigningConfigName)
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("fastSlimRelease") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            matchingFallbacks += "release"
        }
        create("fastFullRelease") {
            initWith(getByName("fastSlimRelease"))
            matchingFallbacks += "release"
        }
        create("fullRelease") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
        }
        debug {
            isMinifyEnabled = false
            if (debugSignature != null) {
                signingConfig = signingConfigs.getByName("sharedDebug")
            }
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
        resources.excludes += setOf(
            "org/bouncycastle/pqc/crypto/picnic/**",
            "org/bouncycastle/x509/CertPathReviewerMessages*.properties"
        )
    }

    buildFeatures {
        compose = true
        prefab = true
        buildConfig = true
    }

    @Suppress("UnstableApiUsage")
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
            // LauncherConfigFloatingToolButtonsDefaultsTest reads the mod source to check that
            // both sides agree on the tool button ids, so edits to it must invalidate the task.
            it.inputs.file(
                rootProject.file(
                    "mods/amethyst-floating-tools/src/main/java/io/stamethyst/" +
                        "floatingtools/FloatingToolPanel.java"
                )
            ).withPropertyName("floatingToolPanelSource")
                .withPathSensitivity(PathSensitivity.RELATIVE)
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

val staleGradleCachesPathPattern = Regex(
    """[a-z]:/(?:[^/\p{Cntrl}\s"']+/)*\.gradle/caches/""",
    RegexOption.IGNORE_CASE
)
val currentGradleCachesPath = gradle.gradleUserHomeDir.resolve("caches")
    .normalizedBuildPath()
    .trimEnd('/')
val sanitizeExternalNativeBuildCaches by tasks.registering {
    group = "build"
    description = "Deletes stale Ninja dependency caches that reference another Gradle user cache."

    doLast {
        val cxxDir = layout.projectDirectory.dir(".cxx").asFile
        if (!cxxDir.isDirectory) {
            return@doLast
        }

        cxxDir.walkTopDown()
            .filter { it.isFile && it.name == ".ninja_deps" }
            .forEach { ninjaDeps ->
                val contents = ninjaDeps.readBytes()
                    .toString(Charsets.ISO_8859_1)
                    .replace('\\', '/')
                    .lowercase()
                val staleGradleCachesPath = staleGradleCachesPathPattern.findAll(contents)
                    .map { it.value.trimEnd('/') }
                    .firstOrNull { it != currentGradleCachesPath }

                if (staleGradleCachesPath != null) {
                    ninjaDeps.delete()
                    logger.lifecycle(
                        "Deleted stale Ninja dependency cache ${ninjaDeps.relativeTo(projectDir)} " +
                            "(referenced $staleGradleCachesPath)"
                    )
                }
            }
    }
}

tasks.matching {
    it.name.startsWith("buildCMake") || it.name.startsWith("externalNativeBuild")
}.configureEach {
    dependsOn(sanitizeExternalNativeBuildCaches)
}

tasks.matching {
    it.name in setOf(
        "generateFastSlimReleaseLintVitalReportModel",
        "lintVitalAnalyzeFastSlimRelease",
        "lintVitalReportFastSlimRelease",
        "lintVitalFastSlimRelease",
        "generateFastFullReleaseLintVitalReportModel",
        "lintVitalAnalyzeFastFullRelease",
        "lintVitalReportFastFullRelease",
        "lintVitalFastFullRelease"
    )
}.configureEach {
    enabled = false
}

val marketNetworkAcceptanceTestClass =
    "io.stamethyst.backend.steamcloud.SteamCommunityMarketNetworkAcceptanceTest"

tasks.matching { it.name == "testDebugUnitTest" }.configureEach {
    if (gradle.startParameter.taskNames.any { it.endsWith("marketNetworkAcceptanceTest") }) {
        (this as org.gradle.api.tasks.testing.Test).filter {
            includeTestsMatching(marketNetworkAcceptanceTestClass)
        }
    }
}

tasks.register("marketNetworkAcceptanceTest") {
    group = "verification"
    description = "Runs the opt-in live Watt Steam Community market acceptance test only."
    dependsOn("testDebugUnitTest")
}

configurations.configureEach {
    resolutionStrategy.force(
        "org.jetbrains.kotlin:kotlin-reflect:${libs.versions.kotlin.get()}"
    )
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":lan-core"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.okhttpBom))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.stream.chat.android.compose)
    implementation(libs.telephoto.zoomable.image.coil3)
    implementation(libs.coil3.network.okhttp)
    implementation(libs.haze)
    implementation(libs.haze.materials)
    implementation(libs.okhttp)
    implementation(libs.reorderable)
    implementation("com.vanniktech:android-image-cropper:4.7.0")
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.security.crypto)
    implementation("in.dragonbra:javasteam:1.6.0")
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.protobuf.java)
    implementation("org.slf4j:slf4j-nop:2.0.17")
    implementation(libs.tukaani.xz)
    implementation(libs.apache.commons.compress)
    implementation(libs.bytedance.bytehook)
    implementation(libs.android.zstd)
    implementation(libs.ow2.asm)
    implementation(libs.ow2.asm.tree)
    implementation(libs.eclipse.ecj)
    implementation(libs.cfr.decompiler)
    implementation(libs.lottie.compose)
    implementation(libs.langchain4j.open.ai)
    implementation(libs.langchain4j.http.client.okhttp) {
        // LangChain4j publishes a JVM variant, but Android already provides the same OkHttp API.
        exclude(group = "com.squareup.okhttp3", module = "okhttp-jvm")
    }
    implementation(project(":workshop-core"))
    implementation(project(":steam-protocol"))

    testImplementation(platform(libs.okhttpBom))
    testImplementation("org.json:json:20240303")
    testImplementation(libs.junit4)
    testImplementation(libs.mockwebserver3)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.apache.commons.compress)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
