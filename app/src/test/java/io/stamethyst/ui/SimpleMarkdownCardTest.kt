package io.stamethyst.ui

import androidx.compose.ui.text.LinkAnnotation
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals

class SimpleMarkdownCardTest {

    @Test
    fun parseSimpleMarkdownTables_parsesGfmTableAndNormalizesRows() {
        val tables = parseSimpleMarkdownTablesForTest(
            """
            | Name | Result |
            | :--- | ---: |
            | **A** | 1 |
            | B |
            """.trimIndent()
        )

        assertEquals(1, tables.size)
        assertEquals(listOf("Name", "Result"), tables.single().first)
        assertEquals(
            listOf(listOf("**A**", "1"), listOf("B", "")),
            tables.single().second
        )
    }

    @Test
    fun parseSimpleMarkdownTables_supportsEscapedPipes() {
        val tables = parseSimpleMarkdownTablesForTest(
            """
            | Key | Description |
            | --- | --- |
            | a\|b | value |
            """.trimIndent()
        )

        assertEquals(listOf("Key", "Description"), tables.single().first)
        assertEquals(listOf(listOf("a|b", "value")), tables.single().second)
    }

    @Test
    fun buildSimpleMarkdownAnnotatedString_marksMarkdownLinksAsLinkAnnotations() {
        val annotated = buildSimpleMarkdownAnnotatedStringForTest(
            "查看 [日志](https://example.com/logs.zip)"
        )

        assertEquals("查看 日志", annotated.text)
        val links = annotated.getLinkAnnotations(0, annotated.length)
        assertEquals(1, links.size)
        val annotation = links.single().item
        assertTrue(annotation is LinkAnnotation.Url)
        annotation as LinkAnnotation.Url
        assertEquals("https://example.com/logs.zip", annotation.url)
        assertEquals("日志", annotated.text.substring(links.single().start, links.single().end))
    }

    @Test
    fun buildSimpleMarkdownAnnotatedString_marksBareUrlsWithoutTrailingPunctuation() {
        val annotated = buildSimpleMarkdownAnnotatedStringForTest(
            "链接：https://example.com/path?a=1."
        )

        assertEquals("链接：https://example.com/path?a=1.", annotated.text)
        val links = annotated.getLinkAnnotations(0, annotated.length)
        assertEquals(1, links.size)
        val annotation = links.single().item
        assertTrue(annotation is LinkAnnotation.Url)
        annotation as LinkAnnotation.Url
        assertEquals("https://example.com/path?a=1", annotation.url)
        assertEquals(
            "https://example.com/path?a=1",
            annotated.text.substring(links.single().start, links.single().end)
        )
    }
}
