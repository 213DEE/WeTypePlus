package cn.dsr213.wetypeplus

/**
 * Every switch this module exposes. Plain data with no Android dependency, so the very same type
 * is used in three places:
 *
 * * the settings screen writes it (this app's own process),
 * * the exported provider transports it,
 * * and the keyboard hooks read it (inside WeType's process).
 *
 * All four default to `true`: a fresh install has every feature on, and a settings read that
 * fails for any reason falls back to this rather than silently disabling the module.
 */
data class KeyboardSettings(
    /** Raises the keyboard width ceiling on unfolded / landscape screens. */
    val unlockKeyboardWidth: Boolean = true,
    /** Forces single-hand mode on even though the host disables it on large screens. */
    val unlockSingleHandMode: Boolean = true,
    /** Dragging one side margin drags the other along, keeping the keyboard centred. */
    val syncSideMargins: Boolean = true,
    /** Turning on single-hand mode turns split keyboard off, and the other way round. */
    val exclusiveHandSplit: Boolean = true
) {
    companion object {
        const val COLUMN_UNLOCK_WIDTH = "unlock_keyboard_width"
        const val COLUMN_UNLOCK_SINGLE_HAND = "unlock_single_hand_mode"
        const val COLUMN_SYNC_SIDE_MARGINS = "sync_side_margins"
        const val COLUMN_EXCLUSIVE_HAND_SPLIT = "exclusive_hand_split"

        /**
         * Column order of the provider cursor. The hook side reads by index, so the two ends must
         * agree; keeping one array as the single source of truth is what guarantees that.
         */
        val COLUMNS = arrayOf(
            COLUMN_UNLOCK_WIDTH,
            COLUMN_UNLOCK_SINGLE_HAND,
            COLUMN_SYNC_SIDE_MARGINS,
            COLUMN_EXCLUSIVE_HAND_SPLIT
        )

        val DEFAULT = KeyboardSettings()
    }
}
