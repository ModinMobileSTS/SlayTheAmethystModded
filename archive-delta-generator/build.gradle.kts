plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}
kotlin { jvmToolchain(21) }
dependencies {
    api(project(":archive-delta-core"))
    testImplementation(libs.junit4)
}
tasks.jar {
    from("LICENSE.archive-patcher") { into("META-INF/licenses") }
    from("UPSTREAM.md") { into("META-INF") }
}
tasks.test {
    exclude("**/LocalArchiveCorpusTest.class")
    doFirst {
        val temporary = rootProject.file("agent-tmp/archive-delta-generator-tests")
        temporary.mkdirs()
        systemProperty("java.io.tmpdir", temporary.absolutePath)
    }
}
tasks.register<Test>("corpusTest") {
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/LocalArchiveCorpusTest.class")
    systemProperty("archiveDelta.corpus", rootProject.file("jar-delta/test-corpus/base.jar").absolutePath)
    doFirst {
        val temporary = rootProject.file("agent-tmp/archive-delta-corpus-tests")
        temporary.mkdirs()
        systemProperty("java.io.tmpdir", temporary.absolutePath)
    }
    outputs.upToDateWhen { false }
    testLogging.showStandardStreams = true
}
