import java.io.File
import java.net.SocketTimeoutException
import java.net.URI
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.protobuf) apply false
}

data class BuildDepSpec(
    val targetName: String,
    val candidatePaths: List<String>,
    val matchNames: List<String>
)

val buildDepsDirectory = layout.projectDirectory.dir("build-deps")
val buildDepsBundleUrl = providers.gradleProperty("buildDeps.bundleUrl").orElse(
    "https://github.com/ModinMobileSTS/SlayTheAmethystModdedDependence/releases/download/deps-20260305/build-deps.tar.gz"
)
val buildDepsDownloadDirectory = layout.buildDirectory.dir("build-deps-download")

val buildDepSpecs = listOf(
    BuildDepSpec(
        targetName = "desktop.jar",
        candidatePaths = listOf(
            "desktop.jar",
            "build-deps/desktop.jar",
            "steamapps/common/SlayTheSpire/desktop-1.0.jar",
            "build-deps/steamapps/common/SlayTheSpire/desktop-1.0.jar",
            "desktop-1.0.jar"
        ),
        matchNames = listOf("desktop.jar", "desktop-1.0.jar")
    ),
    BuildDepSpec(
        targetName = "jre8-pojav.zip",
        candidatePaths = listOf(
            "jre8-pojav.zip",
            "build-deps/jre8-pojav.zip",
            "runtime-pack/jre8-pojav.zip",
            "build-deps/runtime-pack/jre8-pojav.zip"
        ),
        matchNames = listOf("jre8-pojav.zip")
    )
)

fun missingBuildDeps(): List<BuildDepSpec> = buildDepSpecs.filter { spec ->
    val file = buildDepsDirectory.file(spec.targetName).asFile
    !file.isFile || file.length() == 0L
}

// Both dependencies can be placed under build-deps/ manually. When either is absent, the
// shared dependency bundle is downloaded once and the missing files are extracted into
// build-deps/ so a fresh clone can build without any manual setup.
val ensureBuildDependencies = tasks.register("ensureBuildDependencies") {
    group = "build setup"
    description = "Fetches missing build-deps files (desktop.jar, jre8-pojav.zip) from the dependency bundle."
    inputs.property("bundleUrl", buildDepsBundleUrl)
    outputs.upToDateWhen { false }

    doLast {
        val missing = missingBuildDeps()
        if (missing.isEmpty()) {
            logger.lifecycle("build-deps is complete; no download needed.")
            return@doLast
        }

        val url = buildDepsBundleUrl.get()
        val depsDir = buildDepsDirectory.asFile
        depsDir.mkdirs()
        val downloadDir = buildDepsDownloadDirectory.get().asFile
        downloadDir.mkdirs()
        val archive = File(downloadDir, "build-deps.tar.gz")

        if (!archive.isFile || archive.length() == 0L) {
            logger.lifecycle(
                "Missing build-deps/${missing.joinToString(", ") { it.targetName }}; " +
                    "downloading dependency bundle: $url"
            )
            downloadBundle(url, archive)
        }

        val staging = File(downloadDir, "staging")
        if (staging.exists()) {
            staging.deleteRecursively()
        }
        staging.mkdirs()
        try {
            project.copy {
                from(project.tarTree(project.resources.gzip(archive)))
                into(staging)
            }
            missing.forEach { spec ->
                val source = spec.candidatePaths.asSequence()
                    .map { File(staging, it) }
                    .firstOrNull { it.isFile && it.length() > 0L }
                    ?: staging.walkTopDown().firstOrNull { it.isFile && it.name in spec.matchNames }
                    ?: throw GradleException(
                        "Dependency bundle $url does not contain ${spec.targetName}."
                    )
                source.copyTo(File(depsDir, spec.targetName), overwrite = true)
                logger.lifecycle("Installed build-deps/${spec.targetName} from dependency bundle.")
            }
        } finally {
            staging.deleteRecursively()
        }
    }
}

fun downloadBundle(url: String, target: File) {
    // Download to a sibling temp file and publish it atomically, so a failed or
    // interrupted download cannot leave a truncated archive that later runs reuse.
    val partial = File(target.parentFile, "${target.name}.part")
    partial.delete()
    try {
        val connection = URI(url).toURL().openConnection().apply {
            connectTimeout = 30_000
            readTimeout = 180_000
            setRequestProperty("User-Agent", "SlayTheAmethyst-build")
        }
        connection.getInputStream().use { input ->
            partial.outputStream().use { output -> input.copyTo(output) }
        }
        if (partial.length() == 0L) {
            throw java.io.IOException("empty response body")
        }
        if (!partial.renameTo(target)) {
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
    } catch (timeout: SocketTimeoutException) {
        partial.delete()
        throw GradleException(
            "Timed out downloading $url. Place the files under build-deps/ manually or set " +
                "-PbuildDeps.bundleUrl=<url>.",
            timeout
        )
    } catch (failure: java.io.IOException) {
        partial.delete()
        throw GradleException(
            "Failed to download $url (${failure.message}). Place the files under build-deps/ manually " +
                "or set -PbuildDeps.bundleUrl=<url>.",
            failure
        )
    }
}

subprojects {
    tasks.matching { it.name == "installRuntimePackAssets" }.configureEach {
        dependsOn(ensureBuildDependencies)
    }
    plugins.withId("java") {
        tasks.withType<JavaCompile>().configureEach {
            dependsOn(ensureBuildDependencies)
        }
    }
}
