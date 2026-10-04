package io.stamethyst.compatmod.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.Control;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.LineListener;
import javax.sound.sampled.Mixer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

final class AndroidMixer implements Mixer {
    private static final Mixer.Info INFO = new AndroidMixerInfo();
    private static final DataLine.Info CLIP_INFO = new DataLine.Info(Clip.class, formats(), 0, AudioSystem.NOT_SPECIFIED);
    private final List<Line> clips = new CopyOnWriteArrayList<Line>();
    private final List<LineListener> listeners = new CopyOnWriteArrayList<LineListener>();
    private boolean open;

    private static AudioFormat[] formats() {
        AudioFormat[] formats = new AudioFormat[16];
        int i = 0;
        for (AudioFormat.Encoding encoding : new AudioFormat.Encoding[]{AudioFormat.Encoding.PCM_SIGNED, AudioFormat.Encoding.PCM_UNSIGNED}) {
            for (int channels : new int[]{1, 2}) for (int bits : new int[]{8, 16}) for (boolean big : new boolean[]{false, true}) {
                formats[i++] = new AudioFormat(encoding, AudioSystem.NOT_SPECIFIED, bits, channels,
                        channels * bits / 8, AudioSystem.NOT_SPECIFIED, big);
            }
        }
        return formats;
    }

    static Mixer.Info info() { return INFO; }
    @Override public Info getMixerInfo() { return INFO; }
    @Override public Line.Info getLineInfo() { return new Line.Info(Mixer.class); }
    @Override public Line.Info[] getSourceLineInfo() { return new Line.Info[]{CLIP_INFO}; }
    @Override public Line.Info[] getSourceLineInfo(Line.Info info) { return isLineSupported(info) ? getSourceLineInfo() : new Line.Info[0]; }
    @Override public Line.Info[] getTargetLineInfo() { return new Line.Info[0]; }
    @Override public Line.Info[] getTargetLineInfo(Line.Info info) { return new Line.Info[0]; }
    @Override public boolean isLineSupported(Line.Info info) {
        if (info == null || info.getLineClass() != Clip.class) return false;
        if (info instanceof DataLine.Info) {
            for (AudioFormat format : ((DataLine.Info) info).getFormats()) if (!PcmWavWriter.supports(format)) return false;
        }
        return true;
    }
    @Override public Line getLine(Line.Info info) {
        if (!isLineSupported(info)) throw new IllegalArgumentException("Unsupported Clip line: " + info);
        final AndroidClip[] holder = new AndroidClip[1];
        holder[0] = new AndroidClip(
                new Runnable() { @Override public void run() { clips.add(holder[0]); } },
                new Runnable() { @Override public void run() { clips.remove(holder[0]); } });
        return holder[0];
    }
    @Override public int getMaxLines(Line.Info info) { return isLineSupported(info) ? AudioSystem.NOT_SPECIFIED : 0; }
    @Override public synchronized void open() { if (!open) { open = true; fire(LineEvent.Type.OPEN); } }
    @Override public synchronized void close() {
        for (Line clip : clips) clip.close();
        clips.clear();
        if (open) { open = false; fire(LineEvent.Type.CLOSE); }
    }
    @Override public synchronized boolean isOpen() {
        if (open) return true;
        for (Line clip : clips) if (clip.isOpen()) return true;
        return false;
    }
    @Override public Line[] getSourceLines() {
        java.util.ArrayList<Line> opened = new java.util.ArrayList<Line>();
        for (Line clip : clips) if (clip.isOpen()) opened.add(clip);
        return opened.toArray(new Line[opened.size()]);
    }
    @Override public Line[] getTargetLines() { return new Line[0]; }
    @Override public Control[] getControls() { return new Control[0]; }
    @Override public boolean isControlSupported(Control.Type control) { return false; }
    @Override public Control getControl(Control.Type control) { throw new IllegalArgumentException("Mixer has no controls"); }
    @Override public void addLineListener(LineListener listener) { if (listener != null) listeners.add(listener); }
    @Override public void removeLineListener(LineListener listener) { listeners.remove(listener); }
    @Override public boolean isSynchronizationSupported(Line[] lines, boolean maintainSync) { return false; }
    @Override public void synchronize(Line[] lines, boolean maintainSync) { throw new IllegalArgumentException("Synchronization is not supported"); }
    @Override public void unsynchronize(Line[] lines) { throw new IllegalArgumentException("Synchronization is not supported"); }

    private void fire(LineEvent.Type type) {
        LineEvent event = new LineEvent(this, type, 0);
        for (LineListener listener : listeners) listener.update(event);
    }
    private static final class AndroidMixerInfo extends Mixer.Info {
        AndroidMixerInfo() { super("Amethyst Android Audio", "Slay the Amethyst", "LibGDX-backed Java Sound mixer", "1.0"); }
    }
}
