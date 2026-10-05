package io.stamethyst.webruntime;

import android.content.Context;
import org.mozilla.gecko.process.GeckoServiceGpuProcess;

public class RuntimeGpuService extends GeckoServiceGpuProcess {
    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(RuntimeChildService.wrap(base)); }
}
