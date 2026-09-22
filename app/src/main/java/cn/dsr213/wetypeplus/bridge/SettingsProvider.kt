package cn.dsr213.wetypeplus.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import cn.dsr213.wetypeplus.AppSettings
import cn.dsr213.wetypeplus.KeyboardSettings

/**
 * The process boundary, as it was first imagined.
 *
 * A `ContentProvider` is the documented way to expose a couple of values from one app to another,
 * and for a while this class was the whole cross-process story: the hooks inside WeType queried
 * it, and [call] carried the module's status report back. Then it was measured on a real device
 * and found to be unreachable, because Android filters provider *authority resolution* by package
 * visibility and the host neither lists this app in its `<queries>` nor can be made to. The call
 * fails with `IllegalArgumentException: Unknown authority`, in both directions.
 *
 * So what is left here is one direction and no promises: [query] still answers, for any caller that
 * *can* reach it - a non-Android app, a shell, or a future host that happens to see this app. The
 * hooks do not depend on it; they read the same values over the broadcast channel and the
 * framework's remote file, and [SettingsBridge] records what each one is worth. The report half was
 * deleted outright rather than left as a dead fallback, because a fallback that has never once
 * worked is worse than no fallback at all.
 */
class SettingsProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = SettingsBridge.AUTHORITY

        /** Kept as a field so existing call sites read the same way they always have. */
        val CONTENT_URI: Uri = SettingsBridge.CONTENT_URI
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

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    /**
     * Present only because `ContentProvider` declares it abstract.
     *
     * The report used to arrive here. It arrives at [BridgeReceiver] now, for the reason in the
     * class comment.
     */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = null
}
