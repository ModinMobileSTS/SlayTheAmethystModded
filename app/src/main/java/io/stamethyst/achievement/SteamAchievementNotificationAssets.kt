package io.stamethyst.achievement

import android.content.Context
import android.util.Base64
import io.stamethyst.backend.steamcloud.SteamAchievementCatalog
import org.json.JSONObject
import java.util.Locale

/** Bundled-only content: unlocking an achievement never makes a network request. */
internal class SteamAchievementNotificationAssets(private val context: Context) {
    private val percentages: JSONObject by lazy {
        JSONObject(context.assets.open("steam-achievement/global-percentages.json").bufferedReader().use { it.readText() })
            .getJSONObject("achievementpercentages")
            .getJSONArray("achievements")
            .let { achievements ->
                JSONObject().apply {
                    for (index in 0 until achievements.length()) {
                        val entry = achievements.getJSONObject(index)
                        put(entry.getString("name").lowercase(Locale.ROOT), entry.getDouble("percent"))
                    }
                }
            }
    }

    fun payload(entry: SteamAchievementCatalog.Entry, token: Long): String {
        val image = context.resources.openRawResource(entry.unlockedIconResId).use { it.readBytes() }
        val percent = percentages.optDouble(entry.apiName, Double.NaN)
        return JSONObject().apply {
            put("token", token)
            put("title", context.getString(entry.titleResId))
            put("description", context.getString(entry.descriptionResId))
            put("image", "data:image/jpeg;base64," + Base64.encodeToString(image, Base64.NO_WRAP))
            put("rare", SteamAchievementNotificationSpec.isRare(percent))
        }.toString()
    }

    companion object {
        const val ORIGIN = "https://steam-achievement.invalid/"
        val resources = mapOf(
            "notification.css" to "text/css",
            "notification.js" to "application/javascript",
            "motiva-sans-regular.ttf" to "font/ttf",
            "motiva-sans-light.ttf" to "font/ttf",
            "glow-mask-outer.png" to "image/png",
            "glow-mask-inner.png" to "image/png",
        )
    }
}
