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
include(":boot-bridge")
include(":patches:gdx-patch")
include(":patches:downfall-fbo-patch")
include(":patches:basemod-fbo-patch")
include(":patches:basemod-glow-fbo-compat")
