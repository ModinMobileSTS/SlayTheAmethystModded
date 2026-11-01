package io.stamethyst.backend.mods

import org.junit.Assert.assertEquals
import org.junit.Test

class ModSelectionReplacementsTest {
    @Test
    fun enabledSelectionsFollowReplacementWhileDisabledModsStayDisabled() {
        val installed = setOf("alpha-new.jar", "beta.jar", "gamma-new.jar")
        val replacements = mapOf("alpha.jar" to "alpha-new.jar", "gamma.jar" to "gamma-new.jar")
        assertEquals(setOf("alpha-new.jar", "beta.jar"), rebindReplacedModSelection(setOf("alpha.jar", "beta.jar"), installed, replacements))
        assertEquals(emptySet<String>(), rebindReplacedModSelection(emptySet(), installed, replacements))
    }

    @Test
    fun successiveUpdatesFollowAliasesButNeverRemapAnInstalledKey() {
        val replacements = mapOf("old.jar" to "middle.jar", "middle.jar" to "latest.jar", "live.jar" to "latest.jar")
        assertEquals(
            setOf("latest.jar", "live.jar"),
            rebindReplacedModSelection(setOf("old.jar", "live.jar"), setOf("latest.jar", "live.jar"), replacements),
        )
    }

    @Test
    fun corruptAliasCyclesTerminate() {
        assertEquals(setOf("a.jar"), rebindReplacedModSelection(setOf("a.jar"), emptySet(), mapOf("a.jar" to "b.jar", "b.jar" to "a.jar")))
    }
}
