package io.stamethyst.webruntime;

import android.content.Context;
import org.mozilla.gecko.process.GeckoServiceChildProcess;

public class RuntimeChildService extends GeckoServiceChildProcess {
    static Context wrap(Context base) {
        try {
            return (Context) Class.forName("io.stamethyst.web.GeckoDependencyLoader", true, base.getClassLoader())
                .getMethod("wrapContext", Context.class).invoke(null, base);
        } catch (Exception e) { throw new IllegalStateException("Cannot attach Gecko dependency", e); }
    }
    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(wrap(base)); }
}
