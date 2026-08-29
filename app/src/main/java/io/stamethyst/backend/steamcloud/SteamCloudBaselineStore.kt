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

    fun readSnapshot(
        context: Context,
        expectedSteamId64: String? = null,
    ): SteamCloudSyncBaseline? {
        return SteamCloudOperationMutex.runExclusive(context) {
            val file = baselineFile(context)
            if (!file.isFile) {
                return@runExclusive null
            }
            readSnapshotFile(file)?.takeIf { snapshot ->
                expectedSteamId64 == null ||
                    snapshot.steamId64.trim() == expectedSteamId64.trim()
            }
        }
    }

    @Throws(IOException::class)
    fun writeSnapshot(context: Context, snapshot: SteamCloudSyncBaseline) {
        SteamCloudOperationMutex.runExclusive(context) {
            val file = baselineFile(context)
            SteamCloudAtomicFileStore.writeText(file, json.encodeToString(snapshot), Charsets.UTF_8)
        }
    }

    fun clear(context: Context) {
        SteamCloudOperationMutex.runExclusive(context) {
            val baseline = baselineFile(context)
            baseline.delete()
            SteamCloudAtomicFileStore.backupFile(baseline).delete()
        }
    }

    private fun readSnapshotFile(file: File): SteamCloudSyncBaseline? {
        return try {
            decodeSnapshot(file)
        } catch (_: Throwable) {
            val backupFile = SteamCloudAtomicFileStore.backupFile(file)
            if (!backupFile.isFile) {
                null
            } else {
                runCatching {
                    val snapshot = decodeSnapshot(backupFile)
                    SteamCloudAtomicFileStore.writeText(file, json.encodeToString(snapshot), Charsets.UTF_8)
                    snapshot
                }.getOrNull()
            }
        }
    }

    private fun decodeSnapshot(file: File): SteamCloudSyncBaseline =
        json.decodeFromString(file.readText(Charsets.UTF_8))
}
