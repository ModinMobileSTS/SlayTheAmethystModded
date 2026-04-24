package io.stamethyst.backend.steamcloud

import android.content.Context
import android.util.Xml
import java.io.StringReader
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser

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

    private fun parseProfileXml(steamId64: String, xmlText: String): Profile? {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xmlText))

        var resolvedSteamId64 = ""
        var personaName = ""
        var avatarFull = ""
        var avatarMedium = ""
        var avatarIcon = ""
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "steamID64" -> resolvedSteamId64 = parser.nextText().trim()
                    "steamID" -> personaName = parser.nextText().trim()
                    "avatarFull" -> avatarFull = parser.nextText().trim()
                    "avatarMedium" -> avatarMedium = parser.nextText().trim()
                    "avatarIcon" -> avatarIcon = parser.nextText().trim()
                }
            }
            eventType = parser.next()
        }

        val avatarUrl = avatarFull.ifBlank { avatarMedium }.ifBlank { avatarIcon }
        if (resolvedSteamId64.isBlank() || resolvedSteamId64 != steamId64) {
            return null
        }
        if (personaName.isBlank() && avatarUrl.isBlank()) {
            return null
        }
        return Profile(
            steamId64 = resolvedSteamId64,
            personaName = personaName,
            avatarUrl = avatarUrl,
        )
    }

    private const val PROFILE_CONNECT_TIMEOUT_MS = 8_000L
    private const val PROFILE_READ_TIMEOUT_MS = 15_000L
    private const val PROFILE_CALL_TIMEOUT_MS = 20_000L
}
