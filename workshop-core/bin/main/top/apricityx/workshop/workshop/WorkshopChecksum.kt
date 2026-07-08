package top.apricityx.workshop.workshop

import java.io.InputStream

internal fun steamAdler32(input: ByteArray): UInt {
    return steamAdler32(input.inputStream())
}

internal fun steamAdler32(input: InputStream): UInt {
    val mod = 65521u
    var a = 0u
    var b = 0u
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

    while (true) {
        val read = input.read(buffer)
        if (read == -1) {
            break
        }

        for (index in 0 until read) {
            a = (a + buffer[index].toUByte().toUInt()) % mod
            b = (b + a) % mod
        }
    }

    return (b shl 16) or a
}
