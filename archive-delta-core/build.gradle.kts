plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-library`
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit4)
}
tasks.jar {
    from("LICENSE.archive-patcher") { into("META-INF/licenses") }
    from("UPSTREAM.md") { into("META-INF") }
}
tasks.test {
    doFirst {
        val temporary = rootProject.file("agent-tmp/archive-delta-core-tests")
        temporary.mkdirs()
        systemProperty("java.io.tmpdir", temporary.absolutePath)
    }
}
