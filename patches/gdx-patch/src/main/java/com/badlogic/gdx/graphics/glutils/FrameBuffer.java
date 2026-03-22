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

package com.badlogic.gdx.graphics.glutils;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;

public class FrameBuffer extends GLFrameBuffer<Texture> {
	public FrameBuffer (Pixmap.Format format, int width, int height, boolean hasDepth) {
		this(format, width, height, hasDepth, false);
	}

	public FrameBuffer (Pixmap.Format format, int width, int height, boolean hasDepth, boolean hasStencil) {
		super(format, width, height, hasDepth, hasStencil);
	}

	@Override
	protected Texture createColorTexture () {
		int internalFormat = Pixmap.Format.toGlFormat(format);
		int type = Pixmap.Format.toGlType(format);
		GLOnlyTextureData data = new GLOnlyTextureData(
			getColorTextureWidth(),
			getColorTextureHeight(),
			0,
			internalFormat,
			internalFormat,
			type
		);
		Texture texture = new Texture(data);
		texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
		texture.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
		return texture;
	}

	@Override
	protected void disposeColorTexture (Texture colorTexture) {
		colorTexture.dispose();
	}

	@Override
	protected void attachFrameBufferColorTexture () {
		Gdx.gl20.glFramebufferTexture2D(
			GL20.GL_FRAMEBUFFER,
			GL20.GL_COLOR_ATTACHMENT0,
			GL20.GL_TEXTURE_2D,
			colorTexture.getTextureObjectHandle(),
			0
		);
	}

	public static void unbind () {
		GLFrameBuffer.unbind();
	}
}
