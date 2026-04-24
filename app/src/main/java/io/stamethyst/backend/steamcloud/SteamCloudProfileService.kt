package io.stamethyst.backend.steamcloud

import android.content.Context
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import okhttp3.Request
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

internal object SteamCloudProfileService {
    data class Profile(
        val steamId64: String,
        val personaName: String,
        val avatarUrl: String,
    )

    fun fetchProfile(context: Context, steamId64: String): Profile? {
        val normalizedSteamId = steamId64.trim()
        if (normalizedSteamId.isEmpty()) {
            return null
        }

        val client = SteamCloudAcceleratedHttp.createClient(
            context = context,
            connectTimeoutMs = PROFILE_CONNECT_TIMEOUT_MS,
            readTimeoutMs = PROFILE_READ_TIMEOUT_MS,
            callTimeoutMs = PROFILE_CALL_TIMEOUT_MS,
        )
        val request = Request.Builder()
            .url("https://steamcommunity.com/profiles/$normalizedSteamId/?xml=1")
            .header("User-Agent", "SlayTheAmethyst/${context.packageName}")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return null
            }
            val bodyText = response.body.string().trim()
            if (bodyText.isEmpty()) {
                return null
            }
            return parseProfileXml(normalizedSteamId, bodyText)
        }
    }

    internal fun parseProfileXml(steamId64: String, xmlText: String): Profile? {
        return runCatching {
            val document = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isExpandEntityReferences = false
                trySetFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                trySetFeature("http://xml.org/sax/features/external-general-entities", false)
                trySetFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }.newDocumentBuilder().parse(InputSource(StringReader(xmlText)))

            val profileElement = document.documentElement?.takeIf { it.tagName == "profile" } ?: return null
            val resolvedSteamId64 = profileElement.directChildText("steamID64")
            val personaName = profileElement.directChildText("steamID")
            val avatarFull = profileElement.directChildText("avatarFull")
            val avatarMedium = profileElement.directChildText("avatarMedium")
            val avatarIcon = profileElement.directChildText("avatarIcon")
            val avatarUrl = avatarFull.ifBlank { avatarMedium }.ifBlank { avatarIcon }
            if (resolvedSteamId64.isBlank() || resolvedSteamId64 != steamId64) {
                return null
            }
            if (personaName.isBlank() && avatarUrl.isBlank()) {
                return null
            }
            Profile(
                steamId64 = resolvedSteamId64,
                personaName = personaName,
                avatarUrl = avatarUrl,
            )
        }.getOrNull()
    }

    private fun DocumentBuilderFactory.trySetFeature(name: String, value: Boolean) {
        runCatching {
            setFeature(name, value)
        }
    }

    private fun Element.directChildText(tagName: String): String {
        var child = firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE && child.nodeName == tagName) {
                return child.textContent?.trim().orEmpty()
            }
            child = child.nextSibling
        }
        return ""
    }

    private const val PROFILE_CONNECT_TIMEOUT_MS = 8_000L
    private const val PROFILE_READ_TIMEOUT_MS = 15_000L
    private const val PROFILE_CALL_TIMEOUT_MS = 20_000L
}
