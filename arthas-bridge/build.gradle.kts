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

dependencies {
    implementation(files(rootProject.file("scripts/tools/arthas/resource/arthas-core.jar")))
    implementation("org.ow2.asm:asm:9.7.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.ow2.asm:asm:9.7.1")
}

tasks.register<Jar>("fatJar") {
    archiveFileName = "arthas-bridge.jar"
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    manifest {
        attributes(
            "Agent-Class" to "io.stamethyst.arthas.ArthasCommandBridge",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true",
        )
    }
    from(sourceSets.main.get().output)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.jar {
    dependsOn(tasks.named("fatJar"))
    enabled = false
}
tasks.named("assemble") {
    dependsOn(tasks.named("fatJar"))
}
