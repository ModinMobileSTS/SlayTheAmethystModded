package io.stamethyst.backend.mods

import io.stamethyst.ui.main.communityItemKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class AgentPatchCatalogueServiceTest {
    @Test
    fun uploadTargetOmitsMissingOrZeroWorkshopId() {
        for (id in listOf(null, 0uL)) {
            val target = AgentPatchCatalogueService.uploadTarget("LocalMod", id, listOf(" "))
            assertEquals("LocalMod", target.getString("modId"))
            assertTrue(!target.has("workshopId"))
            assertTrue(!JSONObject(target.toString()).has("workshopId"))
            assertEquals("*", target.getJSONArray("versions").getString(0))
        }
    }

    @Test
    fun uploadTargetPreservesWorkshopIdAndNormalizesVersions() {
        val target = AgentPatchCatalogueService.uploadTarget("BaseMod", 1605833019uL, listOf(" 1.0 ", "1.0", ""))
        assertEquals("1605833019", target.getString("workshopId"))
        assertEquals("BaseMod", target.getString("modId"))
        assertEquals(1, target.getJSONArray("versions").length())
        assertEquals("1.0", target.getJSONArray("versions").getString(0))
    }

    @Test
    fun parseForParentFiltersAndSortsValidPatches() {
        val json = index(
            patch("Later", "2.0.0", "amethyst_later", 20),
            patch("Earlier", "1.0.0", "amethyst_earlier", 10),
            patch("Example", "9.9.9", "amethyst_example", 10).put("example", true),
            patch("Wrong parent", "1.0.0", "amethyst_wrong", 10, modId = "other"),
            patch("Bad URL", "1.0.0", "amethyst_bad_url", 10, url = "http://raw.githubusercontent.com/a/b.jar"),
            patch("Bad hash", "1.0.0", "amethyst_bad_hash", 10, hash = "nope"),
        )

        val result = AgentPatchCatalogueService.parseForParent(json.toByteArray(), "basemod")

        assertEquals(listOf("Earlier", "Later"), result.patches.map { it.name })
        assertEquals(20L, result.patches.last().size)
        assertEquals("*", result.patches.first().targetVersions.single())
        assertEquals("Earlier Author", result.patches.first().authorName)
        assertTrue(result.patches.none { it.patchModId == "amethyst_example" })
        assertTrue(!result.fromCache)
    }

    @Test
    fun renamedVersionsSharingAModIdRemainDistinctCatalogueItems() {
        val patchId = "amethyst.ai.patch.chronomuncher.patch-1793399884157-536381f1"
        val original = patch("信徒中文补丁", "3.7.0", patchId, 10)
        val renamed = patch("时序·信徒中文补丁", "3.7.1", patchId, 20, hash = "b".repeat(64))
        val bytes = index(original, renamed).toByteArray()

        val parent = AgentPatchCatalogueService.parseForParent(bytes, "basemod")
        val mod = AgentPatchCatalogueService.parseMods(bytes).single()

        for (patches in listOf(parent.patches, mod.patches)) {
            assertEquals(setOf("3.7.0", "3.7.1"), patches.map { it.version }.toSet())
            assertEquals(listOf(patchId, patchId), patches.map { it.patchModId })
            assertEquals(2, patches.map { it.communityItemKey() }.toSet().size)
        }
    }

    @Test
    fun parseForParentReturnsEmptyForMissingParentAndRejectsUnsupportedSchema() {
        assertTrue(AgentPatchCatalogueService.parseForParent(index().toByteArray(), "missing").patches.isEmpty())
        val error = runCatching {
            AgentPatchCatalogueService.parseForParent("{\"schemaVersion\":2}".toByteArray(), "basemod")
        }.exceptionOrNull()
        assertTrue(error is java.io.IOException)
    }

    @Test
    fun parseParentModIdsIncludesOnlyModsWithNonExamplePatches() {
        val populated = JSONObject()
            .put("schemaVersion", 3)
            .put("mods", JSONObject()
                .put("basemod", JSONObject().put("patches", JSONArray()
                    .put(patch("Example", "1.0", "example_patch", 10).put("example", true))))
                .put("sts_lib", JSONObject().put("patches", JSONArray()
                    .put(patch("Useful", "1.0", "useful_patch", 10, modId = "sts_lib")))))

        assertEquals(setOf("sts_lib"), AgentPatchCatalogueService.parseParentModIds(populated.toString().toByteArray()))
        assertTrue(AgentPatchCatalogueService.parseParentModIds("{bad json".toByteArray()).isEmpty())
    }

    @Test
    fun parseModsUsesCatalogueEntriesAndCountsValidatedPatches() {
        val root = JSONObject(index(
            patch("Useful", "1.0", "useful_patch", 10),
            patch("Example", "1.0", "example_patch", 10).put("example", true),
        ))
        root.getJSONObject("mods").getJSONObject("basemod")
            .put("name", "BaseMod").put("count", 999)
        val result = AgentPatchCatalogueService.parseMods(root.toString().toByteArray())
        assertEquals(1, result.size)
        assertEquals("basemod", result.single().modId)
        assertEquals("BaseMod", result.single().name)
        assertEquals("1605833019", result.single().workshopId.toString())
        assertEquals(1, result.single().patches.size)
    }

    @Test
    fun parseModsHandlesMissingWorkshopIdsAndExcludesEmptyMods() {
        val noWorkshop = patch("Useful", "1.0", "useful_patch", 10)
        noWorkshop.getJSONObject("target").remove("workshopId")
        val root = JSONObject(index(noWorkshop))
        val mods = root.getJSONObject("mods")
        mods.getJSONObject("basemod").remove("workshopId")
        mods.put("empty", JSONObject().put("patches", JSONArray()))
        mods.put("examples", JSONObject().put("patches", JSONArray().put(
            patch("Example", "1.0", "example_patch", 10, modId = "examples").put("example", true)
        )))
        val result = AgentPatchCatalogueService.parseMods(root.toString().toByteArray())
        assertEquals(listOf("basemod"), result.map { it.modId })
        assertEquals("basemod", result.single().name)
        assertEquals(null, result.single().workshopId)
        assertEquals("", result.single().patches.single().workshopId)
        assertEquals(setOf("basemod"), AgentPatchCatalogueService.parseParentModIds(root.toString().toByteArray()))
    }

    @Test
    fun parseModsFallsBackToPatchTargetWorkshopId() {
        val root = JSONObject(index(patch("Useful", "1.0", "useful_patch", 10)))
        root.getJSONObject("mods").getJSONObject("basemod").put("workshopId", "invalid")
        assertEquals("1605833019", AgentPatchCatalogueService.parseMods(root.toString().toByteArray()).single().workshopId.toString())
    }

    private fun index(vararg entries: JSONObject): String = JSONObject()
        .put("schemaVersion", 3)
        .put("count", entries.size)
        .put("mods", JSONObject().put("basemod", JSONObject()
            .put("workshopId", "1605833019")
            .put("patches", JSONArray(entries))))
        .toString()

    private fun patch(
        name: String,
        version: String,
        patchModId: String,
        size: Long,
        modId: String = "basemod",
        url: String = "https://raw.githubusercontent.com/ModinMobileSTS/SlaytheAmethystPlayerModCatalogue/main/patch.jar",
        hash: String = "a".repeat(64),
    ) = JSONObject()
        .put("name", name)
        .put("description", "Description for $name")
        .put("version", version)
        .put("author", JSONObject().put("name", "$name Author"))
        .put("patchModId", patchModId)
        .put("jar", "patch.jar")
        .put("download_url", url)
        .put("size", size)
        .put("sha256", hash)
        .put("target", JSONObject()
            .put("modId", modId)
            .put("workshopId", "1605833019")
            .put("versions", JSONArray().put("*")))
}
