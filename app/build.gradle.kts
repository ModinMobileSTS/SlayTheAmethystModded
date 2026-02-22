import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Sync
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
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
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.tukaani.xz)
    implementation(libs.apache.commons.compress)
    implementation(libs.bytedance.bytehook)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
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
