package io.stamethyst

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlingBreakX5DownloadDiagnosticsTest {
    @Test
    fun negative124_meansCoreWasNotOffered() {
        assertEquals(
            R.string.settings_sling_break_x5_core_not_offered,
            SlingBreakX5DownloadDiagnostics.failureMessageResource(-124)
        )
    }

    @Test
    fun positive124_meansRedirectAddressWasMissing() {
        assertEquals(
            R.string.settings_sling_break_x5_redirect_empty,
            SlingBreakX5DownloadDiagnostics.failureMessageResource(124)
        )
    }

    @Test
    fun positive127_requiresRestart() {
        assertEquals(
            R.string.settings_sling_break_x5_download_restart_required,
            SlingBreakX5DownloadDiagnostics.failureMessageResource(127)
        )
    }

    @Test
    fun otherErrors_keepTheirGenericMessage() {
        assertEquals(
            R.string.settings_sling_break_x5_download_error,
            SlingBreakX5DownloadDiagnostics.failureMessageResource(-125)
        )
    }

    @Test
    fun sdkPrefix_isRemovedFromConfigAndDownloadStatus() {
        listOf(
            "[TbsDownloader.sendRequest]isQuery:false forDecoupleCore is false",
            "[TbsDownloader.sendRequest] httpResponseCode=200",
            "[TbsDownloader.readResponse] blank url,current app is third app...",
            "No need to download, code is -124",
            "[TbsApkDownloader.startDownload] responseCode=302"
        ).forEach { detail ->
            assertEquals(detail, SlingBreakX5DownloadDiagnostics.sdkLogDetail("TbsDownload", "TBS: $detail"))
        }
    }

    @Test
    fun fullPayloadsAndUnrelatedLogs_areNotMirrored() {
        assertNull(SlingBreakX5DownloadDiagnostics.sdkLogDetail("TbsDownload", "TBS: [TbsDownloader.readResponse] response={\"SETTOKEN\":\"secret\"}"))
        assertNull(SlingBreakX5DownloadDiagnostics.sdkLogDetail("TbsDownload", "request JSON contains device identifiers"))
        assertNull(SlingBreakX5DownloadDiagnostics.sdkLogDetail("other", "No need to download, code is -124"))
        assertNull(SlingBreakX5DownloadDiagnostics.sdkLogDetail("TbsDownload", null))
        assertNull(SlingBreakX5DownloadDiagnostics.sdkLogDetail(null, "No need to download, code is -124"))
    }
}
