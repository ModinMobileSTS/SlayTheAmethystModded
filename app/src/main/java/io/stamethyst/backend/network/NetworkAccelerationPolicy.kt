package io.stamethyst.backend.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.stamethyst.backend.easytier.EasyTierConnectionStatus
import io.stamethyst.backend.easytier.EasyTierSessionController
import io.stamethyst.config.LauncherConfig
import java.util.Locale

object NetworkAccelerationPolicy {
    /**
     * Strategy used to pick the upstream hop for every accelerated link.
     *
     * Read per call so a developer-settings change takes effect without restarting the
     * launcher: the OkHttp runtimes hold a provider, not a snapshot.
     */
    @JvmStatic
    fun currentAccelerationStrategy(context: Context): AccelerationStrategy =
        LauncherConfig.readAccelerationStrategy(context)

    @JvmStatic
    fun shouldUseAcceleratedLinks(
        context: Context,
        configuredEnabled: Boolean,
    ): Boolean = shouldUseAcceleratedLinks(
        configuredEnabled = configuredEnabled,
        vpnActiveProvider = { isVpnActive(context) },
        chinaRegionProvider = { isChinaRegion(context) },
    )

    @JvmStatic
    fun shouldBypassAcceleratedLinks(context: Context): Boolean =
        shouldBypassAcceleratedLinks(
            vpnActiveProvider = { isVpnActive(context) },
            chinaRegionProvider = { isChinaRegion(context) },
        )

    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun isChinaRegion(context: Context): Boolean =
        isChinaRegion(Locale.getDefault().country)

    @JvmStatic
    fun isVpnActive(context: Context): Boolean =
        runCatching {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            isExternalVpnActive(
                vpnTransportActive = hasVpnTransport(connectivityManager) { network ->
                    connectivityManager?.getNetworkCapabilities(network)
                },
                easyTierVpnActive = {
                    EasyTierSessionController.currentSnapshot(context).status ==
                        EasyTierConnectionStatus.CONNECTED
                },
            )
        }.getOrDefault(false)

    internal fun shouldUseAcceleratedLinks(
        configuredEnabled: Boolean,
        @Suppress("UNUSED_PARAMETER") vpnActiveProvider: () -> Boolean,
        @Suppress("UNUSED_PARAMETER") chinaRegionProvider: () -> Boolean = { true },
    ): Boolean = configuredEnabled

    internal fun shouldBypassAcceleratedLinks(
        @Suppress("UNUSED_PARAMETER") vpnActiveProvider: () -> Boolean,
        @Suppress("UNUSED_PARAMETER") chinaRegionProvider: () -> Boolean,
    ): Boolean = false

    internal fun isChinaRegion(countryCode: String): Boolean =
        countryCode.trim().equals(CHINA_COUNTRY_CODE, ignoreCase = true)

    @Suppress("DEPRECATION")
    internal fun hasVpnTransport(
        connectivityManager: ConnectivityManager?,
        capabilitiesProvider: (Network) -> NetworkCapabilities?,
    ): Boolean {
        if (connectivityManager == null) {
            return false
        }
        val activeNetwork = connectivityManager.activeNetwork
        if (activeNetwork != null &&
            capabilitiesProvider(activeNetwork)?.let { hasVpnTransport(it::hasTransport) } == true
        ) {
            return true
        }
        return connectivityManager.allNetworks.any { network ->
            capabilitiesProvider(network)?.let { hasVpnTransport(it::hasTransport) } == true
        }
    }

    internal fun hasVpnTransport(hasTransport: (Int) -> Boolean): Boolean =
        hasTransport(NetworkCapabilities.TRANSPORT_VPN)

    internal fun isExternalVpnActive(
        vpnTransportActive: Boolean,
        easyTierVpnActive: () -> Boolean,
    ): Boolean = vpnTransportActive && !easyTierVpnActive()

    private const val CHINA_COUNTRY_CODE = "CN"
}
