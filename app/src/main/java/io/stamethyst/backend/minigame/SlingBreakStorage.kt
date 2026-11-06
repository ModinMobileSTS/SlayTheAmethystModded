package io.stamethyst.backend.minigame

import android.content.Context
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Component ownership is separate from user saves and from the install serialization lock. */
object SlingBreakStorage {
    private val leasedRoots = mutableSetOf<String>()
    private val hash = Regex("[a-f0-9]{64}")
    private val game = Regex("game-[a-f0-9]{64}\\.pck")
    private val assets = Regex("assets-[a-f0-9]{64}\\.zip")
    private val engine = Regex("engine-[a-f0-9]{64}")
    private val retiredEngine = Regex("engine-retired-[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")

    /** Held for the entire engine process lifetime, or throughout installation and pruning. */
    @JvmStatic fun tryAcquireComponents(context: Context): Closeable? =
        tryAcquireComponents(SlingBreakInstallation.root(context))

    @Synchronized internal fun tryAcquireComponents(root: File): Closeable? {
        val key = root.canonicalPath
        // Opening and closing another descriptor for a locally locked inode can release
        // process-scoped POSIX locks. Reject local contenders before opening the file.
        if (key in leasedRoots) return null
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create minigame directory")
        val file = RandomAccessFile(File(root, "components.lock"), "rw")
        try {
            val lock = try { file.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            if (lock == null) {
                file.close()
                return null
            }
            leasedRoots.add(key)
            var closed = false
            return Closeable {
                synchronized(this) {
                    if (!closed) {
                        closed = true
                        try { lock.release() } finally {
                            try { file.close() } finally { leasedRoots.remove(key) }
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            file.close()
            throw error
        }
    }

    /** Call only after atomic activation, while holding the exclusive component lease. */
    internal fun prune(root: File, gameHash: String, engineHash: String): List<File> {
        require(hash.matches(gameHash) && hash.matches(engineHash))
        val keep = setOf("game-$gameHash.pck", "assets-$gameHash.zip", "engine-$engineHash")
        val entries = root.listFiles() ?: return listOf(root)
        return entries.filter { entry ->
            val path = entry.toPath()
            entry.name !in keep && when {
                game.matches(entry.name) || assets.matches(entry.name) ->
                    Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path)
                engine.matches(entry.name) || retiredEngine.matches(entry.name) ->
                    Files.isDirectory(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path)
                else -> false
            }
        }.filterNot { entry ->
            // No FOLLOW_LINKS: a stale component or a nested symlink must never delete saves
            // or any file outside this component directory. Failed deletions can be retried
            // on the next successful install, without rolling back an activated release.
            runCatching {
                Files.walkFileTree(entry.toPath(), object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        Files.delete(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                        if (error != null) throw error
                        Files.delete(dir)
                        return FileVisitResult.CONTINUE
                    }
                })
            }.isSuccess
        }
    }
}
