package io.stamethyst.webruntime;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;

/** Supplies Gecko's APK/omni.ja, resources and library paths without changing the host identity. */
public final class RuntimeContext extends ContextWrapper {
    private final String apk;
    private final String libraries;
    private final Resources resources;

    public RuntimeContext(Context base, String apk, String libraries) throws Exception {
        super(base);
        this.apk = apk;
        this.libraries = libraries;
        AssetManager assets = AssetManager.class.getConstructor().newInstance();
        int cookie = (Integer) AssetManager.class.getMethod("addAssetPath", String.class).invoke(assets, apk);
        if (cookie == 0) throw new IllegalStateException("Cannot load Gecko resources");
        resources = new Resources(assets, base.getResources().getDisplayMetrics(), base.getResources().getConfiguration());
    }

    @Override public Context getApplicationContext() {
        if (getBaseContext() == getBaseContext().getApplicationContext()) return this;
        try { return new RuntimeContext(getBaseContext().getApplicationContext(), apk, libraries); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    @Override public ClassLoader getClassLoader() { return RuntimeContext.class.getClassLoader(); }
    @Override public AssetManager getAssets() { return resources.getAssets(); }
    @Override public Resources getResources() { return resources; }
    @Override public Object getSystemService(String name) {
        Object service = super.getSystemService(name);
        if (LAYOUT_INFLATER_SERVICE.equals(name)) return ((android.view.LayoutInflater) service).cloneInContext(this);
        return service;
    }
    @Override public Resources.Theme getTheme() {
        Resources.Theme theme = resources.newTheme();
        theme.applyStyle(android.R.style.Theme_Material_Light_NoActionBar, true);
        return theme;
    }
    @Override public ApplicationInfo getApplicationInfo() {
        ApplicationInfo info = new ApplicationInfo(super.getApplicationInfo());
        info.sourceDir = apk;
        info.publicSourceDir = apk;
        info.nativeLibraryDir = libraries;
        info.splitSourceDirs = null;
        info.splitPublicSourceDirs = null;
        return info;
    }
    @Override public String getPackageCodePath() { return apk; }
    @Override public String getPackageResourcePath() { return apk; }
}
