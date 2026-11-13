package io.stamethyst.backend.mods

import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * On-disk cache for `decompile_agent_api_class` output.
 *
 * Game/API source is never written into the parent mod's `source/` tree, so without this cache every
 * call paid the full CFR cost again. Entries are keyed by the archive fingerprint and the binary
 * class name, dependency identities and CFR options version. Strong filesystem change tokens avoid
 * hashing the entire game JAR. Callers with dependencies must pass the same classpath used by CFR.
 *
 * The cache lives under the app cache (falling back to internal storage), is bounded, and is
 * pruned oldest-first. A cache miss only costs a slower hit later.
 */
object AgentApiSourceCache {
    private const val CACHE_DIR = "agent-api-source"
    private const val MAX_CACHE_BYTES = 24L * 1024L * 1024L
    private const val MAX_CACHE_FILES = 512
    private const val CACHE_VERSION = "provenance-v1"

    /** Capture before reading/decompiling; publishing must retain this original identity. */
    class SourceToken internal constructor(internal val identity: AgentDecompileIdentity)

    fun capture(jar: File, classpath: String = ""): SourceToken? =
        AgentDecompileIdentity.read(jar, classpath)?.let(::SourceToken)

    /** Resolves the writable cache root, or null when the device has no usable scratch directory. */
    fun root(context: Context): File? =
        AgentPatchSourceCompiler.resolveAgentPatchCacheDir(context, CACHE_DIR)

    fun fingerprint(jar: File): String = capture(jar)?.let(::fingerprint)
        ?: "${jar.name.take(80)}-${java.util.UUID.randomUUID()}"

    private fun fingerprint(token: SourceToken): String =
        "${File(token.identity.jar.path).name.take(80)}-${agentSourceToken("$CACHE_VERSION|${token.identity.fingerprint()}")}"

    fun entryFile(root: File, jar: File, binaryName: String, classpath: String = ""): File? =
        capture(jar, classpath)?.let { entryFile(root, binaryName, it) }

    private fun entryFile(root: File, binaryName: String, token: SourceToken): File? {
        val relative = relativePath(binaryName) ?: return null
        val directory = File(root, sanitize(fingerprint(token)))
        val file = File(directory, relative)
        val canonicalRoot = runCatching { directory.canonicalFile.toPath() }.getOrNull() ?: return null
        val canonicalFile = runCatching { file.canonicalFile.toPath() }.getOrNull() ?: return null
        return if (canonicalFile.startsWith(canonicalRoot)) file else null
    }

    fun read(
        root: File,
        jar: File,
        binaryName: String,
        classpath: String = "",
        sourceToken: SourceToken? = capture(jar, classpath),
    ): String? {
        val token = sourceToken ?: return null
        if (token.identity != AgentDecompileIdentity.read(jar, classpath)) return null
        val file = entryFile(root, binaryName, token) ?: return null
        if (!file.isFile) return null
        return runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull()?.takeIf {
            it.isNotEmpty() && token.identity == AgentDecompileIdentity.read(jar, classpath)
        }
    }

    /** Legacy calls without provenance are deliberately no-ops, rather than relabeling old source. */
    fun write(
        root: File,
        jar: File,
        binaryName: String,
        source: String,
        classpath: String = "",
        sourceToken: SourceToken? = null,
    ) {
        val token = sourceToken ?: return
        if (token.identity != AgentDecompileIdentity.read(jar, classpath)) return
        val file = entryFile(root, binaryName, token) ?: return
        val directory = file.parentFile ?: return
        if (!directory.isDirectory && !directory.mkdirs()) return
        val temporary = File(directory, "${file.name}.${System.nanoTime()}.${Thread.currentThread().id}.tmp")
        val written = runCatching {
            temporary.writeText(source, StandardCharsets.UTF_8)
            if (token.identity != AgentDecompileIdentity.read(jar, classpath)) return@runCatching false
            if (file.exists() && !file.delete()) return@runCatching false
            temporary.renameTo(file)
        }.getOrDefault(false)
        if (!written) temporary.delete()
        prune(root)
    }

    /** Drops the oldest entries once the cache exceeds its byte or file budget. */
    internal fun prune(
        root: File,
        maxBytes: Long = MAX_CACHE_BYTES,
        maxFiles: Int = MAX_CACHE_FILES,
    ) {
        val files = runCatching {
            root.walkTopDown()
                .filter { it.isFile && it.extension.equals("java", ignoreCase = true) }
                .toList()
        }.getOrNull() ?: return
        var total = files.sumOf { it.length() }
        var count = files.size
        if (total <= maxBytes && count <= maxFiles) return
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= maxBytes && count <= maxFiles) return@forEach
            val length = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) {
                total -= length
                count--
            }
        }
    }

    private fun relativePath(binaryName: String): String? {
        val normalized = binaryName.trim()
            .removePrefix("L")
            .removeSuffix(";")
            .replace('/', '.')
        if (normalized.isEmpty() || normalized.contains("..")) return null
        val parts = normalized.split('.').filter(String::isNotEmpty)
        if (parts.isEmpty()) return null
        if (parts.any { part -> part.any { it == '/' || it == '\\' || it == ':' } }) return null
        return parts.joinToString("/") + ".java"
    }

    private fun sanitize(value: String): String =
        value.map { char -> if (char.isLetterOrDigit() || char == '.' || char == '-' || char == '_') char else '_' }
            .joinToString("")
}
