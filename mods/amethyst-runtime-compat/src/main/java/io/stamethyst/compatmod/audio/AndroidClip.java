package io.stamethyst.compatmod.audio;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.files.FileHandle;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.Control;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.LineListener;
import javax.sound.sampled.LineUnavailableException;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Java Sound Clip using the game's OpenAL device, not Android's absent Java Sound mixer. */
final class AndroidClip implements Clip {
    private final List<LineListener> listeners = new CopyOnWriteArrayList<LineListener>();
    private final GainControl gain = new GainControl();
    private final Runnable onOpen;
    private final Runnable onClose;
    private AudioFormat format;
    private int frameLength;
    private Music music;
    private File tempFile;
    private boolean running;
    private int positionFrames;
    private int remainingLoops;

    AndroidClip() {
        this(new Runnable() { @Override public void run() { } }, new Runnable() { @Override public void run() { } });
    }

    AndroidClip(Runnable onOpen, Runnable onClose) { this.onOpen = onOpen; this.onClose = onClose; }

    @Override public synchronized void open(AudioInputStream stream) throws LineUnavailableException, IOException {
        if (stream == null) throw new NullPointerException("stream");
        if (isOpen()) throw new IllegalStateException("Clip is already open");
        PcmWavWriter.validate(stream.getFormat(), false);
        File file = File.createTempFile("amethyst-clip-", ".wav");
        try {
            int frames = PcmWavWriter.write(file, stream);
            attach(file, stream.getFormat(), frames);
        } catch (IOException | RuntimeException | LineUnavailableException error) {
            file.delete();
            throw error;
        }
    }

    @Override public synchronized void open(AudioFormat audioFormat, byte[] data, int offset, int length)
            throws LineUnavailableException {
        if (audioFormat == null || data == null) throw new NullPointerException();
        PcmWavWriter.validate(audioFormat, false);
        if (offset < 0 || length < 0 || offset > data.length - length
                || length % audioFormat.getFrameSize() != 0) {
            throw new IllegalArgumentException("PCM buffer must contain complete frames within the array");
        }
        try {
            open(new AudioInputStream(new java.io.ByteArrayInputStream(data, offset, length),
                    audioFormat, length / audioFormat.getFrameSize()));
        } catch (IOException error) {
            throw unavailable(error);
        }
    }

    private void attach(File file, AudioFormat audioFormat, int frames) throws LineUnavailableException {
        if (Gdx.audio == null) throw unavailable(new IllegalStateException("LibGDX audio is not initialized"));
        Music created;
        try {
            created = Gdx.audio.newMusic(new FileHandle(file));
        } catch (RuntimeException error) {
            throw unavailable(error);
        }
        try {
            created.setVolume(gain.linearVolume());
            created.setOnCompletionListener(new Music.OnCompletionListener() {
                @Override public void onCompletion(Music completed) { complete(completed); }
            });
        } catch (RuntimeException error) {
            created.dispose();
            throw unavailable(error);
        }
        music = created;
        tempFile = file;
        format = audioFormat;
        frameLength = frames;
        positionFrames = 0;
        remainingLoops = 0;
        onOpen.run();
        fire(LineEvent.Type.OPEN);
    }

    @Override public synchronized void start() {
        requireOpen();
        if (running) return;
        music.setLooping(remainingLoops == LOOP_CONTINUOUSLY);
        // OpenALMusic.setPosition requires an allocated source: play before seeking.
        music.play();
        music.setPosition((float) (positionFrames / (double) format.getFrameRate()));
        running = true;
        fire(LineEvent.Type.START);
    }

    @Override public synchronized void stop() {
        if (!running || music == null) return;
        positionFrames = getFramePosition();
        music.pause();
        running = false;
        remainingLoops = 0;
        fire(LineEvent.Type.STOP);
    }

    @Override public synchronized void loop(int count) {
        requireOpen();
        if (count < LOOP_CONTINUOUSLY) throw new IllegalArgumentException("Invalid loop count");
        remainingLoops = count;
        music.setLooping(count == LOOP_CONTINUOUSLY);
        start();
    }

    private synchronized void complete(Music completed) {
        if (completed != music || !running) return;
        if (remainingLoops > 0) {
            remainingLoops--;
            positionFrames = 0;
            music.play();
            return;
        }
        positionFrames = frameLength;
        running = false;
        fire(LineEvent.Type.STOP);
    }

    @Override public synchronized void close() {
        if (music == null) return;
        stop();
        // A STOP listener (including YuAi's) may already have closed this clip.
        if (music == null) return;
        Music disposed = music;
        music = null;
        try {
            disposed.dispose();
        } finally {
            if (tempFile != null) { tempFile.delete(); tempFile = null; }
            onClose.run();
            fire(LineEvent.Type.CLOSE);
        }
    }

    @Override public synchronized void open() throws LineUnavailableException {
        if (!isOpen()) throw new LineUnavailableException("Clip requires audio data");
    }
    @Override public synchronized boolean isOpen() { return music != null; }
    @Override public synchronized Line.Info getLineInfo() { return new DataLine.Info(Clip.class, format); }
    @Override public void addLineListener(LineListener listener) { if (listener != null) listeners.add(listener); }
    @Override public void removeLineListener(LineListener listener) { listeners.remove(listener); }
    @Override public Control[] getControls() { return new Control[]{gain}; }
    @Override public boolean isControlSupported(Control.Type type) { return type == FloatControl.Type.MASTER_GAIN; }
    @Override public Control getControl(Control.Type type) {
        if (!isControlSupported(type)) throw new IllegalArgumentException("Unsupported control: " + type);
        return gain;
    }
    @Override public synchronized AudioFormat getFormat() { return format; }
    @Override public synchronized int getFrameLength() { return frameLength; }
    @Override public synchronized long getMicrosecondLength() { return frameMicros(frameLength); }
    @Override public synchronized int getFramePosition() {
        if (!running || music == null) return positionFrames;
        return Math.min(frameLength, Math.max(0, (int) (music.getPosition() * format.getFrameRate())));
    }
    @Override public synchronized long getLongFramePosition() { return getFramePosition(); }
    @Override public synchronized long getMicrosecondPosition() { return frameMicros(getFramePosition()); }
    @Override public synchronized void setFramePosition(int frames) {
        requireOpen();
        positionFrames = Math.min(frameLength, Math.max(0, frames));
        if (running) music.setPosition((float) (positionFrames / (double) format.getFrameRate()));
    }
    @Override public synchronized void setMicrosecondPosition(long micros) {
        requireOpen();
        setFramePosition((int) Math.min(frameLength, Math.max(0, micros * (format.getFrameRate() / 1000000d))));
    }
    @Override public synchronized void setLoopPoints(int start, int end) {
        requireOpen();
        // Music can loop the complete file only. Never silently ignore a requested partial range.
        if (start != 0 || (end != -1 && end != frameLength - 1)) {
            throw new IllegalArgumentException("Only whole-clip loop points are supported");
        }
    }
    @Override public void flush() { }
    @Override public synchronized void drain() {
        while (running) {
            try { wait(20L); } catch (InterruptedException error) { Thread.currentThread().interrupt(); return; }
        }
    }
    @Override public synchronized boolean isRunning() { return running; }
    @Override public synchronized boolean isActive() { return running && music != null && music.isPlaying(); }
    @Override public float getLevel() { return AudioSystem.NOT_SPECIFIED; }
    @Override public int available() { return 0; }
    @Override public synchronized int getBufferSize() { return format == null ? 0 : frameLength * format.getFrameSize(); }

    private void requireOpen() { if (music == null) throw new IllegalStateException("Clip is not open"); }
    private long frameMicros(int frames) { return format == null ? 0 : (long) (frames * 1000000d / format.getFrameRate()); }
    private void fire(LineEvent.Type type) {
        notifyAll();
        LineEvent event = new LineEvent(this, type, getLongFramePosition());
        for (LineListener listener : listeners) {
            try { listener.update(event); } catch (RuntimeException error) {
                System.err.println("[Amethyst Java Sound] Line listener failed: " + error);
            }
        }
    }
    private static LineUnavailableException unavailable(Exception cause) {
        LineUnavailableException error = new LineUnavailableException("Unable to create LibGDX Clip: " + cause);
        error.initCause(cause);
        return error;
    }

    private final class GainControl extends FloatControl {
        GainControl() { super(FloatControl.Type.MASTER_GAIN, -80f, 0f, 0.1f, 0, 0f, "dB"); }
        @Override public void setValue(float value) {
            synchronized (AndroidClip.this) {
                super.setValue(value);
                if (music != null) music.setVolume(linearVolume());
            }
        }
        float linearVolume() { return getValue() <= getMinimum() ? 0f : (float) Math.pow(10d, getValue() / 20d); }
    }
}
