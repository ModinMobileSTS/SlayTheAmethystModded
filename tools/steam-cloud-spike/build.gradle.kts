plugins {
    application
}

application {
    mainClass.set("io.stamethyst.tools.steamcloud.StsSteamCloudReadOnlySpike")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(11)
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

dependencies {
    implementation("in.dragonbra:javasteam:1.6.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.83")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("com.google.protobuf:protobuf-java:4.31.1")
}
