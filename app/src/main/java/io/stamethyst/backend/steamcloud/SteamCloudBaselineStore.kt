package io.stamethyst.backend.steamcloud

import android.content.Context
import java.io.File
import java.io.IOException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object SteamCloudBaselineStore {
    private const val BASELINE_FILE_NAME = "sync-baseline.json"
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun baselineFile(context: Context): File =
        File(SteamCloudManifestStore.outputDir(context), BASELINE_FILE_NAME)

    fun readSnapshot(context: Context): SteamCloudSyncBaseline? {
        val file = baselineFile(context)
        if (!file.isFile) {
            return null
        }
        return json.decodeFromString(file.readText(Charsets.UTF_8))
    }

    @Throws(IOException::class)
    fun writeSnapshot(context: Context, snapshot: SteamCloudSyncBaseline) {
        val file = baselineFile(context)
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Failed to create Steam Cloud output directory: ${parent.absolutePath}")
        }
        file.writeText(json.encodeToString(snapshot), Charsets.UTF_8)
    }

    fun clear(context: Context) {
        baselineFile(context).delete()
    }
}
