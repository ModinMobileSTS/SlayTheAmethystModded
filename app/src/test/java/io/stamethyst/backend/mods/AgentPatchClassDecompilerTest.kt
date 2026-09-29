package io.stamethyst.backend.mods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentPatchClassDecompilerTest {
    @Test
    fun binaryEntryPath_mapsPackagesAndRejectsUnsafeNames() {
        assertEquals(
            "com/example/Foo.class",
            AgentPatchClassDecompiler.binaryEntryPath("com.example", "Foo"),
        )
        assertEquals("Foo.class", AgentPatchClassDecompiler.binaryEntryPath(null, "Foo"))
        assertEquals("Foo.class", AgentPatchClassDecompiler.binaryEntryPath("", "Foo"))
        assertEquals(
            "com/example/Outer\$Inner.class",
            AgentPatchClassDecompiler.binaryEntryPath("com.example", "Outer\$Inner"),
        )
        assertNull(AgentPatchClassDecompiler.binaryEntryPath("com.example", "../Evil"))
        assertNull(AgentPatchClassDecompiler.binaryEntryPath("com.example", "a/b"))
        assertNull(AgentPatchClassDecompiler.binaryEntryPath("com/../evil", "Foo"))
        assertNull(AgentPatchClassDecompiler.binaryEntryPath("com.example", ""))
    }
}
