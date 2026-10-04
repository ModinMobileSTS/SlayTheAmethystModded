package io.stamethyst.compatmod.audio;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Mixer;

public final class JavaSoundBootstrap {
    private JavaSoundBootstrap() { }

    public static void initialize() {
        // MTS loads mods in its own child loader. Java Sound ServiceLoader uses TCCL,
        // not the loader of the mod making the call. The game thread inherits this context.
        Thread.currentThread().setContextClassLoader(AndroidMixerProvider.class.getClassLoader());
        System.setProperty("javax.sound.sampled.Clip", AndroidMixerProvider.class.getName());
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            if (info.equals(AndroidMixer.info())) {
                System.out.println("[Amethyst Java Sound] LibGDX Clip provider registered");
                return;
            }
        }
        System.err.println("[Amethyst Java Sound] Clip SPI provider is not visible to the game loader");
    }
}
