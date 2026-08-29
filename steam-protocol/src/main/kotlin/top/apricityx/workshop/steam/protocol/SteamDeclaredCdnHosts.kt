package top.apricityx.workshop.steam.protocol

import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks CDN hostnames that Steam itself declared in a content-server directory
 * response (ContentServerDirectory.GetServersForSteamPipe or the public
 * GetServersForSteamPipe API). The app's HTTPS-only transport guard consults this
 * registry so SteamPipe depot manifests and chunks can follow cleartext requests and
 * redirects on Steam China CDN edges without hardcoding every CDN partner domain,
 * while still rejecting cleartext anywhere Steam did not declare a host.
 */
object SteamDeclaredCdnHosts {
    private val declaredHosts: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    fun register(host: String) {
        host.trim().lowercase(Locale.ROOT).takeIf(String::isNotBlank)?.let { declaredHosts += it }
    }

    fun isDeclared(host: String): Boolean =
        host.trim().lowercase(Locale.ROOT) in declaredHosts

    fun clear() {
        declaredHosts.clear()
    }
}
