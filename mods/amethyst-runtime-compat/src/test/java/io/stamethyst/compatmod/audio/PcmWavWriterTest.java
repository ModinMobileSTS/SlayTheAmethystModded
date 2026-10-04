package io.stamethyst.compatmod.audio;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import javax.sound.sampled.*;
import java.io.*;
import java.nio.file.Files;
import java.util.Arrays;
import static org.junit.Assert.*;

public class PcmWavWriterTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private byte[] convert(AudioFormat format, byte[] data) throws Exception {
        File file = temp.newFile();
        int frames = PcmWavWriter.write(file, new AudioInputStream(new ByteArrayInputStream(data), format,
                data.length / format.getFrameSize()));
        assertEquals(data.length / format.getFrameSize(), frames);
        try (AudioInputStream decoded = AudioSystem.getAudioInputStream(file)) {
            assertEquals(16, decoded.getFormat().getSampleSizeInBits());
            assertEquals(format.getChannels(), decoded.getFormat().getChannels());
            assertEquals(format.getSampleRate(), decoded.getFormat().getSampleRate(), 0f);
            assertFalse(decoded.getFormat().isBigEndian());
        }
        byte[] wav = Files.readAllBytes(file.toPath());
        return Arrays.copyOfRange(wav, 44, wav.length);
    }

    @Test public void preservesLittleEndian16Bit() throws Exception {
        byte[] pcm = new byte[]{0, -128, -1, 127};
        assertArrayEquals(pcm, convert(new AudioFormat(22050, 16, 1, true, false), pcm));
    }
    @Test public void swapsBigEndianStereo16Bit() throws Exception {
        assertArrayEquals(new byte[]{0, -128, -1, 127}, convert(new AudioFormat(44100, 16, 2, true, true),
                new byte[]{-128, 0, 127, -1}));
    }
    @Test public void convertsUnsigned8BitToSigned16Bit() throws Exception {
        assertArrayEquals(new byte[]{0, -128, 0, 0, 0, 127}, convert(new AudioFormat(22050, 8, 1, false, false),
                new byte[]{0, -128, -1}));
    }
    @Test public void convertsSigned8BitToSigned16Bit() throws Exception {
        assertArrayEquals(new byte[]{0, -128, 0, 0, 0, 127}, convert(new AudioFormat(22050, 8, 1, true, false),
                new byte[]{-128, 0, 127}));
    }
    @Test public void convertsUnsigned16BitToSigned16Bit() throws Exception {
        assertArrayEquals(new byte[]{0, -128, 0, 0, -1, 127}, convert(new AudioFormat(22050, 16, 1, false, false),
                new byte[]{0, 0, 0, -128, -1, -1}));
    }
    @Test public void unknownRateIsOnlyAllowedForProbing() {
        AudioFormat unknown = new AudioFormat(-1, 16, 2, true, true);
        assertTrue(PcmWavWriter.supports(unknown));
        try { PcmWavWriter.validate(unknown, false); fail("Expected missing-rate rejection"); }
        catch (IllegalArgumentException expected) { }
    }
}
