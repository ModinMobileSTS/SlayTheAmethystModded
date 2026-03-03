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

dependencies {
    compileOnly(files(desktopJar()))
    compileOnly(files("../../app/src/main/assets/components/lwjgl3/lwjgl-glfw-classes.jar"))
}

tasks.jar {
    archiveFileName = "gdx-patch.jar"
}
