package io.stamethyst.backend.llm

import io.stamethyst.backend.mods.ModManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class JarPatch(
    val version: Int = 1,
    val expectedSha256: String,
    val operations: List<JarPatchOperation>,
)

@Serializable
data class JarPatchOperation(
    val action: String,
    val entry: String,
    val content: String? = null,
    val contentBase64: String? = null,
)

data class AppliedJarPatch(
    val backupFile: File,
    val changedEntries: List<String>,
)

object JarPatchCodec {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }

    fun extract(text: String): JarPatch? {
        val fenced = Regex("```(?:json)?\\s*(\\{.*\\})\\s*```", RegexOption.DOT_MATCHES_ALL)
            .findAll(text)
            .map { it.groupValues[1] }
            .toList()
            .asReversed()
        return (fenced + text).firstNotNullOfOrNull { candidate ->
            runCatching { json.decodeFromString<JarPatch>(candidate) }
                .getOrNull()
                ?.takeIf { it.version == 1 }
        }
    }
}

object JarPatchApplier {
    private const val MAX_OPERATIONS = 64
    private const val MAX_ENTRY_BYTES = 64 * 1024 * 1024
    private const val MAX_PATCH_BYTES = 128 * 1024 * 1024

    @Throws(IOException::class)
    fun apply(context: android.content.Context, sourceJar: File, patch: JarPatch): AppliedJarPatch {
        val canonicalSource = sourceJar.canonicalFile
        requireRegisteredOptionalMod(context, canonicalSource)
        return applyToJar(canonicalSource, patch)
    }

    @Throws(IOException::class)
    fun restore(context: android.content.Context, sourceJar: File, backupJar: File): AppliedJarPatch {
        val canonicalSource = sourceJar.canonicalFile
        val canonicalBackup = backupJar.canonicalFile
        requireRegisteredOptionalMod(context, canonicalSource)
        if (canonicalBackup.parentFile != canonicalSource.parentFile || !canonicalBackup.name.contains(".ai-backup-")) {
            throw IOException("The selected backup does not belong to this mod JAR.")
        }
        if (!canonicalBackup.isFile) throw IOException("The selected backup file is missing.")
        verifyZip(canonicalBackup)
        val currentBackup = File(
            canonicalSource.parentFile,
            "${canonicalSource.name}.ai-before-restore-${System.currentTimeMillis()}.jar",
        )
        val tempJar = File(canonicalSource.parentFile, ".${canonicalSource.name}.ai-restore.tmp")
        try {
            copyFile(canonicalSource, currentBackup)
            copyFile(canonicalBackup, tempJar)
            verifyZip(tempJar)
            if (!canonicalSource.delete() || !tempJar.renameTo(canonicalSource)) {
                throw IOException("Unable to restore the selected JAR backup.")
            }
            return AppliedJarPatch(currentBackup, listOf("<restore>"))
        } catch (error: Throwable) {
            tempJar.delete()
            if (error is IOException) throw error
            throw IOException("Unable to restore the JAR backup.", error)
        }
    }

    internal fun applyToJar(sourceJar: File, patch: JarPatch): AppliedJarPatch {
        val canonicalSource = sourceJar.canonicalFile
        validatePatch(patch)
        if (sha256(canonicalSource) != patch.expectedSha256.lowercase()) {
            throw IOException("The source JAR changed since the patch was generated.")
        }

        val operations = patch.operations.associateBy { normalizeEntryName(it.entry) }
        val existingNames = linkedSetOf<String>()
        val changedNames = linkedSetOf<String>()
        val tempJar = File(canonicalSource.parentFile, ".${canonicalSource.name}.ai-patch.tmp")
        val backupJar = File(
            canonicalSource.parentFile,
            "${canonicalSource.name}.ai-backup-${System.currentTimeMillis()}.jar",
        )
        if (tempJar.exists() && !tempJar.delete()) {
            throw IOException("Unable to clear the previous temporary patch file.")
        }

        try {
            ZipFile(canonicalSource).use { zip ->
                ZipOutputStream(FileOutputStream(tempJar, false)).use { output ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val inputEntry = entries.nextElement()
                        val name = normalizeEntryName(inputEntry.name, allowDirectory = true, enforceEditable = false)
                        if (!existingNames.add(name)) {
                            continue
                        }
                        val operation = operations[name]
                        if (operation?.action == "delete") {
                            changedNames += name
                            continue
                        }
                        val outputEntry = ZipEntry(name).apply {
                            time = inputEntry.time
                            comment = inputEntry.comment
                        }
                        output.putNextEntry(outputEntry)
                        if (operation != null && operation.action != "delete") {
                            output.write(requireOperationContent(operation))
                            changedNames += name
                        } else {
                            zip.getInputStream(inputEntry).use { input ->
                                input.copyTo(output)
                            }
                        }
                        output.closeEntry()
                    }

                    operations.forEach { (name, operation) ->
                        if (operation.action == "add_text" || operation.action == "add_bytes") {
                            if (name in existingNames) {
                                throw IOException("Cannot add an existing JAR entry: $name")
                            }
                            output.putNextEntry(ZipEntry(name))
                            output.write(requireOperationContent(operation))
                            output.closeEntry()
                            changedNames += name
                        }
                    }
                }
            }
            if (changedNames.isEmpty()) {
                throw IOException("The patch does not change the JAR.")
            }
            verifyZip(tempJar)
            copyFile(canonicalSource, backupJar)
            replaceWithBackup(canonicalSource, tempJar, backupJar)
            return AppliedJarPatch(backupFile = backupJar, changedEntries = changedNames.toList())
        } catch (error: Throwable) {
            tempJar.delete()
            if (backupJar.exists() && !canonicalSource.exists()) {
                backupJar.renameTo(canonicalSource)
            }
            if (error is IOException) throw error
            throw IOException("Unable to apply the JAR patch.", error)
        }
    }

    private fun validatePatch(patch: JarPatch) {
        if (patch.version != 1) throw IOException("Unsupported JAR patch version: ${patch.version}")
        if (!patch.expectedSha256.matches(Regex("[0-9a-fA-F]{64}"))) {
            throw IOException("A valid source JAR SHA-256 is required.")
        }
        if (patch.operations.isEmpty() || patch.operations.size > MAX_OPERATIONS) {
            throw IOException("The patch must contain between 1 and $MAX_OPERATIONS operations.")
        }
        if (patch.operations.sumOf { operationContentSize(it).toLong() } > MAX_PATCH_BYTES) {
            throw IOException("The patch is too large.")
        }
        val normalizedNames = patch.operations.map { normalizeEntryName(it.entry) }
        if (normalizedNames.size != normalizedNames.toSet().size) {
            throw IOException("Each JAR entry may appear only once in a patch.")
        }
        patch.operations.forEach { operation ->
            normalizeEntryName(operation.entry)
            if (operation.action !in setOf("replace_text", "replace_bytes", "add_text", "add_bytes", "delete")) {
                throw IOException("Unsupported JAR patch operation: ${operation.action}")
            }
            if (operation.action != "delete") {
                val size = requireOperationContent(operation).size
                if (size > MAX_ENTRY_BYTES) throw IOException("JAR entry is too large: ${operation.entry}")
            }
        }
    }

    private fun requireRegisteredOptionalMod(context: android.content.Context, sourceJar: File) {
        val installed = ModManager.listInstalledMods(context).any { installedMod ->
            installedMod.jarFile.canonicalFile == sourceJar &&
                !installedMod.required &&
                installedMod.installed
        }
        if (!installed) throw IOException("Only an installed optional mod JAR can be edited.")
    }

    private fun normalizeEntryName(
        rawName: String,
        allowDirectory: Boolean = false,
        enforceEditable: Boolean = true,
    ): String {
        val name = rawName.trim().replace('\\', '/')
        if (
            name.isEmpty() ||
            name.startsWith('/') ||
            name.matches(Regex("^[A-Za-z]:.*")) ||
            name.split('/').any { it == "." || it == ".." || it.isEmpty() && name != "" }
        ) {
            throw IOException("Invalid JAR entry path: $rawName")
        }
        if ((!allowDirectory && name.endsWith("/")) && enforceEditable) {
            throw IOException("This JAR entry type cannot be edited: $rawName")
        }
        return name
    }

    private fun operationContentSize(operation: JarPatchOperation): Int = when {
        operation.contentBase64 != null -> runCatching {
            Base64.getDecoder().decode(operation.contentBase64).size
        }.getOrDefault(Int.MAX_VALUE)
        operation.content != null -> operation.content.toByteArray(StandardCharsets.UTF_8).size
        else -> 0
    }

    private fun requireOperationContent(operation: JarPatchOperation): ByteArray {
        operation.contentBase64?.let { encoded ->
            return runCatching { Base64.getDecoder().decode(encoded) }
                .getOrElse { throw IOException("Invalid base64 content for ${operation.entry}", it) }
        }
        return operation.content?.toByteArray(StandardCharsets.UTF_8)
            ?: throw IOException("Content or contentBase64 is required for ${operation.entry}")
    }

    private fun verifyZip(file: File) {
        ZipFile(file).use { zip ->
            if (!zip.entries().hasMoreElements()) throw IOException("The patched JAR is empty.")
        }
    }

    private fun copyFile(source: File, target: File) {
        Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun replaceWithBackup(source: File, temp: File, backup: File) {
        if (!source.delete()) {
            throw IOException("Unable to move the original JAR aside for replacement.")
        }
        if (!temp.renameTo(source)) {
            backup.renameTo(source)
            throw IOException("Unable to replace the original JAR.")
        }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
