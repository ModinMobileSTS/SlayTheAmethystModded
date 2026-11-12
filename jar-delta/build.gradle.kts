plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":archive-delta-core"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit4)
    testImplementation(project(":archive-delta-generator"))
}

tasks.withType<Test>().configureEach {
    doFirst {
        val temporary = rootProject.layout.projectDirectory.dir("agent-tmp/jar-delta-tests").asFile
        temporary.mkdirs()
        systemProperty("java.io.tmpdir", temporary.absolutePath)
    }
}

tasks.test {
    // Optional real-file integration task, never downloads or silently skips a missing fixture.
    systemProperty("jarDelta.corpus", layout.projectDirectory.dir("test-corpus").asFile.absolutePath)
    exclude("**/LocalCorpusTest.class")
}

tasks.register<Test>("corpusTest") {
    description = "Explicit local-corpus experiment; requires test-corpus/base.jar."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/LocalCorpusTest.class")
    systemProperty("jarDelta.corpus", layout.projectDirectory.dir("test-corpus").asFile.absolutePath)
    outputs.upToDateWhen { false }
    testLogging.showStandardStreams = true
}
