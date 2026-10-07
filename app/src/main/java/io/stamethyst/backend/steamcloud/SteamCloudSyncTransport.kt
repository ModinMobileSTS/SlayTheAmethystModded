package io.stamethyst.backend.steamcloud

import android.content.Context
import `in`.dragonbra.javasteam.enums.EResult
import java.io.File

/** The synchronization algorithm is independent of Steam RPCs and can use an in-memory remote. */
internal interface SteamCloudSyncTransport : AutoCloseable {
    fun manifest(): SteamCloudManifestSnapshot
    fun download(entry: SteamCloudManifestEntry, target: File)
    fun begin(uploads: List<String>, deletes: List<String>): Long
    fun upload(candidate: SteamCloudUploadCandidate, source: File, batch: Long)
    fun delete(path: String, batch: Long)
    fun complete(batch: Long)
    fun abort(batch: Long)
}

internal class SteamCloudSteamTransport(
    context: Context,
    private val auth: SteamCloudAuthStore.SavedAuthMaterial,
) : SteamCloudSyncTransport {
    private val client = SteamCloudClient(context)
    init {
        try {
            client.beginOperationDiagnostics("sync_v2", auth.accountName, auth.guardData.isNotBlank())
            client.start()
            client.logOnWithRefreshToken(auth.accountName, auth.refreshToken, auth.steamId64)
        } catch (error: Exception) { client.close(); throw error }
    }
    override fun manifest(): SteamCloudManifestSnapshot = SteamCloudPathMapper.buildManifestSnapshot(
        System.currentTimeMillis(), client.listFiles(STEAM_CLOUD_APP_ID), auth.steamId64,
    ).also(SteamCloudSyncPlanner::validateManifest)
    override fun download(entry: SteamCloudManifestEntry, target: File) {
        client.downloadFile(STEAM_CLOUD_APP_ID, entry.remotePath, target, entry.rawSize, entry.sha1)
    }
    override fun begin(uploads: List<String>, deletes: List<String>): Long =
        client.beginUploadBatch(STEAM_CLOUD_APP_ID, uploads, deletes).batchId
    override fun upload(candidate: SteamCloudUploadCandidate, source: File, batch: Long) {
        val result = client.uploadFile(STEAM_CLOUD_APP_ID, candidate.remotePath, source, batch)
        check(result.fileSize == candidate.fileSize && result.sha1Hex.equals(candidate.sha1, true)) {
            "Frozen upload changed: ${candidate.localRelativePath}"
        }
    }
    override fun delete(path: String, batch: Long) { client.deleteFile(STEAM_CLOUD_APP_ID, path, batch) }
    override fun complete(batch: Long) { client.completeUploadBatch(STEAM_CLOUD_APP_ID, batch, EResult.OK) }
    override fun abort(batch: Long) { client.completeUploadBatch(STEAM_CLOUD_APP_ID, batch, EResult.Fail) }
    override fun close() { client.close() }
}
