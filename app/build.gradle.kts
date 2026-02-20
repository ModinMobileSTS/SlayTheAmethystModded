import java.util.zip.ZipFile
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runtimePackPath = providers.environmentVariable("STS_JRE8_PACK")
    .orElse(providers.gradleProperty("sts.jre8.pack"))
    .orElse("runtime-pack/jre8-pojav.zip")
val runtimePackFile = rootProject.layout.projectDirectory.file(runtimePackPath.get()).asFile
val generatedRuntimeAssetsDir = layout.buildDirectory.dir("generated/runtime-assets")
val bootBridgeSourceDir = rootProject.layout.projectDirectory.dir("tools/boot-bridge-src")
val bootBridgeClassesDir = layout.buildDirectory.dir("generated/boot-bridge/classes")
val gdxPatchSourceDir = rootProject.layout.projectDirectory.dir("tools/gdx-patch-src")
val gdxPatchClassesDir = layout.buildDirectory.dir("generated/gdx-patch/classes")
val gdxPatchOutputDir = project.layout.projectDirectory.dir("src/main/assets/components/gdx_patch")
val desktopJarFile = rootProject.layout.projectDirectory.file("tools/desktop-1.0.jar").asFile
val lwjglGlfwClassesJarFile =
    project.layout.projectDirectory.file("src/main/assets/components/lwjgl3/lwjgl-glfw-classes.jar").asFile

val prepareEmbeddedJrePack by tasks.registering {
    outputs.dir(generatedRuntimeAssetsDir)

    doLast {
        if (!runtimePackFile.exists()) {
            throw GradleException("Missing runtime pack: ${runtimePackFile.absolutePath}. Please place jre8-pojav.zip there.")
        }

        val outDir = generatedRuntimeAssetsDir.get().asFile
        val targetDir = File(outDir, "components/jre")
        targetDir.deleteRecursively()
        targetDir.mkdirs()

        ZipFile(runtimePackFile).use { zip ->
            val required = setOf("universal.tar.xz", "version")
            val archCandidates = mapOf(
                "bin-aarch64.tar.xz" to listOf("bin-aarch64.tar.xz", "bin-arm64.tar.xz"),
                "bin-arm.tar.xz" to listOf("bin-arm.tar.xz", "bin-armeabi-v7a.tar.xz")
            )
            val matched = mutableMapOf<String, java.util.zip.ZipEntry>()
            val matchedArch = mutableMapOf<String, java.util.zip.ZipEntry>()
            zip.entries().asSequence().forEach { entry ->
                if (entry.isDirectory) return@forEach
                val name = entry.name.replace('\\', '/')
                val shortName = name.substringAfterLast('/')
                if (shortName in required && shortName !in matched) {
                    matched[shortName] = entry
                    return@forEach
                }
                archCandidates.forEach { (canonicalName, candidates) ->
                    if (canonicalName !in matchedArch && shortName in candidates) {
                        matchedArch[canonicalName] = entry
                    }
                }
            }

            val missing = mutableListOf<String>()
            required.filterTo(missing) { it !in matched.keys }
            archCandidates.forEach { (canonicalName, candidates) ->
                if (canonicalName !in matchedArch) {
                    missing += candidates.joinToString("/")
                }
            }
            if (missing.isNotEmpty()) {
                throw GradleException("Invalid runtime pack, missing: ${missing.joinToString(", ")}")
            }

            matched.forEach { (shortName, entry) ->
                val outFile = File(targetDir, shortName)
                zip.getInputStream(entry).use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
            }

            matchedArch.forEach { (canonicalName, entry) ->
                val outArchFile = File(targetDir, canonicalName)
                zip.getInputStream(entry).use { input ->
                    outArchFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }
}

val compileBootBridgeJava by tasks.registering(JavaCompile::class) {
    source = fileTree(bootBridgeSourceDir) {
        include("**/*.java")
    }
    classpath = files()
    destinationDirectory.set(bootBridgeClassesDir)
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
    options.encoding = "UTF-8"
    doFirst {
        if (source.files.isEmpty()) {
            throw GradleException("Missing boot bridge sources under ${bootBridgeSourceDir.asFile.absolutePath}")
        }
    }
}

val packageBootBridgeJar by tasks.registering(Jar::class) {
    dependsOn(compileBootBridgeJava)
    archiveFileName.set("boot-bridge.jar")
    destinationDirectory.set(generatedRuntimeAssetsDir.map { it.dir("components/boot_bridge") })
    from(bootBridgeClassesDir)
}

val compileGdxPatchJava by tasks.registering(JavaCompile::class) {
    source = fileTree(gdxPatchSourceDir) {
        include("**/*.java")
    }
    classpath = files(desktopJarFile, lwjglGlfwClassesJarFile)
    destinationDirectory.set(gdxPatchClassesDir)
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-proc:none", "-g:source,lines,vars", "-Xlint:-options"))
    doFirst {
        if (source.files.isEmpty()) {
            throw GradleException("Missing gdx patch sources under ${gdxPatchSourceDir.asFile.absolutePath}")
        }
        if (!desktopJarFile.exists()) {
            throw GradleException("Missing desktop jar: ${desktopJarFile.absolutePath}")
        }
        if (!lwjglGlfwClassesJarFile.exists()) {
            throw GradleException("Missing lwjgl bridge jar: ${lwjglGlfwClassesJarFile.absolutePath}")
        }
    }
}

val packageGdxPatchJar by tasks.registering(Jar::class) {
    dependsOn(compileGdxPatchJava)
    archiveFileName.set("gdx-patch.jar")
    destinationDirectory.set(gdxPatchOutputDir)
    from(gdxPatchClassesDir)
    from(gdxPatchSourceDir) {
        include("build.properties")
    }
}

android {
    namespace = "io.stamethyst"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.stamethyst"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.3"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        externalNativeBuild {
            ndkBuild {
                arguments += listOf("APP_SHORT_COMMANDS=true")
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

    kotlinOptions {
        jvmTarget = "1.8"
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(generatedRuntimeAssetsDir)
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        jniLibs.pickFirsts += setOf("**/libbytehook.so")
    }

    buildFeatures {
        compose = true
        prefab = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

tasks.named("preBuild").configure {
    dependsOn(prepareEmbeddedJrePack)
    dependsOn(packageBootBridgeJar)
    dependsOn(packageGdxPatchJar)
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity:1.10.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.tukaani:xz:1.9")
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("com.bytedance:bytehook:1.0.9")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
