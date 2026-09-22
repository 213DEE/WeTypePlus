package cn.dsr213.wetypeplus.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.dsr213.wetypeplus.AppSettings
import cn.dsr213.wetypeplus.ModuleStatus
import cn.dsr213.wetypeplus.ModuleStatusStore

/**
 * The half of the cross-process channel that lives in *this* app's process.
 *
 * Declared in the manifest and addressed by explicit component, which is the only shape that
 * survives Android's package-visibility filtering when the sender is another app's process - see
 * [SettingsBridge] for the measurements behind that.
 *
 * Two things arrive here, and both are answers to questions the host process cannot ask any other
 * way:
 *
 * * [SettingsBridge.ACTION_REPORT] - what the module found when it loaded inside WeType. Without
 *   this, "the module is enabled and nothing happens" is indistinguishable from "the module never
 *   ran", because every fact this app can gather on its own is true either way.
 * * [SettingsBridge.ACTION_REQUEST_SETTINGS] - the host has started and has no value to work from,
 *   so it asks for the switches.
 *
 * Neither is authenticated. See the class comment on [SettingsBridge] for why that is unavoidable
 * and what it is worth to an attacker: a forged report can mislead one screen, and a forged
 * settings message can flip this module's own two switches inside the host. Nothing here can
 * execute, and the hooks never read anything this receiver writes.
 */
class BridgeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            SettingsBridge.ACTION_REPORT -> acceptReport(context, intent)
            SettingsBridge.ACTION_REQUEST_SETTINGS -> {
                // Answering with the stored switches is the whole point of the request: the host
                // caches whatever it hears, and the app's own copy is authoritative.
                AppSettings.push(context)
            }
        }
    }

    private fun acceptReport(context: Context, intent: Intent) {
        val report = intent.extras ?: return
        val wireVersion = report.getInt(SettingsBridge.KEY_WIRE_VERSION, 0)
        if (wireVersion != SettingsBridge.WIRE_VERSION) {
            // Not an error worth showing anyone: it means this app and the module half inside
            // WeType were installed at different times, so one of them is mid-upgrade.
            Log.i("Ignoring report with wire version $wireVersion")
            return
        }
        val status = ModuleStatus.fromReport(report)
        // Written on the main thread, which is where a receiver runs. That is deliberate: the file
        // is a few hundred bytes and the alternative - a thread that outlives `onReceive` - is
        // exactly how a report gets lost, because the process may be frozen the moment this
        // method returns. `commit`, not `apply`, for the same reason.
        ModuleStatusStore.write(context, status)
        Log.i(
            "Report accepted from ${status.processName}: " +
                "${status.installed.size} hooks installed, ${status.failed.size} failed"
        )
    }
}
