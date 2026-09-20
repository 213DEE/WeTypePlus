package cn.dsr213.wetypeplus.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import cn.dsr213.wetypeplus.AppSettings
import cn.dsr213.wetypeplus.KeyboardSettings

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
 * The surface is intentionally tiny: one `query` returning one row of two 0/1 columns. There is
 * nothing else to call - no inserts, no updates, no file paths.
 */
class SettingsProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "cn.dsr213.wetypeplus.settings"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/settings")
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
}
