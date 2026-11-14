package io.stamethyst.jardelta

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 255) }

/** Independent oracle: fixture contents, not production archive scanner/fingerprint. */
internal fun contentHash(contents: Map<String, ByteArray>): String {
    val framed = java.io.ByteArrayOutputStream()
    contents.toSortedMap().forEach { (name, bytes) ->
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        framed.write(ByteBuffer.allocate(4).putInt(nameBytes.size).array())
        framed.write(nameBytes)
        framed.write(ByteBuffer.allocate(8).putLong(bytes.size.toLong()).array())
        framed.write(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
    return sha(framed.toByteArray())
}

internal fun writeZip(path: Path, contents: Map<String, ByteArray>): Path {
    ZipOutputStream(Files.newOutputStream(path)).use { zip ->
        contents.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    return path
}

internal fun readZip(path: Path): Map<String, ByteArray> = ZipFile(path.toFile()).use { zip ->
    zip.entries().asSequence().associate { entry ->
        entry.name to zip.getInputStream(entry).use { it.readBytes() }
    }
}

internal fun patch(
    path: Path, base: Path, result: Map<String, ByteArray>, operations: List<DeltaOperation>,
    payloads: Map<String, ByteArray>, transform: (DeltaManifest) -> DeltaManifest = { it },
): Path {
    val manifest = transform(DeltaManifest(1, sha(Files.readAllBytes(base)), contentHash(result), operations))
    return writeZip(path, mapOf("manifest.json" to Json.encodeToString(manifest).toByteArray()) + payloads)
}

internal fun assertContents(expected: Map<String, ByteArray>, actual: Path) {
    val contents = readZip(actual)
    org.junit.Assert.assertEquals(expected.keys, contents.keys)
    expected.forEach { (key, value) -> org.junit.Assert.assertArrayEquals(key, value, contents.getValue(key)) }
}
