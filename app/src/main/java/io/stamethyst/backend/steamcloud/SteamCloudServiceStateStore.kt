package io.stamethyst.backend.steamcloud

import android.content.Context
import android.os.Bundle
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Latest event is the single reattachment snapshot; callbacks and broadcasts carry the same ID. */
internal class SteamCloudServiceStateStore(private val file: File) {
    @Serializable data class Record(
        val strings: Map<String, String> = emptyMap(),
        val longs: Map<String, Long> = emptyMap(),
        val ints: Map<String, Int> = emptyMap(),
        val booleans: Map<String, Boolean> = emptyMap(),
        val plan: SteamCloudUploadPlan? = null,
        val active: Boolean,
    ) {
        fun toBundle() = Bundle().apply {
            strings.forEach { (k, v) -> putString(k, v) }
            longs.forEach { (k, v) -> putLong(k, v) }
            ints.forEach { (k, v) -> putInt(k, v) }
            booleans.forEach { (k, v) -> putBoolean(k, v) }
            plan?.let { putSerializable(SteamCloudSyncProcessService.EXTRA_PLAN, it) }
        }
    }
    private val json = Json { ignoreUnknownKeys = true }
    fun read(): Record? = if (file.isFile) json.decodeFromString(file.readText()) else null
    fun write(record: Record) = SteamCloudAtomicFileStore.writeTextWithoutBackup(file, json.encodeToString(record))
    fun write(data: Bundle, active: Boolean) {
        @Suppress("DEPRECATION")
        val values = data.keySet().associateWith { data.get(it) }
        write(Record(
            strings = values.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap(),
            longs = values.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap(),
            ints = values.mapNotNull { (k, v) -> (v as? Int)?.let { k to it } }.toMap(),
            booleans = values.mapNotNull { (k, v) -> (v as? Boolean)?.let { k to it } }.toMap(),
            plan = values[SteamCloudSyncProcessService.EXTRA_PLAN] as? SteamCloudUploadPlan,
            active = active,
        ))
    }
    companion object {
        fun forContext(context: Context) = SteamCloudServiceStateStore(File(context.filesDir, "steam-cloud-service-state-v2.json"))
    }
}
