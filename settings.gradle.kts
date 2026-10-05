@file:Suppress("UnstableApiUsage")

pluginManagement {
    includeBuild("gradle/build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.mozilla.org/maven2")
    }
}

rootProject.name = "SlayTheAmethyst"
include(":app")
// Built independently for CDN; never an app dependency (including full variants).
include(":web-runtime")
include(":macrobenchmark")
include(":boot-bridge")
include(":game-probe")
include(":arthas-bridge")
include(":mods:amethyst-runtime-compat")
include(":mods:amethyst-floating-tools")
include(":mods:ram-saver")
include(":mods:amethyst-frame-probe")
include(":patches:gdx-patch")
include(":tools:steam-cloud-spike")
include(":workshop-core")
include(":steam-protocol")
include(":lan-core")
