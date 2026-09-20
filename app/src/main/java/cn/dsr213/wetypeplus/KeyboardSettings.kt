package cn.dsr213.wetypeplus

/**
 * The switches this module exposes. Plain data with no Android dependency, so the very same type
 * is used in three places:
 *
 * * the settings screen writes it (this app's own process),
 * * the exported provider transports it,
 * * and the keyboard hooks read it (inside WeType's process).
 *
 * Both default to `true`: a fresh install has every feature on, and a settings read that fails for
 * any reason falls back to this rather than silently disabling the module.
 *
 * [2026-09-21] Two switches were removed from here: margin mirroring and hand/split exclusivity.
 * They were not preferences but the terms the rest of the layout is built on - the merged adjust
 * panel only stays centred because both margins are written back equal - so a stored `false` from
 * an older install would have silently restored the off-centre, squashed layout. Both behaviours
 * are now unconditional in the hooks, and there is no longer any state that can turn them off.
 */
data class KeyboardSettings(
    /** Raises the keyboard width ceiling on unfolded / landscape screens. */
    val unlockKeyboardWidth: Boolean = true,
    /** Forces single-hand mode on even though the host disables it on large screens. */
    val unlockSingleHandMode: Boolean = true
) {
    companion object {
        const val COLUMN_UNLOCK_WIDTH = "unlock_keyboard_width"
        const val COLUMN_UNLOCK_SINGLE_HAND = "unlock_single_hand_mode"

        /**
         * Column order of the provider cursor. The hook side reads by index, so the two ends must
         * agree; keeping one array as the single source of truth is what guarantees that.
         */
        val COLUMNS = arrayOf(
            COLUMN_UNLOCK_WIDTH,
            COLUMN_UNLOCK_SINGLE_HAND
        )

        val DEFAULT = KeyboardSettings()
    }
}
