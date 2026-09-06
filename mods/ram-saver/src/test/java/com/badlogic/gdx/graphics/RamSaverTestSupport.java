package com.badlogic.gdx.graphics;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Files.FileType;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.files.FileHandle;
import optispire.RamSaver;
import org.junit.After;
import org.junit.Before;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public abstract class RamSaverTestSupport {
    protected final Set<Integer> live = new HashSet<>();
    protected final List<Integer> deleted = new ArrayList<>();
    protected int generated;
    protected int bound;
    protected int activeUnit;
    protected float delta;

    @Before public void setUpGL() throws Exception {
        Gdx.app = proxy(Application.class, (name, args) -> null);
        Gdx.graphics = proxy(Graphics.class, (name, args) -> name.equals("getRawDeltaTime") ? delta : null);
        Gdx.gl = Gdx.gl20 = proxy(GL20.class, (name, args) -> {
            if (name.equals("glGenTexture")) { live.add(++generated); return generated; }
            if (name.equals("glDeleteTexture")) {
                int handle = (Integer) args[0];
                assertTrue("Double/stale deletion: " + handle, live.remove(handle));
                deleted.add(handle);
            }
            if (name.equals("glBindTexture")) {
                bound = (Integer) args[1];
                assertTrue("Binding deleted name: " + bound, bound == 0 || live.contains(bound));
            }
            if (name.equals("glActiveTexture")) activeUnit = (Integer) args[0];
            return null;
        });
        set(RamSaver.class, "timer", 100f);
    }

    @After public void tearDownGL() throws Exception {
        Map<String, ?> loaded = map("loadedAssets");
        for (String id : new ArrayList<>(loaded.keySet())) RamSaver.dispose(id);
        delta = 0;
        for (int i = 0; i < 100; i++) RamSaver.update();
        if (Texture.managedTextures.get(Gdx.app) != null) {
            for (Texture texture : new ArrayList<Texture>() {{
                for (Texture t : Texture.managedTextures.get(Gdx.app)) add(t);
            }}) {
                // Baseline A/B can leave raw-deleted names in the managed list.
                if (live.contains(texture.glHandle)) texture.dispose();
            }
        }
        Texture.clearAllTextures(Gdx.app);
        for (String name : new String[] {"loadedAssets", "textures", "fakeTextureStates"}) map(name).clear();
        ((Set<?>) field(RamSaver.class, "nullAssets")).clear();
        ((Set<?>) field(RamSaver.class, "rejectedTextures")).clear();
        List<List<String>> sets = (List<List<String>>) field(RamSaver.class, "loadedSets");
        sets.clear();
        sets.add(new ArrayList<>());
        set(RamSaver.class, "nextSet", 0);
        Gdx.gl = Gdx.gl20 = null;
        Gdx.graphics = null;
        Gdx.app = null;
    }

    protected void tick() throws Exception {
        set(RamSaver.class, "timer", 0f);
        RamSaver.update();
    }

    protected Texture load(String id, int size, boolean canAge) {
        RamSaver.registerTexture(id, new RamSaver.FileTextureSupplier(new MemoryFile(id + ".png", new byte[0]), null, false) {
            @Override public Texture get() { return new RealTexture(new MockData(size)); }
        });
        return RamSaver.getTexture(null, id, canAge);
    }

    protected static Map<String, Object> map(String name) throws Exception {
        return (Map<String, Object>) field(RamSaver.class, name);
    }

    protected static Object field(Object owner, String name) throws Exception {
        Field f = (owner instanceof Class ? (Class<?>) owner : owner.getClass()).getDeclaredField(name);
        f.setAccessible(true);
        return f.get(owner instanceof Class ? null : owner);
    }

    protected static void set(Object owner, String name, Object value) throws Exception {
        Field f = (owner instanceof Class ? (Class<?>) owner : owner.getClass()).getDeclaredField(name);
        f.setAccessible(true);
        f.set(owner instanceof Class ? null : owner, value);
    }

    interface Call { Object invoke(String name, Object[] args); }
    static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, method, args) -> {
            if (method.getName().equals("hashCode")) return System.identityHashCode(p);
            if (method.getName().equals("equals")) return p == args[0];
            Object result = call.invoke(method.getName(), args);
            if (result != null) return result;
            Class<?> r = method.getReturnType();
            if (r == boolean.class) return false;
            if (r == int.class) return 0;
            if (r == long.class) return 0L;
            if (r == float.class) return 0f;
            return null;
        }));
    }

    static class MemoryFile extends FileHandle {
        final byte[] bytes;
        MemoryFile(String path, byte[] bytes) { this(path, bytes, FileType.Internal); }
        MemoryFile(String path, byte[] bytes, FileType type) { super(path, type); this.bytes = bytes; }
        @Override public boolean exists() { return true; }
        @Override public InputStream read() { return new ByteArrayInputStream(bytes); }
    }

    static class MockData implements TextureData {
        final int size;
        boolean prepared;
        MockData(int size) { this.size = size; }
        public TextureDataType getType() { return TextureDataType.Custom; }
        public boolean isPrepared() { return prepared; }
        public void prepare() { prepared = true; }
        public Pixmap consumePixmap() { throw new AssertionError("Native pixmap path invoked"); }
        public boolean disposePixmap() { return false; }
        public void consumeCustomData(int target) { assertTrue(prepared); prepared = false; }
        public int getWidth() { return size; }
        public int getHeight() { return size; }
        public Pixmap.Format getFormat() { return Pixmap.Format.RGBA8888; }
        public boolean useMipMaps() { return false; }
        public boolean isManaged() { return true; }
    }
}
