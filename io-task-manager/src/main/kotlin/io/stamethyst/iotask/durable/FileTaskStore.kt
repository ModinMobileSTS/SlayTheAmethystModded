package io.stamethyst.iotask.durable

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** One live owner per root. Atomic replacement covers process loss, not a power-loss guarantee. */
class FileTaskStore(val root: File) : Closeable {
    private val lockFile: RandomAccessFile
    private val lock: java.nio.channels.FileLock
    private var closed = false

    init {
        check(root.isDirectory || root.mkdirs())
        lockFile = RandomAccessFile(File(root, "owner.lock"), "rw")
        try {
            lock = checkNotNull(lockFile.channel.tryLock()) { "Task store already owned" }
        } catch (error: Exception) {
            lockFile.close()
            throw error
        }
    }

    private fun key(id: String): String {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid task ID" }
        return id
    }

    @Synchronized fun workspace(id: String): File {
        check(!closed)
        return File(root, "work/${key(id)}").also { check(it.isDirectory || it.mkdirs()) }
    }

    @Synchronized fun all(): List<Snapshot> {
        check(!closed)
        return root.listFiles().orEmpty().filter { it.name.endsWith(".task") }
            .sortedBy { it.name }.map { read(it.name.removeSuffix(".task"))!! }
    }

    @Synchronized fun read(id: String): Snapshot? {
        check(!closed)
        val file = File(root, "${key(id)}.task")
        if (!file.exists()) return null
        require(file.length() in 32..MAX_RECORD.toLong()) { "Invalid task record size: $id" }
        val bytes = file.readBytes()
        val payload = bytes.copyOfRange(32, bytes.size)
        require(MessageDigest.isEqual(bytes.copyOfRange(0, 32), digest(payload))) {
            "Corrupt task record: $id"
        }
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == 2) { "Unsupported task record schema (expected 2)" }
            val savedId = input.readUTF()
            require(savedId == id)
            val definition = Definition(input.readUTF(), input.readInt(), input.readMap(), Kind.valueOf(input.readUTF()))
            Snapshot(savedId, definition, State.valueOf(input.readUTF()), Control.valueOf(input.readUTF()),
                input.readMap(), input.readMap(), input.readNullable(), input.readNullable(),
                input.readNullable(), input.readInt()).also {
                require(input.available() == 0) { "Trailing task record data" }
            }
        }
    }

    @Synchronized fun write(record: Snapshot) {
        check(!closed)
        key(record.id)
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(2)
            out.writeUTF(record.id)
            out.writeUTF(record.definition.type)
            out.writeInt(record.definition.version)
            out.writeMap(record.definition.input)
            out.writeUTF(record.definition.kind.name)
            out.writeUTF(record.state.name)
            out.writeUTF(record.control.name)
            out.writeMap(record.metadata)
            out.writeMap(record.output)
            out.writeNullable(record.reason)
            out.writeNullable(record.parent)
            out.writeNullable(record.childKey)
            out.writeInt(record.attempt)
        }
        val payload = buffer.toByteArray()
        require(payload.size + 32 <= MAX_RECORD) { "Task metadata too large" }
        val temporary = File(root, "${record.id}.pending")
        FileOutputStream(temporary).use {
            it.write(digest(payload))
            it.write(payload)
            it.fd.sync()
        }
        // No non-atomic fallback: unsupported filesystems must fail explicitly.
        Files.move(temporary.toPath(), File(root, "${record.id}.task").toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** Delete work first and the recovery record last. Safe to repeat after process loss. */
    @Synchronized fun remove(id: String) {
        check(!closed)
        key(id)
        val work = File(root, "work/$id")
        // Never follow a symbolic link into a published directory.
        fun deleteTree(file: File) {
            if (Files.isSymbolicLink(file.toPath())) {
                Files.deleteIfExists(file.toPath())
                return
            }
            if (file.isDirectory) {
                val entries = checkNotNull(file.listFiles()) { "Cannot list $file" }
                entries.forEach(::deleteTree)
            }
            Files.deleteIfExists(file.toPath())
        }
        deleteTree(work)
        Files.deleteIfExists(File(root, "$id.pending").toPath())
        Files.deleteIfExists(File(root, "$id.task").toPath())
    }

    @Synchronized override fun close() {
        if (!closed) {
            closed = true
            try { lock.release() } finally { lockFile.close() }
        }
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun DataOutputStream.writeNullable(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeUTF(value)
    }
    private fun DataInputStream.readNullable(): String? = if (readBoolean()) readUTF() else null
    private fun DataOutputStream.writeMap(values: Map<String, String>) {
        require(values.size <= 10000)
        writeInt(values.size)
        values.toSortedMap().forEach { (key, value) -> writeUTF(key); writeUTF(value) }
    }
    private fun DataInputStream.readMap(): Map<String, String> {
        val count = readInt()
        require(count in 0..10000)
        val result = linkedMapOf<String, String>()
        repeat(count) { require(result.put(readUTF(), readUTF()) == null) }
        return result
    }
    private companion object { const val MAX_RECORD = 4 * 1024 * 1024 }
}
