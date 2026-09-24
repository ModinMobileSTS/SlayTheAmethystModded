package io.stamethyst.backend.mods

import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

/** Maintains the identity and small on-demand source cache for one parent JAR snapshot. */
object AgentModSourceSnapshot {
    fun prepare(root: File, sourceRoot: File, sourceJar: File): String {
        require(sourceJar.isFile) { "The selected mod JAR is missing." }
        val hash = AgentModJarReader.sha256(sourceJar)
        val metadataFile = root.resolve("source-metadata.json")
        val previousHash = runCatching {
            if (!metadataFile.isFile) "" else JSONObject(metadataFile.readText(StandardCharsets.UTF_8))
                .optString("source_sha256")
        }.getOrDefault("")
        if (previousHash != hash && sourceRoot.exists()) {
            check(sourceRoot.deleteRecursively()) { "Unable to clear stale parent source cache." }
        }
        check(sourceRoot.isDirectory || sourceRoot.mkdirs()) { "Unable to create parent source cache." }
        val metadata = if (previousHash == hash && metadataFile.isFile) {
            runCatching { JSONObject(metadataFile.readText(StandardCharsets.UTF_8)) }.getOrDefault(JSONObject())
        } else {
            JSONObject()
        }
        metadata.put("source_path", sourceJar.canonicalPath)
            .put("source_sha256", hash)
            .put("source_root", "source")
            .put("source_mode", "on_demand")
            .put("source_extracted", false)
        metadataFile.writeText(metadata.toString(2), StandardCharsets.UTF_8)
        return hash
    }

    fun sourceFile(sourceRoot: File, binaryName: String): File {
        val entry = AgentModJarReader.classEntryName(binaryName)
            ?: throw IllegalArgumentException("Invalid class name: $binaryName")
        return sourceFileForEntry(sourceRoot, entry)
    }

    fun sourceFileForEntry(sourceRoot: File, entry: String): File {
        require(entry.endsWith(".class")) { "Not a class entry: $entry" }
        val file = File(sourceRoot, entry.removeSuffix(".class") + ".java").canonicalFile
        require(file.toPath().startsWith(sourceRoot.canonicalFile.toPath())) {
            "Class source path escapes source/."
        }
        return file
    }
}
