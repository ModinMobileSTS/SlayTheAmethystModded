package io.stamethyst

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingCustomSoftKeyPersistenceTest {
    @Test
    fun encodeAndDecode_preservesOrderDuplicatesAndPositions() {
        val buttons = listOf(
            FloatingCustomSoftKeyStored(
                pickerLabel = "Left Shift",
                buttonLabel = "LShift",
                keyCode = 59,
                toggleable = true,
                leftFraction = 0.25f,
                topFraction = 0.75f,
            ),
            FloatingCustomSoftKeyStored(
                pickerLabel = "Esc",
                buttonLabel = "Esc",
                keyCode = 111,
                toggleable = false,
                leftFraction = 0.25f,
                topFraction = 0.75f,
            ),
            FloatingCustomSoftKeyStored(
                pickerLabel = "Esc",
                buttonLabel = "Esc",
                keyCode = 111,
                toggleable = false,
                leftFraction = 0.8f,
                topFraction = 0.1f,
            ),
        )

        assertEquals(buttons, decodeFloatingCustomSoftKeyLayout(encodeFloatingCustomSoftKeyLayout(buttons)))
    }

    @Test
    fun decode_ignoresMalformedRecordsWithoutDroppingValidRecords() {
        val valid = FloatingCustomSoftKeyStored(
            pickerLabel = "Enter",
            buttonLabel = "Enter",
            keyCode = 66,
            toggleable = false,
            leftFraction = 0.5f,
            topFraction = 0.5f,
        )
        val encoded = encodeFloatingCustomSoftKeyLayout(listOf(valid))

        val decoded = decodeFloatingCustomSoftKeyLayout("$encoded;broken")

        assertEquals(listOf(valid), decoded)
    }

    @Test
    fun roundTripPreservesSplitSymbolsAlongsideOldKeys() {
        val split = FloatingMouseOverlayController.punctuationCustomSoftKeys().map { key ->
            FloatingCustomSoftKeyStored(key.pickerLabel, key.buttonLabel, key.keyCode, false, 0.2f, 0.3f, key.symbol)
        }
        val old = FloatingCustomSoftKeyStored("Tab", "Tab", 61, false, 0.1f, 0.4f)
        val buttons = listOf(old) + split
        assertEquals(buttons, decodeFloatingCustomSoftKeyLayout(encodeFloatingCustomSoftKeyLayout(buttons)))
    }

    @Test
    fun decode_rejectsUnknownVersionAndEmptyLayout() {
        assertTrue(decodeFloatingCustomSoftKeyLayout(null).isEmpty())
        assertTrue(decodeFloatingCustomSoftKeyLayout("").isEmpty())
        assertTrue(decodeFloatingCustomSoftKeyLayout("2;anything").isEmpty())
    }
}
