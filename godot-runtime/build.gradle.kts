import java.util.zip.ZipFile

plugins { alias(libs.plugins.android.application) }

val template = providers.gradleProperty("godot.androidSourceZip")
    .orElse("${System.getProperty("user.home")}/.local/share/godot/export_templates/4.7.2.stable/android_source.zip")
val extractGodotAar = tasks.register<Copy>("extractGodotAar") {
    from(zipTree(template.get())) { include("libs/release/godot-lib.template_release.aar"); eachFile { path = name } }
    into(layout.buildDirectory.dir("godot-sdk"))
    includeEmptyDirs = false
    doLast {
        ZipFile(layout.buildDirectory.file("godot-sdk/godot-lib.template_release.aar").get().asFile).use { zip ->
            val manifest = zip.getInputStream(zip.getEntry("AndroidManifest.xml")).bufferedReader().use { it.readText() }
            check(manifest.contains("4.7.2.stable")) { "Prototype requires the matching Godot 4.7.2 stable Android AAR" }
        }
    }
}
android {
    namespace = "io.stamethyst.godotruntime"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.stamethyst.godotruntime"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "4.7.2-spike1"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes { release { isMinifyEnabled = false; signingConfig = null } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { jniLibs.useLegacyPackaging = true }
}
dependencies {
    implementation(files(extractGodotAar.map { layout.buildDirectory.file("godot-sdk/godot-lib.template_release.aar").get().asFile }))
    implementation(libs.androidx.appcompat)
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:${libs.versions.kotlin.get()}")
}
tasks.named("preBuild") { dependsOn(extractGodotAar) }
