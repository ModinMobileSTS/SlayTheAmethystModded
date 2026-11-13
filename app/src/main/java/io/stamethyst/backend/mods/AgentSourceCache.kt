package io.stamethyst.backend.mods

import java.io.File
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.security.MessageDigest
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/** A cheap change token, not a content hash. Unknown/weak identities deliberately miss caches. */
internal data class AgentSourceFileIdentity(
    val path: String,
    val fileKey: String,
    val size: Long,
    val modified: String,
    val changed: String,
    val kind: String,
) {
    companion object {
        fun read(file: File): AgentSourceFileIdentity? = try {
            val path = file.canonicalFile.toPath()
            // One stat, retaining sub-millisecond precision. ctime detects in-place writes even if
            // an external writer restores size/mtime; fileKey detects rename-over replacements.
            val attrs = Files.readAttributes(path, "unix:fileKey,size,lastModifiedTime,ctime,isRegularFile,isDirectory")
            val key = attrs["fileKey"]?.toString()
            val changed = attrs["ctime"]?.toString()
            if (key == null || changed == null) null else AgentSourceFileIdentity(
                path.toString(), key, (attrs["size"] as Number).toLong(),
                attrs["lastModifiedTime"].toString(), changed,
                when {
                    attrs["isRegularFile"] == true -> "file"
                    attrs["isDirectory"] == true -> "directory"
                    else -> "other"
                },
            )
        } catch (_: NoSuchFileException) {
            AgentSourceFileIdentity(file.canonicalPath, "missing", 0, "", "", "missing")
        } catch (_: Exception) {
            // Providers without inode + ctime support must not use a size/mtime-only shortcut.
            null
        }
    }
}

internal data class AgentDecompileIdentity(
    val jar: AgentSourceFileIdentity,
    val dependencies: List<AgentSourceFileIdentity>,
    val optionsVersion: String = OPTIONS_VERSION,
) {
    fun fingerprint(): String = agentSourceToken(toString())

    companion object {
        const val OPTIONS_VERSION = "cfr-0.152-single-v2-silent-hideutf-no-comments-no-version"

        fun read(jar: File, classpath: String): AgentDecompileIdentity? {
            val source = AgentSourceFileIdentity.read(jar)?.takeIf { it.kind == "file" } ?: return null
            val dependencies = ArrayList<AgentSourceFileIdentity>()
            val entries = classpath.split(File.pathSeparatorChar)
            if (classpath.isNotBlank() && entries.any(String::isBlank)) return null
            for (entry in entries.filter(String::isNotBlank)) {
                if ('*' in entry) return null
                val identity = AgentSourceFileIdentity.read(File(entry)) ?: return null
                // A directory stat cannot detect changes to arbitrary descendant .class files.
                if (identity.kind != "file" && identity.kind != "missing") return null
                dependencies.add(identity)
            }
            return AgentDecompileIdentity(source, dependencies)
        }
    }
}

internal fun agentSourceToken(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** Bounded retained values; concurrent callers of the same key share the in-flight computation. */
internal class AgentSourceWorkCache<K : Any, V : Any>(
    private val maxEntries: Int,
    private val maxWeight: Long = Long.MAX_VALUE,
    private val weight: (V) -> Long = { 1L },
) {
    private val entries = LinkedHashMap<K, V>(16, 0.75f, true)
    private val pending = HashMap<K, FutureTask<V>>()
    private var retainedWeight = 0L

    fun getOrCompute(key: K, compute: () -> V): V {
        var owner = false
        val task = synchronized(this) {
            entries[key]?.let { return it }
            pending[key] ?: FutureTask {
                val value = compute()
                val size = weight(value)
                synchronized(this) {
                    if (size <= maxWeight && maxEntries > 0) {
                        entries[key] = value
                        retainedWeight += size
                        while (entries.size > maxEntries || retainedWeight > maxWeight) {
                            val oldest = entries.entries.iterator()
                            retainedWeight -= weight(oldest.next().value)
                            oldest.remove()
                        }
                    }
                }
                value
            }.also { pending[key] = it; owner = true }
        }
        if (owner) {
            try {
                task.run()
            } finally {
                synchronized(this) { pending.remove(key) }
            }
        }
        return try {
            task.get()
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }
}

internal class AgentVerifiedJarHashes(
    maxEntries: Int = 64,
    private val hash: (File) -> String = AgentModJarReader::sha256,
) {
    private val cache = AgentSourceWorkCache<AgentSourceFileIdentity, String>(maxEntries)

    fun sha256(file: File): String {
        val before = AgentSourceFileIdentity.read(file)?.takeIf { it.kind == "file" }
            ?: return hash(file)
        return cache.getOrCompute(before) {
            hash(file).also {
                check(before == AgentSourceFileIdentity.read(file)) { "Mod JAR changed while hashing." }
            }
        }
    }
}

/** Fixed lock stripes keep snapshot metadata updates ordered without retaining workspace paths. */
internal object AgentSourceSnapshotLocks {
    private val locks = Array(32) { Any() }

    fun <T> withLock(root: File, action: () -> T): T =
        synchronized(locks[(root.canonicalPath.hashCode() and Int.MAX_VALUE) % locks.size], action)
}
