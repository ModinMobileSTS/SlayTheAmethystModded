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

tasks.jar {
    archiveFileName = "boot-bridge.jar"
}

dependencies {
    testImplementation(libs.junit4)
}

tasks.test {
    val callbackJar = project(":app").layout.buildDirectory.file("generated/apkCallbackBridgeJar/callback-bridge.jar")
    val legacyJar = rootProject.layout.projectDirectory.file("gradle/callback-bridge/lwjgl-glfw-classes-base.jar")
    val mtsJar = rootProject.layout.projectDirectory.file("app/src/main/assets/components/mods/ModTheSpire.jar")
    dependsOn(":app:packageJvmCallbackBridgeJar")
    inputs.files(callbackJar, legacyJar, mtsJar).withPropertyName("callbackBridgeClassloaders")
    systemProperty("sts.callbackBridgeJar", callbackJar.get().asFile.absolutePath)
    systemProperty("sts.legacyLwjglJar", legacyJar.asFile.absolutePath)
    systemProperty("sts.mtsJar", mtsJar.asFile.absolutePath)
}
