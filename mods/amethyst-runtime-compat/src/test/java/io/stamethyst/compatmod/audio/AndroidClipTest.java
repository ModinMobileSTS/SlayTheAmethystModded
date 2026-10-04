package io.stamethyst.compatmod.audio;

import com.badlogic.gdx.Audio;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.files.FileHandle;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.sound.sampled.*;
import java.io.File;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class AndroidClipTest {
    private Audio previousAudio;
    private Audio fakeAudio;
    private FakeMusic music;
    private File wav;
    private AndroidClip clip;
    private final AudioFormat format = new AudioFormat(22050, 16, 1, true, false);

    @Before public void setup() {
        previousAudio = Gdx.audio;
        fakeAudio = (Audio) Proxy.newProxyInstance(Audio.class.getClassLoader(), new Class<?>[]{Audio.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("newMusic")) {
                        wav = ((FileHandle) args[0]).file();
                        music = new FakeMusic();
                        return music;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        Gdx.audio = fakeAudio;
        clip = new AndroidClip();
    }

    @After public void cleanup() { clip.close(); Gdx.audio = previousAudio; }

    private void open() throws Exception { clip.open(format, new byte[44100], 0, 44100); }

    @Test public void continuousLoopSurvivesStartAndVolumeIsNotReset() throws Exception {
        open();
        FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
        gain.setValue(-20f);
        clip.loop(Clip.LOOP_CONTINUOUSLY);
        assertTrue(music.looping);
        assertEquals(0.1f, music.volume, 0.00001f);
        clip.start();
        assertEquals(1, music.plays);
        gain.setValue(gain.getMinimum());
        assertEquals(0f, music.volume, 0f);
    }

    @Test public void seekPauseResumeAndCloseTrackRealPlayback() throws Exception {
        List<LineEvent.Type> events = new ArrayList<LineEvent.Type>();
        clip.addLineListener(event -> events.add(event.getType()));
        open();
        File file = wav;
        clip.setFramePosition(11025);
        clip.loop(Clip.LOOP_CONTINUOUSLY);
        assertEquals(0.5f, music.position, 0f);
        assertTrue(music.seekAfterPlay);
        music.position = 0.75f;
        clip.stop();
        assertEquals(16537, clip.getFramePosition());
        assertFalse(clip.isRunning());
        clip.start();
        assertEquals(0.75f, music.position, 0.0001f);
        assertFalse(music.looping);
        clip.close();
        assertTrue(music.disposed);
        assertFalse(file.exists());
        assertFalse(clip.isOpen());
        assertEquals(java.util.Arrays.asList(LineEvent.Type.OPEN, LineEvent.Type.START, LineEvent.Type.STOP,
                LineEvent.Type.START, LineEvent.Type.STOP, LineEvent.Type.CLOSE), events);
    }

    @Test public void finiteLoopCountAndCompletionAreHonored() throws Exception {
        open();
        List<LineEvent.Type> events = new ArrayList<LineEvent.Type>();
        clip.addLineListener(event -> events.add(event.getType()));
        clip.loop(2);
        assertFalse(music.looping);
        music.finish();
        music.finish();
        assertTrue(clip.isRunning());
        assertEquals(3, music.plays);
        music.finish();
        assertFalse(clip.isRunning());
        assertEquals(clip.getFrameLength(), clip.getFramePosition());
        assertEquals(java.util.Arrays.asList(LineEvent.Type.START, LineEvent.Type.STOP), events);
    }

    @Test public void reopenResetsPositionAndLoops() throws Exception {
        open(); clip.loop(Clip.LOOP_CONTINUOUSLY); music.position = 0.5f; clip.close();
        open(); clip.start();
        assertEquals(0, clip.getFramePosition());
        assertFalse(music.looping);
    }

    @Test public void seekingToZeroWhilePausedResetsBackendOnResume() throws Exception {
        open(); clip.start(); music.position = 0.8f; clip.stop();
        clip.setFramePosition(0); clip.start();
        assertEquals(0f, music.position, 0f);
    }

    @Test public void stopListenerCanCloseClipReentrantlyLikeYuAi() throws Exception {
        open();
        clip.addLineListener(event -> { if (event.getType() == LineEvent.Type.STOP) clip.close(); });
        clip.loop(Clip.LOOP_CONTINUOUSLY);
        clip.close();
        assertFalse(clip.isOpen());
        assertTrue(music.disposed);
        assertFalse(wav.exists());
    }

    @Test public void failedBackendSetupDisposesMusicAndDeletesFile() throws Exception {
        Gdx.audio = (Audio) Proxy.newProxyInstance(Audio.class.getClassLoader(), new Class<?>[]{Audio.class},
                (proxy, method, args) -> {
                    wav = ((FileHandle) args[0]).file();
                    music = new FakeMusic() { @Override public void setVolume(float volume) { throw new IllegalStateException("test"); } };
                    return music;
                });
        try { open(); fail("Expected backend error"); } catch (LineUnavailableException expected) { }
        assertTrue(music.disposed);
        assertFalse(wav.exists());
        assertFalse(clip.isOpen());
    }

    @Test public void twoClipsAreIndependentAndSourceListOnlyContainsOpenedLines() throws Exception {
        Mixer mixer = new AndroidMixerProvider().getMixer(null);
        Clip first = (Clip) mixer.getLine(new Line.Info(Clip.class));
        Clip second = (Clip) mixer.getLine(new Line.Info(Clip.class));
        try {
            assertNotSame(first, second);
            assertEquals(0, mixer.getSourceLines().length);
            first.open(format, new byte[4], 0, 4);
            assertEquals(1, mixer.getSourceLines().length);
            second.open(format, new byte[4], 0, 4);
            first.close();
            assertTrue(second.isOpen());
            assertEquals(1, mixer.getSourceLines().length);
        } finally { mixer.close(); }
    }

    @Test public void javaSoundSpiFindsClipWithDefaultUnknownRateProbe() throws Exception {
        ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
        String oldProperty = System.getProperty("javax.sound.sampled.Clip");
        Clip discovered = null;
        try {
            JavaSoundBootstrap.initialize();
            assertTrue(AudioSystem.isLineSupported(new DataLine.Info(Clip.class,
                    new AudioFormat(AudioSystem.NOT_SPECIFIED, 16, 2, true, true))));
            discovered = AudioSystem.getClip();
            assertTrue(discovered instanceof AndroidClip);
            discovered.open(format, new byte[4], 0, 4);
        } finally {
            if (discovered != null) discovered.close();
            Thread.currentThread().setContextClassLoader(oldLoader);
            if (oldProperty == null) System.clearProperty("javax.sound.sampled.Clip");
            else System.setProperty("javax.sound.sampled.Clip", oldProperty);
        }
    }

    @Test public void unsupportedFormatsAndPartialLoopsAreRejected() throws Exception {
        Mixer mixer = new AndroidMixer();
        assertFalse(mixer.isLineSupported(new DataLine.Info(Clip.class, new AudioFormat(44100, 24, 2, true, false))));
        assertFalse(mixer.isLineSupported(new Line.Info(SourceDataLine.class)));
        open();
        clip.setLoopPoints(0, -1);
        try { clip.setLoopPoints(1, -1); fail("Expected partial-loop rejection"); } catch (IllegalArgumentException expected) { }
        try { clip.open(format, new byte[4], 0, 4); fail("Expected already-open rejection"); } catch (IllegalStateException expected) { }
    }

    private static class FakeMusic implements Music {
        boolean playing, looping, disposed, seekAfterPlay;
        float volume, position;
        int plays;
        OnCompletionListener completion;
        void finish() { playing = false; position = 0; completion.onCompletion(this); }
        @Override public void play() { playing = true; plays++; }
        @Override public void pause() { playing = false; }
        @Override public void stop() { playing = false; position = 0; }
        @Override public boolean isPlaying() { return playing; }
        @Override public void setLooping(boolean looping) { this.looping = looping; }
        @Override public boolean isLooping() { return looping; }
        @Override public void setVolume(float volume) { this.volume = volume; }
        @Override public float getVolume() { return volume; }
        @Override public void setPan(float pan, float volume) { this.volume = volume; }
        @Override public void setPosition(float position) { this.position = position; seekAfterPlay = playing; }
        @Override public float getPosition() { return position; }
        @Override public void dispose() { disposed = true; playing = false; }
        @Override public void setOnCompletionListener(OnCompletionListener listener) { completion = listener; }
    }
}
