plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

gradlePlugin {
    plugins {
        create("steamPathPlugin") {
            id = "io.stamethyst.steam-path"
            implementationClass = "SteamPathPlugin"
        }
    }
}
