package com.badlogic.gdx.graphics;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.assets.AssetLoaderParameters;
import com.badlogic.gdx.assets.AssetManager;
import com.badlogic.gdx.assets.loaders.TextureLoader;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.glutils.FileTextureData;
import com.badlogic.gdx.graphics.glutils.PixmapTextureData;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.GdxRuntimeException;
import optispire.RamSaver;
import optispire.RamSaverDiag;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.function.Consumer;

public class Texture extends GLTexture {
    private static final Set<String> imageExtensions = new HashSet<>();
    static {
        imageExtensions.add("jpg");
        imageExtensions.add("jpeg");
        imageExtensions.add("png");
        imageExtensions.add("cim");
        imageExtensions.add("etc1");
        imageExtensions.add("ktx");
        imageExtensions.add("zktx");
        imageExtensions.add("bmp"); //do these even work in libgdx
        imageExtensions.add("gif");
        imageExtensions.add("sff"); //wtf vupshion
    }

    TextureData data;

    public final boolean isFake;
    public FileHandle file = null;
    public boolean useMipMaps = false;
    public Pixmap.Format format = null;
    private String cacheKey;
    private boolean cacheEvicted;
    private boolean explicitlyDisposed;
    private Application managedApp;

    public String getRamSaverKey() {
        return cacheKey;
    }

    public void attachToRamSaver(String key) {
        cacheKey = key;
    }

    private void registerVariant() {
        cacheKey = registerVariant(minFilter, magFilter, uWrap, vWrap);
    }

    private String registerVariant(TextureFilter min, TextureFilter mag, TextureWrap u, TextureWrap v) {
        String key = RamSaver.textureKey(file, format, useMipMaps, min, mag, u, v);
        if (!RamSaver.textureExists(key)) {
            RamSaver.FileTextureSupplier supplier = new RamSaver.FileTextureSupplier(file, format, useMipMaps);
            supplier.setFilter(min, mag);
            supplier.setWrap(u, v);
            RamSaver.registerTexture(key, supplier);
        }
        return key;
    }

    public Texture(String internalPath) {
        this(Gdx.files.internal(internalPath));
    }

    public Texture(FileHandle file) {
        this(file, null, false);
    }

    public Texture(FileHandle file, boolean useMipMaps) {
        this(file, null, useMipMaps);
    }

    public Texture(Pixmap pixmap) {
        this(new PixmapTextureData(pixmap, null, false, false));
    }

    public Texture(Pixmap pixmap, boolean useMipMaps) {
        this(new PixmapTextureData(pixmap, null, useMipMaps, false));
    }

    public Texture(Pixmap pixmap, Pixmap.Format format, boolean useMipMaps) {
        this(new PixmapTextureData(pixmap, format, useMipMaps, false));
    }

    public Texture(int width, int height, Pixmap.Format format) {
        this(new PixmapTextureData(new Pixmap(width, height, format), null, false, true));
    }

    public Texture(TextureData data) {
        this(3553, Gdx.gl.glGenTexture(), data);
    }

    //Useless constructor for filepath textures
    public Texture(FileHandle file, Pixmap.Format format, boolean useMipMaps) {
        //this(TextureData.Factory.loadFromFile(file, format, useMipMaps));
        super(GL20.GL_TEXTURE_2D, 0);
        this.data = placeholderData;
        this.isFake = true;

        this.file = file;
        this.format = format;
        this.useMipMaps = useMipMaps;

        String textureKey = RamSaver.textureKey(file, format, useMipMaps, minFilter, magFilter, uWrap, vWrap);
        cacheKey = textureKey;
        RamSaverDiag.markFakeTextureWrapperConstructed(textureKey);
        int[] cachedSize = RamSaver.getCachedTextureSize(textureKey);
        if (cachedSize != null) {
            this.width = cachedSize[0];
            this.height = cachedSize[1];
            this.knowSize = true;
        }
        if (RamSaver.isTextureRejected(textureKey)) {
            RamSaver.recordFakeTextureCreate(textureKey, true);
            RamSaverDiag.logRepeat(
                    "fake_texture_rejected_cached",
                    textureKey,
                    "extension=" + RamSaverDiag.safe(file.extension())
                            + " format=" + format
                            + " useMipMaps=" + useMipMaps
            );
            super.dispose();
            throw new GdxRuntimeException("Couldn't load file: " + file);
        }

        //Act like a normal texture, throwing an exception if unable to load
        if (!file.exists() || !imageExtensions.contains(file.extension().toLowerCase())) {
            RamSaver.recordFakeTextureCreate(textureKey, true);
            RamSaver.markTextureRejected(
                    textureKey,
                    "exists=" + file.exists()
                            + " extension=" + RamSaverDiag.safe(file.extension())
                            + " format=" + format
                            + " useMipMaps=" + useMipMaps
            );
            RamSaverDiag.logStackRepeat(
                    "fake_texture_rejected",
                    textureKey,
                    "exists=" + file.exists()
                            + " extension=" + RamSaverDiag.safe(file.extension())
                            + " format=" + format
                            + " useMipMaps=" + useMipMaps
            );
            super.dispose();
            throw new GdxRuntimeException("Couldn't load file: " + file);
        }

        int repeatedRenderCreates = RamSaver.recordFakeTextureCreate(textureKey, false);

        if (RamSaverDiag.enabled()
                && ((!RamSaver.isRepeatedRenderTexture(textureKey) && repeatedRenderCreates < 25) || RamSaverDiag.verbose())) {
            RamSaverDiag.logStackRepeat(
                    "fake_texture_created",
                    textureKey,
                    "format=" + format
                            + " useMipMaps=" + useMipMaps
                            + " alreadyRegistered=" + RamSaver.textureExists(textureKey)
                            + " repeatedRenderCreates=" + repeatedRenderCreates
                            + " fake=" + diagTexture(this)
            );
        }
        registerVariant();
    }

    //Not Useless constructor, used by self
    private Texture(int glTarget, int glHandle, TextureData data) {
        super(glTarget, glHandle);
        this.isFake = false;
        //System.out.println("Tex (not file)");
        try {
            this.load(data);
        } catch (RuntimeException error) {
            super.dispose();
            throw error;
        }
        if (data.isManaged()) {
            addManagedTexture(Gdx.app, this);
        }
        if (RamSaverDiag.enabled()) {
            RamSaverDiag.logStackRepeat("real_texture_construct_data", dataKey(data), diagTexture(this));
        }
    }
    //Not Useless constructor, used by RealTexture
    protected Texture(int glTarget, int glHandle) {
        super(glTarget, glHandle);
        this.isFake = false;
    }


    public Texture getRealTexture() {
        return getRealTexture(true);
    }
    public Texture getRealTexture(boolean canAge) {
        return getRealTexture("explicit", canAge);
    }

    /** Descriptor overrides select a shared variant, never mutate another wrapper's sampler. */
    public Texture getRealTexture(TextureFilter min, TextureFilter mag, TextureWrap u, TextureWrap v) {
        if (!isFake && (cacheKey == null || file == null)) return getRealTexture();
        if (explicitlyDisposed) throw new GdxRuntimeException("Texture has been disposed");
        min = min == null ? minFilter : min;
        mag = mag == null ? magFilter : mag;
        u = u == null ? uWrap : u;
        v = v == null ? vWrap : v;
        if (min == minFilter && mag == magFilter && u == uWrap && v == vWrap) {
            // Even binder reuse (which skips bind()) must refresh the cache owner.
            return RamSaver.getTexture(null, cacheKey, true);
        }
        return RamSaver.getTexture(null, registerVariant(min, mag, u, v), true);
    }
    private Texture getRealTexture(String reason, boolean canAge) {
        if (!isFake && !cacheEvicted)
            return this;

        if (explicitlyDisposed) throw new GdxRuntimeException("Texture has been disposed");
        String key = cacheKey;
        boolean diag = RamSaverDiag.enabled();
        long started = diag ? System.nanoTime() : 0L;
        if (diag) {
            RamSaverDiag.logStackRepeat(
                    "fake_texture_materialize_request",
                    key,
                    "reason=" + reason + " canAge=" + canAge + " fake=" + diagTexture(this)
            );
        }
        Texture real = RamSaver.getTexture(null, key, canAge);
        if (diag) {
            RamSaverDiag.logDuration(
                    "fake_texture_materialize",
                    key,
                    started,
                    "reason=" + reason + " canAge=" + canAge + " real=" + diagTexture(real),
                    false
            );
        }
        return real;
    }


    public void load(TextureData data) {
        boolean diag = RamSaverDiag.enabled();
        long started = diag ? System.nanoTime() : 0L;
        if (!isFake) {
            if (this.data != null && data.isManaged() != this.data.isManaged()) {
                throw new GdxRuntimeException("New data must have the same managed status as the old data");
            } else {
                this.data = data;
                if (!data.isPrepared()) {
                    data.prepare();
                }

                this.bind();
                uploadImageData(3553, data);
                // Keep the upload path on the current GL binding. Calling the
                // public setters would perform two redundant re-binds per load.
                this.unsafeSetFilter(this.minFilter, this.magFilter, true);
                this.unsafeSetWrap(this.uWrap, this.vWrap, true);
                Gdx.gl.glBindTexture(this.glTarget, 0);
                if (diag) {
                    RamSaverDiag.logDuration(
                            "real_texture_load",
                            dataKey(data),
                            started,
                            "managed=" + data.isManaged()
                                    + " prepared=" + data.isPrepared()
                                    + " size=" + data.getWidth() + "x" + data.getHeight()
                                    + " format=" + data.getFormat()
                                    + " useMipMaps=" + data.useMipMaps()
                                    + " texture=" + diagTexture(this),
                            true
                    );
                }
            }
        }
        else {
            //if data is provided then it's already loaded a pixmap
            if (data.getType() == TextureData.TextureDataType.Pixmap && data.isPrepared()) {
                Pixmap pixmap = data.consumePixmap();
                if (data.disposePixmap() && pixmap != null) {
                    pixmap.dispose();
                }
            }

            if (data instanceof FileTextureData) {
                file = ((FileTextureData) data).getFileHandle();
                format = data.getFormat();
                useMipMaps = data.useMipMaps();

                if (diag) {
                    RamSaverDiag.logStackRepeat(
                            "fake_texture_load_file_data",
                            file.path(),
                            "format=" + format + " useMipMaps=" + useMipMaps + " fake=" + diagTexture(this)
                    );
                }
                knowSize = false;
                registerVariant();
            }
        }
    }

    @Override
    protected void reload() {
        if (!this.isFake) {
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
        else {
            Texture t = getRealTexture("reload", true);
            t.reload();
        }
    }

    public TextureData getTextureData() {
        if (isFake) {
            Texture t = getRealTexture("getTextureData", true);
            return t.getTextureData();
        }
        else {
            return data;
        }
    }

    public void draw(Pixmap pixmap, int x, int y) {
        if (isFake) {
            Texture t = getRealTexture("draw", true);
            t.draw(pixmap, x, y);
        }
        else {
            if (this.data.isManaged()) {
                throw new GdxRuntimeException("can't draw to a managed texture");
            } else {
                this.bind();
                Gdx.gl.glTexSubImage2D(this.glTarget, 0, x, y, pixmap.getWidth(), pixmap.getHeight(), pixmap.getGLFormat(), pixmap.getGLType(), pixmap.getPixels());
            }
        }
    }

    boolean knowSize = false;
    int width;
    int height;
    public int getWidth() {
        if (!isFake) {
            return data.getWidth();
        }
        if (!knowSize) {
            getSize(this);
        }
        return width;
    }

    @Override
    public int getHeight() {
        if (!isFake) {
            return data.getHeight();
        }
        if (!knowSize) {
            getSize(this);
        }
        return height;
    }

    public int getDepth() {
        return 0;
    }

    @Override
    public boolean isManaged() {
        if (!isFake) {
            return data.isManaged();
        }
        else {
            Texture t = getRealTexture("isManaged", true);
            return t.isManaged();
        }
    }

    @Override
    public Texture.TextureFilter getMinFilter() {
        if (!isFake)
            return super.getMinFilter();

        return super.getMinFilter();
    }

    @Override
    public Texture.TextureFilter getMagFilter() {
        if (!isFake)
            return super.getMagFilter();

        return super.getMagFilter();
    }

    @Override
    public Texture.TextureWrap getUWrap() {
        if (!isFake)
            return super.getUWrap();

        return super.getUWrap();
    }

    @Override
    public Texture.TextureWrap getVWrap() {
        if (!isFake)
            return super.getVWrap();

        return super.getVWrap();
    }

    @Override
    public int getTextureObjectHandle() {
        if (!isFake)
            return super.getTextureObjectHandle();

        Texture t = getRealTexture("getTextureObjectHandle", true);
        return t.getTextureObjectHandle();
    }

    @Override
    public void bind() {
        if (explicitlyDisposed) throw new GdxRuntimeException("Texture has been disposed");
        if (cacheEvicted) {
            getRealTexture().bind();
            return;
        }
        if (!isFake) {
            if (cacheKey != null) RamSaver.touchTexture(cacheKey, this);
            super.bind();
            return;
        }

        Texture t = RamSaver.getTextureForBindFallback(cacheKey);
        if (t != null) {
            t.bind();
            return;
        }
        super.bind();
    }

    @Override
    public void bind(int unit) {
        if (explicitlyDisposed) throw new GdxRuntimeException("Texture has been disposed");
        if (cacheEvicted) {
            getRealTexture().bind(unit);
            return;
        }
        if (!isFake) {
            if (cacheKey != null) RamSaver.touchTexture(cacheKey, this);
            super.bind(unit);
            return;
        }

        Texture t = RamSaver.getTextureForBindFallback(cacheKey);
        if (t != null) {
            t.bind(unit);
            return;
        }
        super.bind(unit);
    }

    @Override
    public void unsafeSetWrap(Texture.TextureWrap u, Texture.TextureWrap v, boolean force) {
        if (!isFake) {
            super.unsafeSetWrap(u, v, force);
            return;
        }

        setFakeSampler(null, null, u, v, force);
    }

    @Override
    public void setWrap(Texture.TextureWrap u, Texture.TextureWrap v) {
        if (!isFake) {
            super.setWrap(u == null ? this.uWrap : u, v == null ? this.vWrap : v);
            return;
        }

        setFakeSampler(null, null, u == null ? uWrap : u, v == null ? vWrap : v, true);
    }

    @Override
    public void unsafeSetFilter(Texture.TextureFilter minFilter, Texture.TextureFilter magFilter, boolean force) {
        if (!isFake) {
            super.unsafeSetFilter(minFilter, magFilter, force);
            return;
        }

        setFakeSampler(minFilter, magFilter, null, null, force);
    }

    @Override
    public void setFilter(Texture.TextureFilter minFilter, Texture.TextureFilter magFilter) {
        if (!isFake) {
            super.setFilter(minFilter == null ? this.minFilter : minFilter, magFilter == null ? this.magFilter : magFilter);
            return;
        }

        setFakeSampler(minFilter == null ? this.minFilter : minFilter,
                magFilter == null ? this.magFilter : magFilter, null, null, true);
    }

    private void setFakeSampler(TextureFilter min, TextureFilter mag, TextureWrap u, TextureWrap v, boolean force) {
        boolean changed = (min != null && min != minFilter) || (mag != null && mag != magFilter)
                || (u != null && u != uWrap) || (v != null && v != vWrap);
        if (!changed && !force) return;
        Texture previous = RamSaver.getExistingTexture(cacheKey);
        if (min != null) minFilter = min;
        if (mag != null) magFilter = mag;
        if (u != null) uWrap = u;
        if (v != null) vWrap = v;
        if (changed) registerVariant();
        Texture real = RamSaver.getExistingTexture(cacheKey);
        if (real == null && previous == null) return; // Unmaterialized wrappers stay lazy.
        // Real textures escape through regions and getRealTexture(), so exclusive
        // ownership cannot be inferred from wrapper count. New samplers need a copy;
        // returning to an existing variant reuses its upload instead.
        if (real == null) real = getRealTexture();
        real.bind();
        real.unsafeSetFilter(min, mag, force);
        real.unsafeSetWrap(u, v, force);
    }

    @Override
    public void dispose() {
        // Fake wrappers do not own the shared real texture. Preserve their lazy reuse semantics.
        if (!isFake) explicitlyDisposed = true;
        super.dispose();
        removeManagedTexture(this);
        if (!isFake && cacheKey != null) {
            if (RamSaverDiag.enabled()) {
                RamSaverDiag.logStackRepeat("real_texture_dispose", cacheKey, diagTexture(this));
            }
            RamSaver.onTextureDisposed(cacheKey, this);
        }
        else if (isFake) {
            if (RamSaverDiag.enabled()) {
                RamSaverDiag.logStackRepeat("fake_texture_dispose", file == null ? "null" : file.path(), diagTexture(this));
            }
        }
    }

    /** Releases a managed real texture without re-entering RamSaver's cache bookkeeping. */
    public void disposeForRamSaver() {
        cacheEvicted = cacheKey != null && !explicitlyDisposed;
        super.dispose();
        removeManagedTexture(this);
    }

    private static void removeManagedTexture(Texture texture) {
        if (texture == null || texture.data == null || !texture.data.isManaged()) {
            return;
        }
        Array<Texture> managed = managedTextures.get(texture.managedApp);
        if (managed != null) {
            managed.removeValue(texture, true);
        }
        texture.managedApp = null;
    }

    private static final FakeData placeholderData = new FakeData();
    private static class FakeData implements TextureData {
        @Override
        public TextureDataType getType() {
            return TextureDataType.Custom;
        }

        @Override
        public boolean isPrepared() {
            return false;
        }

        @Override
        public void prepare() {

        }

        @Override
        public Pixmap consumePixmap() {
            return null;
        }

        @Override
        public boolean disposePixmap() {
            return false;
        }

        @Override
        public void consumeCustomData(int i) {

        }

        @Override
        public int getWidth() {
            return 1;
        }

        @Override
        public int getHeight() {
            return 1;
        }

        @Override
        public Pixmap.Format getFormat() {
            return Pixmap.Format.RGBA8888;
        }

        @Override
        public boolean useMipMaps() {
            return false;
        }

        @Override
        public boolean isManaged() {
            return false;
        }
    }














    //************************STATICS**************************
    private static AssetManager assetManager;
    static final Map<Application, Array<Texture>> managedTextures = new HashMap<>();

    protected static void addManagedTexture(Application app, Texture texture) {
        texture.managedApp = app;
        Array<Texture> managedTextureArray = managedTextures.get(app);
        if (managedTextureArray == null) {
            managedTextureArray = new Array<>();
        }

        managedTextureArray.add(texture);
        managedTextures.put(app, managedTextureArray);
    }

    public static void clearAllTextures(Application app) {
        managedTextures.remove(app);
    }

    public static void invalidateAllTextures(Application app) {
        Array<Texture> managedTextureArray = managedTextures.get(app);
        if (managedTextureArray != null) {
            if (assetManager == null) {
                for(int i = 0; i < managedTextureArray.size; ++i) {
                    Texture texture = managedTextureArray.get(i);
                    texture.reload();
                }
            } else {
                assetManager.finishLoading();
                Array<Texture> textures = new Array<>(managedTextureArray);

                for (Texture texture : textures) {
                    String fileName = assetManager.getAssetFileName(texture);
                    if (fileName == null) {
                        texture.reload();
                    } else {
                        final int refCount = assetManager.getReferenceCount(fileName);
                        assetManager.setReferenceCount(fileName, 0);
                        texture.glHandle = 0;
                        TextureLoader.TextureParameter params = new TextureLoader.TextureParameter();
                        params.textureData = texture.getTextureData();
                        params.minFilter = texture.getMinFilter();
                        params.magFilter = texture.getMagFilter();
                        params.wrapU = texture.getUWrap();
                        params.wrapV = texture.getVWrap();
                        params.genMipMaps = texture.data.useMipMaps();
                        params.texture = texture;
                        params.loadedCallback = new AssetLoaderParameters.LoadedCallback() {
                            public void finishedLoading(AssetManager assetManager, String fileName, Class type) {
                                assetManager.setReferenceCount(fileName, refCount);
                            }
                        };
                        assetManager.unload(fileName);
                        texture.glHandle = Gdx.gl.glGenTexture();
                        assetManager.load(fileName, Texture.class, params);
                    }
                }

                managedTextureArray.clear();
                managedTextureArray.addAll(textures);
            }

        }
    }

    public static void setAssetManager(AssetManager manager) {
        assetManager = manager;
    }

    public static String getManagedStatus() {
        StringBuilder builder = new StringBuilder();
        builder.append("Managed textures/app: { ");

        for (Application app : managedTextures.keySet()) {
            builder.append(managedTextures.get(app).size);
            builder.append(" ");
        }

        builder.append("}");
        return builder.toString();
    }

    public static int getNumManagedTextures() {
        Array<Texture> managed = managedTextures.get(Gdx.app);
        return managed == null ? 0 : managed.size;
    }

    public enum TextureWrap {
        MirroredRepeat(33648),
        ClampToEdge(33071),
        Repeat(10497);

        final int glEnum;

        TextureWrap(int glEnum) {
            this.glEnum = glEnum;
        }

        public int getGLEnum() {
            return this.glEnum;
        }
    }

    public enum TextureFilter {
        Nearest(9728),
        Linear(9729),
        MipMap(9987),
        MipMapNearestNearest(9984),
        MipMapLinearNearest(9985),
        MipMapNearestLinear(9986),
        MipMapLinearLinear(9987);

        final int glEnum;

        TextureFilter(int glEnum) {
            this.glEnum = glEnum;
        }

        public boolean isMipMap() {
            return this.glEnum != 9728 && this.glEnum != 9729;
        }

        public int getGLEnum() {
            return this.glEnum;
        }
    }

    //--------------SIZE-----------------
    private static final byte[] PNG_HEADER = new byte[] { (byte)137, (byte)80, (byte)78, (byte)71, (byte)13, (byte)10, (byte)26, (byte)10 };
    private static final byte[] IHDR = new byte[] { (byte)73, (byte)72, (byte)68, (byte)82 };
    private static final byte[] KTX_HEADER = new byte[] {
            (byte) 0xAB, 0x4B, 0x54, 0x58, 0x20, 0x31, 0x31, (byte) 0xBB, 0x0D, 0x0A, 0x1A, 0x0A
    };


    private static final ThreadLocal<byte[]> filedata = new ThreadLocal<byte[]>() {
        @Override
        protected byte[] initialValue() {
            return new byte[64];
        }
    };
    private static final Map<String, Consumer<Texture>> sizeGetters = new HashMap<>();
    static {
        sizeGetters.put("png", (t)->{
            byte[] data = filedata.get();
            int read = t.file.readBytes(data, 0, data.length);
            if (read < 33 || !matches(data, 0, PNG_HEADER) || readInt(data, 8, true) != 13
                    || !matches(data, 12, IHDR)) return;
            int depth = data[24] & 0xff;
            int color = data[25] & 0xff;
            boolean validDepth = color == 0 ? (depth == 1 || depth == 2 || depth == 4 || depth == 8 || depth == 16)
                    : color == 3 ? (depth == 1 || depth == 2 || depth == 4 || depth == 8)
                    : (color == 2 || color == 4 || color == 6) && (depth == 8 || depth == 16);
            if (!validDepth || data[26] != 0 || data[27] != 0 || (data[28] != 0 && data[28] != 1)) return;
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(data, 12, 17);
            if ((int) crc.getValue() != readInt(data, 29, true)) return;
            acceptSize(t, readInt(data, 16, true), readInt(data, 20, true));
        });
        sizeGetters.put("ktx", (t)->{
            byte[] data = filedata.get();
            int read = t.file.readBytes(data, 0, data.length);
            boolean bigEndian;
            if (read == 64 && matches(data, 0, KTX_HEADER)) {
                int endianness = readInt(data, 12, true);
                if (endianness == 0x01020304) {
                    bigEndian = false;
                }
                else if (endianness == 0x04030201) {
                    bigEndian = true;
                }
                else {
                    return;
                }
                int type = readInt(data, 16, bigEndian);
                int typeSize = readInt(data, 20, bigEndian);
                int format = readInt(data, 24, bigEndian);
                int width = readInt(data, 36, bigEndian);
                int height = readInt(data, 40, bigEndian);
                int levels = readInt(data, 56, bigEndian);
                int metadata = readInt(data, 60, bigEndian);
                if ((type == 0) != (format == 0) || (type == 0 && typeSize != 1)
                        || (typeSize != 1 && typeSize != 2 && typeSize != 4)
                        || readInt(data, 28, bigEndian) == 0 || readInt(data, 32, bigEndian) == 0
                        || readInt(data, 44, bigEndian) != 0 || readInt(data, 48, bigEndian) != 0
                        || readInt(data, 52, bigEndian) != 1 || metadata < 0 || (metadata & 3) != 0
                        || levels < 0 || levels > 32 - Integer.numberOfLeadingZeros(Math.max(width, height))) return;
                acceptSize(t, width, height);
            }
        });
        sizeGetters.put("jpg", Texture::readJpegSize);
        sizeGetters.put("jpeg", Texture::readJpegSize);
    }
    static int[] readHeaderSize(FileHandle file) {
        Texture probe = new Texture(GL20.GL_TEXTURE_2D, 0, placeholderData);
        probe.file = file;
        probe.cacheKey = RamSaver.textureKey(file, null, false, probe.minFilter, probe.magFilter, probe.uWrap, probe.vWrap);
        Consumer<Texture> reader = sizeGetters.get(file.extension().toLowerCase());
        if (reader != null) reader.accept(probe);
        return probe.knowSize ? new int[] {probe.width, probe.height} : null;
    }
    private static void getSize(Texture t) {
        if (!t.isFake) {
            t.knowSize = true;
            t.width = t.getWidth();
            t.height = t.getHeight();
            if (t.file != null) {
                RamSaver.cacheTextureSize(t.cacheKey, t.width, t.height);
            }
            if (RamSaverDiag.enabled()) {
                RamSaverDiag.logRepeat("size_real_texture", dataKey(t.data), "size=" + t.width + "x" + t.height + " texture=" + diagTexture(t));
            }
            return;
        }

        if (t.file != null) {
            int[] cachedSize = RamSaver.getCachedTextureSize(t.cacheKey);
            if (cachedSize != null) {
                t.width = cachedSize[0];
                t.height = cachedSize[1];
                t.knowSize = true;
                if (RamSaverDiag.enabled()) {
                    RamSaverDiag.logRepeat(
                            "size_cache_hit",
                            t.file.path(),
                            "extension=" + RamSaverDiag.safe(t.file.extension()) + " size=" + t.width + "x" + t.height
                    );
                }
                return;
            }
            Consumer<Texture> sizeGetter = sizeGetters.get(t.file.extension().toLowerCase());
            if (sizeGetter != null) {
                boolean diag = RamSaverDiag.enabled();
                long started = diag ? System.nanoTime() : 0L;
                try {
                    sizeGetter.accept(t);
                }
                catch (RuntimeException ignored) {
                    t.knowSize = false;
                }
                //If failed to process, continue to backup method.
                if (t.knowSize) {
                    RamSaver.cacheTextureSize(t.cacheKey, t.width, t.height);
                    if (diag) {
                        RamSaverDiag.logDuration(
                                "size_header_success",
                                t.file.path(),
                                started,
                                "extension=" + RamSaverDiag.safe(t.file.extension()) + " size=" + t.width + "x" + t.height,
                                false
                        );
                    }
                    return;
                }
                if (diag) {
                    RamSaverDiag.logDuration(
                            "size_header_failed",
                            t.file.path(),
                            started,
                            "extension=" + RamSaverDiag.safe(t.file.extension()),
                            false
                    );
                }
            }
        }

        boolean diag = RamSaverDiag.enabled();
        long started = diag ? System.nanoTime() : 0L;
        Texture real = t.getRealTexture("size_fallback", true);
        t.width = real.getWidth();
        t.height = real.getHeight();
        t.knowSize = true;
        if (t.file != null) {
            RamSaver.cacheTextureSize(t.cacheKey, t.width, t.height);
        }
        t.dispose();
        if (diag) {
            RamSaverDiag.logDuration(
                    "size_real_texture_fallback",
                    t.file == null ? "null" : t.file.path(),
                    started,
                    "size=" + t.width + "x" + t.height + " real=" + diagTexture(real),
                    true
            );
        }
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
        if (texture.file != null) {
            builder.append(" file=").append(RamSaverDiag.safe(texture.file.path()));
        }
        builder.append(" useMipMaps=").append(texture.useMipMaps);
        builder.append(" format=").append(texture.format);
        if (!texture.isFake && texture.data != null) {
            try {
                builder.append(" handle=").append(texture.getTextureObjectHandle());
                builder.append(" size=").append(texture.data.getWidth()).append('x').append(texture.data.getHeight());
                builder.append(" data=").append(dataKey(texture.data));
            }
            catch (RuntimeException e) {
                builder.append(" detailError=").append(e.getClass().getName()).append(':').append(e.getMessage());
            }
        }
        else if (texture.knowSize) {
            builder.append(" cachedSize=").append(texture.width).append('x').append(texture.height);
        }
        return builder.toString();
    }

    private static String dataKey(TextureData data) {
        if (!RamSaverDiag.enabled()) {
            return "";
        }
        if (data == null) {
            return "data-null";
        }
        return data.getClass().getName() + '@' + Integer.toHexString(System.identityHashCode(data));
    }

    private static boolean matches(byte[] data, int fromIndex, byte[] toMatch) {
        for (int i=0; i < toMatch.length; ++i)
            if (data[fromIndex + i] != toMatch[i])
                return false;

        return true;
    }

    private static int readInt(byte[] data, int index, boolean bigEndian) {
        if (bigEndian) {
            return (((data[index])            << 24) |
                    ((data[index + 1] & 0xff) << 16) |
                    ((data[index + 2] & 0xff) <<  8) |
                    ((data[index + 3] & 0xff)));
        }
        return (((data[index + 3] & 0xff) << 24) |
                ((data[index + 2] & 0xff) << 16) |
                ((data[index + 1] & 0xff) <<  8) |
                ((data[index] & 0xff)));
    }


    private static void acceptSize(Texture t, int width, int height) {
        if (width <= 0 || height <= 0) return;
        t.width = width;
        t.height = height;
        t.knowSize = true;
    }

    private static void readJpegSize(Texture t) {
        try (InputStream in = t.file.read()) {
            if (readUnsignedByte(in) != 0xFF || readUnsignedByte(in) != 0xD8) {
                return;
            }

            while (true) {
                int markerStart = readUnsignedByte(in);
                while (markerStart != 0xFF) {
                    if (markerStart < 0) {
                        return;
                    }
                    markerStart = readUnsignedByte(in);
                }

                int marker = readUnsignedByte(in);
                while (marker == 0xFF) {
                    marker = readUnsignedByte(in);
                }
                if (marker < 0 || marker == 0xD9 || marker == 0xDA) {
                    return;
                }
                if (isStandaloneJpegMarker(marker)) {
                    continue;
                }

                int length = readUnsignedShort(in);
                if (length < 2) {
                    return;
                }

                int payloadLength = length - 2;
                if (isJpegStartOfFrame(marker)) {
                    if (payloadLength < 5) {
                        return;
                    }
                    readUnsignedByte(in); // precision
                    int height = readUnsignedShort(in);
                    int width = readUnsignedShort(in);
                    if (width > 0 && height > 0) {
                        t.width = width;
                        t.height = height;
                        t.knowSize = true;
                    }
                    return;
                }

                skipFully(in, payloadLength);
            }
        }
        catch (IOException | RuntimeException ignored) {
            // Fall back to the existing real-texture size path.
        }
    }

    private static boolean isStandaloneJpegMarker(int marker) {
        return marker == 0x01 || (marker >= 0xD0 && marker <= 0xD8);
    }

    private static boolean isJpegStartOfFrame(int marker) {
        return marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
    }

    private static int readUnsignedByte(InputStream in) throws IOException {
        return in.read();
    }

    private static int readUnsignedShort(InputStream in) throws IOException {
        int high = readUnsignedByte(in);
        int low = readUnsignedByte(in);
        if (high < 0 || low < 0) {
            return -1;
        }
        return (high << 8) | low;
    }

    private static void skipFully(InputStream in, int byteCount) throws IOException {
        int remaining = byteCount;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
                continue;
            }
            if (in.read() < 0) {
                return;
            }
            remaining--;
        }
    }
}
