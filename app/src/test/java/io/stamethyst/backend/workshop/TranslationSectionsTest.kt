package io.stamethyst.backend.workshop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationSectionsTest {
    @Test
    fun smallParagraphsAreBatched() {
        val text = "Heading\n\nShort paragraph.\n\nAnother short paragraph."
        assertEquals(listOf(text), splitTranslationSections(text))
    }

    @Test
    fun largeDescriptionPreservesAllTextAndUsesParagraphBoundaries() {
        val paragraph = "a".repeat(400) + "\n\n"
        val text = paragraph.repeat(10)
        val parts = splitTranslationSections(text)
        assertEquals(text, parts.joinToString(""))
        assertTrue(parts.all { it.length <= 1200 })
        assertTrue(parts.dropLast(1).all { it.endsWith("\n\n") })
    }

    @Test
    fun longUnbrokenTextIsBoundedWithoutSplittingEmoji() {
        val text = "中😀".repeat(1000)
        val parts = splitTranslationSections(text, 100)
        assertEquals(text, parts.joinToString(""))
        assertTrue(parts.all { it.length <= 100 })
        assertFalse(parts.any { Character.isLowSurrogate(it.first()) || Character.isHighSurrogate(it.last()) })
    }

    @Test
    fun emptyTextHasNoRequests() {
        assertTrue(splitTranslationSections("").isEmpty())
    }
}
