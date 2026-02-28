/*******************************************************************************
 * Copyright 2011 See AUTHORS file.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.badlogic.gdx.graphics;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap.Blending;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.Texture.TextureWrap;
import com.badlogic.gdx.graphics.TextureData.TextureDataType;
import com.badlogic.gdx.graphics.glutils.MipMapGenerator;
import com.badlogic.gdx.utils.Disposable;

/** Class representing an OpenGL texture by it's target and handle. Keeps track of its state like the TextureFilter and
 * TextureWrap. Also provides some (protected) static methods to create Texture instances.
 * @author mzechner
 * @author badlogic */
public abstract class GLTexture implements Disposable {
	private static final String FORCE_LINEAR_MIPMAP_FILTER_PROP = "amethyst.gdx.force_linear_mipmap_filter";
	private static final String FORCE_LINEAR_MIPMAP_FILTER_ENV = "AMETHYST_GDX_FORCE_LINEAR_MIPMAP_FILTER";
	private static boolean forceLinearMipmapFilterLogPrinted;
	public final int glTarget;
	protected int glHandle;
	protected TextureFilter minFilter = TextureFilter.Nearest;
	protected TextureFilter magFilter = TextureFilter.Nearest;
	protected TextureWrap uWrap = TextureWrap.ClampToEdge;
	protected TextureWrap vWrap = TextureWrap.ClampToEdge;

	public abstract int getWidth ();

	public abstract int getHeight ();

	public abstract int getDepth ();

	public GLTexture (int glTarget) {
		this(glTarget, Gdx.gl.glGenTexture());
	}

	public GLTexture (int glTarget, int glHandle) {
		this.glTarget = glTarget;
		this.glHandle = glHandle;
	}

	public abstract boolean isManaged ();

	protected abstract void reload ();

	public void bind () {
		Gdx.gl.glBindTexture(glTarget, glHandle);
	}

	public void bind (int unit) {
		Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0 + unit);
		Gdx.gl.glBindTexture(glTarget, glHandle);
	}

	public TextureFilter getMinFilter () {
		return minFilter;
	}

	public TextureFilter getMagFilter () {
		return magFilter;
	}

	public TextureWrap getUWrap () {
		return uWrap;
	}

	public TextureWrap getVWrap () {
		return vWrap;
	}

	public int getTextureObjectHandle () {
		return glHandle;
	}

	public void unsafeSetWrap (TextureWrap u, TextureWrap v) {
		unsafeSetWrap(u, v, false);
	}

	public void unsafeSetWrap (TextureWrap u, TextureWrap v, boolean force) {
		if (u != null && (force || uWrap != u)) {
			Gdx.gl.glTexParameterf(glTarget, GL20.GL_TEXTURE_WRAP_S, u.getGLEnum());
			uWrap = u;
		}
		if (v != null && (force || vWrap != v)) {
			Gdx.gl.glTexParameterf(glTarget, GL20.GL_TEXTURE_WRAP_T, v.getGLEnum());
			vWrap = v;
		}
	}

	public void setWrap (TextureWrap u, TextureWrap v) {
		uWrap = u;
		vWrap = v;
		bind();
		Gdx.gl.glTexParameterf(glTarget, GL20.GL_TEXTURE_WRAP_S, u.getGLEnum());
		Gdx.gl.glTexParameterf(glTarget, GL20.GL_TEXTURE_WRAP_T, v.getGLEnum());
	}

	public void unsafeSetFilter (TextureFilter minFilter, TextureFilter magFilter) {
		unsafeSetFilter(minFilter, magFilter, false);
	}

	public void unsafeSetFilter (TextureFilter minFilter, TextureFilter magFilter, boolean force) {
		TextureFilter safeMinFilter = coerceMinFilter(minFilter);
		if (safeMinFilter != null && (force || this.minFilter != safeMinFilter)) {
			Gdx.gl.glTexParameterf(glTarget, GL20.GL_TEXTURE_MIN_FILTER, safeMinFilter.getGLEnum());
			this.minFilter = safeMinFilter;
		}
		if (magFilter != null && (force || this.magFilter != magFilter)) {
			Gdx.gl.glTexParameterf(glTarget, GL20.GL_TEXTURE_MAG_FILTER, magFilter.getGLEnum());
			this.magFilter = magFilter;
		}
	}

	public void setFilter (TextureFilter minFilter, TextureFilter magFilter) {
		TextureFilter safeMinFilter = coerceMinFilter(minFilter);
		this.minFilter = safeMinFilter;
		this.magFilter = magFilter;
		bind();
		Gdx.gl.glTexParameterf(glTarget, GL20.GL_TEXTURE_MIN_FILTER, safeMinFilter.getGLEnum());
		Gdx.gl.glTexParameterf(glTarget, GL20.GL_TEXTURE_MAG_FILTER, magFilter.getGLEnum());
	}

	private static TextureFilter coerceMinFilter (TextureFilter minFilter) {
		if (minFilter == null || !minFilter.isMipMap() || !isForceLinearMipmapFilterEnabled()) {
			return minFilter;
		}
		if (!forceLinearMipmapFilterLogPrinted) {
			forceLinearMipmapFilterLogPrinted = true;
			System.out.println(
				"[gdx-patch] GLTexture mipmap-min-filter fallback enabled; coercing mipmap min filters to Linear"
			);
		}
		return TextureFilter.Linear;
	}

	private static boolean isForceLinearMipmapFilterEnabled () {
		String value = System.getProperty(FORCE_LINEAR_MIPMAP_FILTER_PROP);
		if (value == null) {
			value = System.getenv(FORCE_LINEAR_MIPMAP_FILTER_ENV);
		}
		if (value == null) {
			return true;
		}
		value = value.trim();
		return !"0".equals(value) && !"false".equalsIgnoreCase(value) && !"off".equalsIgnoreCase(value);
	}

	protected void delete () {
		if (glHandle != 0) {
			Gdx.gl.glDeleteTexture(glHandle);
			glHandle = 0;
		}
	}

	@Override
	public void dispose () {
		delete();
	}

	protected static void uploadImageData (int target, TextureData data) {
		uploadImageData(target, data, 0);
	}

	/** This method can be used to upload TextureData to a texture. The call must be preceded by calls to {@link GL20#glBindTexture(int, int)}
	 * and perhaps {@link GL20#glPixelStorei(int, int)} to configure how the pixel data should be interpreted. */
	public static void uploadImageData (int target, TextureData data, int miplevel) {
		if (data == null) {
			return;
		}
		if (!data.isPrepared()) {
			data.prepare();
		}
		final TextureDataType type = data.getType();
		if (type == TextureDataType.Custom) {
			data.consumeCustomData(target);
			return;
		}
		Pixmap pixmap = data.consumePixmap();
		boolean disposePixmap = data.disposePixmap();
		if (data.getFormat() != pixmap.getFormat()) {
			Pixmap tmp = new Pixmap(pixmap.getWidth(), pixmap.getHeight(), data.getFormat());
			Blending blend = Pixmap.getBlending();
			Pixmap.setBlending(Blending.None);
			tmp.drawPixmap(pixmap, 0, 0, 0, 0, pixmap.getWidth(), pixmap.getHeight());
			Pixmap.setBlending(blend);
			if (data.disposePixmap()) {
				pixmap.dispose();
			}
			pixmap = tmp;
			disposePixmap = true;
		}
		Gdx.gl.glPixelStorei(GL20.GL_UNPACK_ALIGNMENT, 1);
		if (data.useMipMaps()) {
			MipMapGenerator.generateMipMap(target, pixmap, pixmap.getWidth(), pixmap.getHeight());
		} else {
			Gdx.gl.glTexImage2D(
				target,
				miplevel,
				pixmap.getGLInternalFormat(),
				pixmap.getWidth(),
				pixmap.getHeight(),
				0,
				pixmap.getGLFormat(),
				pixmap.getGLType(),
				pixmap.getPixels()
			);
		}
		if (disposePixmap) {
			pixmap.dispose();
		}
	}
}
