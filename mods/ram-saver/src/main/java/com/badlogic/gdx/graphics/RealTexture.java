package com.badlogic.gdx.graphics;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.glutils.FileTextureData;
import com.badlogic.gdx.graphics.glutils.PixmapTextureData;
import com.badlogic.gdx.utils.GdxRuntimeException;
import optispire.RamSaverDiag;

public class RealTexture extends Texture {
    public RealTexture(String internalPath) {
        this(Gdx.files.internal(internalPath));
    }

    public RealTexture(FileHandle file) {
        this(file, null, false);
    }

    public RealTexture(FileHandle file, boolean useMipMaps) {
        this(file, null, useMipMaps);
    }

    public RealTexture(FileHandle file, Pixmap.Format format, boolean useMipMaps) {
        this(TextureData.Factory.loadFromFile(file, format, useMipMaps));
        this.file = file;
        this.format = format;
        this.useMipMaps = useMipMaps;
    }

    public RealTexture(Pixmap pixmap) {
        this(new PixmapTextureData(pixmap, null, false, false));
    }

    public RealTexture(Pixmap pixmap, boolean useMipMaps) {
        this(new PixmapTextureData(pixmap, null, useMipMaps, false));
    }

    public RealTexture(Pixmap pixmap, Pixmap.Format format, boolean useMipMaps) {
        this(new PixmapTextureData(pixmap, format, useMipMaps, false));
    }

    public RealTexture(int width, int height, Pixmap.Format format) {
        this(new PixmapTextureData(new Pixmap(width, height, format), null, false, true));
    }

    public RealTexture(TextureData data) {
        this(3553, Gdx.gl.glGenTexture(), data);
        if (RamSaverDiag.enabled()) {
            RamSaverDiag.logStackRepeat("real_texture_construct", dataKey(data), dataDetails(data) + " texture=" + diagTexture(this));
        }
    }

    protected RealTexture(int glTarget, int glHandle, TextureData data) {
        super(glTarget, glHandle);
        if (data instanceof FileTextureData) {
            this.file = ((FileTextureData) data).getFileHandle();
        }
        try {
            this.load(data);
        } catch (RuntimeException error) {
            disposeForRamSaver();
            throw error;
        }
        if (data.isManaged()) {
            addManagedTexture(Gdx.app, this);
        }
        if (RamSaverDiag.enabled()) {
            RamSaverDiag.logStackRepeat("real_texture_construct_handle", dataKey(data), "glTarget=" + glTarget + " glHandle=" + glHandle + " " + dataDetails(data) + " texture=" + diagTexture(this));
        }
    }

    public void load(TextureData data) {
        boolean diag = RamSaverDiag.enabled();
        long started = diag ? System.nanoTime() : 0L;
        if (this.data != null && data.isManaged() != this.data.isManaged()) {
            throw new GdxRuntimeException("New data must have the same managed status as the old data");
        } else {
            this.data = data;
            if (!data.isPrepared()) {
                data.prepare();
            }

            this.bind();
            uploadImageData(3553, data);
            // The texture is still bound after upload. Avoid setFilter/setWrap
            // here because those public methods bind again on the render thread.
            this.unsafeSetFilter(this.minFilter, this.magFilter, true);
            this.unsafeSetWrap(this.uWrap, this.vWrap, true);
            Gdx.gl.glBindTexture(this.glTarget, 0);
            if (diag) {
                RamSaverDiag.logDuration(
                        "real_texture_upload",
                        dataKey(data),
                        started,
                        dataDetails(data) + " texture=" + diagTexture(this),
                        true
                );
            }
        }
    }

    protected void reload() {
        if (!this.isManaged()) {
            throw new GdxRuntimeException("Tried to reload unmanaged Texture");
        } else {
            this.glHandle = Gdx.gl.glGenTexture();
            this.load(this.data);
            if (RamSaverDiag.enabled()) {
                RamSaverDiag.logStackRepeat("real_texture_reload", dataKey(this.data), diagTexture(this));
            }
        }
    }

    public void draw(Pixmap pixmap, int x, int y) {
        if (this.data.isManaged()) {
            throw new GdxRuntimeException("can't draw to a managed texture");
        } else {
            this.bind();
            Gdx.gl.glTexSubImage2D(this.glTarget, 0, x, y, pixmap.getWidth(), pixmap.getHeight(), pixmap.getGLFormat(), pixmap.getGLType(), pixmap.getPixels());
        }
    }

    public int getWidth() {
        return this.data.getWidth();
    }

    public int getHeight() {
        return this.data.getHeight();
    }

    public TextureData getTextureData() {
        return this.data;
    }

    public boolean isManaged() {
        return this.data.isManaged();
    }

    private static String dataKey(TextureData data) {
        if (!RamSaverDiag.enabled()) {
            return "";
        }
        if (data instanceof FileTextureData) {
            FileHandle file = ((FileTextureData) data).getFileHandle();
            if (file != null) {
                return file.path();
            }
        }
        if (data == null) {
            return "data-null";
        }
        return data.getClass().getName() + '@' + Integer.toHexString(System.identityHashCode(data));
    }

    private static String dataDetails(TextureData data) {
        if (!RamSaverDiag.enabled()) {
            return "";
        }
        if (data == null) {
            return "data=null";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("data=").append(data.getClass().getName()).append('@').append(Integer.toHexString(System.identityHashCode(data)));
        if (data instanceof FileTextureData) {
            FileHandle file = ((FileTextureData) data).getFileHandle();
            builder.append(" file=").append(file == null ? "null" : RamSaverDiag.safe(file.path()));
        }
        try {
            builder.append(" managed=").append(data.isManaged());
            builder.append(" prepared=").append(data.isPrepared());
            builder.append(" size=").append(data.getWidth()).append('x').append(data.getHeight());
            builder.append(" format=").append(data.getFormat());
            builder.append(" useMipMaps=").append(data.useMipMaps());
        }
        catch (RuntimeException e) {
            builder.append(" detailError=").append(e.getClass().getName()).append(':').append(e.getMessage());
        }
        return builder.toString();
    }

    private static String diagTexture(Texture texture) {
        if (!RamSaverDiag.enabled()) {
            return "";
        }
        if (texture == null) {
            return "null";
        }
        StringBuilder builder = new StringBuilder();
        builder.append(RamSaverDiag.describeObject(texture));
        builder.append(" fake=").append(texture.isFake);
        builder.append(" handle=").append(texture.getTextureObjectHandle());
        if (texture.file != null) {
            builder.append(" file=").append(RamSaverDiag.safe(texture.file.path()));
        }
        if (texture.data != null) {
            builder.append(" size=").append(texture.data.getWidth()).append('x').append(texture.data.getHeight());
        }
        return builder.toString();
    }
}
