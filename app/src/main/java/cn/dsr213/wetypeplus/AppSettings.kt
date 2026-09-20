package cn.dsr213.wetypeplus

import android.content.Context

/**
 * The settings store, owned by this app's own process.
 *
 * Kept deliberately boring: one private `SharedPreferences` file, two booleans. The hooks inside
 * WeType's process cannot open it directly - a different uid owns it - so they read the same
 * values through [cn.dsr213.wetypeplus.bridge.SettingsProvider] instead.
 *
 * Legacy keys from before 2026-09-21 (`sync_side_margins`, `exclusive_hand_split`) are neither read
 * nor written any more. They are left on disk rather than cleaned up: nothing consults them, so
 * they are inert, and leaving them means a downgrade still finds its old settings intact.
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
            )
        )
    }

    fun write(context: Context, settings: KeyboardSettings) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KeyboardSettings.COLUMN_UNLOCK_WIDTH, settings.unlockKeyboardWidth)
            .putBoolean(KeyboardSettings.COLUMN_UNLOCK_SINGLE_HAND, settings.unlockSingleHandMode)
            .commit()
    }
}
