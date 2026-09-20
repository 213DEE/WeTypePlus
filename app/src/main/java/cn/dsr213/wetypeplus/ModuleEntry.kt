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
 */
class ModuleEntry : XposedModule() {

    private var layoutHooksInstalled = false

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        Bridge.attach(this)
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
        if (!param.isFirstPackage) {
            Log.i("Skip secondary package ${param.packageName}")
            return
        }
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
        if (layoutHooksInstalled) return
        layoutHooksInstalled = true
        runCatching { WeTypeLayoutHooks.install() }
            .onFailure { error ->
                Log.e("Failed to install keyboard layout hooks")
                Log.e(error)
            }
    }

    private companion object {
        const val HOST_PACKAGE = "com.tencent.wetype"
    }
}
