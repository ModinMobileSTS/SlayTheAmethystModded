package io.stamethyst.compatmod.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/** Streams supported PCM into the 16-bit little-endian WAV format understood by old LibGDX. */
final class PcmWavWriter {
    private PcmWavWriter() { }

    static boolean supports(AudioFormat format) {
        try { validate(format, true); return true; } catch (IllegalArgumentException error) { return false; }
    }

    static void validate(AudioFormat format, boolean probe) {
        boolean signed = AudioFormat.Encoding.PCM_SIGNED.equals(format.getEncoding());
        boolean unsigned = AudioFormat.Encoding.PCM_UNSIGNED.equals(format.getEncoding());
        int bits = format.getSampleSizeInBits();
        int channels = format.getChannels();
        if ((!signed && !unsigned) || !value(bits, 8, 16, probe) || !value(channels, 1, 2, probe)
                || !rate(format.getSampleRate(), probe) || !rate(format.getFrameRate(), probe)
                || (format.getFrameSize() != AudioSystem.NOT_SPECIFIED
                    && bits != AudioSystem.NOT_SPECIFIED && channels != AudioSystem.NOT_SPECIFIED
                    && format.getFrameSize() != channels * bits / 8)
                || (!probe && format.getFrameSize() <= 0)
                || (!probe && format.getFrameRate() != format.getSampleRate())) {
            throw new IllegalArgumentException("Unsupported Clip PCM format: " + format);
        }
    }

    private static boolean value(int value, int a, int b, boolean probe) {
        return value == a || value == b || (probe && value == AudioSystem.NOT_SPECIFIED);
    }
    private static boolean rate(float rate, boolean probe) {
        return (rate > 0 && rate <= 192000 && !Float.isInfinite(rate))
                || (probe && rate == AudioSystem.NOT_SPECIFIED);
    }

    static int write(File file, AudioInputStream stream) throws IOException {
        AudioFormat format = stream.getFormat();
        validate(format, false);
        int bytesPerSample = format.getSampleSizeInBits() / 8;
        int frameSize = format.getFrameSize();
        int channels = format.getChannels();
        boolean signed = AudioFormat.Encoding.PCM_SIGNED.equals(format.getEncoding());
        byte[] input = new byte[(8192 / frameSize) * frameSize];
        byte[] output = new byte[input.length * 2 / bytesPerSample];
        long bytes = 0;
        try (RandomAccessFile wav = new RandomAccessFile(file, "rw")) {
            wav.setLength(0);
            wav.seek(44);
            int read;
            while ((read = stream.read(input)) != -1) {
                if (read == 0) throw new IOException("PCM stream made no progress");
                if (read % frameSize != 0) throw new IOException("Incomplete PCM frame");
                int n = 0;
                for (int i = 0; i < read; i += bytesPerSample) {
                    int sample;
                    if (bytesPerSample == 1) {
                        sample = (signed ? input[i] : (input[i] & 255) - 128) << 8;
                    } else {
                        int lo = input[i + (format.isBigEndian() ? 1 : 0)] & 255;
                        int hi = input[i + (format.isBigEndian() ? 0 : 1)] & 255;
                        sample = (hi << 8) | lo;
                        if (!signed) sample -= 32768;
                    }
                    output[n++] = (byte) sample;
                    output[n++] = (byte) (sample >> 8);
                }
                bytes += n;
                // Clip frame/buffer sizes are ints; reject overflow rather than corrupting WAV headers.
                if (bytes > Integer.MAX_VALUE - 44L) throw new IOException("Clip exceeds supported buffer size");
                wav.write(output, 0, n);
            }
            if (bytes == 0) throw new IOException("Clip has no PCM frames");
            wav.seek(0);
            wav.writeBytes("RIFF"); littleInt(wav, (int) bytes + 36); wav.writeBytes("WAVEfmt ");
            littleInt(wav, 16); littleShort(wav, 1); littleShort(wav, channels);
            int rate = Math.round(format.getSampleRate());
            littleInt(wav, rate); littleInt(wav, rate * channels * 2);
            littleShort(wav, channels * 2); littleShort(wav, 16);
            wav.writeBytes("data"); littleInt(wav, (int) bytes);
        }
        return (int) (bytes / (channels * 2));
    }

    private static void littleInt(RandomAccessFile out, int value) throws IOException { out.writeInt(Integer.reverseBytes(value)); }
    private static void littleShort(RandomAccessFile out, int value) throws IOException { out.writeShort(Short.reverseBytes((short) value)); }
}
