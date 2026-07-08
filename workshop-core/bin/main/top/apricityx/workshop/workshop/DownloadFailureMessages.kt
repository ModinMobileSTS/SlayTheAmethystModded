package top.apricityx.workshop.workshop

private const val STEAM_CDN_UNAUTHORIZED_MESSAGE = "Steam CDN request failed: 401"

internal fun Throwable.userVisibleDownloadFailureMessage(): String {
    val messages = generateSequence(this) { it.cause }
        .mapNotNull { throwable ->
            throwable.message
                ?.trim()
                ?.takeIf(String::isNotBlank)
        }
        .toList()

    return messages.lastOrNull(::isSteamCdnUnauthorizedFailure)
        ?: messages.firstOrNull()
        ?: (this::class.simpleName ?: "Download failed")
}

internal fun isSteamCdnUnauthorizedFailure(message: String?): Boolean =
    message?.contains(STEAM_CDN_UNAUTHORIZED_MESSAGE) == true
