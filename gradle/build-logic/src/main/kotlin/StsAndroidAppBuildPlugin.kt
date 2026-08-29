import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.AppExtension
import com.android.build.gradle.api.ApkVariantOutput
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.Directory
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.swing.filechooser.FileSystemView

@Suppress("unused")
class StsAndroidAppBuildPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.plugins.withId("com.android.application") {
            project.configureStsAndroidAppBuild()
        }
    }
}

private const val RESOURCE_PACK_ABI = "arm64-v8a"

private val externalizedModAssetPatterns = listOf(
    "components/mods/ModTheSpire.jar",
    "components/mods/BaseMod.jar",
    "components/mods/StSLib.jar"
)

private val externalizedAssetPatterns = listOf(
    "components/jre/**",
    "components/lwjgl3/**",
    "components/log4j_runtime/**",
    "ui/**"
) + externalizedModAssetPatterns

// This was temporarily bundled while the callback bridge migration was in flight.
// Exclude stale generated outputs so incremental builds cannot retain the duplicate JAR.
private val obsoleteCommonAssetPatterns = listOf(
    "components/embedded_lwjgl_bridge/**"
)

private val externalizedNativeLibraries = listOf(
    "libEGL_mesa.so",
    "libOSMesa.so",
    "libVkLayer_khronos_timeline_semaphore.so",
    "libcutils.so",
    "libgdx-freetype.so",
    "libgdx.so",
    "libgl4es_114.so",
    "libglapi.so",
    "libglxshim.so",
    "libjnidispatch.so",
    "liblinkerhook.so",
    "libmobileglues.so",
    "libeasytier_android_jni.so",
    "libeasytier_ffi.so",
    "libspirv-cross-c-shared.so",
    "libvulkan_freedreno.so",
    "libzink_dri.so"
)

private fun Project.configureStsAndroidAppBuild() {
    val packageName = readGradleProperty("application.id")
    val appVersionName = readGradleProperty("application.version.name")
    val generatedRuntimeAssetsDir = layout.buildDirectory.dir("generated/runtime-assets")
    val packagedCommonAssetsDir = layout.buildDirectory.dir("generated/packaged-assets/common")
    val packagedExternalizedAssetsDir = layout.buildDirectory.dir("generated/packaged-assets/externalized")
    val generatedAndroidCallbackBridgeDir = layout.buildDirectory.dir("generated/source/callbackBridge/android")

    configureGeneratedAndroidSources(
        packagedCommonAssetsDir = packagedCommonAssetsDir,
        packagedExternalizedAssetsDir = packagedExternalizedAssetsDir,
        generatedAndroidCallbackBridgeDir = generatedAndroidCallbackBridgeDir
    )
    configureApkOutput(appVersionName)
    configureSlimNativePackaging()

    val generatedAssetTasks = registerRuntimeAssetTasks(
        generatedRuntimeAssetsDir = generatedRuntimeAssetsDir,
        generatedAndroidCallbackBridgeDir = generatedAndroidCallbackBridgeDir
    )
    val packagedAssetTasks = registerPackagedRuntimeAssetTasks(
        generatedRuntimeAssetsDir = generatedRuntimeAssetsDir,
        packagedCommonAssetsDir = packagedCommonAssetsDir,
        packagedExternalizedAssetsDir = packagedExternalizedAssetsDir,
        generatedAssetTasks = generatedAssetTasks
    )
    val copyResourcesZipToDesktop = registerExternalResourceZipTasks(
        packagedExternalizedAssetsDir = packagedExternalizedAssetsDir,
        prepareExternalizedAssetsTask = packagedAssetTasks.prepareExternalizedAssets
    )
    val adb = androidComponents().sdkComponents.adb.map { it.asFile.absolutePath }
    registerAdbTasks(adb, packageName)
    registerHarnessTasks()
    registerArthasResourcePackageTask()

    tasks.named("preBuild").configure {
        dependsOn(packagedAssetTasks.prepareCommonAssets)
        dependsOn(packagedAssetTasks.prepareExternalizedAssets)
    }
    tasks.matching {
        it.name in setOf(
            "assembleRelease",
            "assembleFullRelease",
            "assembleFastSlimRelease",
            "assembleFastFullRelease"
        )
    }.configureEach {
        finalizedBy(copyResourcesZipToDesktop)
    }
}

private fun Project.registerArthasResourcePackageTask() {
    val core = rootProject.layout.projectDirectory.file("scripts/tools/arthas/resource/arthas-core.jar")
    val spy = rootProject.layout.projectDirectory.file("scripts/tools/arthas/resource/arthas-spy.jar")
    val bridge = project(":arthas-bridge").layout.buildDirectory.file("libs/arthas-bridge.jar")
    val manifest = layout.buildDirectory.file("generated/arthas-resource/arthas-resource.properties")
    val generateManifest = tasks.register<DefaultTask>("generateArthasResourceManifest") {
        dependsOn(":arthas-bridge:fatJar")
        inputs.files(core, spy, bridge)
        outputs.file(manifest)
        doLast {
            val files = listOf(core.asFile, spy.asFile, bridge.get().asFile)
            val output = manifest.get().asFile
            output.parentFile.mkdirs()
            output.writeText(buildString {
                append("schemaVersion=1\n")
                append("packageVersion=arthas-3.6.9-bridge-1\n")
                files.forEach { file ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    file.inputStream().use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            digest.update(buffer, 0, read)
                        }
                    }
                    val hash = digest.digest().joinToString("") { "%02x".format(it) }
                    append(file.name).append(".size=").append(file.length()).append('\n')
                    append(file.name).append(".sha256=").append(hash).append('\n')
                }
            }, StandardCharsets.UTF_8)
        }
    }
    tasks.register<Zip>("packageArthasResources") {
        group = "distribution"
        description = "Build the optional Arthas resource pack for deep diagnostics."
        dependsOn(generateManifest)
        archiveFileName.set("arthas-resource.zip")
        destinationDirectory.set(layout.buildDirectory.dir("outputs/arthas"))
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        from(core)
        from(spy)
        from(bridge)
        from(manifest)
    }
}

private fun Project.configureGeneratedAndroidSources(
    packagedCommonAssetsDir: Provider<Directory>,
    packagedExternalizedAssetsDir: Provider<Directory>,
    generatedAndroidCallbackBridgeDir: Provider<Directory>
) {
    extensions.configure<ApplicationExtension> {
        sourceSets.getByName("main") {
            assets.setSrcDirs(listOf(packagedCommonAssetsDir))
            java.srcDir(generatedAndroidCallbackBridgeDir)
        }
        listOf("fastFullRelease", "fullRelease").forEach { sourceSetName ->
            sourceSets.maybeCreate(sourceSetName).assets.srcDir(packagedExternalizedAssetsDir)
        }
    }
}

private fun Project.configureApkOutput(appVersionName: String) {
    @Suppress("DEPRECATION")
    extensions.configure<AppExtension> {
        @Suppress("DEPRECATION")
        applicationVariants.all {
            outputs.all {
                @Suppress("DEPRECATION")
                if (this is ApkVariantOutput) {
                    val buildTypeName = buildType.name
                    val naming = when (buildTypeName) {
                        "release" -> ApkOutputNaming(channelName = "release")
                        "fullRelease" -> ApkOutputNaming(channelName = "release", suffix = "-full")
                        "fastSlimRelease" -> ApkOutputNaming(channelName = "fast-release")
                        "fastFullRelease" -> ApkOutputNaming(channelName = "fast-release", suffix = "-full")
                        else -> null
                    }
                    naming?.let {
                        outputFileName =
                            "SlayTheAmethyst-${it.channelName}-$appVersionName${it.suffix}.apk"
                    }
                }
            }
        }
    }

    androidComponents().onVariants { variant ->
        val assembleTaskName = "assemble${variant.name.replaceFirstChar { it.uppercaseChar() }}"
        tasks.matching { it.name == assembleTaskName }.configureEach {
            doLast {
                val apkDir = variant.artifacts.get(SingleArtifact.APK).get().asFile
                logger.lifecycle("APK output directory: ${apkDir.absolutePath}")
            }
        }
    }
}

private fun Project.configureSlimNativePackaging() {
    val components = androidComponents()
    listOf("debug", "release", "fastSlimRelease").forEach { buildTypeName ->
        components.onVariants(components.selector().withBuildType(buildTypeName)) { variant ->
            externalizedNativeLibraries.forEach { libraryName ->
                variant.packaging.jniLibs.excludes.add("**/$libraryName")
            }
        }
    }
}

private data class ApkOutputNaming(
    val channelName: String,
    val suffix: String = ""
)

private data class PackagedRuntimeAssetTasks(
    val prepareCommonAssets: TaskProvider<Sync>,
    val prepareExternalizedAssets: TaskProvider<Sync>
)

private fun Project.registerPackagedRuntimeAssetTasks(
    generatedRuntimeAssetsDir: Provider<Directory>,
    packagedCommonAssetsDir: Provider<Directory>,
    packagedExternalizedAssetsDir: Provider<Directory>,
    generatedAssetTasks: List<TaskProvider<out Task>>
): PackagedRuntimeAssetTasks {
    val sourceAssetsDir = layout.projectDirectory.dir("src/main/assets")
    val resourcePackSourceDir = layout.projectDirectory.dir("src/main/resource-pack")

    val prepareCommonAssets = tasks.register<Sync>("prepareCommonRuntimeAssets") {
        dependsOn(generatedAssetTasks)
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        from(sourceAssetsDir) {
            exclude(externalizedAssetPatterns + obsoleteCommonAssetPatterns)
        }
        from(generatedRuntimeAssetsDir) {
            exclude(externalizedAssetPatterns + obsoleteCommonAssetPatterns)
        }
        into(packagedCommonAssetsDir)
    }

    val prepareExternalizedAssets = tasks.register<Sync>("prepareExternalizedRuntimeAssets") {
        dependsOn(generatedAssetTasks)
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        from(sourceAssetsDir) {
            include(externalizedAssetPatterns)
        }
        from(generatedRuntimeAssetsDir) {
            include(externalizedAssetPatterns)
        }
        from(resourcePackSourceDir) {
            include(externalizedAssetPatterns)
        }
        into(packagedExternalizedAssetsDir)
    }

    return PackagedRuntimeAssetTasks(
        prepareCommonAssets = prepareCommonAssets,
        prepareExternalizedAssets = prepareExternalizedAssets
    )
}

private fun Project.registerExternalResourceZipTasks(
    packagedExternalizedAssetsDir: Provider<Directory>,
    prepareExternalizedAssetsTask: TaskProvider<Sync>
): TaskProvider<Task> {
    val packageExternalResources = tasks.register<Zip>("packageExternalResources") {
        group = "build"
        description = "Package external launcher runtime resources as resources.zip."
        dependsOn(prepareExternalizedAssetsTask)
        from(packagedExternalizedAssetsDir) {
            into("assets")
        }
        from(layout.projectDirectory.dir("src/main/jniLibs/$RESOURCE_PACK_ABI")) {
            include(externalizedNativeLibraries)
            into("lib/$RESOURCE_PACK_ABI")
        }
        destinationDirectory.set(layout.buildDirectory.dir("outputs/resources"))
        archiveFileName.set("resources.zip")
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    val resourceArchive = packageExternalResources.flatMap { it.archiveFile }
    return tasks.register("copyExternalResourcesToDesktop") {
        group = "build"
        description = "Copy resources.zip to the current user's Desktop."
        dependsOn(packageExternalResources)
        inputs.file(resourceArchive)
        doLast {
            val desktopDirectory = resolveDesktopDirectory()
            desktopDirectory.mkdirs()
            val source = resourceArchive.get().asFile
            val target = File(desktopDirectory, source.name)
            source.copyTo(target, overwrite = true)
            logger.lifecycle("External resources copied to: ${target.absolutePath}")
        }
    }
}

private fun resolveDesktopDirectory(): File {
    val osName = System.getProperty("os.name").orEmpty()
    if (osName.contains("Windows", ignoreCase = true)) {
        runCatching { FileSystemView.getFileSystemView().getHomeDirectory() }
            .getOrNull()
            ?.takeIf { it.path.isNotBlank() }
            ?.let { return it }
    }
    return File(System.getProperty("user.home"), "Desktop")
}

private fun Project.registerRuntimeAssetTasks(
    generatedRuntimeAssetsDir: Provider<Directory>,
    generatedAndroidCallbackBridgeDir: Provider<Directory>
): List<TaskProvider<out Task>> {
    val callbackBridgeTemplatesDir = rootProject.layout.projectDirectory.dir("gradle/callback-bridge/templates")
    val callbackBridgeBaseJar = rootProject.layout.projectDirectory.file("gradle/callback-bridge/lwjgl-glfw-classes-base.jar")
    val generatedJvmCallbackBridgeSourceDir = layout.buildDirectory.dir("generated/source/callbackBridge/jvm")
    val generatedJvmCallbackBridgeClassesDir = layout.buildDirectory.dir("generated/classes/callbackBridge/jvm")
    val packagedLwjglBridgeJarDir = layout.buildDirectory.dir("generated/callbackBridgeRuntimeJar")
    val generatedLwjglBridgeVersionDir = layout.buildDirectory.dir("generated/callbackBridgeVersion")
    val generatedLwjglBridgeAssetDir = generatedRuntimeAssetsDir.map { it.dir("components/lwjgl3") }
    val runtimePackZip = rootProject.layout.projectDirectory.file("build-deps/runtime-pack/jre8-pojav.zip")
    val log4jRuntimeComponents = configurations.create("log4jRuntimeComponents") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isVisible = false
    }

    val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
    dependencies.add(log4jRuntimeComponents.name, libs.findLibrary("log4j-api").get())
    dependencies.add(log4jRuntimeComponents.name, libs.findLibrary("log4j-core").get())

    fun renderCallbackBridge(target: CallbackBridgeTarget): String {
        val templateFile = when (target) {
            CallbackBridgeTarget.ANDROID -> callbackBridgeTemplatesDir.file("android/CallbackBridge.java.tmpl").asFile
            CallbackBridgeTarget.JVM -> callbackBridgeTemplatesDir.file("jvm/CallbackBridge.java.tmpl").asFile
        }
        return CallbackBridgeCodegen.renderTemplate(
            templateFile.readText(StandardCharsets.UTF_8),
            target
        )
    }

    val installBootBridgeJar = tasks.register<Copy>("installBootBridgeJar") {
        dependsOn(":boot-bridge:jar")
        from(project(":boot-bridge").layout.buildDirectory.file("libs/boot-bridge.jar"))
        into(generatedRuntimeAssetsDir.map { it.dir("components/boot_bridge") })
    }

    val installGameProbeJar = tasks.register<Sync>("installGameProbeJar") {
        dependsOn(":game-probe:fatJar")
        from(project(":game-probe").layout.buildDirectory.file("libs/game-probe.jar"))
        into(generatedRuntimeAssetsDir.map { it.dir("components/game_probe") })
    }

    val generateAndroidCallbackBridgeSource = tasks.register<DefaultTask>("generateAndroidCallbackBridgeSource") {
        val templateFile = callbackBridgeTemplatesDir.file("android/CallbackBridge.java.tmpl")
        inputs.file(templateFile)
        inputs.property("callbackBridgeContractHash", CallbackBridgeCodegen.contractHash)
        outputs.dir(generatedAndroidCallbackBridgeDir)
        doLast {
            val outputDir = generatedAndroidCallbackBridgeDir.get().asFile.resolve("org/lwjgl/glfw")
            outputDir.mkdirs()
            File(outputDir, "CallbackBridge.java").writeText(
                renderCallbackBridge(CallbackBridgeTarget.ANDROID),
                StandardCharsets.UTF_8
            )
        }
    }

    val generateJvmCallbackBridgeSource = tasks.register<DefaultTask>("generateJvmCallbackBridgeSource") {
        val templateFile = callbackBridgeTemplatesDir.file("jvm/CallbackBridge.java.tmpl")
        inputs.file(templateFile)
        inputs.property("callbackBridgeContractHash", CallbackBridgeCodegen.contractHash)
        outputs.dir(generatedJvmCallbackBridgeSourceDir)
        doLast {
            val outputDir = generatedJvmCallbackBridgeSourceDir.get().asFile.resolve("org/lwjgl/glfw")
            outputDir.mkdirs()
            File(outputDir, "CallbackBridge.java").writeText(
                renderCallbackBridge(CallbackBridgeTarget.JVM),
                StandardCharsets.UTF_8
            )
        }
    }

    val compileJvmCallbackBridge = tasks.register<JavaCompile>("compileJvmCallbackBridge") {
        dependsOn(generateJvmCallbackBridgeSource)
        source(generatedJvmCallbackBridgeSourceDir)
        destinationDirectory.set(generatedJvmCallbackBridgeClassesDir)
        sourceCompatibility = JavaVersion.VERSION_1_8.toString()
        targetCompatibility = JavaVersion.VERSION_1_8.toString()
        options.release.set(8)
        classpath = files()
    }

    val packageLwjglCallbackBridgeJar = tasks.register<Zip>("packageLwjglCallbackBridgeJar") {
        dependsOn(compileJvmCallbackBridge)
        // Keep the generated bridge ahead of the base runtime's stale class.
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        from(generatedJvmCallbackBridgeClassesDir)
        from(zipTree(callbackBridgeBaseJar))
        destinationDirectory.set(packagedLwjglBridgeJarDir)
        archiveFileName.set("lwjgl-glfw-classes.jar")
        archiveExtension.set("jar")
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    val generateLwjglBridgeVersion = tasks.register<DefaultTask>("generateLwjglBridgeVersion") {
        val androidTemplate = callbackBridgeTemplatesDir.file("android/CallbackBridge.java.tmpl")
        val jvmTemplate = callbackBridgeTemplatesDir.file("jvm/CallbackBridge.java.tmpl")
        val outputFile = generatedLwjglBridgeVersionDir.map { it.file("version") }
        inputs.file(callbackBridgeBaseJar)
        inputs.file(androidTemplate)
        inputs.file(jvmTemplate)
        inputs.property("callbackBridgeContractHash", CallbackBridgeCodegen.contractHash)
        outputs.file(outputFile)
        doLast {
            val targetFile = outputFile.get().asFile
            targetFile.parentFile?.mkdirs()
            targetFile.writeText(
                CallbackBridgeCodegen.fingerprint(
                    CallbackBridgeCodegen.contractHash,
                    androidTemplate.asFile.readText(StandardCharsets.UTF_8),
                    jvmTemplate.asFile.readText(StandardCharsets.UTF_8),
                    callbackBridgeBaseJar.asFile.length().toString(),
                    callbackBridgeBaseJar.asFile.lastModified().toString()
                ),
                StandardCharsets.UTF_8
            )
        }
    }

    val installLwjglBridgeAssets = tasks.register<Sync>("installLwjglBridgeAssets") {
        dependsOn(packageLwjglCallbackBridgeJar)
        dependsOn(generateLwjglBridgeVersion)
        from(packagedLwjglBridgeJarDir) {
            include("lwjgl-glfw-classes.jar")
        }
        from(generateLwjglBridgeVersion)
        into(generatedLwjglBridgeAssetDir)
    }

    val installPatchJars = tasks.register<Sync>("installPatchJars") {
        val patchProjectPaths = listOf(":patches:gdx-patch")
        dependsOn(patchProjectPaths.map { projectPath -> "$projectPath:jar" })
        patchProjectPaths.forEach { projectPath ->
            from(project(projectPath).layout.buildDirectory.dir("libs")) {
                include("*.jar")
            }
        }
        into(generatedRuntimeAssetsDir.map { it.dir("components/gdx_patch") })
    }

    val installBundledModJars = tasks.register<Sync>("installBundledModJars") {
        val bundledModProjectPaths = listOf(
            ":mods:amethyst-runtime-compat",
            ":mods:amethyst-floating-tools",
            ":mods:ram-saver",
            ":mods:amethyst-frame-probe"
        )
        dependsOn(bundledModProjectPaths.map { projectPath -> "$projectPath:jar" })
        bundledModProjectPaths.forEach { projectPath ->
            from(project(projectPath).layout.buildDirectory.dir("libs")) {
                include("*.jar")
            }
        }
        into(generatedRuntimeAssetsDir.map { it.dir("components/mods") })
    }

    val installLog4jRuntimeAssets = tasks.register<Sync>("installLog4jRuntimeAssets") {
        from(log4jRuntimeComponents) {
            include("log4j-api-*.jar")
            rename { "log4j-api.jar" }
        }
        from(log4jRuntimeComponents) {
            include("log4j-core-*.jar")
            rename { "log4j-core.jar" }
        }
        into(generatedRuntimeAssetsDir.map { it.dir("components/log4j_runtime") })
        doLast {
            val outputDir = generatedRuntimeAssetsDir.get().dir("components/log4j_runtime").asFile
            listOf(
                File(outputDir, "log4j-api.jar"),
                File(outputDir, "log4j-core.jar")
            ).forEach { jarFile ->
                if (!jarFile.isFile || jarFile.length() <= 0L) {
                    throw GradleException("Missing packaged Log4j runtime asset: ${jarFile.absolutePath}")
                }
            }
        }
    }

    val installRuntimePackAssets = tasks.register<Sync>("installRuntimePackAssets") {
        doFirst {
            val runtimePackFile = runtimePackZip.asFile
            if (!runtimePackFile.isFile) {
                throw GradleException(
                    "Missing runtime pack zip: ${runtimePackFile.absolutePath}. " +
                        "Expected build-deps/runtime-pack/jre8-pojav.zip."
                )
            }
        }
        from(zipTree(runtimePackZip)) {
            exclude("bin-arm.tar.xz", "bin-x86.tar.xz", "bin-x86_64.tar.xz")
        }
        into(generatedRuntimeAssetsDir.map { it.dir("components/jre") })
    }

    registerRendererBackendImportTask()

    return listOf(
        generateAndroidCallbackBridgeSource,
        installBootBridgeJar,
        installGameProbeJar,
        installLwjglBridgeAssets,
        installPatchJars,
        installBundledModJars,
        installLog4jRuntimeAssets,
        installRuntimePackAssets
    )
}

private fun Project.registerRendererBackendImportTask() {
    val rendererLibsSource = readGradleProperty("rendererLibsSource")
    val rendererBackendImportLibraries = listOf(
        "libc++_shared.so",
        "libEGL_mesa.so",
        "libglapi.so",
        "libglxshim.so",
        "liblinkerhook.so",
        "libmobileglues.so",
        "libspirv-cross-c-shared.so",
        "libzink_dri.so",
        "libcutils.so",
        "libvulkan_freedreno.so",
        "libVkLayer_khronos_timeline_semaphore.so",
        "libOSMesa.so"
    )

    tasks.register<DefaultTask>("importRendererBackendLibs") {
        group = "dev"
        description = "Import backend-native libraries from a renderer APK or an outer archive containing one."

        fun extractApk(source: File, tempDir: File): File {
            if (source.extension.equals("apk", ignoreCase = true)) {
                return source
            }
            ZipFile(source).use { zip ->
                val apkEntry = zip.entries().asSequence()
                    .firstOrNull { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
                    ?: throw GradleException("No .apk found inside ${source.absolutePath}")
                val extractedApk = File(tempDir, apkEntry.name.substringAfterLast('/'))
                extractedApk.parentFile?.mkdirs()
                zip.getInputStream(apkEntry).use { input ->
                    FileOutputStream(extractedApk, false).use { output ->
                        input.copyTo(output)
                    }
                }
                return extractedApk
            }
        }

        doLast {
            if (rendererLibsSource.isBlank()) {
                throw GradleException("Missing -PrendererLibsSource=<apk-or-zip-path>")
            }
            val source = file(rendererLibsSource)
            if (!source.isFile) {
                throw GradleException("Renderer backend source does not exist: ${source.absolutePath}")
            }

            val tempDir = layout.buildDirectory.dir("tmp/importRendererBackendLibs").get().asFile
            tempDir.mkdirs()
            val apkFile = extractApk(source, tempDir)
            ZipFile(apkFile).use { apkZip ->
                for (abi in listOf("arm64-v8a")) {
                    val targetDir = file("src/main/jniLibs/$abi")
                    targetDir.mkdirs()
                    for (libraryName in rendererBackendImportLibraries) {
                        val entry = apkZip.getEntry("lib/$abi/$libraryName") ?: continue
                        val targetFile = File(targetDir, libraryName)
                        if (targetFile.exists()) {
                            logger.lifecycle("Skip existing renderer lib: ${targetFile.absolutePath}")
                            continue
                        }
                        apkZip.getInputStream(entry).use { input ->
                            FileOutputStream(targetFile, false).use { output ->
                                input.copyTo(output)
                            }
                        }
                        logger.lifecycle("Imported renderer lib: ${targetFile.absolutePath}")
                    }
                }

                val angleLicenseEntry = apkZip.getEntry("assets/licenses/ANGLE_LICENSE")
                if (angleLicenseEntry != null) {
                    val licenseFile = file("src/main/assets/licenses/ANGLE_LICENSE")
                    if (!licenseFile.exists()) {
                        licenseFile.parentFile?.mkdirs()
                        apkZip.getInputStream(angleLicenseEntry).use { input ->
                            FileOutputStream(licenseFile, false).use { output ->
                                input.copyTo(output)
                            }
                        }
                        logger.lifecycle("Imported ANGLE license: ${licenseFile.absolutePath}")
                    } else {
                        logger.lifecycle("Skip existing ANGLE license: ${licenseFile.absolutePath}")
                    }
                }
            }
        }
    }
}

private fun Project.registerAdbTasks(adb: Provider<String>, packageName: String) {
    val supportedLaunchModes = setOf("mts", "vanilla")
    val rawLaunchMode = readGradleProperty("launchMode", "mts")
    val launchMode = when (rawLaunchMode) {
        "mts_basemod" -> "mts"
        else -> rawLaunchMode
    }
    val forceJvmCrash = readGradleProperty("forceJvmCrash", "false")
    val forceRuntimeCrash = readGradleProperty("forceRuntimeCrash", "false")
    val debugMode = readGradleProperty("debugMode", "false")
    val autoplay = readGradleProperty("autoplay", "false")
    val autoplaySaveMode = readGradleProperty("autoplaySaveMode", "fresh")
    val autoplayMode = readGradleProperty("autoplayMode", "normal")
    val autoplaySingleRoomSpec = readGradleProperty("autoplaySingleRoomSpec")
    val performanceDeepDiagnostics = readGradleProperty("performanceDeepDiagnostics")
    val disableCardObtainEffectOwnershipCompat =
        readGradleProperty("disableCardObtainEffectOwnershipCompat", "false")
    val deviceSerial = readGradleProperty("deviceSerial")
    val logsDir = readGradleProperty("logsDir")
    require(launchMode in supportedLaunchModes) {
        "Unsupported launchMode: $launchMode. Supported: ${supportedLaunchModes.joinToString(", ")}"
    }

    fun adbCommand(vararg args: String): List<String> = buildList {
        add(adb.get())
        if (deviceSerial.isNotEmpty()) {
            add("-s")
            add(deviceSerial)
        }
        addAll(args)
    }

    fun startLauncherCommand(
        launchMode: String,
        debugMode: String,
        forceJvmCrash: String,
        forceRuntimeCrash: String,
        autoplay: String,
        autoplaySaveMode: String,
        autoplayMode: String,
        autoplaySingleRoomSpec: String,
        performanceDeepDiagnostics: String,
        disableCardObtainEffectOwnershipCompat: String
    ): List<String> = buildList {
        addAll(
            adbCommand(
                "shell",
                "am",
                "start",
                "-n",
                "$packageName/.LauncherActivity",
                "--es",
                "io.stamethyst.debug_launch_mode",
                launchMode,
                "--ez",
                "io.stamethyst.debug_mode",
                debugMode,
                "--ez",
                "io.stamethyst.debug_force_jvm_crash",
                forceJvmCrash,
                "--ez",
                "io.stamethyst.debug_force_runtime_crash",
                forceRuntimeCrash,
                "--ez",
                "io.stamethyst.debug_autoplay",
                autoplay,
                "--es",
                "io.stamethyst.debug_autoplay_save_mode",
                autoplaySaveMode,
                "--es",
                "io.stamethyst.debug_autoplay_mode",
                autoplayMode
            )
        )
        if (performanceDeepDiagnostics.isNotEmpty()) {
            add("--ez")
            add("io.stamethyst.debug_performance_deep_diagnostics")
            add(performanceDeepDiagnostics)
        }
        if (autoplaySingleRoomSpec.isNotEmpty()) {
            add("--es")
            add("io.stamethyst.debug_autoplay_single_room_spec")
            add(autoplaySingleRoomSpec)
        }
        add("--ez")
        add("io.stamethyst.debug_disable_card_obtain_effect_ownership_compat")
        add(disableCardObtainEffectOwnershipCompat)
    }

    tasks.register<Exec>("stsStart") {
        group = "debug"
        description = "Start SlayTheAmethyst on a connected Android device."
        commandLine(
            startLauncherCommand(
                launchMode = launchMode,
                debugMode = debugMode,
                forceJvmCrash = forceJvmCrash,
                forceRuntimeCrash = forceRuntimeCrash,
                autoplay = autoplay,
                autoplaySaveMode = autoplaySaveMode,
                autoplayMode = autoplayMode,
                autoplaySingleRoomSpec = autoplaySingleRoomSpec,
                performanceDeepDiagnostics = performanceDeepDiagnostics,
                disableCardObtainEffectOwnershipCompat = disableCardObtainEffectOwnershipCompat
            )
        )
    }

    tasks.register<Exec>("stsStartAutoplay") {
        group = "debug"
        description = "Start SlayTheAmethyst with the bundled autoplay driver enabled. " +
            "Forces launchMode=mts so amethyst-runtime-compat is loaded; the driver auto-starts " +
            "or resumes according to -PautoplaySaveMode after force-stopping any previous " +
            "session, then plays random cards, ends each turn, and advances through the map " +
            "until the run ends."
        dependsOn("stsStop")
        val autoplayLaunchMode = "mts"
        commandLine(
            startLauncherCommand(
                launchMode = autoplayLaunchMode,
                debugMode = debugMode,
                forceJvmCrash = "false",
                forceRuntimeCrash = "false",
                autoplay = "true",
                autoplaySaveMode = autoplaySaveMode,
                autoplayMode = autoplayMode,
                autoplaySingleRoomSpec = autoplaySingleRoomSpec,
                performanceDeepDiagnostics = performanceDeepDiagnostics,
                disableCardObtainEffectOwnershipCompat = disableCardObtainEffectOwnershipCompat
            )
        )
    }

    tasks.register<Exec>("stsStop") {
        group = "debug"
        description = "Force stop SlayTheAmethyst on a connected Android device."
        commandLine(adbCommand("shell", "am", "force-stop", packageName))
    }

    tasks.register<StsPullLogsTask>("stsPullLogs") {
        group = "debug"
        description = "Export the same JVM log bundle as Settings > Share Logs."
        adbPath.set(adb)
        applicationId.set(packageName)
        this.deviceSerial.set(deviceSerial)
        this.logsDir.set(logsDir)
    }
}

private fun Project.registerHarnessTasks() {
    val harnessScript = rootProject.layout.projectDirectory.file("scripts/tools/main.py")
    val pythonExecutable = readGradleProperty("pythonExecutable", "python")
    val deviceSerial = readGradleProperty("deviceSerial")
    val launchMode = readGradleProperty("launchMode", "mts_basemod")
    val harnessOutDir = readGradleProperty("harnessOutDir")
    val harnessTimeoutSecondsProperty = providers.gradleProperty("harnessTimeoutSeconds")
    val harnessTimeoutSeconds = harnessTimeoutSecondsProperty.orElse("120").get()
    val autoplayHarnessTimeoutSeconds = harnessTimeoutSecondsProperty.orElse("300").get()
    val harnessPollIntervalSeconds = readGradleProperty("harnessPollIntervalSeconds", "2")
    val harnessSkipInstall = readGradleProperty("harnessSkipInstall", "false")
    val forceJvmCrash = readGradleProperty("forceJvmCrash", "false")
    val forceRuntimeCrash = readGradleProperty("forceRuntimeCrash", "false")
    val autoplay = readGradleProperty("autoplay", "false")
    val autoplaySaveMode = readGradleProperty("autoplaySaveMode", "fresh")
    val autoplayMode = readGradleProperty("autoplayMode", "normal")
    val autoplaySingleRoomSpec = readGradleProperty("autoplaySingleRoomSpec")
    val singleRoomSpecFile = readGradleProperty("singleRoomSpecFile")
    val singleRoomCharacter = readGradleProperty("singleRoomCharacter")
    val singleRoomMonster = readGradleProperty("singleRoomMonster")
    val singleRoomCards = readGradleProperty("singleRoomCards")
    val disableCardObtainEffectOwnershipCompat =
        readGradleProperty("disableCardObtainEffectOwnershipCompat", "false")
    val noStopAfterSmoke = readGradleProperty("noStopAfterSmoke", "false")
    val startupCacheHitRuns = readGradleProperty("startupCacheHitRuns", "1")
    val startupCacheNoClear = readGradleProperty("startupCacheNoClear", "false")
    val cloudSyncRelativePath = readGradleProperty("cloudSyncRelativePath", "saves/.amethyst-cloud-sync-harness.txt")
    val cloudSyncPayload = readGradleProperty("cloudSyncPayload")
    val cloudSyncSourceFile = readGradleProperty("cloudSyncSourceFile")
    val cloudSyncPullIntervalSeconds = readGradleProperty("cloudSyncPullIntervalSeconds", "10")

    fun registerHarnessExecTask(
        taskName: String,
        command: String,
        taskDescription: String,
        forceAutoplay: Boolean = false
    ) {
        tasks.register<Exec>(taskName) {
            group = "debug"
            description = taskDescription
            val taskAutoplay = forceAutoplay || autoplay.toBooleanStrictOrNull() == true
            val taskLaunchMode = if (forceAutoplay) "mts" else launchMode
            val taskTimeoutSeconds = if (command == "perf-bench") {
                // -PperfBenchTimeoutSeconds overrides for perf-bench specifically.
                // Falls back to -PharnessTimeoutSeconds, then to default 720 s.
                providers.gradleProperty("perfBenchTimeoutSeconds")
                    .orElse(harnessTimeoutSecondsProperty.orElse("720")).get()
            } else if (taskAutoplay) {
                autoplayHarnessTimeoutSeconds
            } else if (command == "startup-cache-profile" || command == "steam-cloud-sync") {
                autoplayHarnessTimeoutSeconds
            } else {
                harnessTimeoutSeconds
            }
            workingDir(rootProject.layout.projectDirectory.asFile)
            val args = mutableListOf(
                harnessScript.asFile.absolutePath,
                "sts-harness",
                "-Command",
                command,
                "-LaunchMode",
                taskLaunchMode,
                "-TimeoutSeconds",
                taskTimeoutSeconds,
                "-PollIntervalSeconds",
                harnessPollIntervalSeconds
            )
            if (deviceSerial.isNotEmpty()) {
                args.add("-DeviceSerial")
                args.add(deviceSerial)
            }
            if (harnessOutDir.isNotEmpty()) {
                args.add("-OutDir")
                args.add(harnessOutDir)
            }
            if (forceJvmCrash.toBooleanStrictOrNull() == true) {
                args.add("-ForceJvmCrash")
            }
            if (forceRuntimeCrash.toBooleanStrictOrNull() == true) {
                args.add("-ForceRuntimeCrash")
            }
            if (taskAutoplay) {
                args.add("-Autoplay")
            }
            args.add("-AutoplaySaveMode")
            args.add(autoplaySaveMode)
            args.add("-AutoplayMode")
            args.add(autoplayMode)
            if (autoplaySingleRoomSpec.isNotEmpty()) {
                args.add("-SingleRoomDeviceSpec")
                args.add(autoplaySingleRoomSpec)
            }
            if (singleRoomSpecFile.isNotEmpty()) {
                args.add("-SingleRoomSpec")
                args.add(singleRoomSpecFile)
            }
            if (singleRoomCharacter.isNotEmpty()) {
                args.add("-SingleRoomCharacter")
                args.add(singleRoomCharacter)
            }
            if (singleRoomMonster.isNotEmpty()) {
                args.add("-SingleRoomMonster")
                args.add(singleRoomMonster)
            }
            if (singleRoomCards.isNotEmpty()) {
                args.add("-SingleRoomCards")
                args.add(singleRoomCards)
            }
            if (disableCardObtainEffectOwnershipCompat.toBooleanStrictOrNull() == true) {
                args.add("-DisableCardObtainEffectOwnershipCompat")
            }
            if (command == "smoke" && harnessSkipInstall.toBooleanStrictOrNull() == true) {
                args.add("-SkipInstall")
            }
            if (command == "steam-cloud-sync") {
                args.add("-SkipInstall")
                args.add("-CloudSyncRelativePath")
                args.add(cloudSyncRelativePath)
                args.add("-CloudSyncPullIntervalSeconds")
                args.add(cloudSyncPullIntervalSeconds)
                if (cloudSyncPayload.isNotEmpty()) {
                    args.add("-CloudSyncPayload")
                    args.add(cloudSyncPayload)
                }
                if (cloudSyncSourceFile.isNotEmpty()) {
                    args.add("-CloudSyncSourceFile")
                    args.add(cloudSyncSourceFile)
                }
            }
            if (noStopAfterSmoke.toBooleanStrictOrNull() == true) {
                args.add("-NoStopAfterSmoke")
            }
            if (command == "startup-cache-profile") {
                args.add("-CacheHitRuns")
                args.add(startupCacheHitRuns)
                if (startupCacheNoClear.toBooleanStrictOrNull() == true) {
                    args.add("-NoClearStartupCache")
                }
            }
            if (command == "perf-bench") {
                val perfBenchEnableProfiler = providers.gradleProperty("perfBenchEnableProfiler").orElse("false").get()
                val perfBenchProfilerSeconds = providers.gradleProperty("perfBenchProfilerSeconds").orElse("30").get()
                val perfBenchBaseline = providers.gradleProperty("perfBenchBaseline").orElse("").get()
                val perfBenchUpdateBaseline = providers.gradleProperty("perfBenchUpdateBaseline").orElse("false").get()
                val perfBenchCharacter = providers.gradleProperty("perfBenchCharacter").orElse("").get()
                if (perfBenchEnableProfiler.toBooleanStrictOrNull() == true) { args.add("-PerfBenchEnableProfiler") }
                args.add("-PerfBenchProfilerSeconds"); args.add(perfBenchProfilerSeconds)
                if (perfBenchBaseline.isNotEmpty()) { args.add("-PerfBenchBaseline"); args.add(perfBenchBaseline) }
                if (perfBenchUpdateBaseline.toBooleanStrictOrNull() == true) { args.add("-UpdateBaseline") }
                if (perfBenchCharacter.isNotEmpty()) { args.add("-PerfBenchCharacter"); args.add(perfBenchCharacter) }
            }
            commandLine(pythonExecutable, *args.toTypedArray())
        }
    }

    registerHarnessExecTask(
        taskName = "stsHarnessDoctor",
        command = "doctor",
        taskDescription = "Validate SlayTheAmethyst harness prerequisites and capture a status snapshot."
    )
    registerHarnessExecTask(
        taskName = "stsHarnessInstall",
        command = "install",
        taskDescription = "Build and install a debug APK through the SlayTheAmethyst harness."
    )
    registerHarnessExecTask(
        taskName = "stsHarnessStart",
        command = "start",
        taskDescription = "Start SlayTheAmethyst through the SlayTheAmethyst harness."
    )
    registerHarnessExecTask(
        taskName = "stsHarnessStop",
        command = "stop",
        taskDescription = "Force-stop SlayTheAmethyst through the SlayTheAmethyst harness."
    )
    registerHarnessExecTask(
        taskName = "stsHarnessLogs",
        command = "logs",
        taskDescription = "Export SlayTheAmethyst logs through the SlayTheAmethyst harness."
    )
    registerHarnessExecTask(
        taskName = "stsHarnessStatus",
        command = "status",
        taskDescription = "Capture a machine-readable SlayTheAmethyst device status snapshot."
    )
    registerHarnessExecTask(
        taskName = "stsHarnessScreenshot",
        command = "screenshot",
        taskDescription = "Capture a device screenshot through the SlayTheAmethyst harness."
    )
    registerHarnessExecTask(
        taskName = "stsHarnessSmoke",
        command = "smoke",
        taskDescription = "Install, start, observe, screenshot, export logs, and stop through the SlayTheAmethyst harness."
    )
    registerHarnessExecTask(
        taskName = "stsHarnessAutoplaySmoke",
        command = "smoke",
        taskDescription = "Run a SlayTheAmethyst smoke check with the bundled autoplay driver enabled.",
        forceAutoplay = true
    )
    registerHarnessExecTask(
        taskName = "stsHarnessSingleRoom",
        command = "single-room",
        taskDescription = "Run one configured autoplay combat room, export logs, and stop.",
        forceAutoplay = true
    )
    registerHarnessExecTask(
        taskName = "stsHarnessStartupCacheProfile",
        command = "startup-cache-profile",
        taskDescription = "Run a cache-build launch followed by cache-hit launches and summarize startup timings."
    )
    registerHarnessExecTask(
        taskName = "stsHarnessSteamCloudSync",
        command = "steam-cloud-sync",
        taskDescription = "Modify a device-side save marker, open the launcher, poll Steam Cloud diagnostics, export logs, and stop."
    )
    registerHarnessExecTask(
        taskName = "stsHarnessPerfBench",
        command = "perf-bench",
        taskDescription = "Run a full autoplay dungeon run, pull frame-probe-incidents.jsonl, and report a structured performance result against a baseline.",
        forceAutoplay = true
    )
}

private fun Project.androidComponents(): ApplicationAndroidComponentsExtension =
    extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
