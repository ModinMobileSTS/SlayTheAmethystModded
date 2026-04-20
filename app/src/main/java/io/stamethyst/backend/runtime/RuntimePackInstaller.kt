package io.stamethyst.backend.runtime

import android.content.Context
import android.content.res.AssetManager
import android.os.SystemClock
import android.system.Os
import io.stamethyst.R
import io.stamethyst.backend.fs.FileTreeCleaner
import io.stamethyst.config.RuntimePaths
import io.stamethyst.backend.launch.StartupProgressCallback
import io.stamethyst.backend.launch.progressText
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

object RuntimePackInstaller {
    private const val ARCHIVE_UNIVERSAL = "universal.tar.xz"
    private const val ARCHIVE_VERSION = "version"
    private const val ARCHIVE_AARCH64 = "bin-aarch64.tar.xz"
    private const val ARCHIVE_ARM64 = "bin-arm64.tar.xz"
    private const val UNPACK_PROCESS_POLL_INTERVAL_MS = 250L
    private const val UNPACK_PROGRESS_HEARTBEAT_INTERVAL_MS = 5_000L
    private const val PROCESS_OUTPUT_JOIN_TIMEOUT_MS = 2_000L

    private data class ProcessOutputCapture(
        val output: ByteArrayOutputStream,
        val failure: AtomicReference<Throwable?>,
        val readerThread: Thread
    )

    @Throws(IOException::class)
    private fun throwIfInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw IOException("Runtime install cancelled")
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun ensureInstalled(context: Context) {
        ensureInstalled(context, null)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun ensureInstalled(context: Context, progressCallback: StartupProgressCallback?) {
        throwIfInterrupted()
        reportProgress(
            progressCallback,
            2,
            context.progressText(R.string.startup_progress_checking_runtime_pack)
        )
        RuntimePaths.ensureBaseDirs(context)
        val assets = context.assets
        val archArchive = resolveArchArchive(assets)

        val runtimeRoot = RuntimePaths.runtimeRoot(context)
        val markerFile = File(runtimeRoot, ".installed-version")
        val bundledVersion = readAssetAsString(assets, "components/jre/$ARCHIVE_VERSION").trim()
        val bundledMarker = "$bundledVersion|$archArchive"

        if (markerFile.exists()) {
            val installedMarker = String(
                Files.readAllBytes(markerFile.toPath()),
                StandardCharsets.UTF_8
            ).trim()
            val markerMatched = installedMarker == bundledMarker ||
                (installedMarker == bundledVersion && isArm64Archive(archArchive)) ||
                (installedMarker == "$bundledVersion|$ARCHIVE_AARCH64" &&
                    ARCHIVE_ARM64 == archArchive) ||
                (installedMarker == "$bundledVersion|$ARCHIVE_ARM64" &&
                    ARCHIVE_AARCH64 == archArchive)
            val javaHome = locateJavaHome(runtimeRoot)
            if (markerMatched && javaHome != null && isRuntimeReady(javaHome)) {
                reportProgress(
                    progressCallback,
                    100,
                    context.progressText(R.string.startup_progress_runtime_pack_already_up_to_date)
                )
                return
            }
        }

        throwIfInterrupted()
        reportProgress(
            progressCallback,
            10,
            context.progressText(R.string.startup_progress_preparing_runtime_directory)
        )
        prepareCleanDirectory(runtimeRoot, "runtime root")

        val stagingDir = File(context.cacheDir, "runtime-staging")
        throwIfInterrupted()
        reportProgress(
            progressCallback,
            18,
            context.progressText(R.string.startup_progress_preparing_runtime_staging)
        )
        prepareCleanDirectory(stagingDir, "staging directory")

        val requiredFiles = arrayOf(ARCHIVE_UNIVERSAL, archArchive, ARCHIVE_VERSION)
        throwIfInterrupted()
        reportProgress(
            progressCallback,
            26,
            context.progressText(R.string.startup_progress_copying_runtime_archives)
        )
        for (i in requiredFiles.indices) {
            throwIfInterrupted()
            val required = requiredFiles[i]
            copyAssetToFile(assets, "components/jre/$required", File(stagingDir, required))
            val copiedPercent = 26 + ((i + 1) * 14f / requiredFiles.size).roundToInt()
            reportProgress(
                progressCallback,
                copiedPercent,
                context.progressText(R.string.startup_progress_copied_runtime_archive, required)
            )
        }

        throwIfInterrupted()
        reportProgress(
            progressCallback,
            42,
            context.progressText(R.string.startup_progress_extracting_universal_runtime)
        )
        extractTarXz(File(stagingDir, ARCHIVE_UNIVERSAL), runtimeRoot)
        throwIfInterrupted()
        reportProgress(
            progressCallback,
            62,
            context.progressText(R.string.startup_progress_extracting_architecture_runtime)
        )
        extractTarXz(File(stagingDir, archArchive), runtimeRoot)

        throwIfInterrupted()
        reportProgress(
            progressCallback,
            78,
            context.progressText(R.string.startup_progress_unpacking_runtime_pack200_files)
        )
        unpackPack200Files(context, runtimeRoot, progressCallback)

        throwIfInterrupted()
        val javaHome = locateJavaHome(runtimeRoot)
            ?: throw IOException(
                "Runtime install failed: libjli.so not found under ${runtimeRoot.absolutePath}"
            )
        throwIfInterrupted()
        reportProgress(
            progressCallback,
            92,
            context.progressText(R.string.startup_progress_finalizing_runtime_setup)
        )
        postPrepareRuntime(context, javaHome)
        if (!isRuntimeReady(javaHome)) {
            throw IOException(
                "Runtime install failed: missing core Java classes under ${javaHome.absolutePath}"
            )
        }

        throwIfInterrupted()
        reportProgress(
            progressCallback,
            98,
            context.progressText(R.string.startup_progress_writing_runtime_install_marker)
        )
        Files.write(markerFile.toPath(), bundledMarker.toByteArray(StandardCharsets.UTF_8))
        reportProgress(
            progressCallback,
            100,
            context.progressText(R.string.startup_progress_runtime_pack_ready)
        )
    }

    @JvmStatic
    fun locateJavaHome(runtimeRoot: File): File? {
        val libjli = findFileByName(runtimeRoot, "libjli.so") ?: return null
        var cursor: File? = libjli.parentFile
        while (cursor != null) {
            if ("lib" == cursor.name) {
                return cursor.parentFile
            }
            cursor = cursor.parentFile
        }
        return null
    }

    @JvmStatic
    fun findFileByName(root: File?, fileName: String): File? {
        if (root == null || !root.exists()) {
            return null
        }
        if (root.isFile) {
            return if (fileName == root.name) root else null
        }
        val children = root.listFiles() ?: return null
        for (child in children) {
            val found = findFileByName(child, fileName)
            if (found != null) {
                return found
            }
        }
        return null
    }

    @Throws(IOException::class)
    private fun extractTarXz(tarXzFile: File, destination: File) {
        throwIfInterrupted()
        FileInputStream(tarXzFile).use { fileInput ->
            XZInputStream(fileInput).use { xzInput ->
                TarArchiveInputStream(xzInput).use { tarInput ->
                    val buffer = ByteArray(8192)
                    var entry: TarArchiveEntry?
                    while (tarInput.nextEntry.also { entry = it } != null) {
                        throwIfInterrupted()
                        val current = entry!!
                        val outFile = File(destination, current.name)
                        if (current.isDirectory) {
                            if (!outFile.exists() && !outFile.mkdirs()) {
                                throw IOException("Failed to create directory: $outFile")
                            }
                            continue
                        }

                        val parent = outFile.parentFile
                        if (parent != null && !parent.exists() && !parent.mkdirs()) {
                            throw IOException("Failed to create parent: $parent")
                        }
                        if (current.isSymbolicLink) {
                            try {
                                if (outFile.exists() && !outFile.delete()) {
                                    throw IOException("Failed to replace existing symlink target: $outFile")
                                }
                                Os.symlink(current.linkName, outFile.absolutePath)
                            } catch (_: Throwable) {
                                // Best effort: most runtimes do not require symlink entries for JVM startup.
                            }
                            continue
                        }

                        FileOutputStream(outFile).use { output ->
                            while (true) {
                                throwIfInterrupted()
                                val read = tarInput.read(buffer)
                                if (read <= 0) {
                                    break
                                }
                                output.write(buffer, 0, read)
                            }
                        }

                        outFile.setExecutable((current.mode and 0b001001001) != 0, true)
                        outFile.setReadable(true, true)
                        outFile.setWritable(true, true)
                    }
                }
            }
        }
    }

    private fun isRuntimeReady(javaHome: File): Boolean {
        val libDir = File(javaHome, "lib")
        return File(libDir, "rt.jar").exists() || File(libDir, "modules").exists()
    }

    @Throws(IOException::class)
    private fun unpackPack200Files(
        context: Context,
        runtimeRoot: File,
        progressCallback: StartupProgressCallback?
    ) {
        throwIfInterrupted()
        val packFiles = ArrayList<File>()
        collectPackFiles(runtimeRoot, packFiles)
        if (packFiles.isEmpty()) {
            return
        }

        val unpackBinary = File(context.applicationInfo.nativeLibraryDir, "libunpack200.so")
        if (!unpackBinary.exists()) {
            throw IOException("Missing unpack helper binary: ${unpackBinary.absolutePath}")
        }

        val processBuilder = ProcessBuilder().directory(File(context.applicationInfo.nativeLibraryDir))
        for (i in packFiles.indices) {
            throwIfInterrupted()
            val packFile = packFiles[i]
            val startPercent = 78 + (i * 10f / packFiles.size).roundToInt()
            val unpackingMessage = context.progressText(
                R.string.startup_progress_unpacking_runtime_pack_file,
                packFile.name,
                i + 1,
                packFiles.size
            )
            reportProgress(
                progressCallback,
                startPercent,
                unpackingMessage
            )
            val name = packFile.name
            val unpackedJar = File(packFile.parentFile, name.substring(0, name.length - ".pack".length))
            val process = processBuilder
                .command(
                    unpackBinary.absolutePath,
                    "-r",
                    packFile.absolutePath,
                    unpackedJar.absolutePath
                )
                .redirectErrorStream(true)
                .start()

            val outputCapture = startProcessOutputCapture(process)
            val exitCode: Int = try {
                waitForProcessWithHeartbeats(process) {
                    reportProgress(progressCallback, startPercent, unpackingMessage)
                }
            } catch (error: IOException) {
                destroyProcess(process)
                throw error
            }
            val output = finishProcessOutputCapture(outputCapture)
            if (exitCode != 0 || !unpackedJar.exists()) {
                destroyProcess(process)
                throw IOException("unpack200 failed for ${packFile.name} (exit=$exitCode): $output")
            }
            val endPercent = 78 + ((i + 1) * 10f / packFiles.size).roundToInt()
            reportProgress(
                progressCallback,
                endPercent,
                context.progressText(
                    R.string.startup_progress_unpacked_runtime_pack_file,
                    packFile.name,
                    i + 1,
                    packFiles.size
                )
            )
        }
    }

    @Throws(IOException::class)
    private fun waitForProcessWithHeartbeats(
        process: Process,
        onHeartbeat: () -> Unit
    ): Int {
        var lastHeartbeatAtMs = SystemClock.uptimeMillis()
        while (true) {
            throwIfInterrupted()
            val exitCode = try {
                process.exitValue()
            } catch (_: IllegalThreadStateException) {
                null
            }
            if (exitCode != null) {
                return exitCode
            }
            val now = SystemClock.uptimeMillis()
            if (now - lastHeartbeatAtMs >= UNPACK_PROGRESS_HEARTBEAT_INTERVAL_MS) {
                onHeartbeat()
                lastHeartbeatAtMs = now
            }
            try {
                Thread.sleep(UNPACK_PROCESS_POLL_INTERVAL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted while waiting for unpack process", e)
            }
        }
    }

    private fun startProcessOutputCapture(process: Process): ProcessOutputCapture {
        val output = ByteArrayOutputStream()
        val failure = AtomicReference<Throwable?>()
        val readerThread = Thread(
            {
                try {
                    process.inputStream.use { stream ->
                        val buffer = ByteArray(4096)
                        while (true) {
                            val read = stream.read(buffer)
                            if (read <= 0) {
                                break
                            }
                            synchronized(output) {
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                } catch (error: Throwable) {
                    if (!Thread.currentThread().isInterrupted) {
                        failure.compareAndSet(null, error)
                    }
                }
            },
            "STS-Unpack200Output"
        )
        readerThread.isDaemon = true
        readerThread.start()
        return ProcessOutputCapture(output, failure, readerThread)
    }

    private fun finishProcessOutputCapture(capture: ProcessOutputCapture): String {
        try {
            capture.readerThread.join(PROCESS_OUTPUT_JOIN_TIMEOUT_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return synchronized(capture.output) {
                capture.output.toString(StandardCharsets.UTF_8.name()).trim()
            }
        }
        val text = synchronized(capture.output) {
            capture.output.toString(StandardCharsets.UTF_8.name()).trim()
        }
        val failure = capture.failure.get() ?: return text
        val suffix = "output-capture-failed:${failure.javaClass.name}:${failure.message.orEmpty()}".trim()
        return if (text.isBlank()) suffix else "$text\n$suffix"
    }

    private fun destroyProcess(process: Process) {
        runCatching { process.destroy() }
        runCatching {
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
    }

    private fun collectPackFiles(root: File?, out: MutableList<File>) {
        if (Thread.currentThread().isInterrupted) {
            return
        }
        if (root == null || !root.exists()) {
            return
        }
        if (root.isFile) {
            if (root.name.endsWith(".pack")) {
                out.add(root)
            }
            return
        }
        val children = root.listFiles() ?: return
        for (child in children) {
            collectPackFiles(child, out)
        }
    }

    @Throws(IOException::class)
    private fun postPrepareRuntime(context: Context, javaHome: File) {
        throwIfInterrupted()
        val archLibDir = findRuntimeArchLibDir(javaHome) ?: return

        val freetypeVersioned = File(archLibDir, "libfreetype.so.6")
        val freetype = File(archLibDir, "libfreetype.so")
        if (freetypeVersioned.exists() &&
            (!freetype.exists() || freetype.length() != freetypeVersioned.length())
        ) {
            if (!freetypeVersioned.renameTo(freetype)) {
                copyFile(freetypeVersioned, freetype)
            }
        }

        val appAwtXawt = File(context.applicationInfo.nativeLibraryDir, "libawt_xawt.so")
        if (appAwtXawt.exists()) {
            copyFile(appAwtXawt, File(archLibDir, "libawt_xawt.so"))
        }
    }

    @Throws(IOException::class)
    private fun resolveArchArchive(assets: AssetManager): String {
        val is64BitProcess = android.os.Process.is64Bit()
        if (is64BitProcess) {
            if (assetExists(assets, "components/jre/$ARCHIVE_AARCH64")) {
                return ARCHIVE_AARCH64
            }
            if (assetExists(assets, "components/jre/$ARCHIVE_ARM64")) {
                return ARCHIVE_ARM64
            }
            throw IOException(
                "Runtime pack missing required architecture archive: " +
                    ARCHIVE_AARCH64 +
                    " or " +
                    ARCHIVE_ARM64 +
                    " (process=64-bit, available=" +
                    listRuntimeArchives(assets) +
                    ")"
            )
        }

        throw IOException(
            "This build only supports 64-bit ARM devices; refusing to install a 32-bit runtime " +
                "(available=" +
                listRuntimeArchives(assets) +
                ")"
        )
    }

    private fun isArm64Archive(archiveName: String): Boolean {
        return ARCHIVE_AARCH64 == archiveName || ARCHIVE_ARM64 == archiveName
    }

    private fun listRuntimeArchives(assets: AssetManager): String {
        return try {
            val names = assets.list("components/jre")
            if (names == null || names.isEmpty()) {
                return "<empty>"
            }
            val builder = StringBuilder()
            for (i in names.indices) {
                if (i > 0) {
                    builder.append(',')
                }
                builder.append(names[i])
            }
            builder.toString()
        } catch (_: Throwable) {
            "<unavailable>"
        }
    }

    private fun findRuntimeArchLibDir(javaHome: File): File? {
        val libRoot = File(javaHome, "lib")
        val candidates = arrayOf("aarch64", "arm64")
        for (candidate in candidates) {
            val dir = File(libRoot, candidate)
            if (dir.isDirectory) {
                return dir
            }
        }
        return null
    }

    private fun assetExists(assets: AssetManager, assetPath: String): Boolean {
        return try {
            assets.open(assetPath).use { _: InputStream -> }
            true
        } catch (_: IOException) {
            false
        }
    }

    @Throws(IOException::class)
    private fun readAssetAsString(assets: AssetManager, assetPath: String): String {
        throwIfInterrupted()
        assets.open(assetPath).use { input ->
            val data = ByteArray(4096)
            val out = StringBuilder()
            while (true) {
                throwIfInterrupted()
                val read = input.read(data)
                if (read <= 0) {
                    break
                }
                out.append(String(data, 0, read, StandardCharsets.UTF_8))
            }
            return out.toString()
        }
    }

    @Throws(IOException::class)
    private fun copyAssetToFile(assets: AssetManager, assetPath: String, targetFile: File) {
        throwIfInterrupted()
        val parent = targetFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create asset target directory: $parent")
        }
        assets.open(assetPath).use { input ->
            FileOutputStream(targetFile, false).use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    throwIfInterrupted()
                    val read = input.read(buffer)
                    if (read <= 0) {
                        break
                    }
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun copyFile(source: File, target: File) {
        throwIfInterrupted()
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create target directory: $parent")
        }
        FileInputStream(source).use { input ->
            FileOutputStream(target, false).use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    throwIfInterrupted()
                    val read = input.read(buffer)
                    if (read <= 0) {
                        break
                    }
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun prepareCleanDirectory(directory: File, label: String) {
        throwIfInterrupted()
        val parent = directory.parentFile
        if (parent != null) {
            if (parent.exists() && !parent.isDirectory) {
                throw IOException("$label parent is not a directory: ${parent.absolutePath}")
            }
            if (!parent.exists() && !parent.mkdirs()) {
                throw IOException("Failed to create $label parent: ${parent.absolutePath}")
            }
        }

        FileTreeCleaner.deleteRecursively(directory)
        if (!directory.exists()) {
            if (!directory.mkdirs() && !directory.isDirectory) {
                throw IOException("Failed to create $label: ${directory.absolutePath}")
            }
            return
        }

        if (!directory.isDirectory) {
            throw IOException("$label path is not a directory: ${directory.absolutePath}")
        }

        val remaining = directory.listFiles()
            ?: throw IOException("Failed to inspect $label: ${directory.absolutePath}")
        if (remaining.isEmpty()) {
            return
        }

        for (child in remaining) {
            throwIfInterrupted()
            FileTreeCleaner.deleteRecursively(child)
        }
        val stillRemaining = directory.listFiles()
        if (stillRemaining == null || stillRemaining.isNotEmpty()) {
            val remainingSummary = FileTreeCleaner.summarizeRemainingEntries(directory)
            val detail = if (remainingSummary.isNullOrBlank()) "" else " (remaining: $remainingSummary)"
            throw IOException("Failed to clean $label: ${directory.absolutePath}$detail")
        }
    }

    private fun reportProgress(callback: StartupProgressCallback?, percent: Int, message: String) {
        if (callback == null) {
            return
        }
        val bounded = percent.coerceIn(0, 100)
        callback.onProgress(bounded, message)
    }
}
