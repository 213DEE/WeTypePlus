package cn.dsr213.wetypeplus

import android.content.Context

/**
 * The settings store, owned by this app's own process.
 *
 * Kept deliberately boring: one private `SharedPreferences` file, four booleans. The hooks inside
 * WeType's process cannot open it directly - a different uid owns it - so they read the same
 * values through [cn.dsr213.wetypeplus.bridge.SettingsProvider] instead.
 */
object AppSettings {
    private const val PREFS_NAME = "wetype_plus"

    fun read(context: Context): KeyboardSettings {
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return KeyboardSettings(
            unlockKeyboardWidth = preferences.getBoolean(
                KeyboardSettings.COLUMN_UNLOCK_WIDTH, true
            ),
            unlockSingleHandMode = preferences.getBoolean(
                KeyboardSettings.COLUMN_UNLOCK_SINGLE_HAND, true
            ),
            syncSideMargins = preferences.getBoolean(
                KeyboardSettings.COLUMN_SYNC_SIDE_MARGINS, true
            ),
            exclusiveHandSplit = preferences.getBoolean(
                KeyboardSettings.COLUMN_EXCLUSIVE_HAND_SPLIT, true
            )
        )
    }

    fun write(context: Context, settings: KeyboardSettings) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KeyboardSettings.COLUMN_UNLOCK_WIDTH, settings.unlockKeyboardWidth)
            .putBoolean(KeyboardSettings.COLUMN_UNLOCK_SINGLE_HAND, settings.unlockSingleHandMode)
            .putBoolean(KeyboardSettings.COLUMN_SYNC_SIDE_MARGINS, settings.syncSideMargins)
            .putBoolean(KeyboardSettings.COLUMN_EXCLUSIVE_HAND_SPLIT, settings.exclusiveHandSplit)
            .commit()
    }
}
