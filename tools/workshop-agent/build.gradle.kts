plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin { jvmToolchain(21) }
application { mainClass.set("io.stamethyst.tools.workshop.WorkshopAgentKt") }

dependencies {
    implementation(project(":workshop-core"))
    implementation(platform(libs.okhttpBom))
    implementation(libs.okhttp)
    implementation(libs.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zstd)
    testImplementation(libs.junit4)
    testImplementation(libs.mockwebserver3)
}

tasks.test {
    workingDir = rootProject.projectDir
    systemProperty("java.io.tmpdir", rootProject.file("agent-tmp/workshop-agent-tests").apply { mkdirs() }.absolutePath)
}
