package io.stamethyst.backend.easytier

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * A room invitation decoded from a shared clipboard string.
 *
 * [password] is empty when the room has no password, or when the sender chose not to include it.
 */
data class EasyTierSharedRoomInvite(
    val roomId: String,
    val description: String = "",
    val password: String = "",
)

/**
 * Encodes and decodes the plain-text room invitation that players paste to each other.
 *
 * The shared string mixes human-readable lines with a single machine-readable URI line. Parsing only
 * looks for the URI prefix, so the friendly wording can change without breaking older invitations.
 * Everything here is plain JVM (no `android.net.Uri`), which keeps it unit-testable and independent
 * of the room API backend.
 */
object EasyTierRoomShareCodec {
    const val SHARE_URI_PREFIX = "stamethyst://lan/join?"

    private const val PARAM_ROOM = "room"
    private const val PARAM_PASSWORD = "password"
    private const val PARAM_DESCRIPTION = "desc"

    fun buildShareUri(
        roomId: String,
        description: String = "",
        password: String = "",
    ): String {
        val normalizedRoomId = roomId.trim()
        require(normalizedRoomId.isNotEmpty()) { "roomId must not be blank" }
        val params = buildList {
            add(PARAM_ROOM to normalizedRoomId)
            if (password.isNotEmpty()) {
                add(PARAM_PASSWORD to password)
            }
            if (description.isNotBlank()) {
                add(PARAM_DESCRIPTION to description.trim())
            }
        }
        return SHARE_URI_PREFIX + params.joinToString(separator = "&") { (key, value) ->
            "$key=${encode(value)}"
        }
    }

    /** Returns the invitation embedded in [text], or null when the text carries no valid URI. */
    fun parseShareText(text: String?): EasyTierSharedRoomInvite? {
        val raw = text?.trim().orEmpty()
        val startIndex = raw.indexOf(SHARE_URI_PREFIX)
        if (startIndex < 0) {
            return null
        }
        val uriText = raw.substring(startIndex)
            .trim()
            .takeWhile { !it.isWhitespace() }
        val query = uriText.removePrefix(SHARE_URI_PREFIX)
        if (query.isBlank()) {
            return null
        }
        val params = query.split('&')
            .mapNotNull { part ->
                val separator = part.indexOf('=')
                if (separator <= 0) {
                    null
                } else {
                    decode(part.substring(0, separator)) to decode(part.substring(separator + 1))
                }
            }
            .toMap()
        val roomId = params[PARAM_ROOM]?.trim().orEmpty()
        if (roomId.isEmpty()) {
            return null
        }
        return EasyTierSharedRoomInvite(
            roomId = roomId,
            description = params[PARAM_DESCRIPTION].orEmpty(),
            password = params[PARAM_PASSWORD].orEmpty(),
        )
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrDefault(value)
}
