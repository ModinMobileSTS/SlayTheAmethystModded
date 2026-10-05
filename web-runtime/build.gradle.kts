import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.stamethyst.webruntime"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.stamethyst.webruntime"
        minSdk = 28
        targetSdk = 33
        versionCode = 1
        versionName = "${libs.versions.geckoview.get()}-v1"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            // This is a resource/code container, NOT an installable companion application.
            signingConfig = null
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { jniLibs.useLegacyPackaging = true }
}

dependencies { implementation(libs.mozilla.geckoview) }

val generatedSources = layout.buildDirectory.dir("generated/children")
val generateChildServices by tasks.registering {
    outputs.dir(generatedSources)
    doLast {
        val source = generatedSources.get().file("io/stamethyst/webruntime/ChildServices.java").asFile
        source.parentFile.mkdirs()
        source.writeText(buildString {
            append("package io.stamethyst.webruntime;\npublic final class ChildServices {\n")
            for (i in 0 until 40) append("public static final class tab$i extends RuntimeChildService {}\n")
            for (name in listOf("gmplugin", "socket", "rdd", "utility", "ipdlunittest"))
                append("public static final class $name extends RuntimeChildService {}\n")
            append("public static final class gpu extends RuntimeGpuService {}\n}\n")
        })
    }
}
android.sourceSets.getByName("main").java.srcDir(generatedSources)
tasks.named("preBuild") { dependsOn(generateChildServices) }

tasks.register("bundleWebRuntime") {
    group = "distribution"
    description = "Build the download-only GeckoView ZIP and CDN checksum metadata."
    dependsOn("assembleRelease")
    val apk = layout.buildDirectory.file("outputs/apk/release/web-runtime-release-unsigned.apk")
    inputs.file(apk)
    val out = layout.buildDirectory.dir("outputs/web-dependency")
    outputs.dir(out)
    doLast {
        val version = "${libs.versions.geckoview.get()}-v1"
        val directory = out.get().asFile.apply { mkdirs() }
        val archive = directory.resolve("geckoview-$version-arm64-v8a.zip")
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            // The container already compresses its large assets and native libraries.
            zip.setLevel(0)
            zip.putNextEntry(ZipEntry("runtime.apk").apply { time = 0 })
            apk.get().asFile.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        val digest = MessageDigest.getInstance("SHA-256")
        archive.inputStream().use { stream ->
            val buffer = ByteArray(1024 * 1024)
            while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        directory.resolve("${archive.name}.sha256").writeText("$sha  ${archive.name}\n")
        directory.resolve("runtime.properties").writeText("version=$version\nsha256=$sha\n")
        directory.resolve("manifest.json").writeText("""
            {"schema":1,"version":"$version","abi":"arm64-v8a","minSdk":28,"file":"${archive.name}","bytes":${archive.length()},"sha256":"$sha","geckoview":"${libs.versions.geckoview.get()}","installRequired":false,"source":"https://archive.mozilla.org/pub/mobile/releases/148.0/source/","license":"MPL-2.0"}
        """.trimIndent() + "\n")
        logger.lifecycle("Web dependency: $archive\nSHA-256: $sha")
    }
}
