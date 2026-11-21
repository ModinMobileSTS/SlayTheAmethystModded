package io.stamethyst.backend.steamcloud

import android.content.Context
import io.stamethyst.backend.steam.SteamCmAccountEpoch
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import io.stamethyst.config.LauncherConfig
import io.stamethyst.config.SteamCloudSaveMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Non-secret account fence, readable even while AndroidKeyStore is unavailable. Logout and
 * successful login (including account replacement without logout) invalidate old handoffs. */
internal object SteamCloudGameReturn {
    data class Pending(val launch: Long, val scope: String)
    private fun login(context: Context) = File(context.filesDir, "steam-cloud-login-generation")
    fun newLogin(context: Context) = SteamCloudAtomicFileStore.writeTextWithoutBackup(login(context), UUID.randomUUID().toString())
    fun scope(context: Context) = SteamCmAccountEpoch.read(context) + ":" + login(context).let { if (it.exists()) it.readText() else "" }
    fun launchScope(context: Context): String? = LauncherConfig.readSteamCloudControls(context).let {
        if (!it.disabled && !it.independentSwitchPending && it.mode == SteamCloudSaveMode.STEAM_CLOUD.persistedValue) scope(context) else null
    }
    private fun dir(context: Context) = File(context.filesDir, "steam-cloud-returns")
    private fun file(context: Context, launch: Long) = File(dir(context), launch.toString())

    /** One latest snapshot requirement, consumed by its generation only. Only launcher writes. */
    fun record(context: Context, pending: Pending) {
        val target = file(context, pending.launch)
        SteamCloudAtomicFileStore.writeTextWithoutBackup(target, pending.scope)
        dir(context).listFiles()?.filter { it != target && it.name.toLongOrNull() != null }?.forEach { it.delete() }
    }
    fun pending(context: Context): Pending? {
        val file = dir(context).listFiles()?.maxByOrNull { it.name.toLongOrNull() ?: -1L } ?: return null
        val launch = file.name.toLongOrNull() ?: return null
        return try { Pending(launch, file.readText()) } catch (_: FileNotFoundException) { null }
    }
    fun consume(context: Context, launch: Long) { file(context, launch).delete() }

    /** The unencrypted marker survives both Activity loss and exhaustion of the retry budget.
     * A successful startService is not an ACK: only the service consumes a delivered request. */
    suspend fun dispatch(context: Context, pending: Pending,
        read: () -> SteamCloudAuthStore.AuthMaterialRead = { SteamCloudAuthStore.readCmAuthMaterialWithStatus(context) },
        backoff: suspend (Long) -> Unit = { delay(it) },
        send: (String) -> Unit): Boolean {
        for (wait in listOf(0L, 150L, 450L, 1200L)) {
            if (wait > 0L) backoff(wait)
            val auth = withContext(Dispatchers.IO) {
                if (pending(context) != pending) return@withContext null
                val controls = LauncherConfig.readSteamCloudControls(context)
                if (controls.disabled || controls.independentSwitchPending || controls.mode != SteamCloudSaveMode.STEAM_CLOUD.persistedValue ||
                    scope(context) != pending.scope) {
                    consume(context, pending.launch)
                    return@withContext null
                }
                read().also {
                    if ((!it.readFailed && it.material == null) || pending.scope != scope(context)) {
                        consume(context, pending.launch)
                        return@withContext null
                    }
                }
            } ?: return false
            if (!auth.readFailed) {
                send(requireNotNull(auth.material).steamId64)
                return true
            }
        }
        return false
    }
}
