/* Copyright 2011 See AUTHORS file.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.badlogic.gdx.graphics.glutils;

import com.badlogic.gdx.Application.ApplicationType;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.utils.GdxRuntimeException;

/** libGDX 1.9.5 API/selection, with explicit ownership for temporary software mip levels. */
public class MipMapGenerator {
    private static boolean useHWMipMap = true;
    private MipMapGenerator() { }

    public static void setUseHardwareMipMap(boolean use) { useHWMipMap = use; }
    public static void generateMipMap(Pixmap pixmap, int width, int height) {
        generateMipMap(GL20.GL_TEXTURE_2D, pixmap, width, height);
    }
    public static void generateMipMap(int target, Pixmap pixmap, int width, int height) {
        if (useHWMipMap && (Gdx.app.getType() == ApplicationType.Android
                || Gdx.app.getType() == ApplicationType.WebGL || Gdx.app.getType() == ApplicationType.iOS
                || Gdx.graphics.supportsExtension("GL_ARB_framebuffer_object")
                || Gdx.graphics.supportsExtension("GL_EXT_framebuffer_object") || Gdx.gl30 != null)) {
            upload(target, 0, pixmap);
            Gdx.gl20.glGenerateMipmap(target);
            return;
        }
        upload(target, 0, pixmap);
        if (Gdx.gl20 == null && width != height)
            throw new GdxRuntimeException("texture width and height must be square when using mipmapping.");
        Pixmap current = pixmap;
        Pixmap.Blending blending = Pixmap.getBlending();
        try {
            Pixmap.setBlending(Pixmap.Blending.None);
            int level = 1;
            while (current.getWidth() / 2 > 0 && current.getHeight() / 2 > 0) {
                Pixmap next = new Pixmap(current.getWidth() / 2, current.getHeight() / 2, current.getFormat());
                try {
                    next.drawPixmap(current, 0, 0, current.getWidth(), current.getHeight(),
                            0, 0, next.getWidth(), next.getHeight());
                    upload(target, level++, next);
                } catch (RuntimeException | Error error) {
                    next.dispose();
                    throw error;
                }
                if (current != pixmap) current.dispose();
                current = next;
            }
        } finally {
            if (current != pixmap) current.dispose();
            Pixmap.setBlending(blending);
        }
    }
    private static void upload(int target, int level, Pixmap pixmap) {
        Gdx.gl.glTexImage2D(target, level, pixmap.getGLInternalFormat(), pixmap.getWidth(), pixmap.getHeight(),
                0, pixmap.getGLFormat(), pixmap.getGLType(), pixmap.getPixels());
    }
}
