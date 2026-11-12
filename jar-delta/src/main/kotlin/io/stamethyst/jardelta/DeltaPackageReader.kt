package io.stamethyst.jardelta

import kotlinx.serialization.json.Json
import java.util.zip.ZipFile

internal object DeltaPackageReader {
    private val json = Json { ignoreUnknownKeys = false }

    fun read(zip: ZipFile, limits: DeltaLimits): DeltaManifest {
        val manifestEntry = zip.getEntry("manifest.json") ?: throw DeltaException("Missing manifest.json")
        checkDelta(manifestEntry.size in 1..limits.maxManifestBytes, "Manifest exceeds limit")
        val bytes = zip.getInputStream(manifestEntry).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var count = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                count += n
                checkDelta(count <= limits.maxManifestBytes, "Manifest exceeds limit")
                output.write(buffer, 0, n)
            }
            output.toByteArray()
        }
        val manifest = json.decodeFromString<DeltaManifest>(bytes.toString(Charsets.UTF_8))
        checkDelta(manifest.formatVersion == 1, "Unsupported delta format")
        validateHash(manifest.baseSha256)
        validateHash(manifest.resultEntriesSha256)
        checkDelta(manifest.operations.size <= limits.maxEntries, "Too many operations")
        val targets = mutableSetOf<String>()
        val payloads = mutableSetOf("manifest.json")
        manifest.operations.forEach { operation ->
            validateName(operation.entry)
            checkDelta(targets.add(operation.entry), "Repeated target: ${operation.entry}")
            when (operation.type) {
                "delete" -> checkDelta(operation.payload == null && operation.sha256 == null, "Delete has payload")
                "add", "replace" -> {
                    val payload = operation.payload ?: throw DeltaException("Missing payload")
                    validateName(payload)
                    checkDelta(payload.startsWith("payload/") && !payload.endsWith('/'), "Invalid payload path")
                    checkDelta(payloads.add(payload), "Repeated payload")
                    validateHash(operation.sha256 ?: throw DeltaException("Missing payload hash"))
                }
                else -> throw DeltaException("Unknown operation: ${operation.type}")
            }
        }
        val entries = JarDigests.scan(zip, limits)
        checkDelta(entries.keys == payloads, "Missing or undeclared delta entries")
        manifest.operations.filter { it.type != "delete" }.forEach {
            checkDelta(entries.getValue(it.payload!!).sha256 == it.sha256, "Payload digest mismatch")
            checkDelta(!it.entry.endsWith('/') || entries.getValue(it.payload).size == 0L, "Nonempty directory payload")
        }
        return manifest
    }
}
