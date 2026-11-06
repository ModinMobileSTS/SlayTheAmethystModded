package io.stamethyst.godot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class LegacyGeckoSlingBreakSaveTest {
    @Test public void readsUtf8AndUtf16AndChecksCharacterCount() throws Exception {
        String text = "{\"存档\":\"🌙\"}";
        assertEquals(text, LegacyGeckoSlingBreakSave.decode(text.getBytes(StandardCharsets.UTF_8), text.length(), 1, 0));
        assertEquals(text, LegacyGeckoSlingBreakSave.decode(text.getBytes(StandardCharsets.UTF_16LE), text.length(), 0, 0));
        assertThrows(IOException.class, () -> LegacyGeckoSlingBreakSave.decode(text.getBytes(StandardCharsets.UTF_8), 1, 1, 0));
        assertThrows(IOException.class, () -> LegacyGeckoSlingBreakSave.decode(new byte[]{(byte) 0xff}, 1, 1, 0));
    }
    @Test public void decodesSnappyLiteralsAndOverlappingCopies() throws Exception {
        // 6 output bytes: literal "ab", then COPY_1 repeats the last 2 bytes four times.
        assertEquals("ababab", LegacyGeckoSlingBreakSave.decode(new byte[]{6, 4, 'a', 'b', 1, 2}, 6, 1, 1));
        assertEquals("hello", LegacyGeckoSlingBreakSave.decode(new byte[]{5, 16, 'h', 'e', 'l', 'l', 'o'}, 5, 1, 1));
        // COPY_2 and COPY_4 encodings.
        assertEquals("abab", LegacyGeckoSlingBreakSave.decode(new byte[]{4, 4, 'a', 'b', 6, 2, 0}, 4, 1, 1));
        assertEquals("abab", LegacyGeckoSlingBreakSave.decode(new byte[]{4, 4, 'a', 'b', 7, 2, 0, 0, 0}, 4, 1, 1));
    }
    @Test public void rejectsCorruptionUnknownEncodingAndExpansionBombs() {
        for (byte[] invalid : new byte[][]{
            {}, {6, 1, 0}, {1, 0}, {2, 0, 'a'}, {0, 0, 'a'},
            {(byte) 0xff, (byte) 0xff, (byte) 0xff, 0x7f}
        }) assertThrows(IOException.class, () -> LegacyGeckoSlingBreakSave.decode(invalid, 1, 1, 1));
        assertThrows(IOException.class, () -> LegacyGeckoSlingBreakSave.decode(new byte[0], 0, 2, 0));
        assertThrows(IOException.class, () -> LegacyGeckoSlingBreakSave.decode(new byte[0], 0, 0, 2));
        assertThrows(IOException.class, () -> LegacyGeckoSlingBreakSave.decode(new byte[0], Integer.MAX_VALUE, 0, 0));
    }
}
