package cn.dsr213.wetypeplus.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import cn.dsr213.wetypeplus.AppSettings
import cn.dsr213.wetypeplus.KeyboardSettings
import cn.dsr213.wetypeplus.ModuleStatus
import cn.dsr213.wetypeplus.ModuleStatusStore

/**
 * The process boundary.
 *
 * The keyboard hooks are injected into WeType's process, which runs as a different uid and can
 * therefore neither read this app's private preferences nor be handed a `Context` for it. Two
 * ways out of that exist: push the whole settings UI into the host's process (what embedding a
 * dialog there amounts to), or expose the values over a normal Android IPC boundary.
 *
 * This is the second one. It needs no shared storage, no root and no host internals; it is the
 * documented mechanism for exactly this problem, and it lets the settings screen be an ordinary
 * activity in an ordinary process.
 *
 * Two directions travel over it, both narrow:
 *
 * * `query` returns one row of two 0/1 columns - the switch states the hooks need to read.
 * * `call` accepts one status report from the host process and stores it for the diagnostics
 *   screen. Nothing here is read back by the hooks, so a forged or garbled report can only
 *   mislead the *display*; it cannot change how the keyboard behaves.
 */
class SettingsProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = SettingsBridge.AUTHORITY

        /** Kept as a field so existing call sites read the same way they always have. */
        val CONTENT_URI: Uri = SettingsBridge.CONTENT_URI

        /** Result of [call]: `true` when the report was understood and stored. */
        const val RESULT_ACCEPTED = "accepted"
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val columns = MatrixCursor(KeyboardSettings.COLUMNS)
        val context = context ?: return columns
        val settings = AppSettings.read(context)
        columns.addRow(
            arrayOf(
                if (settings.unlockKeyboardWidth) 1 else 0,
                if (settings.unlockSingleHandMode) 1 else 0
            )
        )
        return columns
    }

    /**
     * Receives the module's status report from WeType's process.
     *
     * Runs on a binder thread, so the synchronous store write below cannot stall this app's main
     * thread. A wire-version mismatch is refused rather than decoded: it means the module half and
     * the app half of an upgrade were installed at different times, and reading it as the current
     * shape would put nonsense on screen.
     *
     * The call is unauthenticated. That is deliberate - the host process holds no permission this
     * app could ask for, and the only thing at stake is what the diagnostics screen shows.
     */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != SettingsBridge.METHOD_REPORT) return null
        val context = context ?: return null
        val report = extras ?: return null
        val accepted = report.getInt(SettingsBridge.KEY_WIRE_VERSION, 0) ==
            SettingsBridge.WIRE_VERSION
        if (accepted) {
            ModuleStatusStore.write(context, ModuleStatus.fromReport(report))
        }
        return Bundle().apply { putBoolean(RESULT_ACCEPTED, accepted) }
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
