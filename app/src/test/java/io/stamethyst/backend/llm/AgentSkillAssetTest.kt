package io.stamethyst.backend.llm

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AgentSkillAssetTest {
    private fun asset(path: String): File? = listOf(
        File("src/main/assets/$path"),
        File("app/src/main/assets/$path"),
    ).firstOrNull(File::isFile)

    @Test
    fun everyRegisteredSkillHasAShippedAsset() {
        assertTrue("at least one skill must be registered", AgentSkills.names().isNotEmpty())
        AgentSkills.names().forEach { name ->
            val skill = AgentSkills.get(name)
            assertNotNull("skill $name must resolve", skill)
            assertNotNull("skill $name asset must ship: ${skill!!.assetPath}", asset(skill.assetPath))
        }
    }

    @Test
    fun basemodSkillCoversTheFactsTheAgentNeeds() {
        val skill = AgentSkills.get(AgentSkills.BASEMOD_AND_STSLIB)
        assertNotNull(skill)
        val skillFile = asset(skill!!.assetPath)
        assertNotNull(skillFile)
        val content = skillFile!!.readText()

        // Registration lifecycle and ordering.
        assertTrue(content.contains("@SpireInitializer"))
        assertTrue(content.contains("receiveEditStrings"))
        assertTrue(content.contains("receivePostInitialize"))
        // Content routing.
        assertTrue(content.contains("BaseMod.addCard"))
        assertTrue(content.contains("addRelicToCustomPool"))
        assertTrue(content.contains("BaseMod.addColor"))
        // The crash-causing pitfalls.
        assertTrue(content.contains("RelicStrings"))
        assertTrue(content.contains("MAX_HAND_SIZE"))
        assertTrue(content.contains("unsubscribeLater"))
        // StSLib surface.
        assertTrue(content.contains("stslib:"))
        assertTrue(content.contains("ExhaustiveVariable"))
        assertTrue(content.contains("ClickableRelic"))
        // It must point at the tool it relies on for exact signatures.
        assertTrue(content.contains("describe_agent_api_class"))
    }

    @Test
    fun unknownSkillDoesNotResolve() {
        assertTrue(AgentSkills.get("does-not-exist") == null)
    }
}
