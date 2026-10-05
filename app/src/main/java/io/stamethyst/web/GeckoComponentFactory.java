package io.stamethyst.web;

import android.app.Application;
import android.app.Service;
import android.content.Intent;
import androidx.core.app.CoreComponentFactory;

/** Only Gecko service components use the external loader; all host components keep AndroidX. */
public final class GeckoComponentFactory extends CoreComponentFactory {
    private static final String PREFIX = "org.mozilla.gecko.process.GeckoChildProcessServices$";
    private static Application application;

    @Override public Application instantiateApplication(ClassLoader loader, String name)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        String process = Application.getProcessName();
        boolean geckoChild = process != null && process.matches(".*:(tab[0-9]+|gpu|socket|rdd|utility|gmplugin|ipdlunittest|media|crashhelper)");
        application = geckoChild ? new Application() : super.instantiateApplication(loader, name);
        return application;
    }
    @Override public Service instantiateService(ClassLoader loader, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        boolean auxiliary = name.equals("org.mozilla.gecko.media.MediaManager") || name.equals("org.mozilla.gecko.crashhelper.CrashHelper");
        if (!name.startsWith(PREFIX) && !auxiliary) return super.instantiateService(loader, name, intent);
        try {
            return (Service) GeckoDependencyLoader.classLoader(application)
                .loadClass(auxiliary ? name : "io.stamethyst.webruntime.ChildServices$" + name.substring(PREFIX.length()))
                .getConstructor().newInstance();
        } catch (Exception e) { throw new ClassNotFoundException("Cannot load external Gecko service " + name, e); }
    }
}
