package top.apricityx.workshop.steam.protocol

import java.io.EOFException
import java.io.InputStream

fun InputStream.readFullyCompat(
    buffer: ByteArray,
    offset: Int = 0,
    length: Int = buffer.size - offset,
): Int {
    require(offset >= 0) { "offset must be non-negative" }
    require(length >= 0) { "length must be non-negative" }
    require(offset + length <= buffer.size) { "offset + length exceeds buffer size" }

    var totalRead = 0
    while (totalRead < length) {
        val read = read(buffer, offset + totalRead, length - totalRead)
        if (read == -1) {
            break
        }
        totalRead += read
    }
    return totalRead
}

fun InputStream.readExactlyCompat(length: Int): ByteArray {
    require(length >= 0) { "length must be non-negative" }
    val buffer = ByteArray(length)
    val bytesRead = readFullyCompat(buffer)
    if (bytesRead != length) {
        throw EOFException("Unexpected EOF while reading $length bytes")
    }
    return buffer
}
