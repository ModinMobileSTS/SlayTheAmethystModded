package io.stamethyst.ui.aimod

internal enum class AiConversationPresentation { LOADING, EMPTY, CONTENT }

/** An empty in-memory list is not evidence of an empty conversation until storage has loaded. */
internal fun aiConversationPresentation(
    storageReady: Boolean,
    switchingConversation: Boolean,
    hasMessages: Boolean,
    transcriptReady: Boolean,
    initialScrollApplied: Boolean,
): AiConversationPresentation = when {
    !storageReady || switchingConversation -> AiConversationPresentation.LOADING
    !hasMessages -> AiConversationPresentation.EMPTY
    !transcriptReady || !initialScrollApplied -> AiConversationPresentation.LOADING
    else -> AiConversationPresentation.CONTENT
}
