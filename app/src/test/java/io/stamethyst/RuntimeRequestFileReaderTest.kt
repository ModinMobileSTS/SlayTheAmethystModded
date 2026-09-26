package io.stamethyst

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeRequestFileReaderTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun unchangedFileIsNotReadAgainDuringFallback() {
        val file = temporaryFolder.newFile(".keyboard_request")
        val reader = RuntimeRequestFileReader()
        file.writeText("first\n")

        assertEquals("first", reader.readChanged(file, force = false))
        assertEquals("", reader.readChanged(file, force = false))

        file.writeText("second\n")
        assertEquals("second", reader.readChanged(file, force = false))
    }

    @Test fun fileEventReadsRewriteWithSameSizeAndTimestamp() {
        val file = temporaryFolder.newFile(".keyboard_request")
        val reader = RuntimeRequestFileReader()
        file.writeText("first\n")
        val modifiedAt = file.lastModified()
        assertEquals("first", reader.readChanged(file, force = false))

        file.writeText("other\n")
        assertTrue(file.setLastModified(modifiedAt))
        assertEquals("", reader.readChanged(file, force = false))
        assertEquals("other", reader.readChanged(file, force = true))
    }

    @Test fun deletionAndSessionResetInvalidateSnapshot() {
        val file = temporaryFolder.newFile(".keyboard_request")
        val reader = RuntimeRequestFileReader()
        file.writeText("first\n")
        assertEquals("first", reader.readChanged(file, force = false))
        assertTrue(file.delete())
        assertEquals("", reader.readChanged(file, force = false))

        file.writeText("other\n")
        assertEquals("other", reader.readChanged(file, force = false))
        reader.clear()
        assertEquals("other", reader.readChanged(file, force = false))
    }
}
