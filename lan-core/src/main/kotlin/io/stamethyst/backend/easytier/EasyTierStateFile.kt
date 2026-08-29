package io.stamethyst.backend.easytier

import java.io.File
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Credential-free state file deliberately consumable by the game JVM. */
class EasyTierStateFile(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun read(): EasyTierConnectionSnapshot? = runCatching {
        json.decodeFromString<EasyTierConnectionSnapshot>(file.readText(Charsets.UTF_8))
    }.recoverCatching {
        json.decodeFromString<EasyTierConnectionSnapshot>(EasyTierAtomicFileStore.backupFile(file).readText(Charsets.UTF_8))
    }.getOrNull()

    fun write(snapshot: EasyTierConnectionSnapshot) = EasyTierAtomicFileStore.writeText(
        file, json.encodeToString(snapshot), Charsets.UTF_8,
    )

    fun path(): File = file
}
