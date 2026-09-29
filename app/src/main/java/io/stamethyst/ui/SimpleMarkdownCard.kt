package io.stamethyst.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val markdownImageInlineRegex =
    Regex("""!\[([^\]]*)]\(([^)\s]+)(?:\s+["'][^"']*["'])?\)""")
private val htmlImageTagInlineRegex =
    Regex("""<\s*(?:img|image)\b([^>]*)/?\s*>(?:\s*</\s*image\s*>)?""", RegexOption.IGNORE_CASE)
private val htmlImageWrappedInlineRegex =
    Regex("""<\s*image\s*>\s*(.*?)\s*</\s*image\s*>""", RegexOption.IGNORE_CASE)
private val htmlAttributeRegex =
    Regex("""([A-Za-z_:][-A-Za-z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")
private val bareUrlRegex =
    Regex("""https?://[^\s<>()]+(?:\([^\s<>()]*\)[^\s<>()]*)*""", RegexOption.IGNORE_CASE)
private val markdownTableSeparatorRegex = Regex(":?-{3,}:?")
private val markdownOrderedListLineRegex = Regex("^\\d+[.)]\\s+.*")

/** `---`, `***`, `___` (with optional spaces) on a line of their own. */
private val markdownHorizontalRuleRegex =
    Regex("""^(?:\*[ \t]*){3,}$|^(?:-[ \t]*){3,}$|^(?:_[ \t]*){3,}$""")

@Composable
internal fun SimpleMarkdownCard(
    title: String,
    markdown: String,
    modifier: Modifier = Modifier,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    textSelectable: Boolean = false,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = containerColor
        )
    ) {
        SimpleMarkdownContent(
            title = title,
            markdown = markdown,
            modifier = Modifier.padding(12.dp),
            textSelectable = textSelectable,
        )
    }
}

@Composable
internal fun SimpleMarkdownContent(
    markdown: String,
    modifier: Modifier = Modifier,
    title: String = "",
    textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    codeContainerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    codeTextColor: Color = MaterialTheme.colorScheme.onSurface,
    textSelectable: Boolean = false,
    imageShowOpenButton: Boolean = true,
    onImageClick: ((String) -> Unit)? = null,
) {
    val normalizedMarkdown = remember(markdown) {
        markdown.replace("\r\n", "\n").replace('\r', '\n').trim()
    }
    val plainText = remember(normalizedMarkdown) { isPlainMarkdownText(normalizedMarkdown) }
    val blocks = remember(normalizedMarkdown, plainText) {
        if (plainText) emptyList() else parseSimpleMarkdown(normalizedMarkdown)
    }
    val content: @Composable () -> Unit = {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (title.isNotBlank()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor
                )
            }
            if (plainText) {
                Text(
                    text = normalizedMarkdown,
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor,
                )
            }
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
                            },
                            textColor = textColor
                        )
                    }

                    is MarkdownBlock.Paragraph -> {
                        MarkdownRichText(
                            text = block.text,
                            style = MaterialTheme.typography.bodySmall,
                            textColor = textColor
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
                                        color = textColor
                                    )
                                    MarkdownRichText(
                                        text = item,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f),
                                        textColor = textColor
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
                                color = codeTextColor
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(codeContainerColor)
                                .padding(10.dp)
                        )
                    }

                    is MarkdownBlock.Table -> {
                        MarkdownTable(
                            table = block,
                            textColor = textColor,
                            codeContainerColor = codeContainerColor
                        )
                    }

                    is MarkdownBlock.HorizontalRule -> {
                        HorizontalDivider(color = textColor.copy(alpha = 0.25f))
                    }

                    is MarkdownBlock.BlockQuote -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .fillMaxHeight()
                                    .background(textColor.copy(alpha = 0.35f), RoundedCornerShape(2.dp))
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                block.lines.forEach { line ->
                                    MarkdownRichText(
                                        text = line,
                                        style = MaterialTheme.typography.bodySmall,
                                        textColor = textColor
                                    )
                                }
                            }
                        }
                    }

                    is MarkdownBlock.Image -> {
                        SimpleMarkdownImage(
                            imageUrl = block.url,
                            alt = block.alt,
                            textColor = textColor,
                            showOpenButton = imageShowOpenButton,
                            onClick = onImageClick
                        )
                    }
                }
            }
        }
    }
    if (textSelectable) {
        SelectionContainer(content = content)
    } else {
        content()
    }
}

/** Avoid the parser and annotated-string builder for the common plain-text response. */
private fun isPlainMarkdownText(markdown: String): Boolean {
    if (markdown.isBlank()) return false
    if (markdown.lineSequence().any { line ->
            val trimmed = line.trimStart()
            trimmed.startsWith("#") || trimmed.startsWith(">") || trimmed.startsWith("```") ||
                trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") ||
                markdownOrderedListLineRegex.matches(trimmed)
        }
    ) return false
    return !markdown.containsAny(
        "**", "~~", "`", "![", "](", "http://", "https://", "<img", "<image", "|",
        "*", "_", "[",
    )
}

private fun String.containsAny(vararg values: String): Boolean = values.any(::contains)

@Composable
private fun MarkdownTable(
    table: MarkdownBlock.Table,
    textColor: Color,
    codeContainerColor: Color,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        MarkdownTableRow(
            cells = table.headers,
            textColor = textColor,
            containerColor = codeContainerColor,
            bold = true,
        )
        table.rows.forEach { row ->
            MarkdownTableRow(
                cells = row,
                textColor = textColor,
                containerColor = Color.Transparent,
                bold = false,
            )
        }
    }
}

@Composable
private fun MarkdownTableRow(
    cells: List<String>,
    textColor: Color,
    containerColor: Color,
    bold: Boolean,
) {
    Row {
        cells.forEach { cell ->
            Surface(
                modifier = Modifier
                    .width(140.dp)
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                color = containerColor,
            ) {
                MarkdownRichText(
                    text = cell,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal
                    ),
                    modifier = Modifier.padding(10.dp),
                    textColor = textColor,
                )
            }
        }
    }
}

@Composable
internal fun SimpleMarkdownImage(
    imageUrl: String,
    modifier: Modifier = Modifier,
    alt: String = "",
    textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    showOpenButton: Boolean = true,
    onClick: ((String) -> Unit)? = null
) {
    val context = LocalContext.current.applicationContext
    val uriHandler = LocalUriHandler.current
    val normalizedUrl = remember(imageUrl) { normalizeMarkdownImageUrl(imageUrl) }
    val imageState by produceState<RemoteImageState>(
        initialValue = RemoteImageState.Loading,
        normalizedUrl
    ) {
        value = if (normalizedUrl.isBlank()) {
            RemoteImageState.Failed
        } else {
            withContext(Dispatchers.IO) {
                RemoteBitmapCacheStore.load(context, normalizedUrl)
            }?.let(RemoteImageState::Loaded) ?: RemoteImageState.Failed
        }
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        val bitmap = (imageState as? RemoteImageState.Loaded)?.bitmap
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (bitmap == null) {
                        Modifier.heightIn(min = 112.dp)
                    } else {
                        val aspectRatio = (
                            requireNotNull(bitmap).width.toFloat() /
                                requireNotNull(bitmap).height.coerceAtLeast(1).toFloat()
                            ).coerceIn(0.65f, 2.4f)
                        Modifier.aspectRatio(aspectRatio)
                    }
                )
                .clip(RoundedCornerShape(12.dp))
                .then(
                    if (normalizedUrl.isNotBlank() && onClick != null) {
                        Modifier.clickable { onClick(normalizedUrl) }
                    } else {
                        Modifier
                    }
                )
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center
        ) {
            when (val state = imageState) {
                RemoteImageState.Loading -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(16.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text(
                            text = alt.ifBlank { stringResource(R.string.markdown_image_loading) },
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor
                        )
                    }
                }

                RemoteImageState.Failed -> {
                    Text(
                        text = alt.ifBlank { stringResource(R.string.markdown_image_failed) },
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor,
                        modifier = Modifier.padding(16.dp)
                    )
                }

                is RemoteImageState.Loaded -> {
                    Image(
                        bitmap = state.bitmap.asImageBitmap(),
                        contentDescription = alt.takeIf(String::isNotBlank),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        if (showOpenButton && normalizedUrl.isNotBlank()) {
            TextButton(
                onClick = { uriHandler.openUri(normalizedUrl) },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(stringResource(R.string.markdown_image_open))
            }
        }
    }
}

internal fun extractSimpleMarkdownImageUrls(markdown: String): List<String> {
    return markdown
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lines()
        .flatMap { line -> findMarkdownImageTokens(line) }
        .map { token -> token.image.url }
        .distinct()
}

@Composable
private fun MarkdownRichText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val linkStyle = SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline
    )
    val linkStyles = remember(linkStyle) {
        TextLinkStyles(style = linkStyle)
    }
    val boldStyle = SpanStyle(fontWeight = FontWeight.Bold)
    val italicStyle = SpanStyle(fontStyle = FontStyle.Italic)
    val codeStyle = SpanStyle(
        fontFamily = FontFamily.Monospace,
        background = MaterialTheme.colorScheme.surfaceContainerHighest,
        color = MaterialTheme.colorScheme.onSurface
    )
    val strikeStyle = SpanStyle(textDecoration = TextDecoration.LineThrough)
    val annotatedText = remember(
        text,
        linkStyles,
        boldStyle,
        italicStyle,
        codeStyle,
        strikeStyle
    ) {
        buildMarkdownAnnotatedString(
            text = text,
            linkStyles = linkStyles,
            boldStyle = boldStyle,
            italicStyle = italicStyle,
            codeStyle = codeStyle,
            strikeStyle = strikeStyle
        )
    }
    Text(
        text = annotatedText,
        modifier = modifier,
        style = style.copy(color = textColor)
    )
}

private sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class ListBlock(val ordered: Boolean, val items: List<String>) : MarkdownBlock
    data class CodeBlock(val text: String) : MarkdownBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock
    data class Image(val alt: String, val url: String) : MarkdownBlock
    data object HorizontalRule : MarkdownBlock
    data class BlockQuote(val lines: List<String>) : MarkdownBlock
}

private sealed class RemoteImageState {
    object Loading : RemoteImageState()
    object Failed : RemoteImageState()
    data class Loaded(val bitmap: Bitmap) : RemoteImageState()
}

private data class MarkdownImageToken(
    val range: IntRange,
    val image: MarkdownBlock.Image
)

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

        if (markdownHorizontalRuleRegex.matches(trimmed)) {
            flushParagraph()
            flushList()
            blocks += MarkdownBlock.HorizontalRule
            index += 1
            continue
        }

        if (trimmed.startsWith(">")) {
            flushParagraph()
            flushList()
            val quoted = mutableListOf<String>()
            while (index < lines.size && lines[index].trim().startsWith(">")) {
                quoted += lines[index].trim().removePrefix(">").trimStart()
                index += 1
            }
            blocks += MarkdownBlock.BlockQuote(quoted)
            continue
        }

        if (index + 1 < lines.size) {
            val tableHeader = parseTableRow(lines[index])
            val tableSeparator = parseTableSeparator(lines[index + 1], tableHeader?.size ?: 0)
            if (tableHeader != null && tableSeparator) {
                flushParagraph()
                flushList()
                index += 2
                val rows = mutableListOf<List<String>>()
                while (index < lines.size) {
                    val row = parseTableRow(lines[index]) ?: break
                    rows += normalizeTableRow(row, tableHeader.size)
                    index += 1
                }
                blocks += MarkdownBlock.Table(
                    headers = normalizeTableRow(tableHeader, tableHeader.size),
                    rows = rows,
                )
                continue
            }
        }

        val headingMatch = Regex("""^(#{1,6})\s+(.+?)\s*#*$""").matchEntire(trimmed)
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

        val imageBlocks = parseImageLineSegments(lines[index])
        if (imageBlocks.isNotEmpty()) {
            flushParagraph()
            flushList()
            blocks += imageBlocks
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

internal fun parseSimpleMarkdownTablesForTest(
    markdown: String
): List<Pair<List<String>, List<List<String>>>> {
    return parseSimpleMarkdown(markdown)
        .filterIsInstance<MarkdownBlock.Table>()
        .map { table -> table.headers to table.rows }
}

/** Stable, type-free view of the parsed block structure for unit tests. */
internal fun parseSimpleMarkdownOutlineForTest(markdown: String): List<String> =
    parseSimpleMarkdown(markdown).map { block ->
        when (block) {
            is MarkdownBlock.Heading -> "heading${block.level}"
            is MarkdownBlock.Paragraph -> "paragraph"
            is MarkdownBlock.ListBlock -> if (block.ordered) "olist" else "ulist"
            is MarkdownBlock.CodeBlock -> "code"
            is MarkdownBlock.Table -> "table"
            is MarkdownBlock.Image -> "image"
            MarkdownBlock.HorizontalRule -> "hr"
            is MarkdownBlock.BlockQuote -> "quote:" + block.lines.joinToString("|")
        }
    }

private fun parseTableRow(line: String): List<String>? {
    val trimmed = line.trim()
    if (!trimmed.contains('|')) {
        return null
    }
    val content = trimmed
        .removePrefix("|")
        .removeSuffix("|")
    val cells = splitTableCells(content)
    return cells.takeIf { it.isNotEmpty() }
}

private fun parseTableSeparator(line: String, expectedColumns: Int): Boolean {
    val cells = parseTableRow(line) ?: return false
    if (cells.size != expectedColumns) {
        return false
    }
    return cells.all { cell -> cell.trim().matches(markdownTableSeparatorRegex) }
}

private fun splitTableCells(content: String): List<String> {
    val cells = mutableListOf<String>()
    val current = StringBuilder()
    var escaped = false
    content.forEach { character ->
        when {
            character == '|' && !escaped -> {
                cells += current.toString().trim()
                current.clear()
            }
            character == '\\' && !escaped -> escaped = true
            else -> {
                if (escaped && character != '|') {
                    current.append('\\')
                }
                current.append(character)
                escaped = false
            }
        }
    }
    if (escaped) {
        current.append('\\')
    }
    cells += current.toString().trim()
    return cells
}

private fun normalizeTableRow(row: List<String>, columnCount: Int): List<String> {
    return row.take(columnCount).let { normalized ->
        normalized + List(columnCount - normalized.size) { "" }
    }
}

private fun parseImageLineSegments(line: String): List<MarkdownBlock> {
    val tokens = findMarkdownImageTokens(line)
    if (tokens.isEmpty()) {
        return emptyList()
    }

    val blocks = mutableListOf<MarkdownBlock>()
    var cursor = 0
    tokens.forEach { token ->
        if (token.range.first < cursor) {
            return@forEach
        }
        val prefix = line.substring(cursor, token.range.first).trim()
        if (prefix.isNotBlank()) {
            blocks += MarkdownBlock.Paragraph(prefix)
        }
        blocks += token.image
        cursor = token.range.last + 1
    }
    val suffix = line.substring(cursor).trim()
    if (suffix.isNotBlank()) {
        blocks += MarkdownBlock.Paragraph(suffix)
    }
    return blocks
}

private fun findMarkdownImageTokens(line: String): List<MarkdownImageToken> {
    val tokens = mutableListOf<MarkdownImageToken>()

    markdownImageInlineRegex.findAll(line).forEach { match ->
        val url = normalizeMarkdownImageUrl(match.groupValues[2])
        if (url.isNotBlank()) {
            tokens += MarkdownImageToken(
                range = match.range,
                image = MarkdownBlock.Image(
                    alt = match.groupValues[1].trim(),
                    url = url
                )
            )
        }
    }

    htmlImageWrappedInlineRegex.findAll(line).forEach { match ->
        val url = normalizeMarkdownImageUrl(match.groupValues[1])
        if (url.isNotBlank()) {
            tokens += MarkdownImageToken(
                range = match.range,
                image = MarkdownBlock.Image(alt = "", url = url)
            )
        }
    }

    htmlImageTagInlineRegex.findAll(line).forEach { match ->
        val attributes = parseHtmlAttributes(match.groupValues[1])
        val url = normalizeMarkdownImageUrl(attributes["src"].orEmpty())
        if (url.isNotBlank()) {
            tokens += MarkdownImageToken(
                range = match.range,
                image = MarkdownBlock.Image(
                    alt = attributes["alt"].orEmpty().trim(),
                    url = url
                )
            )
        }
    }

    return tokens
        .sortedBy { it.range.first }
        .fold(mutableListOf<MarkdownImageToken>()) { result, token ->
            val previous = result.lastOrNull()
            if (previous == null || token.range.first > previous.range.last) {
                result += token
            }
            result
        }
}

private fun parseHtmlAttributes(rawAttributes: String): Map<String, String> {
    return htmlAttributeRegex.findAll(rawAttributes)
        .associate { match ->
            val key = match.groupValues[1].lowercase()
            val value = match.groupValues
                .drop(2)
                .firstOrNull(String::isNotEmpty)
                .orEmpty()
            key to value
        }
}

private fun normalizeMarkdownImageUrl(rawUrl: String): String {
    return rawUrl
        .trim()
        .removePrefix("<")
        .removeSuffix(">")
        .trim()
}

private fun buildMarkdownAnnotatedString(
    text: String,
    linkStyles: TextLinkStyles,
    boldStyle: SpanStyle,
    italicStyle: SpanStyle,
    codeStyle: SpanStyle,
    strikeStyle: SpanStyle,
): AnnotatedString {
    return buildAnnotatedString {
        appendMarkdownInline(
            text = text,
            linkStyles = linkStyles,
            boldStyle = boldStyle,
            italicStyle = italicStyle,
            codeStyle = codeStyle,
            strikeStyle = strikeStyle
        )
    }
}

internal fun buildSimpleMarkdownAnnotatedStringForTest(text: String): AnnotatedString {
    return buildMarkdownAnnotatedString(
        text = text,
        linkStyles = TextLinkStyles(),
        boldStyle = SpanStyle(fontWeight = FontWeight.Bold),
        italicStyle = SpanStyle(fontStyle = FontStyle.Italic),
        codeStyle = SpanStyle(fontFamily = FontFamily.Monospace),
        strikeStyle = SpanStyle(textDecoration = TextDecoration.LineThrough)
    )
}

private fun AnnotatedString.Builder.appendMarkdownInline(
    text: String,
    linkStyles: TextLinkStyles,
    boldStyle: SpanStyle,
    italicStyle: SpanStyle,
    codeStyle: SpanStyle,
    strikeStyle: SpanStyle,
) {
    var index = 0
    while (index < text.length) {
        when {
            text[index] == '\\' && index + 1 < text.length -> {
                append(text[index + 1])
                index += 2
            }

            text.startsWith("~~", index) -> {
                val end = text.indexOf("~~", startIndex = index + 2)
                if (end > index + 2) {
                    pushStyle(strikeStyle)
                    appendMarkdownInline(
                        text = text.substring(index + 2, end),
                        linkStyles = linkStyles,
                        boldStyle = boldStyle,
                        italicStyle = italicStyle,
                        codeStyle = codeStyle,
                        strikeStyle = strikeStyle
                    )
                    pop()
                    index = end + 2
                } else {
                    append("~~")
                    index += 2
                }
            }

            text.startsWith("**", index) -> {
                val end = text.indexOf("**", startIndex = index + 2)
                if (end > index + 2) {
                    pushStyle(boldStyle)
                    appendMarkdownInline(
                        text = text.substring(index + 2, end),
                        linkStyles = linkStyles,
                        boldStyle = boldStyle,
                        italicStyle = italicStyle,
                        codeStyle = codeStyle,
                        strikeStyle = strikeStyle
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
                        linkStyles = linkStyles,
                        boldStyle = boldStyle,
                        italicStyle = italicStyle,
                        codeStyle = codeStyle,
                        strikeStyle = strikeStyle
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
                    val url = normalizeMarkdownLinkUrl(text.substring(openParen + 1, closeParen))
                    if (url.isNotBlank()) {
                        withLink(LinkAnnotation.Url(url = url, styles = linkStyles)) {
                            appendMarkdownInline(
                                text = label,
                                linkStyles = linkStyles,
                                boldStyle = boldStyle,
                                italicStyle = italicStyle,
                                codeStyle = codeStyle,
                                strikeStyle = strikeStyle
                            )
                        }
                        index = closeParen + 1
                    } else {
                        append('[')
                        index += 1
                    }
                } else {
                    val bareUrl = matchBareUrlAt(text, index)
                    if (bareUrl != null) {
                        withLink(LinkAnnotation.Url(url = bareUrl, styles = linkStyles)) {
                            append(bareUrl)
                        }
                        index += bareUrl.length
                    } else {
                        append('[')
                        index += 1
                    }
                }
            }

            matchBareUrlAt(text, index) != null -> {
                val bareUrl = requireNotNull(matchBareUrlAt(text, index))
                withLink(LinkAnnotation.Url(url = bareUrl, styles = linkStyles)) {
                    append(bareUrl)
                }
                index += bareUrl.length
            }

            else -> {
                append(text[index])
                index += 1
            }
        }
    }
}

private fun normalizeMarkdownLinkUrl(rawUrl: String): String {
    return rawUrl
        .trim()
        .removePrefix("<")
        .removeSuffix(">")
        .trim()
}

private fun matchBareUrlAt(text: String, startIndex: Int): String? {
    val match = bareUrlRegex.find(text, startIndex)
        ?.takeIf { it.range.first == startIndex }
        ?: return null
    var candidate = match.value.trimEnd('.', ',', ';', ':', '!', '?')
    while (candidate.endsWith(")") && candidate.count { it == '(' } < candidate.count { it == ')' }) {
        candidate = candidate.dropLast(1)
    }
    return candidate.takeIf(String::isNotBlank)
}
