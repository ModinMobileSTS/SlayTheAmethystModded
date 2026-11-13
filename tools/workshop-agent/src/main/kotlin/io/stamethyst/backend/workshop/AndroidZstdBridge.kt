package io.stamethyst.backend.workshop

import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdException

/** Desktop implementation of the reflection ABI used by workshop-core's ChunkProcessor.
 * This class lives only in the CLI distribution; the launcher keeps its Android JNI bridge.
 */
object AndroidZstdBridge {
    @JvmStatic
    fun decompress(destination: ByteArray, compressed: ByteArray): Int {
        val written = try {
            Zstd.decompress(destination, compressed)
        } catch (error: ZstdException) {
            throw IllegalStateException("Zstd decompression failed: ${error.message}", error)
        }
        check(!Zstd.isError(written)) { "Zstd decompression failed: ${Zstd.getErrorName(written)}" }
        return Math.toIntExact(written)
    }
}
