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
    compileOnly(files(appProjectRef.layout.buildDirectory.file("generated/callbackBridgeRuntimeJar/lwjgl-glfw-classes.jar")))
}

tasks.compileJava {
    dependsOn(":app:packageLwjglCallbackBridgeJar")
}

tasks.jar {
    archiveFileName = "gdx-patch.jar"
}
