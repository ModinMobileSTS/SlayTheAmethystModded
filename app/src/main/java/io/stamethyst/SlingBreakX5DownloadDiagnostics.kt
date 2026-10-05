package io.stamethyst

import com.tencent.smtt.sdk.TbsListener

/** Signed config interrupt codes are not the positive APK download error codes. */
internal object SlingBreakX5DownloadDiagnostics {
    // SDK 44286 readResponse: RET=0, but DOWNLOADURL is empty. Not an HTTP redirect error.
    private const val CONFIG_DOWNLOAD_URL_EMPTY = -124

    fun failureMessageResource(errorCode: Int): Int = when (errorCode) {
        CONFIG_DOWNLOAD_URL_EMPTY -> R.string.settings_sling_break_x5_core_not_offered
        TbsListener.ErrorCode.DOWNLOAD_REDIRECT_EMPTY -> R.string.settings_sling_break_x5_redirect_empty
        TbsListener.ErrorCode.STARTDOWNLOAD_OUT_OF_MAXTIME -> R.string.settings_sling_break_x5_download_restart_required
        else -> R.string.settings_sling_break_x5_download_error
    }

    fun sdkLogDetail(tag: String?, message: String?): String? {
        if (tag != "TbsDownload") return null
        val detail = message?.removePrefix("TBS:")?.trimStart() ?: return null
        // Do not mirror the full response or request JSON: they can contain SDK tokens/device IDs.
        return detail.takeIf {
            it.startsWith("[TbsApkDownloader.startDownload] mDownloadUrl=") ||
                it.startsWith("[TbsApkDownloader.startDownload] responseCode=") ||
                it.startsWith("[TbsDownloader.sendRequest]isQuery:") ||
                it.startsWith("[TbsDownloader.sendRequest] httpResponseCode=") ||
                it.startsWith("[TbsDownloader.readResponse] blank url,") ||
                it.startsWith("[TbsDownloader.readResponse] return #2,returnCode=") ||
                it.startsWith("[TbsDownloader.readResponse] return #5,responseCode=") ||
                it.startsWith("No need to download, code is")
        }
    }
}
