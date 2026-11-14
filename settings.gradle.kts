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
    }
}

rootProject.name = "SlayTheAmethyst"
include(":app")
// Download-only Godot engine container; never a launcher dependency.
include(":godot-runtime")
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
include(":tools:workshop-agent")
include(":workshop-core")
include(":steam-protocol")
include(":lan-core")
// Standalone delta experiment; deliberately not an app dependency.
include(":jar-delta")
// Offline archive delta tools; deliberately not app dependencies.
include(":archive-delta-core")
include(":archive-delta-generator")
