package io.stamethyst.backend.workshop

/** Keep paragraph boundaries where possible, batching small paragraphs into bounded requests. */
internal fun splitTranslationSections(text: String, maxChars: Int = 1200): List<String> {
    require(maxChars >= 2)
    if (text.isEmpty()) return emptyList()
    val sections = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var end = minOf(start + maxChars, text.length)
        if (end < text.length) {
            val paragraph = text.lastIndexOf("\n\n", end - 2)
            val line = text.lastIndexOf('\n', end - 1)
            val space = text.lastIndexOf(' ', end - 1)
            end = when {
                paragraph >= start + maxChars / 3 -> paragraph + 2
                line >= start + maxChars / 3 -> line + 1
                space >= start + maxChars / 3 -> space + 1
                else -> end
            }
            if (end < text.length && Character.isLowSurrogate(text[end]) && Character.isHighSurrogate(text[end - 1])) {
                end--
            }
        }
        sections += text.substring(start, end)
        start = end
    }
    return sections
}
