package io.stamethyst.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

private const val BASIC_TUTORIAL_WEB_URL = "https://www.bilibili.com/video/BV1omRkBQEdk/"
private const val BASIC_TUTORIAL_BILI_URI = "bilibili://video/116521154117866"
private const val BILIBILI_PACKAGE_NAME = "tv.danmaku.bili"

fun openBasicTutorial(context: Context) {
    val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse(BASIC_TUTORIAL_BILI_URI)).apply {
        setPackage(BILIBILI_PACKAGE_NAME)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (startActivitySafely(context, appIntent)) {
        return
    }

    val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(BASIC_TUTORIAL_WEB_URL)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    startActivitySafely(context, webIntent)
}

private fun startActivitySafely(context: Context, intent: Intent): Boolean {
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
