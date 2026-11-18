package io.stamethyst.ui.aimod

import org.junit.Assert.*
import org.junit.Test

class AiEditorCachePresentationTest {
    @Test fun unknownIsNotPresentedAsZeroPercent() {
        assertEquals("--", formatAiCacheRate(null))
        assertNotEquals("--", formatAiCacheRate(0.0))
        assertTrue(formatAiCacheRate(0.975).contains("97"))
    }

    @Test fun unknownTokenCountsAreNotPresentedAsZero() {
        assertEquals("--", formatAiTokenCount(null))
        assertEquals(java.text.NumberFormat.getIntegerInstance().format(0), formatAiTokenCount(0))
        assertEquals(java.text.NumberFormat.getIntegerInstance().format(200_000), formatAiTokenCount(200_000))
    }

    @Test fun differentModsShareAllCommonRulesBeforeTheirSpecificEnvironment() {
        val a = buildAiEditorSystemPrompt("Alpha", "alpha")
        val b = buildAiEditorSystemPrompt("Beta", "beta")
        val shared = a.commonPrefixWith(b)
        assertTrue(shared.length > a.length * 0.95)
        assertTrue(shared.contains("Never claim that a patch mod was created"))
        assertTrue(a.endsWith("for the selected mod."))
        assertEquals(a, buildAiEditorSystemPrompt("Alpha", "alpha"))
    }
}
