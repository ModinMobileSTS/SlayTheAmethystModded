package io.stamethyst.achievement

import io.stamethyst.backend.steamcloud.SteamAchievementCatalog
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamAchievementNotificationAssetsTest {
    private fun source(path: String): File = listOf(File(path), File("app", path)).first { it.isFile }
    private fun asset(name: String) = source("src/main/assets/steam-achievement/$name")

    @Test
    fun raritySnapshotCoversEveryBundledAchievementWithoutInventingARarity() {
        val achievements = JSONObject(asset("global-percentages.json").readText())
            .getJSONObject("achievementpercentages").getJSONArray("achievements")
        val entries = (0 until achievements.length()).map { achievements.getJSONObject(it) }
        val names = entries.map { it.getString("name").lowercase(Locale.ROOT) }
        assertEquals(names.size, names.toSet().size)
        assertEquals(SteamAchievementCatalog.apiNames, names.toSet())
        assertTrue(entries.all { it.getDouble("percent") in 0.0..100.0 })
        val rare = entries.filter { SteamAchievementNotificationSpec.isRare(it.getDouble("percent")) }
            .map { it.getString("name").lowercase(Locale.ROOT) }.toSet()
        assertEquals(
            setOf("donut", "ascend_20", "speed_climber", "neon", "focused", "minimalist", "common_sense", "one_relic", "eternal_one"),
            rare,
        )
    }

    @Test
    fun originalSoundFontsAndBothDistinctMasksAreNotReplacedByApproximations() {
        val hashes = mapOf(
            source("src/main/res/raw/steam_desktop_toast.wav") to "c03fc4796f78001b8d9e279c053d859b6e68ca97bdcb4bc657e45af3d17a3ef7",
            asset("motiva-sans-regular.ttf") to "19b8db163bcc51732457efa40911b4a422f297ff3cd566467d87eab93cef0c14",
            asset("motiva-sans-light.ttf") to "6b7a3177485c193a2e80be6269b6b12880e695a8b4349f49fccf87f9205badcc",
            asset("glow-mask-outer.png") to "fdceb73b3e83404318667bd87a6f1a8f3c900fadf4ff8c3178a84bf9e59ecf40",
            asset("glow-mask-inner.png") to "f26d07d9b1d86c46a7185943d927eaf239040ab0d343c8b0165f065da59a8560",
        )
        for ((file, expected) in hashes) {
            val actual = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            assertEquals(file.name, expected, actual)
        }
    }
}
