package io.stamethyst.archivedelta

import com.google.archivepatcher.shared.DefaultDeflateCompatibilityWindow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.zip.Inflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** v1 ZIP envelope: exactly manifest.json and payload.deflate. Not jar-delta's entry format. */
object ArchivePackages {
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    private const val MANIFEST_LIMIT = 16384L

    fun write(destination: Path, manifest: ArchivePackageManifest, compressedPayload: Path) {
        Files.newOutputStream(destination).use { raw ->
            ZipOutputStream(raw).use { zip ->
                fun entry(name: String, body: () -> Unit) {
                    // STORED avoids compressing an already compressed codec payload twice.
                    val crc = java.util.zip.CRC32()
                    val data = if (name == "manifest.json") json.encodeToString(manifest).toByteArray() else null
                    if (data != null) crc.update(data) else Files.newInputStream(compressedPayload).use { input ->
                        val buffer = ByteArray(65536)
                        while (true) { val n = input.read(buffer); if (n < 0) break; crc.update(buffer, 0, n) }
                    }
                    zip.putNextEntry(ZipEntry(name).apply {
                        time = 0L; method = ZipEntry.STORED
                        size = data?.size?.toLong() ?: Files.size(compressedPayload); this.crc = crc.value
                    })
                    body(); zip.closeEntry()
                }
                entry("manifest.json") { zip.write(json.encodeToString(manifest).toByteArray()) }
                entry("payload.deflate") { Files.copy(compressedPayload, zip) }
            }
        }
    }

    /** Verifies structure and payload; does not execute or authenticate the package. */
    fun inspect(path: Path, limits: ArchiveLimits = ArchiveLimits()): ArchivePackageManifest = guarded {
        ArchiveIO.workspace(path.toAbsolutePath().parent) { work ->
            read(path, work.resolve("payload"), limits, ArchiveControl())
        }
    }

    private fun read(path: Path, payload: Path, limits: ArchiveLimits, control: ArchiveControl): ArchivePackageManifest {
        ArchiveIO.requireValid(Files.size(ArchiveIO.regular(path)) <= limits.maxPackageBytes, "Package exceeds limit", ArchiveFailure.LIMIT)
        return ZipFile(path.toFile()).use { zip ->
            val entries = zip.entries()
            val names = mutableSetOf<String>()
            var count = 0
            while (entries.hasMoreElements()) {
                val entryName = entries.nextElement().name
                ArchiveIO.requireValid(++count <= 2 && names.add(entryName), "Unexpected/duplicate package entries")
            }
            ArchiveIO.requireValid(names == setOf("manifest.json", "payload.deflate"), "Unexpected package entries")
            val entry = zip.getEntry("manifest.json")
            ArchiveIO.requireValid(entry.size in 1..MANIFEST_LIMIT, "Manifest too large")
            val bytes = java.io.ByteArrayOutputStream()
            zip.getInputStream(entry).use { ArchiveIO.copy(it, bytes, MANIFEST_LIMIT, control) }
            ArchiveIO.requireValid(bytes.size().toLong() == entry.size && java.util.zip.CRC32().apply { update(bytes.toByteArray()) }.value == entry.crc,
                "Manifest CRC/size mismatch")
            val manifest = json.decodeFromString<ArchivePackageManifest>(bytes.toString(Charsets.UTF_8))
            ArchiveIO.requireValid(manifest.format == "stamethyst-archive-delta" && manifest.version == 1 &&
                manifest.codec == "archive-patcher-file-by-file-v1" && manifest.compression == "deflate-raw", "Unsupported package", ArchiveFailure.UNSUPPORTED)
            fun identity(value: FileIdentity, max: Long) {
                ArchiveIO.requireValid(value.size in 0..max && value.sha256.matches(Regex("[0-9a-f]{64}")), "Invalid identity")
            }
            identity(manifest.base, limits.maxFileBytes); identity(manifest.target, limits.maxFileBytes)
            identity(manifest.payload, limits.maxPackageBytes)
            Files.newOutputStream(payload).use { out ->
                zip.getInputStream(zip.getEntry("payload.deflate")).use { ArchiveIO.copy(it, out, manifest.payload.size, control) }
            }
            val payloadEntry = zip.getEntry("payload.deflate")
            val crc = java.util.zip.CRC32()
            Files.newInputStream(payload).use { input ->
                val buffer = ByteArray(65536)
                while (true) { val n = input.read(buffer); if (n < 0) break; crc.update(buffer, 0, n) }
            }
            ArchiveIO.requireValid(Files.size(payload) == payloadEntry.size && crc.value == payloadEntry.crc, "Payload CRC/size mismatch")
            ArchiveIO.requireValid(ArchiveIO.identity(payload, limits.maxPackageBytes, control) == manifest.payload, "Payload digest/length mismatch", ArchiveFailure.VERIFICATION)
            manifest
        }
    }

    /** Produces a NEW exact-byte candidate; never replaces an installed file or manages history. */
    fun buildCandidate(
        base: Path, deltaPackage: Path, destination: Path, temporaryDirectory: Path,
        limits: ArchiveLimits = ArchiveLimits(), control: ArchiveControl = ArchiveControl(),
    ): ArchivePackageManifest = guarded {
        ArchiveIO.requireValid(!Files.exists(destination, NOFOLLOW_LINKS), "Destination exists")
        ArchiveIO.requireValid(DefaultDeflateCompatibilityWindow().isCompatible, "Deflate runtime is incompatible", ArchiveFailure.UNSUPPORTED)
        val outputParent = destination.toAbsolutePath().parent
        ArchiveIO.workspace(temporaryDirectory) { work ->
            val payload = work.resolve("payload")
            val manifest = read(deltaPackage, payload, limits, control)
            ArchiveIO.requireValid(ArchiveIO.identity(base, limits.maxFileBytes, control) == manifest.base, "Base digest/length mismatch", ArchiveFailure.VERIFICATION)
            val raw = work.resolve("native.patch")
            inflate(payload, raw, limits.maxPatchBytes, control)
            ArchiveIO.workspace(outputParent) { outputWork ->
                val candidate = outputWork.resolve("candidate.jar")
                NativePatch.apply(base, raw, candidate, work, limits, control)
                ArchiveIO.requireValid(ArchiveIO.identity(candidate, limits.maxFileBytes, control) == manifest.target, "Target digest/length mismatch", ArchiveFailure.VERIFICATION)
                ArchiveIO.requireValid(ArchiveIO.identity(base, limits.maxFileBytes, control) == manifest.base, "Base changed", ArchiveFailure.VERIFICATION)
                control.check()
                ArchiveIO.publish(candidate, destination)
            }
            manifest
        }
    }

    private fun inflate(payload: Path, output: Path, limit: Long, control: ArchiveControl) {
        val inflater = Inflater(true)
        try {
            Files.newInputStream(payload).use { input -> Files.newOutputStream(output).use { raw ->
                val out = ArchiveIO.bounded(raw, limit, control)
                val compressed = ByteArray(65536); val expanded = ByteArray(65536)
                while (!inflater.finished()) {
                    control.check()
                    if (inflater.needsInput()) {
                        val n = input.read(compressed)
                        ArchiveIO.requireValid(n > 0, "Truncated deflate payload")
                        inflater.setInput(compressed, 0, n)
                    }
                    val n = inflater.inflate(expanded)
                    if (n > 0) out.write(expanded, 0, n)
                    else ArchiveIO.requireValid(inflater.finished() || inflater.needsInput(), "Non-progressing deflate payload")
                }
                ArchiveIO.requireValid(inflater.remaining == 0 && input.read() == -1, "Trailing compressed payload")
            } }
        } finally { inflater.end() }
    }

    fun <T> guarded(block: () -> T): T {
        try { return block() }
        catch (error: ArchiveDeltaException) { throw error }
        catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ArchiveDeltaException(ArchiveFailure.CANCELLED, "Interrupted", error)
        }
        catch (error: java.io.IOException) { throw ArchiveDeltaException(ArchiveFailure.IO, error.message ?: "I/O failure", error) }
        catch (error: IllegalArgumentException) { throw ArchiveDeltaException(ArchiveFailure.INVALID_INPUT, error.message ?: "Invalid input", error) }
        catch (error: java.util.zip.DataFormatException) { throw ArchiveDeltaException(ArchiveFailure.INVALID_INPUT, "Invalid deflate payload", error) }
    }
}
