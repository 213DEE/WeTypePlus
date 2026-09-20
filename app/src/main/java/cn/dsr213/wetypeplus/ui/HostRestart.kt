package cn.dsr213.wetypeplus.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

private const val HOST_PACKAGE = "com.tencent.wetype"
private const val HOST_IME_ID = "com.tencent.wetype/.plugin.hld.WxHldService"

/**
 * Force-stops WeType and starts it again, so the framework rebinds the module to a fresh process.
 *
 * Two paths, tried in order:
 *
 * 1. `su -c "am force-stop …; ime enable …; ime set …"` - works when root is available, and it is
 *    also the only path that repairs the input-method binding. `force-stop` alone leaves the IME
 *    unregistered, so the `ime` calls matter as much as the kill.
 * 2. `ActivityManager.killBackgroundProcesses` - the unprivileged fallback, backed by the
 *    `KILL_BACKGROUND_PROCESSES` normal permission. It cannot re-bind the IME, hence the launch
 *    at the end: opening the host app brings its process back and the framework re-attaches.
 *
 * Runs off the main thread and reports back on it.
 */
internal fun restartHostIme(context: Context, onResult: (Boolean) -> Unit) {
    val appContext = context.applicationContext ?: context
    Thread {
        val viaRoot = runCatching {
            ProcessBuilder(
                "su", "-c",
                "am force-stop $HOST_PACKAGE; ime enable $HOST_IME_ID; ime set $HOST_IME_ID"
            )
                .redirectErrorStream(true)
                .start()
                .waitFor() == 0
        }.getOrDefault(false)

        val viaFramework = runCatching {
            val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            manager.killBackgroundProcesses(HOST_PACKAGE)
            true
        }.getOrDefault(false)

        if (viaRoot || viaFramework) {
            runCatching {
                appContext.packageManager.getLaunchIntentForPackage(HOST_PACKAGE)
                    ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    ?.let(appContext::startActivity)
            }
        }

        Handler(Looper.getMainLooper()).post { onResult(viaRoot || viaFramework) }
    }.apply { isDaemon = true }.start()
}
