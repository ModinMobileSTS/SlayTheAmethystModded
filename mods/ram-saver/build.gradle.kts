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
    // Keep exercising the opt-in implementations even though production defaults are OFF.
    systemProperty("ramsaver.release.shared_budget_enabled", "true")
    systemProperty("ramsaver.decode.enabled", "true")
    systemProperty("ramsaver.debug.visual", "false")
    exclude("**/VisualReleaseDebugTest.class")
    exclude("**/ReleaseOverlayInputTest.class")
    exclude("**/ReleaseOverlayRenderTest.class")
    exclude("**/RamSaverOptimizationDefaultsTest.class")
    exclude("**/ResourceLifecycleTimingIntegrationTest.class")
}

val optimizationDefaultsTest by tasks.registering(Test::class) {
    description = "Checks default-OFF runtime behavior without optimization JVM properties."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = tasks.test.get().classpath
    workingDir = rootProject.file("agent-tmp")
    systemProperties(tasks.test.get().systemProperties.filterKeys {
        it != "ramsaver.release.shared_budget_enabled" && it != "ramsaver.decode.enabled"
    })
    include("**/RamSaverOptimizationDefaultsTest.class")
}

val visualReleaseDebugTest by tasks.registering(Test::class) {
    description = "Checks release events and in-game notice paging with visual diagnostics enabled."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = tasks.test.get().classpath
    workingDir = rootProject.file("agent-tmp")
    systemProperties(tasks.test.get().systemProperties)
    systemProperty("ramsaver.debug.visual", "true")
    include("**/VisualReleaseDebugTest.class", "**/ReleaseOverlayInputTest.class", "**/ReleaseOverlayRenderTest.class")
}

val handTextureResidencyTest by tasks.registering(Test::class) {
    description = "Checks hand protection with run residency disabled and both optimizations OFF."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = tasks.test.get().classpath
    workingDir = rootProject.file("agent-tmp")
    systemProperties(tasks.test.get().systemProperties)
    systemProperty("ramsaver.run.budget_mb", "0")
    systemProperty("ramsaver.release.shared_budget_enabled", "false")
    systemProperty("ramsaver.decode.enabled", "false")
    include("**/HandTextureResidencyTest.class", "**/PlayerCardTextureResidencyTest.class")
    filter {
        includeTestsMatching("com.badlogic.gdx.graphics.HandTextureResidencyTest.*")
        includeTestsMatching("optispire.patches.PlayerCardTextureResidencyTest.hand*")
    }
}

val resourceLifecycleTimingTest by tasks.registering(Test::class) {
    description = "Checks every-event persisted lifecycle timings without verbose/visual diagnostics."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = tasks.test.get().classpath
    workingDir = rootProject.file("agent-tmp")
    systemProperties(tasks.test.get().systemProperties)
    systemProperty("amethyst.gdx.resource_timings", "true")
    systemProperty("amethyst.gdx.resource_timings.dir", rootProject.file("agent-tmp/resource-timings/integration").absolutePath)
    include("**/ResourceLifecycleTimingIntegrationTest.class")
}

tasks.check { dependsOn(visualReleaseDebugTest, optimizationDefaultsTest, handTextureResidencyTest, resourceLifecycleTimingTest) }

tasks.jar {
    archiveFileName = "RamSaver.jar"
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
