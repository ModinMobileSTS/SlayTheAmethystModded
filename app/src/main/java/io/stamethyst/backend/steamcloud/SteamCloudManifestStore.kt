package io.stamethyst.backend.steamcloud

import android.content.Context
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.io.IOException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object SteamCloudManifestStore {
    private const val OUTPUT_DIR_NAME = "steam-cloud"
    private const val MANIFEST_FILE_NAME = "manifest.json"
    private const val PULL_SUMMARY_FILE_NAME = "pull-summary.txt"
    private const val PULL_DOWNLOAD_DETAILS_FILE_NAME = "pull-download-details.tsv"
    private const val PUSH_SUMMARY_FILE_NAME = "push-summary.txt"
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun outputDir(context: Context): File = File(RuntimePaths.storageRoot(context), OUTPUT_DIR_NAME)

    fun manifestFile(context: Context): File = File(outputDir(context), MANIFEST_FILE_NAME)

    fun pullSummaryFile(context: Context): File = File(outputDir(context), PULL_SUMMARY_FILE_NAME)

    fun pullDownloadDetailsFile(context: Context): File = File(outputDir(context), PULL_DOWNLOAD_DETAILS_FILE_NAME)

    fun pushSummaryFile(context: Context): File = File(outputDir(context), PUSH_SUMMARY_FILE_NAME)

    fun readSnapshot(context: Context): SteamCloudManifestSnapshot? {
        val file = manifestFile(context)
        if (!file.isFile) {
            return null
        }
        return readSnapshotFile(file)
    }

    @Throws(IOException::class)
    fun writeSnapshot(context: Context, snapshot: SteamCloudManifestSnapshot) {
        val file = manifestFile(context)
        SteamCloudAtomicFileStore.writeText(file, json.encodeToString(snapshot), Charsets.UTF_8)
    }

    fun clear(context: Context) {
        val manifest = manifestFile(context)
        manifest.delete()
        SteamCloudAtomicFileStore.backupFile(manifest).delete()
        pullSummaryFile(context).delete()
        pullDownloadDetailsFile(context).delete()
        pushSummaryFile(context).delete()
    }

    private fun readSnapshotFile(file: File): SteamCloudManifestSnapshot? {
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

    private fun decodeSnapshot(file: File): SteamCloudManifestSnapshot =
        json.decodeFromString(file.readText(Charsets.UTF_8))
}
