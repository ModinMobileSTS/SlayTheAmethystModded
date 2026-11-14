package io.stamethyst.backend.mods

import android.content.Context
import io.stamethyst.config.RuntimePaths
import org.eclipse.jdt.core.compiler.CompilationProgress
import org.eclipse.jdt.core.compiler.batch.BatchCompiler
import java.io.File
import java.io.InterruptedIOException
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CancellationException
import java.util.zip.ZipFile

data class AgentPatchCompileResult(
    val success: Boolean,
    val compiledClassCount: Int,
    val diagnostics: String,
)

/** Resolved compile classpath plus the jars that could not be indexed. */
internal data class CompileClasspathResolution(
    val entries: List<File>,
    val unindexable: List<File>,
)

/**
 * Compiles one patch mod's agent-authored Java sources on-device with ECJ.
 *
 * The launcher's own runtime is Android/ART, which has no `javax.tools` compiler. ECJ is pure
 * Java and ships in the APK, so it runs inside the launcher process while emitting standard
 * Java 8 (class file 52) bytecode for the embedded OpenJDK 8 game JVM. Compilation is pinned to
 * `-source 1.8 -target 1.8` and bootstrapped against the installed runtime's `rt.jar`, because
 * ART's `java.*` surface differs from the JRE the resulting mod actually runs on.
 */
object AgentPatchSourceCompiler {
    private val classpathCache = AgentCompileClasspathCache()
    /** Java sources live here inside a patch workspace; compiled classes land at its root. */
    const val PATCH_SOURCE_DIR = "src"

    private const val MAX_DIAGNOSTICS_CHARS = 16_000
    private const val STAGING_RETENTION_MS = 60L * 60L * 1000L

    fun compile(
        context: Context,
        workspace: AgentPatchWorkspace,
        parentJar: File?,
    ): AgentPatchCompileResult {
        val sources = collectSources(workspace.patchRoot)
        if (sources.isEmpty()) {
            return AgentPatchCompileResult(
                success = false,
                compiledClassCount = 0,
                diagnostics = "No .java sources found in the current patch workspace. Write sources under its $PATCH_SOURCE_DIR/ directory first.",
            )
        }

        val rtJar = File(RuntimePaths.runtimeRoot(context), "lib/rt.jar")
        if (!rtJar.isFile || rtJar.length() <= 0L) {
            return AgentPatchCompileResult(
                success = false,
                compiledClassCount = 0,
                diagnostics = "Java runtime not installed (missing ${rtJar.absolutePath}). Launch the game once to install it.",
            )
        }

        val classpath = resolveCompileClasspathEntries(context, parentJar)
        // Compile into a staging directory so a failed build cannot leave half-written classes,
        // and a successful build can replace the previous class tree as a unit. It lives outside
        // the patch root so a packaged JAR can never contain build output.
        val stagingParent = resolveAgentPatchCacheDir(context, COMPILE_STAGING_CACHE_DIR)
            ?: return AgentPatchCompileResult(
                success = false,
                compiledClassCount = 0,
                diagnostics = "No writable cache directory for compile staging; free some internal storage and retry.",
            )
        pruneStaleStaging(stagingParent)
        val stagingDir = File(stagingParent, "compile-${workspace.patchId}-${System.nanoTime()}")
        if (!stagingDir.mkdirs()) {
            return AgentPatchCompileResult(
                success = false,
                compiledClassCount = 0,
                diagnostics = "Could not create compile staging at ${stagingDir.absolutePath}.",
            )
        }
        val args = buildCommandLine(
            sources = sources,
            outputDir = stagingDir,
            bootClasspath = rtJar,
            classpath = classpath.entries.joinToString(File.pathSeparator) { it.absolutePath },
        )

        val stdout = StringWriter()
        val stderr = StringWriter()
        try {
            val succeeded = runCatching {
                BatchCompiler.compile(
                    args,
                    PrintWriter(stdout),
                    PrintWriter(stderr),
                    cancellationProgress(),
                )
            }.getOrElse { error ->
                return AgentPatchCompileResult(
                    success = false,
                    compiledClassCount = 0,
                    diagnostics = "ECJ failed to run: ${error.message ?: error.javaClass.simpleName}",
                )
            }
            // ECJ stops at its own checkpoints; never treat a cancelled build as a real result.
            checkCancelled()

            val stagedClasses = collectClassFiles(stagingDir)
            val installed = if (succeeded && stagedClasses.isNotEmpty()) {
                runCatching {
                    // The build may have taken a while; re-check before mutating the workspace so a
                    // cancelled run cannot leave classes behind that its session no longer owns.
                    checkCancelled()
                    replaceCompiledClasses(workspace.patchRoot, stagingDir)
                }.getOrElse { error ->
                    if (error is java.util.concurrent.CancellationException) throw error
                    return AgentPatchCompileResult(
                        success = false,
                        compiledClassCount = 0,
                        diagnostics = buildString {
                            append(stdout.toString())
                            append(stderr.toString())
                            append("\nCould not install the compiled classes: ")
                            append(error.message ?: error.javaClass.simpleName)
                        }.trim().take(MAX_DIAGNOSTICS_CHARS),
                    )
                }
            } else {
                0
            }
            val replaced = installed > 0
            val diagnostics = buildString {
                append(stdout.toString())
                append(stderr.toString())
                if (classpath.unindexable.isNotEmpty()) {
                    append("\nWarning: these classpath jars could not be indexed and were skipped: ")
                    append(classpath.unindexable.joinToString(", ") { it.name })
                }
                if (!replaced && succeeded) {
                    append("\nNo .class files were produced; the previous class tree was left unchanged.")
                } else if (!succeeded && stagedClasses.isNotEmpty()) {
                    append("\nCompilation failed; the previous class tree was left unchanged.")
                }
            }
                .trim()
                .take(MAX_DIAGNOSTICS_CHARS)
            return AgentPatchCompileResult(
                success = replaced,
                compiledClassCount = if (replaced) installed else 0,
                diagnostics = diagnostics,
            )
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    /**
     * Bridges the run's cancellation into ECJ.
     *
     * ECJ polls [CompilationProgress.isCanceled] at its own checkpoints; passing `null` (the old
     * behaviour) removed the only way to stop a build, so a cancelled run kept compiling while
     * holding the exclusive tool lock. ECJ ignores thread interruption on its own.
     */
    private fun cancellationProgress(): CompilationProgress = object : CompilationProgress() {
        override fun isCanceled(): Boolean = Thread.currentThread().isInterrupted

        override fun begin(work: Int) = Unit

        override fun done() = Unit

        override fun setTaskName(name: String?) = Unit

        override fun worked(workDone: Int, workRemaining: Int) = Unit
    }

    /** Fails the compile as cancelled instead of reporting a result nobody is waiting for. */
    private fun checkCancelled() {
        if (Thread.currentThread().isInterrupted) {
            throw java.util.concurrent.CancellationException("Patch compilation cancelled")
        }
    }

    /** Test seam: the exact predicate ECJ polls, and the pre-install cancellation gate. */
    internal fun cancellationProgressForTest(): CompilationProgress = cancellationProgress()

    internal fun checkCancelledForTest() = checkCancelled()

    /**
     * Replaces the patch root's class files with the freshly compiled staging tree.
     *
     * New classes are copied first, then every class the new build did not produce is deleted, so a
     * renamed or deleted source cannot leave an old top-level or inner class behind for
     * `validate_agent_patch_mod` and packaging to pick up. Non-class files (the manifest, descriptor,
     * resources, sources) are preserved.
     *
     * @return number of class files installed into [patchRoot].
     */
    internal fun replaceCompiledClasses(patchRoot: File, stagingDir: File): Int {
        val sourceTree = File(patchRoot, PATCH_SOURCE_DIR).canonicalFile
        val existingClasses = collectClassFiles(patchRoot).filter { classFile ->
            !classFile.canonicalFile.toPath().startsWith(sourceTree.toPath())
        }
        val stagedClasses = collectClassFiles(stagingDir)
        if (stagedClasses.isEmpty()) return 0

        // Copy first so a failed build never leaves the workspace with no classes at all: the old
        // tree is only pruned after the new classes are in place.
        val installedRelativePaths = HashSet<String>()
        stagedClasses.forEach { staged ->
            val relative = staged.relativeTo(stagingDir)
            val target = File(patchRoot, relative.path)
            target.parentFile?.mkdirs()
            staged.copyTo(target, overwrite = true)
            installedRelativePaths += relative.invariantSeparatorsPath
        }

        // Then drop everything the new build did not produce: renamed or deleted sources leave
        // their old top-level and inner classes behind otherwise.
        val removed = existingClasses.filter { classFile ->
            classFile.relativeTo(patchRoot).invariantSeparatorsPath !in installedRelativePaths
        }
        removed.forEach { it.delete() }
        pruneEmptyDirectories(patchRoot, sourceTree, removed)
        return installedRelativePaths.size
    }

    internal fun collectClassFiles(root: File): List<File> =
        if (!root.isDirectory) {
            emptyList()
        } else {
            root.walkTopDown()
                .filter { it.isFile && it.extension.equals("class", ignoreCase = true) }
                .toList()
        }

    /** Removes build staging directories a killed process left behind. */
    private fun pruneStaleStaging(stagingParent: File) {
        val cutoff = System.currentTimeMillis() - STAGING_RETENTION_MS
        stagingParent.listFiles().orEmpty().forEach { entry ->
            if (entry.isDirectory && entry.lastModified() < cutoff) {
                runCatching { entry.deleteRecursively() }
            }
        }
    }

    /** Removes package directories left empty by the previous class tree. */
    private fun pruneEmptyDirectories(root: File, keep: File, removedClasses: List<File>) {
        val rootDir = root.canonicalFile
        val keepDir = keep.canonicalFile
        removedClasses.forEach { removed ->
            var dir = removed.parentFile?.canonicalFile
            while (dir != null && dir != rootDir && !dir.toPath().startsWith(keepDir.toPath())) {
                if (dir.listFiles()?.isEmpty() != true) break
                val parent = dir.parentFile
                dir.delete()
                dir = parent
            }
        }
    }

    internal fun collectSources(patchRoot: File): List<File> =
        patchRoot.walkTopDown()
            .filter { it.isFile && it.extension.equals("java", ignoreCase = true) }
            .sortedBy { it.absolutePath }
            .toList()

    internal fun buildCommandLine(
        sources: List<File>,
        outputDir: File,
        bootClasspath: File,
        classpath: String,
    ): Array<String> = buildList {
        add("-source")
        add("1.8")
        add("-target")
        add("1.8")
        // ModTheSpire reads patch method parameter names from the LocalVariableTable; ECJ emits
        // only LineNumberTable by default, which makes ParamInfo2.findName return null and
        // ModTheSpire fail with `Unknown error acquiring parameter name for "null"`.
        add("-g")
        add("-proc:none")
        add("-nowarn")
        add("-encoding")
        add("UTF-8")
        add("-bootclasspath")
        add(bootClasspath.absolutePath)
        add("-classpath")
        add(classpath)
        add("-d")
        add(outputDir.absolutePath)
        sources.forEach { add(it.absolutePath) }
    }.toTypedArray()

    /**
     * Symbol resolution classpath: the game jar, ModTheSpire, the required mods, and the parent
     * mod being patched. ART never loads these classes; ECJ only reads their bytecode.
     *
     * Jars that Android's [ZipFile] cannot open (duplicate entry names) are replaced by a cached,
     * duplicate-free copy so ECJ can index them.
     */
    internal fun buildClasspath(context: Context, parentJar: File?): String =
        resolveCompileClasspath(context, parentJar).joinToString(File.pathSeparator) { it.absolutePath }

    /**
     * Resolves the symbol-resolution classpath: the game jar, ModTheSpire, the required mods, and
     * the parent mod being patched. ART never loads these classes; ECJ and CFR only read their bytecode.
     *
     * Jars that Android's [ZipFile] cannot open (duplicate entry names) are replaced by a cached,
     * duplicate-free copy so they can be indexed.
     */
    internal fun resolveCompileClasspath(context: Context, parentJar: File?): List<File> =
        resolveCompileClasspathEntries(context, parentJar).entries

    // Parallel API tools share the duplicate-free JAR cache and its write probe. Publish a cache
    // entry completely before another query can inspect it; bytecode reads afterwards run in parallel.
    internal fun resolveCompileClasspathEntries(
        context: Context,
        parentJar: File?,
    ): CompileClasspathResolution = classpathCache.resolve(
        namespace = "${context.filesDir.canonicalPath}|${context.cacheDir?.canonicalPath.orEmpty()}",
        gameJars = listOf(RuntimePaths.importedStsJar(context), RuntimePaths.importedMtsJar(context)),
        requiredMods = RuntimePaths.requiredModsDir(context),
        parentJar = parentJar,
    ) { jar -> resolveCompileClasspathEntry(context, jar) }

    /**
     * Returns a zip ECJ can index: the jar itself when Android's [ZipFile] accepts it, otherwise a
     * cached duplicate-free class-only overlay. A patched `desktop-1.0.jar` is normally already
     * duplicate-free, so the common path copies nothing and preserves the original JAR untouched.
     */
    internal fun resolveCompileClasspathEntry(
        context: Context,
        jar: File,
        contentHash: (File) -> String = overlayHashes::sha256,
        createTemporary: (File) -> File = { File.createTempFile("classes-", ".tmp", it) },
        readableZip: (File) -> Boolean = ::isReadableZip,
    ): File? {
        var temporary: File? = null
        return try {
            checkCancelled()
            val readable = readableZip(jar)
            checkCancelled()
            if (readable) return jar
            val cacheDir = resolveAgentPatchCacheDir(context, COMPILE_CLASSPATH_CACHE_DIR) ?: return null
            // Only duplicate-entry fallback pays for a content hash, with path isolation.
            val sourceIdentity = AgentSourceFileIdentity.read(jar)
            val sourceHash = contentHash(jar)
            checkCancelled()
            val token = agentSourceToken("${jar.canonicalPath}|$sourceHash|classes-v2")
            val cached = File(cacheDir, "$token-classes.jar")
            val cachedReadable = readableZip(cached)
            checkCancelled()
            if (cachedReadable) return cached
            val staged = createTemporary(cacheDir).also { temporary = it }
            DuplicateZipEntryNormalizer.copyClassEntries(jar, staged)
            check(readableZip(staged)) { "Unreadable classpath overlay." }
            check(
                if (sourceIdentity == null) sourceHash == AgentModJarReader.sha256(jar)
                else sourceIdentity == AgentSourceFileIdentity.read(jar),
            ) { "Compile classpath archive changed while building its overlay." }
            checkCancelled()
            Files.move(staged.toPath(), cached.toPath(), StandardCopyOption.REPLACE_EXISTING)
            cached
        } catch (error: CancellationException) {
            throw error
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("Compile classpath preparation interrupted.").apply { initCause(error) }
        } catch (error: InterruptedIOException) {
            Thread.currentThread().interrupt()
            throw CancellationException("Compile classpath preparation interrupted.").apply { initCause(error) }
        } catch (_: Exception) {
            checkCancelled()
            null
        } finally {
            runCatching { temporary?.delete() }
        }
    }

    internal fun isReadableZip(file: File): Boolean =
        file.isFile && file.length() > 0L && runCatching { ZipFile(file).use { it.size() } }.isSuccess

    /**
     * Resolves a writable scratch directory, preferring the internal cache and falling back to
     * internal storage. Some devices report an unusable `cacheDir`, and silently failing here used
     * to drop classpath jars without explanation.
     */
    internal fun resolveAgentPatchCacheDir(context: Context, name: String): File? {
        val candidates = listOf(
            File(context.cacheDir, name),
            File(context.filesDir, name),
        )
        for (dir in candidates) {
            if (!dir.isDirectory && !dir.mkdirs()) continue
            if (canWriteProbe(dir)) return dir
        }
        return null
    }

    private fun canWriteProbe(dir: File): Boolean {
        val probe = File(dir, ".write-probe")
        return runCatching {
            probe.writeText("1")
            probe.delete()
        }.isSuccess
    }

    internal const val COMPILE_CLASSPATH_CACHE_DIR = "agent-patch-compile-classpath"
    internal const val COMPILE_STAGING_CACHE_DIR = "agent-patch-compile-staging"
    private val overlayHashes = AgentVerifiedJarHashes()
}
