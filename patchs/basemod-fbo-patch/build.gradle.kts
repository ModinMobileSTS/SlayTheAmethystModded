import io.stamethyst.gradle.desktopJar
import io.stamethyst.gradle.modJar

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

tasks.jar {
    archiveFileName = "basemod-postprocess-fbo-compat.jar"
}

dependencies {
    compileOnly(files(desktopJar()))
    compileOnly(files(modJar("1605833019", "BaseMod.jar")))
}
