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
