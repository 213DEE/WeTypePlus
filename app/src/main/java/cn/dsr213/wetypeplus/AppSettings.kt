package cn.dsr213.wetypeplus

import android.content.Context
import cn.dsr213.wetypeplus.bridge.Log
import cn.dsr213.wetypeplus.bridge.SettingsBridge

/**
 * The settings store. This app's own process owns it; the keyboard hooks only ever read it.
 *
 * Kept deliberately boring: one private `SharedPreferences` file, two booleans. The hooks inside
 * WeType's process cannot open that file directly - a different uid owns it - so the values reach
 * them over the broadcast channel described in [SettingsBridge], and this object owns the one
 * place they are sent from.
 *
 * Legacy keys from before 2026-09-21 (`sync_side_margins`, `exclusive_hand_split`) are neither read
 * nor written any more. They are left on disk rather than cleaned up: nothing consults them, so
 * they are inert, and leaving them means a downgrade still finds its old settings intact.
 */
object AppSettings {

    /**
     * Named through [cn.dsr213.wetypeplus.bridge.SettingsBridge] on purpose.
     *
     * The hooks inside WeType read this very file - through the framework's remote-file channel,
     * where the path is the file name - so a rename on one side that missed the other would leave
     * the switches silently inert. That is the failure this project already spent a release
     * chasing, so the name is written once, in [SettingsBridge.SETTINGS_PREFS_FILE].
     */
    private const val PREFS_NAME = SettingsBridge.SETTINGS_PREFS_FILE

    fun read(context: Context): KeyboardSettings {
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return KeyboardSettings(
            unlockKeyboardWidth = preferences.getBoolean(
                KeyboardSettings.COLUMN_UNLOCK_WIDTH, true
            ),
            unlockSingleHandMode = preferences.getBoolean(
                KeyboardSettings.COLUMN_UNLOCK_SINGLE_HAND, true
            )
        )
    }

    /**
     * Stores the switches and immediately tells the host about them.
     *
     * Pushing from inside the write is what keeps the two halves from drifting: a caller cannot set
     * a switch and forget to announce it, and the host never has to poll. Every write in this app
     * goes through here, so this is the single point where "the user changed something" becomes
     * "WeType knows".
     */
    fun write(context: Context, settings: KeyboardSettings) {
        // `commit` rather than `apply`: the broadcast below is sent immediately, and a host that
        // asked a fraction of a second later would read the *old* file if the write were still
        // queued.
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KeyboardSettings.COLUMN_UNLOCK_WIDTH, settings.unlockKeyboardWidth)
            .putBoolean(KeyboardSettings.COLUMN_UNLOCK_SINGLE_HAND, settings.unlockSingleHandMode)
            .commit()
        push(context, settings)
    }

    /**
     * Sends the stored switches to WeType, without changing anything.
     *
     * Called on every launch of this app, and in answer to the host asking. The launch call is the
     * one that matters for a cold start: the host reads its value at process start and may ask
     * before this app has ever run, so opening the settings app is what repairs a host that is
     * running on defaults.
     */
    fun push(context: Context) {
        push(context, read(context))
    }

    private fun push(context: Context, settings: KeyboardSettings) {
        // `sendBroadcast` has no return value and throws nothing when nothing is listening - the
        // host is very often not running, and that is not an error. The logcat line is the only
        // observable trace, and it is what a bug report from `adb logcat` would show.
        runCatching { context.sendBroadcast(SettingsBridge.settingsIntent(settings)) }
            .onSuccess { Log.i("Switches sent to ${SettingsBridge.HOST_PACKAGE}: $settings") }
            .onFailure { Log.i("Could not send switches: ${it.javaClass.simpleName}: ${it.message}") }
    }

    /**
     * Asks the host for a fresh status report.
     *
     * Lives here because this object owns the one direction this app sends in: the switches, and
     * the request for what came back. The answer is asynchronous - the host builds and sends it
     * from a thread of its own, precisely so a cold start of WeType is not held up by it - so a
     * caller that wants to see the result has to wait a moment before reading
     * [ModuleStatusStore]. See the diagnostics screen for how long.
     *
     * It is also what makes the module's log readable without root: the log tail travels inside
     * the report, because the framework's own log files are root-only and WeType's uid owns
     * neither this app's storage nor those files.
     */
    fun askHostForReport(context: Context) {
        runCatching { context.sendBroadcast(SettingsBridge.reportRequestIntent()) }
            .onSuccess { Log.i("Asked ${SettingsBridge.HOST_PACKAGE} for a status report") }
            .onFailure {
                Log.i("Could not ask for a report: ${it.javaClass.simpleName}: ${it.message}")
            }
    }
}
