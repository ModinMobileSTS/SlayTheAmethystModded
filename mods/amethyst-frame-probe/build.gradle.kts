plugins {
    id("java")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    toolchain {
        languageVersion = JavaLanguageVersion.of(8)
    }
}

val appProjectRef = rootProject.project(":app")

dependencies {
    compileOnly(files(rootProject.file("build-deps/steamapps/common/SlayTheSpire/desktop-1.0.jar")))
    // FrameRingBuffer lives in gdx-patch; depend on its jar for compilation.
    compileOnly(project(":patches:gdx-patch"))
    compileOnly(files(appProjectRef.file("src/main/assets/components/mods/BaseMod.jar")))
    compileOnly(files(appProjectRef.file("src/main/assets/components/mods/ModTheSpire.jar")))
}

tasks.jar {
    archiveFileName = "AmethystFrameProbe.jar"
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
