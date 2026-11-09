package io.stamethyst.backend.workshop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkshopDetailRatingParserTest {
    @Test fun readsAllFiveDisplayedStarValuesWithoutNormalizingOneStarToFive() {
        for (stars in 1..5) {
            assertEquals(WorkshopItemRating(stars, 5), WorkshopDetailRatingParser.parse(widget("$stars-star_large.png?v=2")))
        }
    }

    @Test fun acceptsRelativeUrlsAndSmallAssetsWithFlexibleHtmlAttributes() {
        val html = """<div class='fileRatingDetails extra'><img alt='Rating' src='/public/images/sharedfiles/4-star.png'></div>"""
        assertEquals(WorkshopItemRating(4, 5), WorkshopDetailRatingParser.parse(html))
    }

    @Test fun ignoresUnratedAndInvalidAssets() {
        for (asset in listOf("not-yet.png", "0-star_large.png", "6-star_large.png", "5-star_large.png.invalid", "thumbsup.png")) {
            assertNull(asset, WorkshopDetailRatingParser.parse(widget(asset)))
        }
        assertNull(WorkshopDetailRatingParser.parse("<div>No ratings</div>"))
    }

    @Test fun doesNotTakeRatingFromRecommendationsOrDescriptionImages() {
        val unrelated = """<img class='fileRating' src='https://community.akamai.steamstatic.com/public/images/sharedfiles/5-star.png'>"""
        assertNull(WorkshopDetailRatingParser.parse(unrelated))
        assertEquals(WorkshopItemRating(2, 5), WorkshopDetailRatingParser.parse(unrelated + widget("2-star_large.png")))
    }

    private fun widget(asset: String) = """
        <div class="ratingSection">
          <div class="fileRatingDetails"><img src="https://community.akamai.steamstatic.com/public/images/sharedfiles/$asset" /></div>
          <div class="numRatings">773 个评价</div>
        </div>
    """.trimIndent()
}
