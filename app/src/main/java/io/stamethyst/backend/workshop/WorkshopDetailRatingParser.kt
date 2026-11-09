package io.stamethyst.backend.workshop

import org.jsoup.Jsoup

/** The public details API often omits vote_data; the community page exposes its displayed stars. */
internal object WorkshopDetailRatingParser {
    private val ratingAsset = Regex("""/sharedfiles/([1-5])-star(?:_large)?\.png(?:[?#].*)?$""", RegexOption.IGNORE_CASE)

    fun parse(html: String): WorkshopItemRating? = Jsoup.parse(html)
        .select(".fileRatingDetails img[src]")
        .firstNotNullOfOrNull { image ->
            ratingAsset.find(image.attr("src"))?.groupValues?.get(1)?.toIntOrNull()
                ?.let { score -> WorkshopItemRating(score = score, maxScore = 5) }
        }
}
