package io.stamethyst.backend.steamcloud

import android.content.Context
import java.io.File
import java.util.UUID

/** Each launch owns one marker. Publishing it serializes with the engine's final local commit;
 * releasing it cannot clear another launch, even across a launcher/service process restart. */
internal object SteamCloudLaunchFence {
    private val process = UUID.randomUUID().toString()
    private fun dir(context: Context) = File(context.filesDir, "steam-cloud-launch-fences")
    fun token() = "$process.${UUID.randomUUID()}"
    fun acquire(context: Context, token: String) = SteamCloudControlStore.locked(context) {
        val dir = dir(context)
        check(dir.mkdirs() || dir.isDirectory)
        check(File(dir, token).createNewFile())
    }
    fun release(context: Context, token: String?) {
        if (token != null && token.matches(Regex("[a-f0-9-]{36}\\.[a-f0-9-]{36}"))) File(dir(context), token).delete()
    }
    fun active(context: Context): Boolean = dir(context).let { it.exists() && it.list()?.isEmpty() != true }

    /** Only the launcher creates markers. A dead launcher's unhanded preparations cannot run. */
    fun recoverLauncher(context: Context, handedOff: String?) {
        dir(context).list()?.filter { !it.startsWith("$process.") && it != handedOff }?.forEach { release(context, it) }
    }
}
