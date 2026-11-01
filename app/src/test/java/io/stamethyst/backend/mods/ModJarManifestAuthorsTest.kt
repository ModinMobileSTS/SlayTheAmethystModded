package io.stamethyst.backend.mods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModJarManifestAuthorsTest {
    @Test fun standardAuthorListIsTrimmedAndDeduplicated() {
        val manifest = ModJarManifestParser.parseManifest(
            """{"modid":"test","author_list":[" Alice ","Bob","Alice","",null,42,{}]}"""
        )!!
        assertEquals(listOf("Alice", "Bob"), manifest.authors)
    }

    @Test fun legacyAuthorKeysAreCaseInsensitiveAndSupportSingleStrings() {
        val manifest = ModJarManifestParser.parseManifest(
            """{"modid":"test","AUTHOR_LIST":["Alice"],"Authors":["Bob"],"author":"Carol"}"""
        )!!
        assertEquals(listOf("Alice", "Bob", "Carol"), manifest.authors)
    }

    @Test fun rootArrayAndAuthorListAliasAreSupported() {
        val manifest = ModJarManifestParser.parseManifest(
            """[{"modid":"test","authorList":["Alice, Jr.","Bob"]}]"""
        )!!
        assertEquals(listOf("Alice, Jr.", "Bob"), manifest.authors)
    }

    @Test fun absentOrInvalidAuthorsRemainEmptyWithoutAffectingMetadata() {
        val manifest = ModJarManifestParser.parseManifest(
            """{"modid":"test","name":"Test","version":"1.2","authors":{"name":"Alice"}}"""
        )!!
        assertTrue(manifest.authors.isEmpty())
        assertEquals("Test", manifest.name)
        assertEquals("1.2", manifest.version)
    }
}
