import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Sync
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val generatedRuntimeAssetsDir = layout.buildDirectory.dir("generated/runtime-assets")
val appVersionName = "0.0.4"

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
    "last_crash_report.txt"
)

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
    dependsOn(installRuntimePackAssets)
}

tasks.register("stsStart") {
    group = "debug"
    description = "Start SlayTheAmethyst on a connected Android device."
    doLast {
        val launchMode = (findProperty("launchMode")?.toString() ?: "mts_basemod").trim()
        if (!supportedLaunchModes.contains(launchMode)) {
            throw GradleException(
                "Unsupported launchMode: $launchMode. Supported: ${supportedLaunchModes.joinToString(", ")}"
            )
        }

        exec {
            commandLine(
                project.adbCommand(
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
        }
    }
}

tasks.register("stsStop") {
    group = "debug"
    description = "Force stop SlayTheAmethyst on a connected Android device."
    doLast {
        exec {
            commandLine(
                project.adbCommand(
                    "shell",
                    "am",
                    "force-stop",
                    stsPackageName
                )
            )
        }
    }
}

tasks.register("stsPullLogs") {
    group = "debug"
    description = "Export SlayTheAmethyst runtime logs from device to a local directory."
    doLast {
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
        for (name in stsLogFiles) {
            val output = ByteArrayOutputStream()
            val errors = ByteArrayOutputStream()
            val result = exec {
                isIgnoreExitValue = true
                commandLine(
                    project.adbCommand(
                        "exec-out",
                        "run-as",
                        stsPackageName,
                        "sh",
                        "-c",
                        "cat files/sts/$name 2>/dev/null"
                    )
                )
                standardOutput = output
                errorOutput = errors
            }
            val bytes = output.toByteArray()
            if (result.exitValue == 0 && bytes.isNotEmpty()) {
                File(outputDir, name).writeBytes(bytes)
                pulled.add(name)
            } else {
                missing.add(name)
            }
        }

        val logcatOut = ByteArrayOutputStream()
        exec {
            isIgnoreExitValue = true
            commandLine(project.adbCommand("logcat", "-d", "-t", "2000"))
            standardOutput = logcatOut
            errorOutput = ByteArrayOutputStream()
        }
        if (logcatOut.size() > 0) {
            File(outputDir, "logcat.txt").writeBytes(logcatOut.toByteArray())
            pulled.add("logcat.txt")
        }

        logger.lifecycle("SlayTheAmethyst logs exported to: ${outputDir.absolutePath}")
        logger.lifecycle("Pulled: ${pulled.joinToString(", ").ifBlank { "(none)" }}")
        if (missing.isNotEmpty()) {
            logger.lifecycle("Missing/empty on device: ${missing.joinToString(", ")}")
        }
    }
}
