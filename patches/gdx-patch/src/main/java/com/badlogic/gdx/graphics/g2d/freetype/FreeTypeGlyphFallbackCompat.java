package com.badlogic.gdx.graphics.g2d.freetype;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.BitmapFont.BitmapFontData;
import com.badlogic.gdx.graphics.g2d.PixmapPacker;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.GdxRuntimeException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class FreeTypeGlyphFallbackCompat {
	private static final String TAG = "FreeTypeGlyphFallbackCompat";
	private static final char FALLBACK_CHAR = '?';
	private static final Set<String> LOGGED_FAILURES = Collections.synchronizedSet(new HashSet<String>());

	private FreeTypeGlyphFallbackCompat () {
	}

	public static BitmapFont.Glyph createGlyphOrFallback (FreeTypeFontGenerator generator, char ch,
		FreeTypeFontGenerator.FreeTypeBitmapFontData data, FreeTypeFontGenerator.FreeTypeFontParameter parameter,
		FreeType.Stroker stroker, float baseLine, PixmapPacker packer) {
		try {
			return generator.createGlyph(ch, data, parameter, stroker, baseLine, packer);
		} catch (GdxRuntimeException error) {
			logOnce("render:" + (int)ch,
				"Glyph render failed for " + formatCodePoint(ch) + ", falling back to '?'.", error);
		}

		BitmapFont.Glyph fallbackGlyph = getOrCreateFallbackGlyph(generator, data, parameter, stroker, baseLine, packer, ch);
		if (fallbackGlyph != null) {
			return aliasGlyph(fallbackGlyph, ch);
		}

		if (data.missingGlyph != null) {
			logOnce("missing:" + (int)ch,
				"Question-mark fallback unavailable for " + formatCodePoint(ch) + ", using missing glyph instead.",
				null);
			return aliasGlyph(data.missingGlyph, ch);
		}

		return null;
	}

	private static BitmapFont.Glyph getOrCreateFallbackGlyph (FreeTypeFontGenerator generator,
		FreeTypeFontGenerator.FreeTypeBitmapFontData data, FreeTypeFontGenerator.FreeTypeFontParameter parameter,
		FreeType.Stroker stroker, float baseLine, PixmapPacker packer, char failedChar) {
		BitmapFont.Glyph cachedFallback = lookupCachedGlyph(data, FALLBACK_CHAR);
		if (cachedFallback != null) return cachedFallback;
		if (failedChar == FALLBACK_CHAR) return null;

		try {
			BitmapFont.Glyph generatedFallback = generator.createGlyph(FALLBACK_CHAR, data, parameter, stroker, baseLine, packer);
			if (generatedFallback == null) return null;
			registerGeneratedGlyph(data, generatedFallback, FALLBACK_CHAR);
			return generatedFallback;
		} catch (GdxRuntimeException error) {
			logOnce("fallback:" + (int)failedChar,
				"Question-mark fallback glyph also failed while handling " + formatCodePoint(failedChar) + ".", error);
			return null;
		}
	}

	private static BitmapFont.Glyph lookupCachedGlyph (FreeTypeFontGenerator.FreeTypeBitmapFontData data, char ch) {
		BitmapFontData fontData = data;
		BitmapFont.Glyph[][] glyphPages = fontData.glyphs;
		if (glyphPages == null || glyphPages.length == 0) return null;

		int pageSize = (Character.MAX_VALUE + 1) / glyphPages.length;
		int pageIndex = ch / pageSize;
		if (pageIndex < 0 || pageIndex >= glyphPages.length) return null;

		BitmapFont.Glyph[] page = glyphPages[pageIndex];
		if (page == null) return null;
		return page[ch % pageSize];
	}

	private static void registerGeneratedGlyph (FreeTypeFontGenerator.FreeTypeBitmapFontData data, BitmapFont.Glyph glyph,
		char ch) {
		if (data.regions != null && glyph.page >= 0 && glyph.page < data.regions.size) {
			data.setGlyphRegion(glyph, data.regions.get(glyph.page));
		}
		data.setGlyph(ch, glyph);
		Array<BitmapFont.Glyph> incrementalGlyphs = data.glyphs;
		if (incrementalGlyphs != null) {
			incrementalGlyphs.add(glyph);
		}
	}

	private static BitmapFont.Glyph aliasGlyph (BitmapFont.Glyph source, char ch) {
		BitmapFont.Glyph alias = new BitmapFont.Glyph();
		alias.id = ch;
		alias.srcX = source.srcX;
		alias.srcY = source.srcY;
		alias.width = source.width;
		alias.height = source.height;
		alias.u = source.u;
		alias.v = source.v;
		alias.u2 = source.u2;
		alias.v2 = source.v2;
		alias.xoffset = source.xoffset;
		alias.yoffset = source.yoffset;
		alias.xadvance = source.xadvance;
		alias.fixedWidth = source.fixedWidth;
		alias.page = source.page;
		return alias;
	}

	private static void logOnce (String key, String message, Throwable error) {
		if (!LOGGED_FAILURES.add(key)) return;

		Application app = Gdx.app;
		if (app != null) {
			if (error != null) {
				app.error(TAG, message, error);
			} else {
				app.log(TAG, message);
			}
			return;
		}

		System.err.println("[" + TAG + "] " + message);
		if (error != null) error.printStackTrace(System.err);
	}

	private static String formatCodePoint (char ch) {
		return String.format(Locale.ROOT, "U+%04X", (int)ch);
	}
}
