package cn.dsr213.wetypeplus

import cn.dsr213.wetypeplus.bridge.Bridge
import cn.dsr213.wetypeplus.bridge.Log
import cn.dsr213.wetypeplus.hook.HookSettings
import cn.dsr213.wetypeplus.hook.WeTypeLayoutHooks
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * The libxposed entry point, named in `META-INF/xposed/java_init.list`.
 *
 * The module only ever touches one host package, so the lifecycle handling stays small: attach
 * the framework, wait until WeType's own class loader is ready, then install the keyboard layout
 * hooks. Everything else in the project hangs off that.
 *
 * Note what is deliberately absent: no hook into the host's settings screens, no shared process,
 * no code injected into the host's own UI. The module is a keyboard-layout patch plus an ordinary
 * settings app.
 *
 * **On the API level this class demands.** Both hot-reload callbacks below, and the
 * `HotReloadingParam` / `HotReloadedParam` types in their signatures, exist only from libxposed
 * **API 102** onwards, and `module.prop` therefore declares `minApiVersion=102`. The consequence
 * is worth stating plainly, because it is invisible from inside the module: a framework below that
 * level - anything older than LSPosed 2.2 / Vector 2.2 - **refuses to load the module at all**,
 * silently. The manager shows it as enabled and nothing whatsoever happens. Every version up to
 * and including 1.0.22-alpha shipped with that ceiling and nothing on screen explaining it, which
 * is what the "installed it and nothing changed" reports were. Raising the requirement is the
 * intended trade - hot reload stays - so the fix is not to lower the bar but to *say* the bar
 * exists: [Bridge.publishStatusAsync] reports the framework's real API level to the settings app,
 * and the diagnostics screen turns a version shortfall into instructions.
 */
class ModuleEntry : XposedModule() {

    /**
     * Set once *this* process has installed its hooks.
     *
     * Per-instance is exactly right: the framework creates one module instance per process, and
     * WeType runs its keyboard layout in a second process (`:hld`). That process needs an
     * installation of its own - this flag only stops one process from installing twice.
     */
    private var layoutHooksInstalled = false

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        Bridge.attach(this)
        // Kept for the diagnostics screen: the framework's own identity is the one fact a field
        // report cannot supply, and it is what decides whether the module was ever eligible to run.
        Bridge.recordFramework(
            name = frameworkName,
            version = frameworkVersion,
            versionCode = frameworkVersionCode,
            apiVersion = apiVersion
        )
        Log.i(
            "Loaded in ${param.processName}: $frameworkName $frameworkVersion " +
                "($frameworkVersionCode), API $apiVersion"
        )
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != HOST_PACKAGE) {
            Log.i("Skip out-of-scope package ${param.packageName}")
            return
        }
        // `isFirstPackage` is deliberately *not* consulted here.
        //
        // It answers "has this process already loaded a package before this one", and for
        // WeType's `:hld` process the answer can be yes - yet that is precisely the process that
        // runs the keyboard layout and therefore the one that needs these hooks. Treating the flag
        // as a gate is a way to skip that process entirely and leave the module with no effect at
        // all. The boolean below covers the only thing the flag was standing in for: doing the
        // work once per process.
        if (layoutHooksInstalled) {
            Log.i("Skip repeated package-ready for ${param.packageName}")
            return
        }
        layoutHooksInstalled = true
        Bridge.updateClassLoader(param.classLoader)
        installLayoutHooks()
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        Bridge.prepareForHotReload()
        WeTypeLayoutHooks.prepareForHotReload()
        HookSettings.prepareForHotReload()
        layoutHooksInstalled = false
        Log.i("Old generation is ready for hot reload")
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        Bridge.attach(this)
        Bridge.finishHotReload(param.oldHookHandles)
        installLayoutHooks()
    }

    private fun installLayoutHooks() {
        runCatching { WeTypeLayoutHooks.install() }
            .onFailure { error ->
                Log.e("Failed to install keyboard layout hooks")
                Log.e(error)
            }
        // Publish what happened to the settings app, so "the module is enabled but nothing
        // changed" becomes a screen the user can read instead of a guess. Runs on its own thread:
        // this call site is the host's main thread during package start-up.
        Bridge.publishStatusAsync()
    }

    private companion object {
        const val HOST_PACKAGE = "com.tencent.wetype"
    }
}
