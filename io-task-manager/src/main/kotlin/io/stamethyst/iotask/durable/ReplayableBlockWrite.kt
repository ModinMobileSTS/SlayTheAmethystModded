package io.stamethyst.iotask.durable

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

data class WriteBlock(val offset: Long, val length: Long, val sha256: String) {
    init {
        require(offset >= 0 && length > 0 && offset <= Long.MAX_VALUE - length)
        require(sha256.matches(Regex("[0-9a-f]{64}")))
    }
}

/** Owned candidate only; never use an existing published file as this workspace's candidate.
 * One owner, serialized writes. Source regeneration (including patch decoder restart) is caller-owned.
 */
class ReplayableBlockWrite private constructor(
    private val store: FileTaskStore,
    private val size: Long,
    private val blocks: List<WriteBlock>,
) : Closeable {
    val candidate: File = File(store.root, "candidate")

    companion object {
        fun open(root: File, generation: String, size: Long, blocks: List<WriteBlock>): ReplayableBlockWrite {
            require(generation.isNotBlank() && size >= 0)
            val layout = blocks.toList()
            var end = 0L
            layout.forEach { require(it.offset == end); end += it.length }
            require(end == size) { "Blocks must cover the candidate exactly without overlap" }
            val store = FileTaskStore(root)
            try {
                val definition = Definition("replayable-block-write", input = buildMap {
                    put("generation", generation)
                    put("size", size.toString())
                    layout.forEachIndexed { index, block ->
                        put("block.$index", "${block.offset}:${block.length}:${block.sha256}")
                    }
                })
                val old = store.read("blocks")
                if (old == null) {
                    check(!File(root, "candidate").exists()) { "Unowned candidate exists" }
                    store.write(Snapshot("blocks", definition, metadata = mapOf("prepared" to "false")))
                } else require(old.definition == definition) { "Generation or block layout changed" }
                val result = ReplayableBlockWrite(store, size, layout)
                if (store.read("blocks")!!.metadata["prepared"] != "true") {
                    RandomAccessFile(result.candidate, "rw").use { it.setLength(size); it.fd.sync() }
                    val record = store.read("blocks")!!
                    store.write(record.copy(metadata = record.metadata + ("prepared" to "true")))
                }
                result.validateLength()
                return result
            } catch (error: Exception) { store.close(); throw error }
        }
    }

    @Synchronized fun isComplete(index: Int): Boolean {
        val block = blocks[index]
        validateLength()
        if (store.read("blocks")!!.metadata["done.$index"] != "true") return false
        val digest = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(candidate, "r").use { file ->
            file.seek(block.offset)
            var remaining = block.length
            val buffer = ByteArray(64 * 1024)
            while (remaining > 0) {
                val count = minOf(buffer.size.toLong(), remaining).toInt()
                file.readFully(buffer, 0, count)
                digest.update(buffer, 0, count)
                remaining -= count
            }
        }
        if (hex(digest.digest()) == block.sha256) return true
        revert(index)
        return false
    }

    /** Revert this block's logical completion durably; does not restore or erase bytes. */
    @Synchronized fun revert(index: Int) {
        blocks[index]
        val record = store.read("blocks")!!
        store.write(record.copy(metadata = record.metadata - "done.$index"))
    }

    /** Consumes exactly length bytes; caller owns/closes input. No full-block allocation. */
    @Synchronized fun write(index: Int, input: InputStream) {
        val block = blocks[index]
        validateLength()
        revert(index)
        val digest = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(candidate, "rw").use { file ->
            file.seek(block.offset)
            var remaining = block.length
            val buffer = ByteArray(64 * 1024)
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                check(count > 0) { "Incomplete block input" }
                file.write(buffer, 0, count)
                digest.update(buffer, 0, count)
                remaining -= count
            }
            check(hex(digest.digest()) == block.sha256) { "Block digest mismatch" }
            file.fd.sync()
        }
        val record = store.read("blocks")!!
        store.write(record.copy(metadata = record.metadata + ("done.$index" to "true")))
    }

    @Synchronized fun verified(): Boolean = blocks.indices.all { isComplete(it) }
    private fun validateLength() { check(candidate.isFile && candidate.length() == size) { "Candidate missing or resized" } }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    @Synchronized override fun close() = store.close()
}
