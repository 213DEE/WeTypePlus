package cn.dsr213.wetypeplus.hook

import android.os.SystemClock
import cn.dsr213.wetypeplus.KeyboardSettings
import cn.dsr213.wetypeplus.bridge.SettingsProvider
import cn.dsr213.wetypeplus.bridge.currentApplication
import java.util.concurrent.atomic.AtomicLong

/**
 * The hook side's read-only view of the user's settings.
 *
 * Reads happen inside WeType's process, so they go through [SettingsProvider] rather than
 * touching this app's private storage. Results are cached for a short window because the hooks
 * sit on hot paths - `m1.g1()` in particular is evaluated on every keyboard width calculation -
 * and one binder round trip per evaluation would be wasteful. A one-second window means a flipped
 * switch takes effect on the next layout, while keeping the worst case at one query per second.
 *
 * Any failure (provider not callable, host not installed, no `Application` yet) keeps the last
 * known values, or the defaults, which are all-features-on.
 *
 * Only genuinely optional behaviour lives here. Margin centring and hand/split exclusivity do not:
 * they are structural, so they are unconditional in [WeTypeLayoutHooks] rather than configurable.
 */
object HookSettings {
    private const val CACHE_TTL_MS = 1000L

    @Volatile
    private var cached: KeyboardSettings = KeyboardSettings.DEFAULT

    private val lastRead = AtomicLong(0L)
    private val lock = Any()

    val unlockKeyboardWidth: Boolean get() = snapshot().unlockKeyboardWidth

    val unlockSingleHandMode: Boolean get() = snapshot().unlockSingleHandMode

    fun prepareForHotReload() {
        cached = KeyboardSettings.DEFAULT
        lastRead.set(0L)
    }

    private fun snapshot(): KeyboardSettings {
        if (SystemClock.uptimeMillis() - lastRead.get() < CACHE_TTL_MS) return cached
        synchronized(lock) {
            val now = SystemClock.uptimeMillis()
            if (now - lastRead.get() >= CACHE_TTL_MS) {
                lastRead.set(now)
                readFromProvider()?.let { cached = it }
            }
        }
        return cached
    }

    private fun readFromProvider(): KeyboardSettings? = runCatching {
        val application = currentApplication() ?: return@runCatching null
        application.contentResolver
            .query(SettingsProvider.CONTENT_URI, null, null, null, null)
            ?.use { cursor ->
                // Column order is KeyboardSettings.COLUMNS, written by the provider above.
                if (!cursor.moveToFirst() || cursor.columnCount < 2) return@use null
                KeyboardSettings(
                    unlockKeyboardWidth = cursor.getInt(0) != 0,
                    unlockSingleHandMode = cursor.getInt(1) != 0
                )
            }
    }.getOrNull()
}
