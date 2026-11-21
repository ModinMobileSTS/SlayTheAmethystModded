package io.stamethyst.backend.steam

import android.content.Context
import android.os.FileObserver
import io.stamethyst.backend.steamcloud.SteamCloudAtomicFileStore
import io.stamethyst.backend.steamcloud.SteamCloudAuthStore
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import top.apricityx.workshop.steam.protocol.SteamAccountSession

/** Persisted so failures from :steamcloud/:game also reach the launcher, including after resume. */
internal object SteamCmLoginFailureNotice {
    private const val FILE_NAME = "steam-cm-login-failure.json"

    @Serializable
    data class Notice(
        val steamId: String,
        val credentialRevision: Long,
        val epoch: String,
        val dismissed: Boolean = false,
        // Old writers persisted network failures without distinguishing credential rejection.
        val classificationVersion: Int = 0,
    )

    fun noticeForRejectedLogon(account: SteamAccountSession, revision: Long, epoch: String,
        auth: SteamCloudAuthStore.SavedAuthMaterial?, currentEpoch: String): Notice? {
        if (epoch != currentEpoch || auth == null || auth.credentialRevision != revision ||
            auth.steamId64 != account.steamId.toString() || auth.refreshToken != account.refreshToken) return null
        return Notice(auth.steamId64, revision, epoch, classificationVersion = 1)
    }

    fun report(context: Context, account: SteamAccountSession, revision: Long, epoch: String) {
        // At most one dialog per credential revision, even if several features fail together.
        locked(context) {
            val notice = noticeForRejectedLogon(account, revision, epoch,
                SteamCloudAuthStore.readPersistedCmAuthMaterial(context, epoch), SteamCmAccountEpoch.read(context))
                ?: return@locked
            if (read(context)?.copy(dismissed = false) != notice) write(context, notice)
        }
    }

    fun observe(context: Context) = callbackFlow {
        @Suppress("DEPRECATION")
        val observer = object : FileObserver(context.filesDir.path, MOVED_TO or CLOSE_WRITE or DELETE) {
            override fun onEvent(event: Int, path: String?) {
                if (path == FILE_NAME || path == SteamCmAccountEpoch.FILE_NAME) trySend(Unit)
            }
        }
        observer.startWatching()
        trySend(Unit)
        awaitClose { observer.stopWatching() }
    }.map {
        val notice = read(context)?.takeIf { !it.dismissed && it.classificationVersion == 1 } ?: return@map null
        runCatching {
            val auth = SteamCloudAuthStore.readCmAuthMaterial(context)
            notice.takeIf { auth?.steamId64 == it.steamId && auth.credentialRevision == it.credentialRevision &&
                it.epoch == SteamCmAccountEpoch.read(context) }
        }.getOrNull()
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    fun dismiss(context: Context, notice: Notice) {
        locked(context) {
            if (read(context) == notice) write(context, notice.copy(dismissed = true))
        }
    }

    fun clear(context: Context) = locked(context) {
        val file = File(context.filesDir, FILE_NAME)
        if (file.exists() && !file.delete()) throw java.io.IOException("Cannot clear Steam CM failure notice")
    }

    @Synchronized
    private fun <T> locked(context: Context, block: () -> T): T =
        RandomAccessFile(File(context.filesDir, "$FILE_NAME.lock"), "rw").use { file ->
            file.channel.lock().use { block() }
        }

    private fun read(context: Context): Notice? = runCatching {
        Json.decodeFromString<Notice>(File(context.filesDir, FILE_NAME).readText())
    }.getOrNull()

    private fun write(context: Context, notice: Notice) = SteamCloudAtomicFileStore.writeTextWithoutBackup(
        File(context.filesDir, FILE_NAME), Json.encodeToString(notice),
    )
}
