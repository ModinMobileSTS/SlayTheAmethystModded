package io.stamethyst.backend.steamcloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.stamethyst.backend.network.NetworkAccelerationPolicy
import io.stamethyst.config.LauncherConfig
import java.io.File

internal object SteamCloudNetworkEnvironment {
    private const val LAST_CM_ENDPOINT_FILE_NAME = "last-websocket-cm-endpoint.txt"
    private const val CM_SERVER_LIST_FILE_NAME = "steam-cm-server-list.bin"

    @Suppress("UNUSED_PARAMETER")
    fun shouldPromptForDirectMode(context: Context): Boolean {
        return false
    }

    fun switchToDirectMode(context: Context) {
        SteamCloudOperationMutex.runExclusive(context) {
            LauncherConfig.setSteamCloudWattAccelerationEnabled(context, false)
            clearNetworkCache(context)
        }
    }

    fun clearNetworkCache(context: Context) {
        SteamCloudOperationMutex.runExclusive(context) {
            lastCmEndpointFile(context).delete()
            cmServerListFile(context).delete()
        }
    }

    fun lastCmEndpointFile(context: Context): File =
        File(SteamCloudManifestStore.outputDir(context), LAST_CM_ENDPOINT_FILE_NAME)

    fun cmServerListFile(context: Context): File =
        File(SteamCloudManifestStore.outputDir(context), CM_SERVER_LIST_FILE_NAME)

    fun readCachedCmEndpoint(context: Context): String =
        runCatching { lastCmEndpointFile(context).takeIf { it.isFile }?.readText(Charsets.UTF_8).orEmpty().trim() }
            .getOrDefault("")

    @JvmStatic
    fun isProxyOrAcceleratorActive(context: Context): Boolean =
        isWattAccelerationEnabled(context) || NetworkAccelerationPolicy.isVpnActive(context)

    @Suppress("DEPRECATION")
    internal fun hasVpnTransport(
        connectivityManager: ConnectivityManager?,
        capabilitiesProvider: (android.net.Network) -> NetworkCapabilities?,
    ): Boolean = NetworkAccelerationPolicy.hasVpnTransport(connectivityManager, capabilitiesProvider)

    internal fun hasVpnTransport(hasTransport: (Int) -> Boolean): Boolean =
        NetworkAccelerationPolicy.hasVpnTransport(hasTransport)

    private fun isWattAccelerationEnabled(context: Context): Boolean =
        runCatching {
            NetworkAccelerationPolicy.shouldUseAcceleratedLinks(
                context = context,
                configuredEnabled = LauncherConfig.isSteamCloudWattAccelerationEnabled(context),
            )
        }.getOrDefault(false)
}
