plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.stamethyst.deltabench"
    compileSdk = 36
    ndkVersion = "27.0.12077973"
    defaultConfig {
        applicationId = "io.stamethyst.deltabench"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.5"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { cppFlags += "-O3"; cFlags += "-O3" } }
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }
    buildTypes {
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            matchingFallbacks += "release"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Compile the existing algorithm sources without pulling archive Kotlin/JVM-21 code
    // into Android. Only build-generated copies; supported archive APIs stay unchanged.
    sourceSets.getByName("main").java {
        srcDir(layout.buildDirectory.dir("algorithm-sources"))
    }
    sourceSets.getByName("main").assets {
        srcDir(layout.buildDirectory.dir("license-assets"))
        srcDir(layout.buildDirectory.dir("corpus-assets"))
    }
}

val benchmarkLicenses = tasks.register<Sync>("benchmarkLicenses") {
    from("../../archive-delta-generator") {
        include("LICENSE.archive-patcher", "UPSTREAM.md")
    }
    from("build/native-vendor/bsdiff") { include("LICENSE"); into("native/bsdiff") }
    from("build/native-vendor/HDiffPatch") {
        include("LICENSE", "libHDiffPatch/**/*.h", "libHDiffPatch/**/*.c", "libHDiffPatch/**/*.cpp")
        into("native/HDiffPatch")
    }
    from("build/native-vendor/xdelta/xdelta3") { include("LICENSE"); into("native/xdelta") }
    from("build/native-vendor/pins.json") { into("native") }
    into(layout.buildDirectory.dir("license-assets"))
}
val benchmarkSources = tasks.register<Sync>("benchmarkSources") {
    from("../../archive-delta-generator/src/main/java") {
        include("com/google/archivepatcher/generator/bsdiff/**")
        exclude("**/BsDiffDeltaGenerator.java")
    }
    from("../../archive-delta-core/src/main/java") {
        include("com/google/archivepatcher/applier/bsdiff/BsPatch.java")
        include("com/google/archivepatcher/applier/PatchFormatException.java")
    }
    into(layout.buildDirectory.dir("algorithm-sources"))
}
tasks.named("preBuild") { dependsOn(benchmarkLicenses, benchmarkSources) }

dependencies {
    testImplementation(libs.junit4)
}
