package io.stamethyst.backend.mods

import org.benf.cfr.reader.api.CfrDriver
import org.benf.cfr.reader.api.OutputSinkFactory
import org.benf.cfr.reader.api.SinkReturns
import java.io.File

/** Summary of turning an extracted parent JAR into a readable Java project. */
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
 * The workspace only holds the parent mod's bytecode, so a patch author needs readable source to
 * know what to override. CFR is pure Java (class file 50) and runs inside the launcher process on
 * ART; it never executes the class it decompiles, it only parses it.
 */
object AgentPatchClassDecompiler {
    const val DEFAULT_MAX_DECOMPILE_CLASSES = 3000

    /**
     * Turns an already-extracted JAR into a readable Java project in [sourceDir].
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
