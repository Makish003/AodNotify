package io.github.makish003.aodnotify;

import io.github.libxposed.api.XposedModule;

/** libxposed (modern API) entry, listed in META-INF/xposed/java_init.list. */
public final class ModuleMain extends XposedModule {
    private static final String SYSTEMUI = "com.android.systemui";
    /** Always-On Display app: its "Display settings" page gets the entry to our screen. */
    private static final String AOD = "com.oplus.aod";

    private String mProcess;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        mProcess = param.getProcessName();
        if (!SYSTEMUI.equals(mProcess) && !AOD.equals(mProcess)) {
            detach(); // only the main processes of the scoped apps
        }
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!param.isFirstPackage() || !param.getPackageName().equals(mProcess)) {
            return;
        }
        if (SYSTEMUI.equals(mProcess)) {
            AodNotify.install(this, param.getClassLoader());
        } else if (AOD.equals(mProcess)) {
            AodEntry.install(this, param.getClassLoader());
        }
    }
}
