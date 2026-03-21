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
import com.badlogic.gdx.backends.lwjgl.LwjglApplication;

public class HdpiUtils {
	private static int activeBackBufferWidth () {
		int overrideWidth = LwjglApplication.getScaledRenderBackBufferWidthOverride();
		return overrideWidth > 0 ? overrideWidth : Gdx.graphics.getBackBufferWidth();
	}

	private static int activeBackBufferHeight () {
		int overrideHeight = LwjglApplication.getScaledRenderBackBufferHeightOverride();
		return overrideHeight > 0 ? overrideHeight : Gdx.graphics.getBackBufferHeight();
	}

	public static void glScissor (int x, int y, int width, int height) {
		int backBufferWidth = activeBackBufferWidth();
		int backBufferHeight = activeBackBufferHeight();
		if (Gdx.graphics.getWidth() != backBufferWidth || Gdx.graphics.getHeight() != backBufferHeight) {
			Gdx.gl.glScissor(
				toBackBufferX(x, backBufferWidth),
				toBackBufferY(y, backBufferHeight),
				toBackBufferX(width, backBufferWidth),
				toBackBufferY(height, backBufferHeight)
			);
		} else {
			Gdx.gl.glScissor(x, y, width, height);
		}
	}

	public static void glViewport (int x, int y, int width, int height) {
		int backBufferWidth = activeBackBufferWidth();
		int backBufferHeight = activeBackBufferHeight();
		if (Gdx.graphics.getWidth() != backBufferWidth || Gdx.graphics.getHeight() != backBufferHeight) {
			Gdx.gl.glViewport(
				toBackBufferX(x, backBufferWidth),
				toBackBufferY(y, backBufferHeight),
				toBackBufferX(width, backBufferWidth),
				toBackBufferY(height, backBufferHeight)
			);
		} else {
			Gdx.gl.glViewport(x, y, width, height);
		}
	}

	public static int toLogicalX (int backBufferX) {
		return (int)((float)(backBufferX * Gdx.graphics.getWidth()) / (float)activeBackBufferWidth());
	}

	public static int toLogicalY (int backBufferY) {
		return (int)((float)(backBufferY * Gdx.graphics.getHeight()) / (float)activeBackBufferHeight());
	}

	public static int toBackBufferX (int logicalX) {
		return toBackBufferX(logicalX, activeBackBufferWidth());
	}

	public static int toBackBufferY (int logicalY) {
		return toBackBufferY(logicalY, activeBackBufferHeight());
	}

	private static int toBackBufferX (int logicalX, int backBufferWidth) {
		return (int)((float)(logicalX * backBufferWidth) / (float)Gdx.graphics.getWidth());
	}

	private static int toBackBufferY (int logicalY, int backBufferHeight) {
		return (int)((float)(logicalY * backBufferHeight) / (float)Gdx.graphics.getHeight());
	}
}
