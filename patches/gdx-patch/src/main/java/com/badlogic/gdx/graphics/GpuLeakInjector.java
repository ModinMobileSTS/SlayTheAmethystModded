package com.badlogic.gdx.graphics;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;

/** Property-gated synthetic tracked GPU leak injector for validating reclaim behavior. */
public final class GpuLeakInjector {
	private static final String MODE_PROP = "amethyst.gdx.debug_leak_injector";
	private static final String INTERVAL_PROP = "amethyst.gdx.debug_leak_interval_frames";
	private static final String MAX_BYTES_PROP = "amethyst.gdx.debug_leak_max_bytes";
	private static final String SIZE_PROP = "amethyst.gdx.debug_leak_texture_size";
	private static final String DIAG_PROP = "amethyst.gdx.gpu_resource_diag";

	private static final String MODE = readStringSystemProperty(MODE_PROP, "off");
	private static final int INTERVAL_FRAMES = readIntSystemProperty(INTERVAL_PROP, 120, 1, 36000);
	private static final long MAX_BYTES = readLongSystemProperty(MAX_BYTES_PROP, 256L * 1024L * 1024L, 0L, Long.MAX_VALUE);
	private static final int TEXTURE_SIZE = readIntSystemProperty(SIZE_PROP, 2048, 64, 8192);
	private static final boolean DIAG = readBooleanSystemProperty(DIAG_PROP, false);
	private static final List<Texture> LEAKED_TEXTURES = new ArrayList<Texture>();
	private static long leakedBytes;
	private static boolean initialized;
	private static boolean completedLogged;
	private static FileHandle leakFile;

	private GpuLeakInjector () {
	}

	public static void afterFrame (long frameId) {
		if (!"texture".equalsIgnoreCase(MODE) && !"both".equalsIgnoreCase(MODE)) return;
		if (Gdx.gl == null || Gdx.files == null || frameId < 0L) return;
		if ((frameId % INTERVAL_FRAMES) != 0L) return;
		if (MAX_BYTES > 0L && leakedBytes >= MAX_BYTES) {
			if (!completedLogged && DIAG) {
				completedLogged = true;
				System.out.println("[gdx-diag] GpuLeakInjector capped leakedBytes=" + leakedBytes
					+ " textures=" + LEAKED_TEXTURES.size());
			}
			return;
		}
		try {
			FileHandle file = ensureLeakFile();
			if (file == null || !file.exists()) return;
			Texture texture = downfallLikeTextureLeak(file);
			LEAKED_TEXTURES.add(texture);
			long bytes = (long)TEXTURE_SIZE * (long)TEXTURE_SIZE * 4L;
			leakedBytes += bytes;
			if (DIAG) {
				System.out.println("[gdx-diag] GpuLeakInjector texture frame=" + frameId
					+ " size=" + TEXTURE_SIZE + "x" + TEXTURE_SIZE
					+ " bytes=" + bytes
					+ " leakedBytes=" + leakedBytes
					+ " textures=" + LEAKED_TEXTURES.size()
					+ " path=" + file.path());
			}
		} catch (Throwable t) {
			if (DIAG) {
				System.out.println("[gdx-diag] GpuLeakInjector failed error=" + t);
			}
		}
	}

	private static Texture downfallLikeTextureLeak (FileHandle file) {
		return new Texture(file);
	}

	private static FileHandle ensureLeakFile () {
		if (initialized) return leakFile;
		initialized = true;
		FileHandle dir = Gdx.files.local("downfallresources");
		dir.mkdirs();
		leakFile = dir.child("guardian_leak_" + TEXTURE_SIZE + ".png");
		if (leakFile.exists()) return leakFile;
		Pixmap pixmap = new Pixmap(TEXTURE_SIZE, TEXTURE_SIZE, Pixmap.Format.RGBA8888);
		try {
			int stripe = Math.max(1, TEXTURE_SIZE / 16);
			for (int y = 0; y < TEXTURE_SIZE; y += stripe) {
				float shade = (float)(y % (stripe * 4)) / (float)(stripe * 4);
				pixmap.setColor(0.35f + shade * 0.35f, 0.05f, 0.55f, 1f);
				pixmap.fillRectangle(0, y, TEXTURE_SIZE, Math.min(stripe, TEXTURE_SIZE - y));
			}
			PixmapIO.writePNG(leakFile, pixmap);
		} finally {
			pixmap.dispose();
		}
		return leakFile;
	}

	private static String readStringSystemProperty (String key, String defaultValue) {
		String configured = System.getProperty(key);
		if (configured == null) return defaultValue;
		configured = configured.trim();
		return configured.length() == 0 ? defaultValue : configured;
	}

	private static boolean readBooleanSystemProperty (String key, boolean defaultValue) {
		String configured = System.getProperty(key);
		if (configured == null) return defaultValue;
		configured = configured.trim();
		if (configured.length() == 0) return defaultValue;
		if ("false".equalsIgnoreCase(configured) || "0".equals(configured) || "off".equalsIgnoreCase(configured)) return false;
		if ("true".equalsIgnoreCase(configured) || "1".equals(configured) || "on".equalsIgnoreCase(configured)) return true;
		return defaultValue;
	}

	private static int readIntSystemProperty (String key, int defaultValue, int minValue, int maxValue) {
		String configured = System.getProperty(key);
		if (configured == null) return defaultValue;
		configured = configured.trim();
		if (configured.length() == 0) return defaultValue;
		try {
			int parsed = Integer.parseInt(configured);
			if (parsed < minValue) return minValue;
			if (parsed > maxValue) return maxValue;
			return parsed;
		} catch (Throwable ignored) {
			return defaultValue;
		}
	}

	private static long readLongSystemProperty (String key, long defaultValue, long minValue, long maxValue) {
		String configured = System.getProperty(key);
		if (configured == null) return defaultValue;
		configured = configured.trim();
		if (configured.length() == 0) return defaultValue;
		try {
			long parsed = Long.parseLong(configured);
			if (parsed < minValue) return minValue;
			if (parsed > maxValue) return maxValue;
			return parsed;
		} catch (Throwable ignored) {
			return defaultValue;
		}
	}
}
