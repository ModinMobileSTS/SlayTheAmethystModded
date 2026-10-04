package io.stamethyst.compatmod.audio;

import javax.sound.sampled.Mixer;
import javax.sound.sampled.spi.MixerProvider;

/** Supplies javax.sound.sampled.Clip on the Android game runtime. */
public final class AndroidMixerProvider extends MixerProvider {
    private static final Mixer.Info INFO = AndroidMixer.info();
    private static final Mixer MIXER = new AndroidMixer();
    @Override public Mixer.Info[] getMixerInfo() { return new Mixer.Info[]{INFO}; }
    @Override public Mixer getMixer(Mixer.Info info) {
        if (info != null && !INFO.equals(info)) throw new IllegalArgumentException("Unknown mixer");
        return MIXER;
    }
}
