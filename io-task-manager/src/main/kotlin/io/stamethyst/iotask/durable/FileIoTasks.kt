package io.stamethyst.iotask.durable

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Read-only operations; deliberately rejects symbolic links, including ancestor links. */
object FileInspection {
    fun checked(file: File): File {
        require(file.isAbsolute) { "Absolute path required" }
        val path = file.toPath().normalize()
        var cursor = path.root
        for (part in path) {
            cursor = cursor.resolve(part)
            require(!Files.isSymbolicLink(cursor)) { "Symbolic link: $cursor" }
        }
        return path.toFile()
    }

    fun sha256(file: File): String {
        val source = checked(file)
        require(source.isFile) { "Regular file required" }
        val digest = MessageDigest.getInstance("SHA-256")
        source.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return hex(digest.digest())
    }

    fun scan(directory: File): List<File> {
        val root = checked(directory)
        require(root.isDirectory)
        return (root.listFiles() ?: error("Cannot list $root")).sortedBy { it.name }.flatMap {
            checked(it)
            require(it.isDirectory || it.isFile) { "Unsupported file type" }
            listOf(it) + if (it.isDirectory) scan(it) else emptyList()
        }
    }

    internal fun identity(file: File): String {
        val attributes = Files.readAttributes(checked(file).toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(attributes.isRegularFile || attributes.isDirectory) { "Unsupported file type" }
        val key = checkNotNull(attributes.fileKey()) { "Filesystem identity unavailable" }
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        add(key.toString())
        if (attributes.isRegularFile) add(sha256(file)) else {
            add("directory")
            scan(file).forEach {
                add(file.toPath().relativize(it.toPath()).toString())
                val attrs = Files.readAttributes(it.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                add(checkNotNull(attrs.fileKey()).toString())
                add(if (it.isFile) sha256(it) else "directory")
            }
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}

/**
 * Managed reversible mutations. Requires exclusive host ownership of operand namespaces (not a
 * security boundary against concurrent writers). Dedicated history MUST outlive Manager workspaces.
 * All renames require the same filesystem as history; no non-atomic or copy/delete fallback.
 */
class FileIoTasks(private val historyRoot: File) : TaskFactory {
    companion object {
        private const val TYPE = "file-io"
        fun mkdir(target: File) = definition("mkdir", target = target)
        fun copy(source: File, target: File) = definition("copy", source, target)
        fun move(source: File, target: File) = definition("move", source, target)
        fun delete(source: File) = definition("delete", source)
        /** Writes a complete regular-file input; input is never consumed/deleted. */
        fun write(source: File, target: File, replace: Boolean = false) =
            definition(if (replace) "replace" else "write", source, target)
        /** Undo retained history after a root Plan has reclaimed the original task record. */
        fun undo(originalId: String): Definition {
            require(originalId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
            return Definition(TYPE, input = mapOf("operation" to "undo", "originalId" to originalId))
        }

        private fun definition(operation: String, source: File? = null, target: File? = null) =
            Definition(TYPE, input = buildMap {
                put("operation", operation)
                source?.let { put("source", FileInspection.checked(it).path) }
                target?.let { put("target", FileInspection.checked(it).path) }
            })
    }

    override fun create(definition: Definition): Task {
        require(definition.type == TYPE && definition.version == 1 && definition.kind == Kind.TASK)
        val operation = definition.input.getValue("operation")
        require(operation in setOf("mkdir", "copy", "move", "delete", "write", "replace", "undo"))
        return Mutation(definition, operation)
    }

    private inner class Mutation(private val definition: Definition, private val operation: String) : Task() {
        private val source get() = definition.input["source"]?.let { FileInspection.checked(File(it)) }
        private val target get() = definition.input["target"]?.let { FileInspection.checked(File(it)) }

        private fun validate(context: Context, history: File, operands: List<File>) {
            val work = context.workspace.toPath().parent
            require(!history.toPath().startsWith(work) && !work.startsWith(history.toPath())) { "History overlaps workspaces" }
            operands.forEach {
                val path = FileInspection.checked(it).toPath()
                require(!path.startsWith(history.toPath()) && !history.toPath().startsWith(path)) { "History overlaps operand" }
                require(!path.startsWith(work) && !work.startsWith(path)) { "Workspace overlaps operand" }
            }
        }

        private suspend fun <T> journal(context: Context, block: (FileTaskStore) -> T): T = withContext(Dispatchers.IO) {
            val history = FileInspection.checked(historyRoot)
            validate(context, history, listOfNotNull(source, target))
            if (source != null && target != null) {
                val a = source!!.toPath(); val b = target!!.toPath()
                require(!a.startsWith(b) && !b.startsWith(a)) { "Overlapping operands" }
            }
            // One history owner serializes mutations; lock contention explicitly fails, never spins.
            FileTaskStore(history).use(block)
        }

        private fun record(store: FileTaskStore, context: Context): Snapshot? = store.read(context.id)?.also {
            require(it.definition == definition) { "History ID reused" }
        }

        override suspend fun execute(context: Context): Result {
            if (context.control == Control.CANCEL) return Result.Cancel
            if (context.control == Control.PAUSE) return Result.Paused
            return journal(context) { store ->
                if (operation == "undo") {
                    val original = checkNotNull(store.read(definition.input.getValue("originalId"))) { "Unknown history" }
                    validate(context, historyRoot, listOfNotNull(original.definition.input["source"], original.definition.input["target"]).map(::File))
                    undoRecord(store, original)
                    return@journal Result.Success()
                }
                var saved = record(store, context)
                check(saved?.metadata?.get("phase") != "undone") { "Use a new Task after undo" }
                if (saved == null) {
                    val src = source; val dst = target
                    require(dst == null || (dst.parentFile.isDirectory && (!dst.exists() || operation == "replace"))) { "Target conflict or missing parent" }
                    require(src == null || src.exists()) { "Missing source" }
                    if (operation in setOf("copy", "write", "replace")) require(src!!.isFile) { "Regular file required" }
                    if (operation == "replace") require(dst!!.isFile) { "Replacement requires existing regular file" }
                    // Do not query FileStore: Android's provider rejects it for app UIDs.
                    // ATOMIC_MOVE below is the authoritative same-filesystem check; EXDEV
                    // propagates with the durable intent retained, never copy/delete fallback.
                    saved = Snapshot(context.id, definition, metadata = buildMap {
                        put("phase", "building")
                        src?.let { put("sourceIdentity", FileInspection.identity(it)) }
                        if (dst?.exists() == true) put("targetIdentity", FileInspection.identity(dst))
                    })
                    store.write(saved)
                }
                val candidate = File(store.workspace(context.id), "candidate")
                val backup = File(store.workspace(context.id), "backup")
                if (saved.metadata["phase"] == "building") {
                    source?.let { check(FileInspection.identity(it) == saved.metadata.getValue("sourceIdentity")) { "Source changed" } }
                    when (operation) {
                        "mkdir" -> check(candidate.isDirectory || candidate.mkdir())
                        "copy", "write", "replace" -> {
                            FileOutputStream(candidate).use { output ->
                                source!!.inputStream().use { it.copyTo(output, 64 * 1024) }
                                output.fd.sync()
                            }
                            check(FileInspection.sha256(source!!) == FileInspection.sha256(candidate)) { "Copy mismatch" }
                            check(FileInspection.identity(source!!) == saved.metadata.getValue("sourceIdentity")) { "Source changed during copy" }
                        }
                    }
                    val edges = when (operation) {
                        "move" -> listOf(source!! to target!!)
                        "delete" -> listOf(source!! to backup)
                        "replace" -> listOf(target!! to backup, candidate to target!!)
                        else -> listOf(candidate to target!!)
                    }
                    val metadata = saved.metadata.toMutableMap()
                    metadata["phase"] = "ready"
                    metadata["count"] = edges.size.toString()
                    edges.forEachIndexed { index, (from, to) ->
                        metadata["from.$index"] = from.path
                        metadata["to.$index"] = to.path
                        metadata["identity.$index"] = FileInspection.identity(from)
                    }
                    if (operation == "replace") check(metadata["identity.0"] == metadata["targetIdentity"]) { "Target changed" }
                    saved = saved.copy(metadata = metadata)
                    store.write(saved)
                }
                check(saved.metadata["phase"] in setOf("ready", "done")) { "Mutation being undone" }
                edges(saved, reverse = false)
                store.write(saved.copy(metadata = saved.metadata + ("phase" to "done")))
                Result.Success(mapOf("historyId" to context.id) + (target?.let { mapOf("file" to it.path) } ?: emptyMap()))
            }
        }

        override suspend fun recover(context: Context): Recovery = Recovery.Finished(execute(context))

        private fun edges(saved: Snapshot, reverse: Boolean) {
            val count = saved.metadata.getValue(if (reverse) "undoCount" else "count").toInt()
            val indices = if (reverse) (0 until count).reversed() else (0 until count).toList()
            for (index in indices) {
                val a = File(saved.metadata.getValue("from.$index"))
                val b = File(saved.metadata.getValue("to.$index"))
                val from = FileInspection.checked(if (reverse) b else a)
                val to = FileInspection.checked(if (reverse) a else b)
                val identity = saved.metadata.getValue("identity.$index")
                if (to.exists() && FileInspection.identity(to) == identity) {
                    // A later edge can reuse the original path during replacement.
                    check(!from.exists() || FileInspection.identity(from) != identity) { "Ambiguous identity" }
                } else {
                    check(from.exists() && FileInspection.identity(from) == identity && !to.exists()) { "File conflict" }
                    Files.move(from.toPath(), to.toPath(), ATOMIC_MOVE)
                }
            }
        }

        private fun undoRecord(store: FileTaskStore, saved: Snapshot) {
            when (saved.metadata["phase"]) {
                "undone" -> Unit
                "building" -> store.write(saved.copy(metadata = saved.metadata + ("phase" to "undone")))
                else -> {
                    val undoCount = saved.metadata["undoCount"] ?: if (saved.metadata["phase"] == "done") {
                        saved.metadata.getValue("count")
                    } else {
                        // Ready intent may have committed only a prefix. Persist that prefix
                        // before undo so a crash cannot change our interpretation halfway back.
                        var completed = 0
                        for (index in 0 until saved.metadata.getValue("count").toInt()) {
                            val to = File(saved.metadata.getValue("to.$index"))
                            val from = File(saved.metadata.getValue("from.$index"))
                            val identity = saved.metadata.getValue("identity.$index")
                            if (to.exists() && FileInspection.identity(to) == identity) completed++ else {
                                check(from.exists() && FileInspection.identity(from) == identity) { "Cannot reconcile undo" }
                                break
                            }
                        }
                        completed.toString()
                    }
                    val undoing = saved.copy(metadata = saved.metadata + mapOf("phase" to "undoing", "undoCount" to undoCount))
                    store.write(undoing)
                    edges(undoing, reverse = true)
                    store.write(undoing.copy(metadata = undoing.metadata + ("phase" to "undone")))
                }
            }
        }

        override suspend fun revert(context: Context) {
            check(operation != "undo") { "Undo task cannot itself be undone" }
            journal(context) { store -> record(store, context)?.let { undoRecord(store, it) } }
        }

        override suspend fun cleanup(context: Context) {
            if (operation != "undo") revert(context)
        }
    }
}
