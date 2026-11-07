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
    compileOnly(project(":patches:gdx-patch"))
    compileOnly(files(rootProject.file("build-deps/desktop.jar")))
    compileOnly(files(appProjectRef.file("src/main/assets/components/mods/BaseMod.jar")))
    compileOnly(files(appProjectRef.file("src/main/assets/components/mods/ModTheSpire.jar")))
    testImplementation(libs.junit4)
    testImplementation(project(":patches:gdx-patch"))
    testImplementation(files(rootProject.file("build-deps/desktop.jar")))
    testImplementation(files(appProjectRef.file("src/main/assets/components/mods/BaseMod.jar")))
    testImplementation(files(appProjectRef.file("src/main/assets/components/mods/ModTheSpire.jar")))
}

tasks.compileJava {
    // File dependencies precede project jars in Gradle's classpath. Prefer our replacement APIs.
    dependsOn(":patches:gdx-patch:classes")
    classpath = files(project(":patches:gdx-patch").layout.buildDirectory.dir("classes/java/main")) + classpath
}

tasks.compileTestJava {
    dependsOn(":patches:gdx-patch:classes")
    classpath = files(project(":patches:gdx-patch").layout.buildDirectory.dir("classes/java/main")) + classpath
}

tasks.test {
    // Replacement GLTexture must precede the copy embedded in the desktop game jar.
    classpath = sourceSets.test.get().output + sourceSets.main.get().output +
        files(project(":patches:gdx-patch").layout.buildDirectory.dir("classes/java/main")) + classpath
    workingDir = rootProject.file("agent-tmp")
    systemProperty("ramsaver.diag.enabled", "false")
    systemProperty("ramsaver.age.tick_seconds", "1")
    systemProperty("ramsaver.hot.budget_mb", "1")
    systemProperty("ramsaver.run.budget_mb", "1")
    systemProperty("ramsaver.run.pressure_mb", "64")
    systemProperty("ramsaver.release.max_per_frame", "4")
}

tasks.jar {
    archiveFileName = "RamSaver.jar"
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
