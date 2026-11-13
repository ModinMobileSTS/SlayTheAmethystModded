package io.stamethyst.tools.workshop

import com.github.luben.zstd.Zstd
import io.stamethyst.backend.workshop.AndroidZstdBridge
import org.junit.Assert.*
import org.junit.Test

class DesktopZstdTest {
    @Test fun desktopBridgeImplementsTheEngineReflectionAbi() {
        val input = "SteamPipe Zstd 分块".repeat(500).toByteArray()
        val compressed = Zstd.compress(input)
        val output = ByteArray(input.size)
        val method = Class.forName("io.stamethyst.backend.workshop.AndroidZstdBridge")
            .getMethod("decompress", ByteArray::class.java, ByteArray::class.java)
        assertEquals(input.size, method.invoke(null, output, compressed))
        assertArrayEquals(input, output)
    }

    @Test fun invalidCompressedDataFailsInsteadOfReturningPartialOutput() {
        assertThrows(IllegalStateException::class.java) {
            AndroidZstdBridge.decompress(ByteArray(10), byteArrayOf(1, 2, 3))
        }
    }
}
