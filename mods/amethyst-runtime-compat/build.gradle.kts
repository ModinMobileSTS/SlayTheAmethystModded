plugins {
    id("java")
    id("io.stamethyst.steam-path")
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
    compileOnly(files(desktopJar()))
    compileOnly(files(appProjectRef.file("src/main/assets/components/mods/BaseMod.jar")))
    compileOnly(files(appProjectRef.file("src/main/assets/components/mods/ModTheSpire.jar")))
}

tasks.jar {
    archiveFileName = "AmethystRuntimeCompat.jar"
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
