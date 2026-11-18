package io.stamethyst.ui.main

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offline structural guards; actual Compose navigation is covered by instrumentation tests. */
class ModCommunityBadgeWiringTest {
    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "app/src/main/java").isDirectory }

    private fun source(name: String) =
        File(root, "app/src/main/java/io/stamethyst/ui/main/$name.kt").readText()

    @Test fun cardBadgeOpensDetailsInsteadOfTheCatalogueDialog() {
        val card = source("ModCardItem")
        val badge = card.substringAfter("onOpenAgentPatchCatalogue = {")
            .substringBefore("onOpenAgentPatchContributions =")
        assertTrue(badge.contains("if (!batchSelectionMode) openDetails(community = true)"))
        assertFalse(badge.contains("showAgentPatchCatalogue = true"))
        val openDetails = card.substringAfter("fun openDetails(").substringBefore("val cardShape")
        assertTrue(openDetails.contains("detailsStartAtAi = community"))
        assertTrue(openDetails.contains("detailsCommunitySelected = community"))
        assertTrue(openDetails.contains("showDetailsDialog = true"))
        assertTrue(card.contains("startAtAi = detailsStartAtAi"))
        assertTrue(source("ModDetailsDialog").contains("if (startAtAi) 2 else if (startAtActions) 0 else 1"))
    }

    @Test fun detailsCommunityRelationSelectsCommunityBeforeNavigatingToAi() {
        val card = source("ModCardItem")
        val relation = card.substringAfter("onCatalogue = {").substringBefore("onContributions =")
        assertTrue(relation.contains("detailsCommunitySelected = true"))
        assertTrue(relation.contains("onScrollToAi()"))
        assertFalse(relation.contains("openCatalogue()"))
        assertTrue(card.contains("communitySelected = detailsCommunitySelected"))
        assertTrue(card.contains("onCommunitySelected = { detailsCommunitySelected = it }"))
        val section = source("ModDetailsAiSection")
        assertTrue(section.contains("onCommunitySelected(false)"))
        assertTrue(section.contains("onCommunitySelected(true)"))
    }

    @Test fun disabledParentHintIsRemovedWithoutRestrictingPatchControls() {
        val section = source("ModDetailsAiSection")
        assertFalse(section.contains("mod_details_ai_parent_disabled"))
        assertFalse(section.contains("mod.enabled"))
        assertTrue(section.contains("enabled = controlsEnabled"))
        listOf("values", "values-zh-rCN", "values-zh-rTW").forEach { locale ->
            val strings = File(root, "app/src/main/res/$locale/mod_details_strings.xml").readText()
            assertFalse(strings.contains("mod_details_ai_parent_disabled"))
        }
    }
}
