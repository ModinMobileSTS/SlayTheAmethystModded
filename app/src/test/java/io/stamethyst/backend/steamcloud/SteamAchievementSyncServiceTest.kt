package io.stamethyst.backend.steamcloud

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamAchievementSyncServiceTest {
    @Test
    fun parseRequest_readsRuntimeAchievementArrayAndDeduplicates() {
        val request = SteamAchievementSyncService.parseRequest(
            "{\"type\":\"achievement_sync\",\"achievements\":[\"shrug_it_off\",\"unknown\",\"shrug_it_off\"]}"
        )

        assertEquals(setOf(SteamAchievementService.SHRUG_IT_OFF_API_NAME), request?.achievementIds)
        assertEquals(SteamAchievementService.SHRUG_IT_OFF_API_NAME, request?.id)
    }

    @Test
    fun parseRequest_normalizesVanillaUppercaseAchievementIds() {
        val request = SteamAchievementSyncService.parseRequest(
            "{\"version\":1,\"type\":\"achievement_sync\",\"save_slot\":0," +
                "\"achievements\":[\"SHRUG_IT_OFF\"]}"
        )

        assertEquals(setOf(SteamAchievementService.SHRUG_IT_OFF_API_NAME), request?.achievementIds)
        assertEquals(SteamAchievementService.SHRUG_IT_OFF_API_NAME, request?.id)
        assertEquals(0, request?.saveSlot)
    }

    @Test
    fun parseRequest_rejectsEmptyOrMalformedRequests() {
        assertNull(SteamAchievementSyncService.parseRequest("{}"))
        assertNull(SteamAchievementSyncService.parseRequest("not-json"))
    }

    @Test
    fun plan_uploadsLocalOnlyUnlocksAndKeepsFilesForInboundUpdates() {
        val file = File("STSAchievements")
        val plan = SteamAchievementSyncService.plan(
            localUnlocked = setOf("local_only", "shared"),
            remoteUnlocked = setOf("shared", "remote_only"),
            files = listOf(file),
        )

        assertEquals(setOf("local_only"), plan.upload)
        assertEquals(listOf(file), plan.localFiles)
    }

    @Test
    fun plan_usesUnionAgainstRemoteAchievementState() {
        val files = listOf(File("STSAchievements"), File("1_STSAchievements"), File("2_STSAchievements"))
        val plan = SteamAchievementSyncService.plan(
            localUnlocked = setOf("slot_zero", "slot_one", "shared"),
            remoteUnlocked = setOf("shared", "remote_only"),
            files = files,
        )

        assertEquals(setOf("slot_zero", "slot_one"), plan.upload)
        assertEquals(files, plan.localFiles)
    }

    @Test
    fun readUnlocked_acceptsVanillaIntegerAchievementValues() {
        val file = File.createTempFile("sts-achievements", ".json")
        try {
            file.writeText("{\"shrug_it_off\":1,\"the_guardian\":0}")
            assertTrue(SteamAchievementSyncService.readUnlocked(file).contains("shrug_it_off"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun localAchievementsMissingFromSteam_usesUnionOfAllSaveSlots() {
        val root = kotlin.io.path.createTempDirectory("sts-achievement-diff").toFile()
        try {
            val files = listOf("STSAchievements", "1_STSAchievements", "2_STSAchievements")
                .map { File(root, it) }
            files[0].writeText("{\"shrug_it_off\":1}")
            files[1].writeText("{\"GUARDIAN\":1}")
            files[2].writeText("{\"shrug_it_off\":1}")

            val result = SteamAchievementSyncService.localAchievementsMissingFromSteam(
                files = files,
                remoteUnlocked = setOf("shrug_it_off"),
            )

            assertEquals(setOf("guardian"), result)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun lockAchievementInFiles_removesTargetFromAllSavesAndKeepsOtherValues() {
        val directory = kotlin.io.path.createTempDirectory("sts-achievement-lock").toFile()
        val files = listOf("STSAchievements", "1_STSAchievements", "2_STSAchievements")
            .map { File(directory, it) }
        try {
            files.forEachIndexed { index, file ->
                file.writeText("{\"shrug_it_off\":1,\"the_guardian\":${index + 1}}")
            }

            SteamAchievementSyncService.lockAchievementInFiles(
                files,
                SteamAchievementService.SHRUG_IT_OFF_API_NAME,
            )

            files.forEachIndexed { index, file ->
                val contents = file.readText()
                assertFalse(contents.contains("shrug_it_off"))
                assertTrue(contents.contains("\"the_guardian\":${index + 1}"))
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
