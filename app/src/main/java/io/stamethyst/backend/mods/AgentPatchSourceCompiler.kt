package io.stamethyst.backend.mods

import android.content.Context
import io.stamethyst.config.RuntimePaths
import org.eclipse.jdt.core.compiler.batch.BatchCompiler
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
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
 * Compiles the agent-authored Java sources of a patch revision on-device with ECJ.
 *
 * The launcher's own runtime is Android/ART, which has no `javax.tools` compiler. ECJ is pure
 * Java and ships in the APK, so it runs inside the launcher process while emitting standard
 * Java 8 (class file 52) bytecode for the embedded OpenJDK 8 game JVM. Compilation is pinned to
 * `-source 1.8 -target 1.8` and bootstrapped against the installed runtime's `rt.jar`, because
 * ART's `java.*` surface differs from the JRE the resulting mod actually runs on.
 */
object AgentPatchSourceCompiler {
    /** Java sources live here inside the patch tree; compiled classes land at the patch root. */
    const val PATCH_SOURCE_DIR = "src"

    private const val MAX_DIAGNOSTICS_CHARS = 16_000

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
                diagnostics = "No .java sources found under patch/. Write sources to patch/$PATCH_SOURCE_DIR/ first.",
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
        val args = buildCommandLine(
            sources = sources,
            outputDir = workspace.patchRoot,
            bootClasspath = rtJar,
            classpath = classpath.entries.joinToString(File.pathSeparator) { it.absolutePath },
        )

        val stdout = StringWriter()
        val stderr = StringWriter()
        val succeeded = runCatching {
            BatchCompiler.compile(
                args,
                PrintWriter(stdout),
                PrintWriter(stderr),
                null,
            )
        }.getOrElse { error ->
            return AgentPatchCompileResult(
                success = false,
                compiledClassCount = 0,
                diagnostics = "ECJ failed to run: ${error.message ?: error.javaClass.simpleName}",
            )
        }

        val diagnostics = buildString {
            append(stdout.toString())
            append(stderr.toString())
            if (classpath.unindexable.isNotEmpty()) {
                append("\nWarning: these classpath jars could not be indexed and were skipped: ")
                append(classpath.unindexable.joinToString(", ") { it.name })
            }
        }
            .trim()
            .take(MAX_DIAGNOSTICS_CHARS)
        val compiledClassCount = workspace.patchRoot.walkTopDown()
            .count { it.isFile && it.extension.equals("class", ignoreCase = true) }
        return AgentPatchCompileResult(
            success = succeeded && compiledClassCount > 0,
            compiledClassCount = compiledClassCount,
            diagnostics = diagnostics,
        )
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

    internal fun resolveCompileClasspathEntries(
        context: Context,
        parentJar: File?,
    ): CompileClasspathResolution {
        val candidates = LinkedHashSet<File>()
        RuntimePaths.importedStsJar(context).takeIf { it.isFile }?.let(candidates::add)
        RuntimePaths.importedMtsJar(context).takeIf { it.isFile }?.let(candidates::add)
        RuntimePaths.requiredModsDir(context).listFiles().orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
            .forEach(candidates::add)
        parentJar?.takeIf { it.isFile }?.let(candidates::add)
        val entries = ArrayList<File>()
        val unindexable = ArrayList<File>()
        candidates.forEach { jar ->
            val resolved = resolveCompileClasspathEntry(context, jar)
            if (resolved != null) entries.add(resolved) else unindexable.add(jar)
        }
        return CompileClasspathResolution(entries, unindexable)
    }

    /**
     * Returns a zip ECJ can index: the jar itself when Android's [ZipFile] accepts it, otherwise a
     * cached duplicate-free copy. A patched `desktop-1.0.jar` is normally already duplicate-free, so
     * the common path copies nothing.
     */
    private fun resolveCompileClasspathEntry(context: Context, jar: File): File? {
        if (isReadableZip(jar)) {
            return jar
        }
        val cacheDir = resolveAgentPatchCacheDir(context, COMPILE_CLASSPATH_CACHE_DIR) ?: return null
        val cached = File(cacheDir, "${jar.name}-${jar.length()}-${jar.lastModified()}.jar")
        if (isReadableZip(cached)) {
            return cached
        }
        val copied = runCatching {
            DuplicateZipEntryNormalizer.copyDeduplicated(jar, cached)
        }.isSuccess
        if (!copied) {
            cached.delete()
            return null
        }
        return cached.takeIf(::isReadableZip)
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
}
