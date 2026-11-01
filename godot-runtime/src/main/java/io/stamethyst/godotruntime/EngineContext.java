package io.stamethyst.godotruntime;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;

/** Private engine resources, without installing the container as an application. */
public final class EngineContext extends ContextWrapper {
    private final Resources resources;
    private final String apk, libraries;

    public EngineContext(Context base, String apk, String libraries, String gameAssets) throws Exception {
        super(base);
        this.apk = apk;
        this.libraries = libraries;
        AssetManager assets = AssetManager.class.getConstructor().newInstance();
        int cookie = (Integer) AssetManager.class.getMethod("addAssetPath", String.class).invoke(assets, apk);
        if (cookie == 0) throw new IllegalStateException("Engine resource container unavailable");
        int gameCookie = (Integer) AssetManager.class.getMethod("addAssetPath", String.class).invoke(assets, gameAssets);
        if (gameCookie == 0) throw new IllegalStateException("Game asset container unavailable");
        resources = new Resources(assets, base.getResources().getDisplayMetrics(), base.getResources().getConfiguration());
    }
    @Override public Resources getResources() { return resources; }
    @Override public AssetManager getAssets() { return resources.getAssets(); }
    @Override public ClassLoader getClassLoader() { return EngineContext.class.getClassLoader(); }
    @Override public Context getApplicationContext() { return this; }
    @Override public java.io.File getFilesDir() {
        // The host owns save isolation. GodotIO uses Activity.getFilesDir(), so do not
        // append a second subdirectory here and confuse the storage-scope identifier.
        return super.getFilesDir();
    }
    @Override public ApplicationInfo getApplicationInfo() {
        ApplicationInfo info = new ApplicationInfo(super.getApplicationInfo());
        info.sourceDir = apk; info.publicSourceDir = apk; info.nativeLibraryDir = libraries;
        info.splitSourceDirs = null; info.splitPublicSourceDirs = null;
        return info;
    }
    @Override public Object getSystemService(String name) {
        Object service = super.getSystemService(name);
        return LAYOUT_INFLATER_SERVICE.equals(name)
            ? ((android.view.LayoutInflater) service).cloneInContext(this) : service;
    }
    @Override public Resources.Theme getTheme() {
        Resources.Theme theme = resources.newTheme();
        theme.applyStyle(android.R.style.Theme_Material_Light_NoActionBar, true);
        return theme;
    }
}
