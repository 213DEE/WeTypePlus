package cn.dsr213.wetypeplus.hook

import android.app.Application
import cn.dsr213.wetypeplus.bridge.Bridge
import cn.dsr213.wetypeplus.bridge.Log
import cn.dsr213.wetypeplus.bridge.currentApplication
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Makes sure the hooks are installed against the class loader the host actually runs on.
 *
 * ### The failure this exists for (WeType 3.5.4 + RFix patch, 2026-09-28)
 *
 * WeType hot-patches itself with an RFix/Tinker patch. Tinker's `NewClassLoaderInjector` builds a
 * brand-new class loader — `dalvik.system.DelegateLastClassLoader` on the measured device (its SDK
 * >= 27 branch; below 27 it is `com.tencent.tinker.loader.TinkerClassLoader`), parented on the
 * previous one, so both see the same names — and swaps it in as the process's loader from inside
 * `Application#attachBaseContext`. Everything the patch ships is then loaded from the *new*
 * loader: `utils.n1`, `utils.j1`, `model.Q`, all of it, even where the patch's copy of a class is
 * byte-for-byte identical to the installed one.
 *
 * LSPosed hands this module its class loader on `onPackageReady`, which runs **before** that swap.
 * So every hook got installed against the pre-patch loader: 30 installed, 0 failed, and the keyboard
 * ignored all 30 — the hooks sat on `ArtMethod`s the host never calls. The status report was
 * perfectly green, the switches were on, and nothing happened.
 *
 * ### What this does instead of guessing
 *
 * It watches for the swap and reinstalls on the far side of it:
 *
 * 1. `Application#onCreate`, hooked **after** — Tinker performs the swap inside the host's own
 *    `Application#attachBaseContext` override, *after* its `super` call, so `onCreate` is the first
 *    callback that runs on the far side of it. Measured on 2026-09-28: the swap is seen ~0.4 s into
 *    start-up, long before any keyboard layout;
 * 2. a short bounded probe thread, for the case where the framework hands `onPackageReady` over too
 *    late for (1) to still catch it.
 *
 * Both routes funnel into [inspect], which is idempotent by construction: it acts only when the
 * loader differs *by identity* from the one [Bridge.installedClassLoader] holds, and the swap itself
 * ([Bridge.adoptClassLoader]) refuses a second time for the same loader. So the lifecycle hook
 * firing twice, or racing the probe thread, cannot double-install.
 *
 * ### Why identity, and why one callback is enough
 *
 * A name check is not trustworthy here. Which name a Tinker loader prints depends on the branch its
 * `NewClassLoaderInjector` took — `DelegateLastClassLoader` on SDK >= 27, `TinkerClassLoader` below
 * that — *and* on what the previous loader happened to be, so after a second swap the two names
 * would agree and the check would silently no-op. On ART, one loader also cannot define the same
 * class twice, so two `Class` objects for `utils.n1` can only mean two loaders — which makes `===`
 * both the necessary and the sufficient test.
 *
 * `Application` is defined by the boot class loader and is the same `Class` object for every loader
 * in the process, so the watch survives the very swap it is watching for. That is why it goes
 * through [Bridge.registerDetached] rather than [Bridge.register]: the latter's handles are
 * deliberately released on a swap, and unhooking the watch would leave the module unable to notice
 * a second one.
 */
internal object ClassLoaderGuard {

    /** The replaced loader, plus a human-readable note on what noticed it. */
    @Volatile
    private var onReplaced: ((ClassLoader, String) -> Unit)? = null

    /** One installation per process, however many times the installer path is entered. */
    private val watching = AtomicBoolean(false)

    /**
     * Starts watching, and hands over what to do when the host changes its loader.
     *
     * Idempotent. Called from the module's normal install path, which every entry point out of
     * start-up reaches — a cold start, a hot reload, and a reinstall after a swap.
     */
    fun watch(onLoaderReplaced: (ClassLoader, String) -> Unit) {
        onReplaced = onLoaderReplaced
        // Set before the hooks go in, so a callback that fires during installation finds the
        // callback in place rather than a half-built object.
        if (!watching.compareAndSet(false, true)) return
        hookApplicationOnCreate()
        startFallbackProbe()
    }

    /**
     * The one decision point: is [loader] different from the one the hooks were built against?
     *
     * Silent and cheap on every call that answers "no", which is every call in a normal process.
     */
    private fun inspect(loader: ClassLoader?, source: String) {
        if (loader == null) return
        // A `null` here means no installation has happened yet, i.e. `onPackageReady` has not run
        // or is still inside it — the module's own install path owns that case, and acting now would
        // race it into installing twice against two different loaders.
        val installed = Bridge.installedClassLoader() ?: return
        if (installed === loader) return
        Log.i(
            "Host class loader changed at $source: " +
                "${Bridge.classLoaderDescription()} -> ${loader.javaClass.name} " +
                "(identity ${System.identityHashCode(installed)} -> " +
                "${System.identityHashCode(loader)}); reinstalling hooks"
        )
        onReplaced?.invoke(loader, source)
    }

    /**
     * `Application#onCreate`, after-hooked.
     *
     * This one callback is enough, and an earlier revision that also hooked `attachBaseContext` was
     * wrong twice over. First it could never install: `Application` does not *declare*
     * `attachBaseContext` — `ContextWrapper` does, and `getDeclaredMethod` does not search
     * superclasses — so it threw `NoSuchMethodException` in every process and put a `Failed: ` line
     * in the user-visible report on every start. Second, even resolved correctly it would have been
     * too early: Tinker performs the swap inside the *host's own* `Application#attachBaseContext`
     * override, **after** its `super` call, so an after-hook there always sees the old loader.
     * `onCreate` runs once `attachBaseContext` has returned — on the far side — and still well
     * before the keyboard is laid out. It has been removed rather than repaired.
     *
     * It reports the *application's* loader, which is the one the keyboard's classes are actually
     * resolved through — not the framework's `param.classLoader`, which is captured before the swap
     * and is exactly the stale value this whole mechanism exists to notice.
     */
    private fun hookApplicationOnCreate() {
        hookAfter("onCreate")
    }

    /**
     * Installs one after-hook, reporting the outcome in the log the diagnostics screen reads.
     *
     * The callback body is wrapped: it runs inside the host's start-up, and a throw from here would
     * be this module breaking the keyboard rather than observing it.
     *
     * A successful install deliberately does **not** use the `Success: ` prefix the functional hooks
     * use. Those prefixes feed the report's "N hooks installed" line, which is a count of keyboard
     * behaviour; folding a diagnostic watch into it would move a number users and I both read as a
     * baseline. A *failed* install does use `Failed: `, because that one is a real problem and
     * should stand out in the report.
     */
    private fun hookAfter(name: String) {
        val failure = runCatching {
            val method = Application::class.java
                .getDeclaredMethod(name)
                .apply { isAccessible = true }
            Bridge.registerDetached(method, "loader-watch") { chain ->
                val result = chain.proceed()
                runCatching { inspect((chain.thisObject as? Application)?.classLoader, "Application#$name") }
                result
            }
        }.exceptionOrNull()
        if (failure == null) {
            Log.i("ClassLoaderGuard: watching Application#$name for a class loader swap")
        } else {
            Log.i("Failed: ClassLoaderGuard on Application#$name - " +
                "${failure.javaClass.simpleName}: ${failure.message}")
        }
    }

    /**
     * A bounded poll of the application's loader, covering the one gap the watch above cannot.
     *
     * If the framework delivered `onPackageReady` so late that `attachBaseContext` — and therefore
     * `onCreate` — had already run, there is no callback left to catch, but the loader still
     * changed and this notices it. Bounded on purpose: 30 s is far longer than any start-up, it is
     * a daemon thread, and each iteration is one reflection call and a reference comparison.
     */
    private fun startFallbackProbe() {
        Thread {
            var attempt = 0
            while (attempt < PROBE_ATTEMPTS) {
                attempt++
                runCatching { Thread.sleep(PROBE_INTERVAL_MS) }
                val application = currentApplication() ?: continue
                runCatching { inspect(application.classLoader, "start-up probe #$attempt") }
            }
        }.apply {
            name = "wetypeplus-loader-probe"
            isDaemon = true
        }.start()
    }

    private const val PROBE_ATTEMPTS = 20
    private const val PROBE_INTERVAL_MS = 1_500L
}
