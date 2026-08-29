import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Properties
import org.gradle.api.tasks.PathSensitivity

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    id("io.stamethyst.android-app-build")
}

val packageName = readGradleProperty("application.id")
val appVersionName = readGradleProperty("application.version.name")
val appVersionCode = readGradleProperty("application.version.code").toInt()
val feedbackApiKey = readGradleProperty("feedback.apiKey")

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

val releaseStoreFilePath = readReleaseSigningProperty("RELEASE_STORE_FILE", "release.storeFile")
val releaseStorePassword = readReleaseSigningProperty("RELEASE_STORE_PASSWORD", "release.storePassword")
val releaseKeyAlias = readReleaseSigningProperty("RELEASE_KEY_ALIAS", "release.keyAlias")
val releaseKeyPassword = readReleaseSigningProperty("RELEASE_KEY_PASSWORD", "release.keyPassword")
val defaultResourcePackDownloadUrl =
    "https://github.com/ModinMobileSTS/SlayTheAmethystResource/releases/download/v1.4/resources.zip"
val defaultResourcePackDownloadFallbackUrls = listOf(
    "https://gitee.com/apricityx/SlayTheAmethystResource/releases/download/v1.4/resources.zip"
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
val resourcePackVersion = readGradleProperty(
    "resourcePack.version",
    readLocalProperty("resourcePack.version").ifEmpty { "resources-v1.4" }
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
        buildConfigField("String", "CLOUD_CONTROL_CONFIG_URL", cloudControlConfigUrl.toBuildConfigStringLiteral())

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
            isMinifyEnabled = true
            isShrinkResources = true
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
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
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
        jvmTarget = JvmTarget.JVM_1_8
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

dependencies {
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
    implementation(libs.lottie.compose)
    implementation(project(":workshop-core"))
    implementation(project(":steam-protocol"))

    testImplementation(platform(libs.okhttpBom))
    testImplementation("org.json:json:20240303")
    testImplementation(libs.junit4)
    testImplementation(libs.mockwebserver3)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.uiautomator)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
