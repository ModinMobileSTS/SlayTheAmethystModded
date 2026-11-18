package io.stamethyst

import android.view.KeyEvent
import net.kdt.pojavlaunch.LwjglGlfwKeycode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FloatingConsoleShortcutTest {
    @Test
    fun punctuationPickerOffersEverySymbolSeparatelyAndLeavesCommonUnchanged() {
        val punctuation = FloatingMouseOverlayController.punctuationCustomSoftKeys()
        assertEquals("`~-_=+[{]}\\|;:'\",<.>/?".map(Char::toString), punctuation.map { it.pickerLabel })
        assertEquals(punctuation.map { it.pickerLabel }, punctuation.map { it.buttonLabel })
        assertEquals(punctuation.map { it.pickerLabel.single() }, punctuation.map { it.symbol })
        assertFalse(punctuation.any { it.toggleable })
        assertEquals(
            listOf("Ctrl", "Shift", "Alt", "Tab", "Esc", "Enter", "Space", "Backspace"),
            FloatingMouseOverlayController.commonCustomSoftKeys().map { it.pickerLabel },
        )
    }

    @Test
    fun tildeUsesGraveWithoutRequiringShiftAndOtherAlternatesUseShift() {
        val punctuation = FloatingMouseOverlayController.punctuationCustomSoftKeys()
        assertEquals(
            listOf(KeyEvent.KEYCODE_GRAVE, KeyEvent.KEYCODE_GRAVE),
            punctuation.filter { it.symbol in listOf('`', '~') }.map { it.keyCode },
        )
        val shift = LwjglGlfwKeycode.GLFW_MOD_SHIFT
        val ctrl = LwjglGlfwKeycode.GLFW_MOD_CONTROL
        punctuation.chunked(2).forEach { (base, alternate) ->
            assertEquals(base.keyCode, alternate.keyCode)
            assertEquals(ctrl, FloatingMouseOverlayController.floatingSymbolModifiers(base.symbol!!, ctrl))
            assertEquals(
                if (alternate.symbol == '~') ctrl else ctrl or shift,
                FloatingMouseOverlayController.floatingSymbolModifiers(alternate.symbol!!, ctrl),
            )
        }
    }

    @Test
    fun savedBacktickShortcutKeepsItsLabelsKeyAndPosition() {
        // v1 record from the old Symbols picker: "` / ~" option and "`" button.
        val layout = "1;68,0,0.25,0.75,YCAvIH4=,YA=="
        val stored = decodeFloatingCustomSoftKeyLayout(layout).single()

        assertEquals("` / ~", stored.pickerLabel)
        assertEquals("`", stored.buttonLabel)
        assertEquals(KeyEvent.KEYCODE_GRAVE, stored.keyCode)
        assertFalse(stored.toggleable)
        assertEquals(null, stored.symbol)
        assertEquals(0.25f, stored.leftFraction, 0f)
        assertEquals(0.75f, stored.topFraction, 0f)
        assertEquals(layout, encodeFloatingCustomSoftKeyLayout(listOf(stored)))
    }
}
