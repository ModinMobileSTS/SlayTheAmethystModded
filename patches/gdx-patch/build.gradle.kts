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
    compileOnly(files(rootProject.file("build-deps/desktop.jar")))
    compileOnly(files(appProjectRef.layout.buildDirectory.file("generated/callbackBridgeRuntimeJar/lwjgl-glfw-classes.jar")))
    testImplementation(libs.junit4)
}

tasks.compileJava {
    dependsOn(":app:packageLwjglCallbackBridgeJar")
}

tasks.jar {
    archiveFileName = "gdx-patch.jar"
}
