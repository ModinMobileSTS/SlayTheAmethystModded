package io.stamethyst.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

private const val markdownUrlTag = "markdown_url"

@Composable
internal fun SimpleMarkdownCard(
    title: String,
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(markdown) { parseSimpleMarkdown(markdown) }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium
            )
            blocks.forEach { block ->
                when (block) {
                    is MarkdownBlock.Heading -> {
                        MarkdownRichText(
                            text = block.text,
                            style = when (block.level) {
                                1 -> MaterialTheme.typography.titleSmall
                                2 -> MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = FontWeight.SemiBold
                                )
                                else -> MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        )
                    }

                    is MarkdownBlock.Paragraph -> {
                        MarkdownRichText(
                            text = block.text,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    is MarkdownBlock.ListBlock -> {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            block.items.forEachIndexed { index, item ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = if (block.ordered) "${index + 1}." else "\u2022",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    MarkdownRichText(
                                        text = item,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }

                    is MarkdownBlock.CodeBlock -> {
                        Text(
                            text = block.text,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .padding(10.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownRichText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val linkStyle = SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline
    )
    val boldStyle = SpanStyle(fontWeight = FontWeight.Bold)
    val italicStyle = SpanStyle(fontStyle = FontStyle.Italic)
    val codeStyle = SpanStyle(
        fontFamily = FontFamily.Monospace,
        background = MaterialTheme.colorScheme.surfaceContainerHighest,
        color = MaterialTheme.colorScheme.onSurface
    )
    val annotatedText = remember(
        text,
        linkStyle,
        boldStyle,
        italicStyle,
        codeStyle
    ) {
        buildMarkdownAnnotatedString(
            text = text,
            linkStyle = linkStyle,
            boldStyle = boldStyle,
            italicStyle = italicStyle,
            codeStyle = codeStyle
        )
    }
    ClickableText(
        text = annotatedText,
        modifier = modifier,
        style = style.copy(color = textColor),
        onClick = { offset ->
            annotatedText.getStringAnnotations(markdownUrlTag, offset, offset)
                .firstOrNull()
                ?.let { annotation -> uriHandler.openUri(annotation.item) }
        }
    )
}

private sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class ListBlock(val ordered: Boolean, val items: List<String>) : MarkdownBlock
    data class CodeBlock(val text: String) : MarkdownBlock
}

private fun parseSimpleMarkdown(markdown: String): List<MarkdownBlock> {
    val normalized = markdown.replace("\r\n", "\n").replace('\r', '\n').trim()
    if (normalized.isBlank()) {
        return listOf(MarkdownBlock.Paragraph(""))
    }
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraphLines = mutableListOf<String>()
    val listItems = mutableListOf<String>()
    var orderedList = false
    val lines = normalized.lines()
    var index = 0

    fun flushParagraph() {
        if (paragraphLines.isEmpty()) {
            return
        }
        blocks += MarkdownBlock.Paragraph(paragraphLines.joinToString("\n").trim())
        paragraphLines.clear()
    }

    fun flushList() {
        if (listItems.isEmpty()) {
            return
        }
        blocks += MarkdownBlock.ListBlock(
            ordered = orderedList,
            items = listItems.toList()
        )
        listItems.clear()
    }

    while (index < lines.size) {
        val trimmed = lines[index].trim()
        if (trimmed.isBlank()) {
            flushParagraph()
            flushList()
            index += 1
            continue
        }

        if (trimmed.startsWith("```")) {
            flushParagraph()
            flushList()
            index += 1
            val codeLines = mutableListOf<String>()
            while (index < lines.size && !lines[index].trim().startsWith("```")) {
                codeLines += lines[index]
                index += 1
            }
            if (index < lines.size) {
                index += 1
            }
            blocks += MarkdownBlock.CodeBlock(codeLines.joinToString("\n").trimEnd())
            continue
        }

        val headingMatch = Regex("""^(#{1,3})\s+(.+?)\s*#*$""").matchEntire(trimmed)
        if (headingMatch != null) {
            flushParagraph()
            flushList()
            blocks += MarkdownBlock.Heading(
                level = headingMatch.groupValues[1].length,
                text = headingMatch.groupValues[2].trim()
            )
            index += 1
            continue
        }

        val orderedMatch = Regex("""^\d+[.)]\s+(.+)$""").matchEntire(trimmed)
        if (orderedMatch != null) {
            flushParagraph()
            if (listItems.isNotEmpty() && !orderedList) {
                flushList()
            }
            orderedList = true
            listItems += orderedMatch.groupValues[1].trim()
            index += 1
            continue
        }

        val bulletMatch = Regex("""^[-*+]\s+(.+)$""").matchEntire(trimmed)
        if (bulletMatch != null) {
            flushParagraph()
            if (listItems.isNotEmpty() && orderedList) {
                flushList()
            }
            orderedList = false
            listItems += bulletMatch.groupValues[1].trim()
            index += 1
            continue
        }

        flushList()
        paragraphLines += lines[index].trimEnd()
        index += 1
    }

    flushParagraph()
    flushList()
    return blocks
}

private fun buildMarkdownAnnotatedString(
    text: String,
    linkStyle: SpanStyle,
    boldStyle: SpanStyle,
    italicStyle: SpanStyle,
    codeStyle: SpanStyle,
): AnnotatedString {
    return buildAnnotatedString {
        appendMarkdownInline(
            text = text,
            linkStyle = linkStyle,
            boldStyle = boldStyle,
            italicStyle = italicStyle,
            codeStyle = codeStyle
        )
    }
}

private fun AnnotatedString.Builder.appendMarkdownInline(
    text: String,
    linkStyle: SpanStyle,
    boldStyle: SpanStyle,
    italicStyle: SpanStyle,
    codeStyle: SpanStyle,
) {
    var index = 0
    while (index < text.length) {
        when {
            text[index] == '\\' && index + 1 < text.length -> {
                append(text[index + 1])
                index += 2
            }

            text.startsWith("**", index) -> {
                val end = text.indexOf("**", startIndex = index + 2)
                if (end > index + 2) {
                    pushStyle(boldStyle)
                    appendMarkdownInline(
                        text = text.substring(index + 2, end),
                        linkStyle = linkStyle,
                        boldStyle = boldStyle,
                        italicStyle = italicStyle,
                        codeStyle = codeStyle
                    )
                    pop()
                    index = end + 2
                } else {
                    append("**")
                    index += 2
                }
            }

            text[index] == '*' || text[index] == '_' -> {
                val marker = text[index]
                val end = text.indexOf(marker, startIndex = index + 1)
                if (end > index + 1) {
                    pushStyle(italicStyle)
                    appendMarkdownInline(
                        text = text.substring(index + 1, end),
                        linkStyle = linkStyle,
                        boldStyle = boldStyle,
                        italicStyle = italicStyle,
                        codeStyle = codeStyle
                    )
                    pop()
                    index = end + 1
                } else {
                    append(marker)
                    index += 1
                }
            }

            text[index] == '`' -> {
                val end = text.indexOf('`', startIndex = index + 1)
                if (end > index + 1) {
                    pushStyle(codeStyle)
                    append(text.substring(index + 1, end))
                    pop()
                    index = end + 1
                } else {
                    append('`')
                    index += 1
                }
            }

            text[index] == '[' -> {
                val closeBracket = text.indexOf(']', startIndex = index + 1)
                val openParen = closeBracket.takeIf { it >= 0 }?.let { bracket ->
                    if (bracket + 1 < text.length && text[bracket + 1] == '(') {
                        bracket + 1
                    } else {
                        -1
                    }
                } ?: -1
                val closeParen = openParen.takeIf { it >= 0 }?.let { paren ->
                    text.indexOf(')', startIndex = paren + 1)
                } ?: -1
                if (closeBracket > index + 1 && closeParen > openParen + 1) {
                    val label = text.substring(index + 1, closeBracket)
                    val url = text.substring(openParen + 1, closeParen)
                    pushStringAnnotation(tag = markdownUrlTag, annotation = url)
                    pushStyle(linkStyle)
                    appendMarkdownInline(
                        text = label,
                        linkStyle = linkStyle,
                        boldStyle = boldStyle,
                        italicStyle = italicStyle,
                        codeStyle = codeStyle
                    )
                    pop()
                    pop()
                    index = closeParen + 1
                } else {
                    append('[')
                    index += 1
                }
            }

            else -> {
                append(text[index])
                index += 1
            }
        }
    }
}
