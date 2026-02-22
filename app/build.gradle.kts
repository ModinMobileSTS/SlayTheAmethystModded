import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Sync
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val generatedRuntimeAssetsDir = layout.buildDirectory.dir("generated/runtime-assets")

android {
    namespace = "io.stamethyst"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.stamethyst"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.3"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        externalNativeBuild {
            ndkBuild {
                arguments += listOf("APP_SHORT_COMMANDS=true")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(generatedRuntimeAssetsDir)
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = true
        jniLibs.pickFirsts += setOf("**/libbytehook.so")
    }

    buildFeatures {
        compose = true
        prefab = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_1_8
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation(platform("androidx.compose:compose-bom:2026.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("org.tukaani:xz:1.9")
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("com.bytedance:bytehook:1.0.9")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

val installBootBridgeJar by tasks.registering(Copy::class) {
    val dep = project(":boot-bridge").tasks.named<Jar>("jar")
    dependsOn(dep)
    from(dep.flatMap { it.archiveFile })
    into(generatedRuntimeAssetsDir.map { it.dir("components/boot_bridge") })
}

val patchProjectPaths = listOf(
    ":patchs:gdx-patch",
    ":patchs:downfall-fbo-patch",
    ":patchs:basemod-fbo-patch",
    ":patchs:basemod-glow-fbo-compat"
)

val installPatchJars by tasks.registering(Sync::class) {
    val patchJarTasks = patchProjectPaths.map { projectPath ->
        project(projectPath).tasks.named<Jar>("jar")
    }
    dependsOn(patchJarTasks)
    patchProjectPaths.forEach { projectPath ->
        from(project(projectPath).tasks.named<Jar>("jar").flatMap { it.archiveFile })
    }
    into(generatedRuntimeAssetsDir.map { it.dir("components/gdx_patch") })
}

tasks.named("preBuild").configure {
    dependsOn(installBootBridgeJar)
    dependsOn(installPatchJars)
}
