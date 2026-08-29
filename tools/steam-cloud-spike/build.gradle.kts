plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("io.stamethyst.tools.steamcloud.StsSteamCloudReadOnlySpike")
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

tasks.register<JavaExec>("depotKey") {
    group = "steam"
    description = "Login to Steam and write a depot decryption key file."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.stamethyst.tools.steamcloud.StsDepotKeyToolKt")
    standardInput = System.`in`
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("refreshToken") {
    group = "steam"
    description = "Login to Steam and write a refresh-token env file."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.stamethyst.tools.steamcloud.StsDepotKeyToolKt")
    prependToolCommand("refreshToken")
    standardInput = System.`in`
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("achievementUnlock") {
    group = "steam"
    description = "Experimentally unlock only Slay the Spire's Shrug It Off achievement through Steam CM."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.stamethyst.tools.steamcloud.StsDepotKeyToolKt")
    prependToolCommand("achievementUnlock")
    standardInput = System.`in`
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("achievementLock") {
    group = "steam"
    description = "Experimentally lock only Slay the Spire's Shrug It Off achievement through Steam CM."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.stamethyst.tools.steamcloud.StsDepotKeyToolKt")
    prependToolCommand("achievementLock")
    standardInput = System.`in`
    workingDir = rootProject.projectDir
}

private fun JavaExec.prependToolCommand(command: String) {
    doFirst {
        val suppliedArgs = args?.toList().orEmpty()
        if (suppliedArgs.firstOrNull() != command) {
            setArgs(listOf(command) + suppliedArgs)
        }
    }
}

dependencies {
    implementation(project(":steam-protocol"))
    implementation(libs.coroutines.core)
    implementation("in.dragonbra:javasteam:1.6.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.83")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("com.google.protobuf:protobuf-java:4.31.1")
}
