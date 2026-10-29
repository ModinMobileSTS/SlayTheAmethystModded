package io.stamethyst.ui.aimod

import org.junit.Assert.assertEquals
import org.junit.Test

class AiConversationPresentationTest {
    @Test
    fun historyStillLoadingMustNotShowEmptyGuidance() {
        assertEquals(AiConversationPresentation.LOADING, presentation(storageReady = false, hasMessages = false))
    }

    @Test
    fun loadedEmptyConversationShowsGuidanceWithoutWaitingForRowsOrScroll() {
        assertEquals(AiConversationPresentation.EMPTY, presentation(hasMessages = false))
    }

    @Test
    fun populatedConversationWaitsForTranscriptAndInitialScroll() {
        assertEquals(AiConversationPresentation.LOADING, presentation())
        assertEquals(AiConversationPresentation.LOADING, presentation(transcriptReady = true))
        assertEquals(AiConversationPresentation.CONTENT, presentation(transcriptReady = true, initialScrollApplied = true))
    }

    @Test
    fun switchingConversationsHidesPreviousContentIncludingEmptyGuidance() {
        assertEquals(AiConversationPresentation.LOADING, presentation(switchingConversation = true, hasMessages = false))
        assertEquals(AiConversationPresentation.LOADING, presentation(
            switchingConversation = true, transcriptReady = true, initialScrollApplied = true,
        ))
    }

    private fun presentation(
        storageReady: Boolean = true,
        switchingConversation: Boolean = false,
        hasMessages: Boolean = true,
        transcriptReady: Boolean = false,
        initialScrollApplied: Boolean = false,
    ) = aiConversationPresentation(storageReady, switchingConversation, hasMessages, transcriptReady, initialScrollApplied)
}
