package io.stamethyst.backend.workshop

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkshopPreviewImageVariantTest {
    @Test
    fun addsVariantParamsToBareUgcUrl() {
        val variant = workshopCoverVariantUrl(
            "https://steamuserimages-a.akamaihd.net/ugc/2482116236401327367/9E2E72339F73CC987617BBEC6BC45442C0DC748B/",
            512,
        )
        val url = checkNotNull(variant.toHttpUrlOrNull())
        assertEquals("512", url.queryParameter("imw"))
        assertEquals("fit", url.queryParameter("ima"))
        assertEquals("Letterbox", url.queryParameter("impolicy"))
        assertEquals("false", url.queryParameter("letterbox"))
    }

    @Test
    fun rewritesExistingSizeParamsOnUgcUrl() {
        val variant = workshopCoverVariantUrl(
            "https://steamuserimages-a.akamaihd.net/ugc/111/AAA/?imw=1920&imh=1080&ima=orig&impolicy=Letterbox&letterbox=false",
            512,
        )
        val url = checkNotNull(variant.toHttpUrlOrNull())
        assertEquals("512", url.queryParameter("imw"))
        assertNull(url.queryParameter("imh"))
        assertEquals("fit", url.queryParameter("ima"))
        assertEquals("Letterbox", url.queryParameter("impolicy"))
        assertEquals("false", url.queryParameter("letterbox"))
    }

    @Test
    fun preservesUnrelatedParams() {
        val variant = workshopCoverVariantUrl(
            "https://images.steamusercontent.com/ugc/111/BBB/?imw=512&&ima=fit&impolicy=Letterbox&imcolor=%23000000",
            512,
        )
        val url = checkNotNull(variant.toHttpUrlOrNull())
        assertEquals("512", url.queryParameter("imw"))
        assertEquals("#000000", url.queryParameter("imcolor"))
    }

    @Test
    fun handlesSteamusercontentHosts() {
        val variant = workshopCoverVariantUrl("https://images.steamusercontent.com/ugc/222/CCC/", 320)
        assertEquals("320", variant.toHttpUrlOrNull()?.queryParameter("imw"))
    }

    @Test
    fun leavesUnknownHostsUntouched() {
        val original = "https://cdn.example/some-mod/preview.jpg?imw=1920"
        assertEquals(original, workshopCoverVariantUrl(original, 512))
    }

    @Test
    fun leavesLookalikeHostsUntouched() {
        val original = "https://evilsteamusercontent.com/ugc/1/x/?imw=1920"
        assertEquals(original, workshopCoverVariantUrl(original, 512))
    }

    @Test
    fun passesThroughInvalidInput() {
        assertEquals("", workshopCoverVariantUrl("", 512))
        assertEquals("not a url", workshopCoverVariantUrl("not a url", 512))
        val original = "https://steamuserimages-a.akamaihd.net/ugc/333/DDD/"
        assertEquals(original, workshopCoverVariantUrl(original, 0))
    }
}
