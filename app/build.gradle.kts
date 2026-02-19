import java.util.zip.ZipFile
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar

plugins {
    id("com.android.application")
}

val runtimePackPath = providers.environmentVariable("STS_JRE8_PACK")
    .orElse(providers.gradleProperty("sts.jre8.pack"))
    .orElse("runtime-pack/jre8-pojav.zip")
val runtimePackFile = rootProject.layout.projectDirectory.file(runtimePackPath.get()).asFile
val generatedRuntimeAssetsDir = layout.buildDirectory.dir("generated/runtime-assets")
val bootBridgeSourceDir = rootProject.layout.projectDirectory.dir("tools/boot-bridge-src")
val bootBridgeClassesDir = layout.buildDirectory.dir("generated/boot-bridge/classes")

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
            val archCandidates = listOf("bin-aarch64.tar.xz", "bin-arm64.tar.xz")
            val matched = mutableMapOf<String, java.util.zip.ZipEntry>()
            var matchedArch: java.util.zip.ZipEntry? = null
            zip.entries().asSequence().forEach { entry ->
                if (entry.isDirectory) return@forEach
                val name = entry.name.replace('\\', '/')
                val shortName = name.substringAfterLast('/')
                if (shortName in required && shortName !in matched) {
                    matched[shortName] = entry
                } else if (matchedArch == null && shortName in archCandidates) {
                    matchedArch = entry
                }
            }

            val missing = mutableListOf<String>()
            required.filterTo(missing) { it !in matched.keys }
            if (matchedArch == null) {
                missing += "bin-aarch64.tar.xz/bin-arm64.tar.xz"
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

            val outArchFile = File(targetDir, "bin-aarch64.tar.xz")
            zip.getInputStream(matchedArch!!).use { input ->
                outArchFile.outputStream().use { output -> input.copyTo(output) }
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
            abiFilters += listOf("arm64-v8a")
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
        prefab = true
    }
}

tasks.named("preBuild").configure {
    dependsOn(prepareEmbeddedJrePack)
    dependsOn(packageBootBridgeJar)
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.tukaani:xz:1.9")
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("com.bytedance:bytehook:1.0.9")
}
