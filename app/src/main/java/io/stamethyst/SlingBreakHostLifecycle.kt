package io.stamethyst

/** Only game assets are remapped. The teardown page must not carry a launcher capability. */
internal fun resolveSlingBreakGeckoUrl(
    url: String,
    launcherToken: String?,
    mapAssetUrl: (String) -> String,
): String {
    if (url == "about:blank") return url
    val mapped = mapAssetUrl(url)
    return if (launcherToken == null) mapped else {
        mapped + (if (mapped.contains('?')) "&" else "?") + "geckoLauncher=" + launcherToken
    }
}

/** A failing engine operation must not prevent destruction or native overlay dismissal. */
internal fun cleanupSlingBreakHost(
    stopLoading: () -> Unit,
    pause: () -> Unit,
    clearPage: () -> Unit,
    closeAudio: () -> Unit,
    destroy: () -> Unit,
    onFailure: (String, Throwable) -> Unit,
) {
    listOf(
        "stop_loading" to stopLoading,
        "pause" to pause,
        "clear_page" to clearPage,
        "close_audio" to closeAudio,
        "destroy" to destroy,
    ).forEach { (step, action) ->
        runCatching(action).onFailure { error -> onFailure(step, error) }
    }
}
