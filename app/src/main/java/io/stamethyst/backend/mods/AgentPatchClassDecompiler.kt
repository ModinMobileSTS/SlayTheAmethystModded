package io.stamethyst.backend.mods

import org.benf.cfr.reader.api.CfrDriver
import org.benf.cfr.reader.api.OutputSinkFactory
import org.benf.cfr.reader.api.SinkReturns
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class AgentSingleClassDecompileResult(
    val requestedClass: String,
    val classEntry: String,
    val source: String,
)

/** Summary of turning an extracted parent JAR into a readable Java project (legacy compatibility). */
data class AgentPatchJarDecompileResult(
    val totalClasses: Int,
    val decompiledClasses: Int,
    val failedClasses: Int,
    /** True when the class count exceeded the cap and the raw `.class` files were kept. */
    val skipped: Boolean,
) {
    companion object {
        val SKIPPED = AgentPatchJarDecompileResult(0, 0, 0, skipped = true)
    }
}

/**
 * Decompiles the selected parent mod's extracted classes to Java source on-device with CFR.
 *
 * The shared source tree holds the parent mod's bytecode and decompiled Java, so a patch author has
 * readable context to know what to override. CFR is pure Java (class file 50) and runs inside the
 * launcher process on ART; it never executes the class it decompiles, it only parses it.
 */
object AgentPatchClassDecompiler {
    const val DEFAULT_MAX_DECOMPILE_CLASSES = 3000
    private data class SourceKey(val identity: AgentDecompileIdentity, val binaryName: String)
    private val sources = AgentSourceWorkCache<SourceKey, AgentSingleClassDecompileResult>(
        maxEntries = 128,
        maxWeight = 4L * 1024 * 1024,
        weight = { it.source.length.toLong() * 2 },
    )
    internal val analysisCount = AtomicLong()

    /**
     * Decompiles one class without extracting the source archive. CFR receives a tiny temporary JAR
     * containing only the requested class while the original archive remains on its classpath for
     * reference resolution. The archive may be the parent mod or a game/API dependency.
     */
    fun decompileClass(
        jarFile: File,
        binaryName: String,
        classpath: String,
        scratchDir: File? = null,
    ): AgentSingleClassDecompileResult {
        val identity = AgentDecompileIdentity.read(jarFile, classpath)
        val compute = {
            decompileUncached(jarFile, binaryName, classpath, scratchDir, identity).also {
                check(identity == null || identity == AgentDecompileIdentity.read(jarFile, classpath)) {
                    "Decompile inputs changed during analysis; retry with the current archives."
                }
            }
        }
        return if (identity == null) compute() else sources.getOrCompute(SourceKey(identity, binaryName), compute)
    }

    private fun decompileUncached(
        jarFile: File,
        binaryName: String,
        classpath: String,
        scratchDir: File?,
        identity: AgentDecompileIdentity?,
    ): AgentSingleClassDecompileResult {
        val entryName = AgentModJarReader.classEntryName(jarFile, binaryName)
            ?: throw IllegalArgumentException("Class not found in archive: $binaryName")
        val classBytes = AgentModJarReader.readEntryBytes(jarFile, entryName)
            ?: throw IllegalArgumentException("Class bytes could not be read: $entryName")
        val directory = scratchDir?.takeIf { it.isDirectory || it.mkdirs() }
            ?: jarFile.parentFile?.takeIf { it.isDirectory }
            ?: File(System.getProperty("java.io.tmpdir") ?: ".")
        val temporaryJar = File.createTempFile("agent-class-", ".jar", directory)
        try {
            ZipOutputStream(temporaryJar.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(classBytes)
                zip.closeEntry()
            }
            val source = StringBuilder()
            activeSink.set { _, _, java -> if (source.isEmpty()) source.append(java) }
            try {
                val driver = driverFor(classpath, identity)
                runCatching {
                    analysisCount.incrementAndGet()
                    driver.analyse(listOf(temporaryJar.absolutePath))
                }.getOrElse { error ->
                    // A driver that failed mid-analysis may hold a half-built type cache; rebuild it.
                    if (identity != null) drivers.get().remove(identity)
                    throw IllegalStateException(
                        "CFR failed for $binaryName: ${error.message ?: error.javaClass.simpleName}",
                        error,
                    )
                }
            } finally {
                activeSink.remove()
            }
            if (source.isEmpty()) {
                if (identity != null) drivers.get().remove(identity)
                throw IllegalStateException("CFR produced no source for $binaryName")
            }
            return AgentSingleClassDecompileResult(binaryName, entryName, source.toString())
        } finally {
            temporaryJar.delete()
        }
    }

    /**
     * CFR keeps its classpath source (and the archive handles behind it) inside the driver, so a
     * driver reused for the same input identity keeps type lookups warm between calls. CFR's source is
     * not thread-safe, so each thread owns its own cache; the API decompile tool is allowed to run
     * in parallel and relies on that isolation.
     */
    private fun driverFor(classpath: String, identity: AgentDecompileIdentity?): CfrDriver {
        val cache = drivers.get()
        if (identity != null) cache[identity]?.let {
            if (it.analyses < MAX_ANALYSES_PER_DRIVER) {
                it.analyses++
                return it.driver
            }
            cache.remove(identity)
        }
        val options = buildMap {
            put("silent", "true")
            put("hideutf", "true")
            put("comments", "false")
            put("showversion", "false")
            if (classpath.isNotBlank()) put("extraclasspath", classpath)
        }
        val driver = CfrDriver.Builder()
            .withOptions(options)
            .withOutputSink(ThreadOutputSink)
            .build()
        if (identity != null) cache[identity] = CachedDriver(driver, analyses = 1)
        while (cache.size > MAX_CACHED_DRIVERS) {
            val eldest = cache.keys.firstOrNull() ?: break
            cache.remove(eldest)
        }
        return driver
    }

    /**
     * Decompiles an already-extracted JAR into a readable Java project in [sourceDir].
     *
     * Assumes the JAR's entries were extracted into [sourceDir] first (resources and `.class`
     * files). CFR runs once over the archive; every decompiled class is written as a `.java` next
     * to its `.class`, and the now-redundant `.class` is removed. Classes CFR could not handle keep
     * their raw bytecode so nothing is lost. Above [maxClasses] the archive is left as-is to bound
     * the work.
     */
    fun decompileJarInto(
        sourceDir: File,
        jarFile: File,
        classpath: String,
        maxClasses: Int = DEFAULT_MAX_DECOMPILE_CLASSES,
    ): AgentPatchJarDecompileResult {
        val classFiles = sourceDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("class", ignoreCase = true) }
            .toList()
        if (classFiles.isEmpty()) {
            return AgentPatchJarDecompileResult(0, 0, 0, skipped = false)
        }
        if (classFiles.size > maxClasses) {
            return AgentPatchJarDecompileResult.SKIPPED.copy(totalClasses = classFiles.size)
        }

        val emitted = LinkedHashSet<String>()
        val options = buildMap {
            put("silent", "true")
            put("hideutf", "true")
            put("comments", "false")
            put("showversion", "false")
            if (classpath.isNotBlank()) put("extraclasspath", classpath)
        }
        val sink = OutputSinkFactoryAdapter { packageName, className, java ->
            val relative = binaryEntryPath(packageName, className) ?: return@OutputSinkFactoryAdapter
            if (!emitted.add(relative)) return@OutputSinkFactoryAdapter
            val javaFile = File(sourceDir, relative.removeSuffix(".class") + ".java")
            if (!javaFile.canonicalFile.toPath().startsWith(sourceDir.canonicalFile.toPath())) {
                return@OutputSinkFactoryAdapter
            }
            javaFile.parentFile?.mkdirs()
            runCatching { javaFile.writeText(java, Charsets.UTF_8) }
            File(sourceDir, relative).delete()
        }
        val ran = runCatching {
            CfrDriver.Builder()
                .withOptions(options)
                .withOutputSink(sink)
                .build()
                .analyse(listOf(jarFile.absolutePath))
        }.isSuccess
        if (!ran) {
            return AgentPatchJarDecompileResult(classFiles.size, 0, classFiles.size, skipped = false)
        }
        val decompiled = classFiles.count { emitted.contains(it.relativeTo(sourceDir).invariantSeparatorsPath) }
        return AgentPatchJarDecompileResult(
            totalClasses = classFiles.size,
            decompiledClasses = decompiled,
            failedClasses = classFiles.size - decompiled,
            skipped = false,
        )
    }

    /** Builds the JAR-relative `.class` entry for a decompiled class, or null when unusable. */
    internal fun binaryEntryPath(packageName: String?, className: String?): String? {
        val simple = className?.trim().orEmpty()
        if (simple.isEmpty() || simple.contains("..") || simple.contains('/') || simple.contains('\\')) return null
        val rawPackage = packageName?.trim().orEmpty().trim('.')
        if (rawPackage.contains("..") || rawPackage.contains('/') || rawPackage.contains('\\')) return null
        val packagePath = rawPackage.replace('.', '/')
        return (if (packagePath.isEmpty()) simple else "$packagePath/$simple") + ".class"
    }

    private const val MAX_CACHED_DRIVERS = 2
    private const val MAX_ANALYSES_PER_DRIVER = 32
    private data class CachedDriver(val driver: CfrDriver, var analyses: Int)

    /** Bound each driver's internal archive/type history as well as the number of warm drivers. */
    private val drivers = ThreadLocal.withInitial { LinkedHashMap<AgentDecompileIdentity, CachedDriver>() }

    /** Sink of the decompile call currently running on this thread. */
    private val activeSink = ThreadLocal<((String?, String?, String) -> Unit)?>()

    /**
     * Stable sink handed to cached drivers. A driver is reused across calls, so the sink cannot
     * capture one call's buffer; it forwards to whichever call is active on the current thread.
     */
    private object ThreadOutputSink : OutputSinkFactory {
        override fun getSupportedSinks(
            sinkType: OutputSinkFactory.SinkType,
            available: Collection<OutputSinkFactory.SinkClass>,
        ): List<OutputSinkFactory.SinkClass> = when (sinkType) {
            OutputSinkFactory.SinkType.JAVA -> listOf(OutputSinkFactory.SinkClass.DECOMPILED)
            OutputSinkFactory.SinkType.EXCEPTION -> listOf(OutputSinkFactory.SinkClass.EXCEPTION_MESSAGE)
            else -> emptyList()
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> getSink(
            sinkType: OutputSinkFactory.SinkType,
            sinkClass: OutputSinkFactory.SinkClass,
        ): OutputSinkFactory.Sink<T> = object : OutputSinkFactory.Sink<T> {
            override fun write(output: T) {
                if (output is SinkReturns.Decompiled) {
                    activeSink.get()?.invoke(output.packageName, output.className, output.java)
                }
            }
        }
    }

    /**
     * Base [OutputSinkFactory] that accepts CFR's `DECOMPILED` and `EXCEPTION_MESSAGE` sinks and
     * forwards each decompiled class to [onClass] as `(packageName, className, java)`.
     */
    private open class OutputSinkFactoryAdapter(
        private val onClass: (String?, String?, String) -> Unit,
    ) : OutputSinkFactory {
        override fun getSupportedSinks(
            sinkType: OutputSinkFactory.SinkType,
            available: Collection<OutputSinkFactory.SinkClass>,
        ): List<OutputSinkFactory.SinkClass> = when (sinkType) {
            OutputSinkFactory.SinkType.JAVA -> listOf(OutputSinkFactory.SinkClass.DECOMPILED)
            OutputSinkFactory.SinkType.EXCEPTION -> listOf(OutputSinkFactory.SinkClass.EXCEPTION_MESSAGE)
            else -> emptyList()
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> getSink(
            sinkType: OutputSinkFactory.SinkType,
            sinkClass: OutputSinkFactory.SinkClass,
        ): OutputSinkFactory.Sink<T> = object : OutputSinkFactory.Sink<T> {
            override fun write(output: T) {
                if (output is SinkReturns.Decompiled) {
                    onClass(output.packageName, output.className, output.java)
                }
            }
        }
    }
}
