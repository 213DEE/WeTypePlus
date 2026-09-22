package cn.dsr213.wetypeplus.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import cn.dsr213.wetypeplus.KeyboardSettings
import cn.dsr213.wetypeplus.bridge.Bridge
import cn.dsr213.wetypeplus.bridge.Log
import cn.dsr213.wetypeplus.bridge.SettingsBridge
import cn.dsr213.wetypeplus.bridge.SettingsProvider
import cn.dsr213.wetypeplus.bridge.currentApplication
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The hook side's read-only view of the user's settings.
 *
 * Reads happen inside WeType's process, so this app's private preferences file is not directly
 * reachable - a different uid owns it. Four channels could carry the two booleans, and all four
 * were measured on a real device (LSPosed 2.2.0 / Android 16 / WeType 3.5.3):
 *
 * * **A broadcast from the settings app.** **The only one that works.** Authoritative from the
 *   moment it lands: the app sends it on every switch change, on every launch, and in answer to the
 *   host's request, so there is nothing to poll for afterwards.
 * * **The framework's remote file.** `listRemoteFiles()` returns **empty** - the framework serves
 *   this module no files at all - so the synchronous read of the preferences file never happens.
 * * **The framework's remote preferences.** Read-only, and the key set for this app's group is
 *   **empty**. It is the manager's store, not this app's file.
 * * **The exported provider.** Fails resolution from the host with `Unknown authority`.
 *
 * The three that do not work are still tried, and each one logs what it found, once per process.
 * That is not nostalgia: the log lines are what let the diagnostics screen say *which* channel a
 * host is running on, and a future framework that populates the remote file would start working
 * without a code change. But nothing depends on them - the broadcast is the design.
 *
 * Results are cached because the hooks sit on hot paths - `m1.g1()` is evaluated on every keyboard
 * width calculation - and a framework call per evaluation would be wasteful. Anything that fails
 * keeps the last known values, or the defaults, which are all-features-on.
 *
 * Only genuinely optional behaviour lives here. Margin centring and hand/split exclusivity do not:
 * they are structural, so they are unconditional in [WeTypeLayoutHooks] rather than configurable.
 */
object HookSettings {

    /**
     * How long a pulled value is trusted before it is read again.
     *
     * Only applies to the pull channels. Once the settings app has pushed a value over the
     * broadcast, that value stays authoritative until the next push, and nothing is re-read at all.
     */
    private const val CACHE_TTL_MS = 5_000L

    /**
     * Where the current values came from.
     *
     * Carried into the log because "my switch does nothing" has four different answers depending
     * on which channel answered, and a field report is worth nothing if it does not say.
     */
    enum class Source(val label: String) {
        Default("built-in defaults"),
        Broadcast("settings app broadcast"),
        RemoteFile("framework remote file"),
        FrameworkPreferences("framework remote preferences"),
        Provider("settings provider")
    }

    @Volatile
    private var cached: KeyboardSettings = KeyboardSettings.DEFAULT

    @Volatile
    private var source = Source.Default

    /** True once the settings app has pushed a value; from then on the pull channels are idle. */
    @Volatile
    private var authoritative = false

    @Volatile
    private var listening = false

    @Volatile
    private var asked = false

    @Volatile
    private var channelsLogged = false

    @Volatile
    private var preferencesLogged = false

    private val lastRead = AtomicLong(0L)
    private val noChannelLogged = AtomicBoolean(false)
    private val lock = Any()

    val unlockKeyboardWidth: Boolean get() = snapshot().unlockKeyboardWidth

    val unlockSingleHandMode: Boolean get() = snapshot().unlockSingleHandMode

    /**
     * Which channel supplied the values in force, in one line short enough for a table row.
     *
     * Goes into a status report, because "my switch does nothing" is answered by *which* channel
     * the host actually read from - and that is only knowable from inside the host.
     */
    fun describe(): String =
        "width=${if (cached.unlockKeyboardWidth) "on" else "off"}, " +
            "single-hand=${if (cached.unlockSingleHandMode) "on" else "off"} " +
            "(from ${source.label})"

    fun prepareForHotReload() {
        cached = KeyboardSettings.DEFAULT
        source = Source.Default
        authoritative = false
        lastRead.set(0L)
        // `listening`, `asked` and the probe flags are *not* reset here, and the receiver is not
        // unregistered. A reload loads the next generation in a new class loader, so this object's
        // statics do not carry over anyway - the new generation starts with `listening == false`
        // and registers its own receiver. The old receiver stays registered on the host's
        // `Application` until the process dies, delivering to a generation whose hooks are gone;
        // that costs one object per reload and changes nothing the user can see, which is why it is
        // a known limitation rather than something the reload path has to unwind.
    }

    /**
     * Called from the host's receiver when the settings app pushes the current switches.
     *
     * Deliberately the one place that overrides everything else: the app owns these values, so a
     * push is the user speaking, and no pull result may contradict it.
     */
    fun applyFromApp(settings: KeyboardSettings) {
        val wasAuthoritative = authoritative
        if (wasAuthoritative && settings == cached) return
        cached = settings
        source = Source.Broadcast
        authoritative = true
        lastRead.set(SystemClock.uptimeMillis())
        // Logged on every change but not on a repeat: the settings app re-sends the same values on
        // every launch, and a log that repeats itself is a log nobody reads.
        Log.i("Settings applied from ${Source.Broadcast.label}: $settings")
    }

    /**
     * Starts this process's listening, at a point where doing so is safe.
     *
     * Called from the hook installer so the receiver is up as early as possible, and again from
     * every read so that a process which had no `Context` yet at install time catches up.
     */
    fun startForProcess() {
        // Handed over once so every report this process sends carries the switch state. Registered
        // here rather than read by the report itself because this is where the state lives, and the
        // report runs on a thread that has no reason to know about the hook package.
        if (Bridge.settingsSummary == null) Bridge.settingsSummary = { describe() }
        listenOnce()
        // Not `askOnce()`: at install time the host may not have an `Application` to send from, and
        // the ask is retried from the read path, where it will.
    }

    private fun snapshot(): KeyboardSettings {
        startForProcess()
        if (!authoritative) {
            val now = SystemClock.uptimeMillis()
            if (now - lastRead.get() >= CACHE_TTL_MS) {
                synchronized(lock) {
                    if (SystemClock.uptimeMillis() - lastRead.get() >= CACHE_TTL_MS) {
                        lastRead.set(SystemClock.uptimeMillis())
                        refresh()
                    }
                }
            }
        }
        askOnce()
        return cached
    }

    private fun refresh() {
        val found = pull()
        if (found == null) {
            // Once per process, not per read: this sits on paths that run many times a second, and
            // "no channel answered" is one bit of information rather than a stream.
            if (noChannelLogged.compareAndSet(false, true)) {
                Log.i("No settings channel answered; running on $cached from ${source.label}")
            }
            return
        }
        val (settings, origin) = found
        val changed = settings != cached || origin != source
        cached = settings
        source = origin
        if (changed) Log.i("Settings read from ${origin.label}: $settings")
    }

    /** Each channel in turn, cheapest first. `null` when none of them carries a value. */
    private fun pull(): Pair<KeyboardSettings, Source>? {
        logChannelsOnce()
        readRemoteFile()?.let { return it }
        readFrameworkPreferences()?.let { return it }
        readFromProvider()?.let { return it }
        return null
    }

    /**
     * One line per process naming the files the framework is willing to serve.
     *
     * This is the probe that answers "is the remote file channel usable on this device" without
     * needing root to look at the framework's log: it ends up inside the next status report, which
     * the diagnostics screen shows.
     */
    private fun logChannelsOnce() {
        if (channelsLogged) return
        channelsLogged = true
        Log.i("Settings channels: remoteFiles=${Bridge.remoteFiles()}")
    }

    /**
     * The framework's view of this app's own preferences file.
     *
     * The path is relative to the module's data directory, which is where the settings app's
     * `SharedPreferences` file lives, so this is a synchronous read with no IPC and no visibility
     * rule - when the framework serves it. When it does not, [Bridge.remoteFileText] logs why, once,
     * and this returns `null`.
     */
    private fun readRemoteFile(): Pair<KeyboardSettings, Source>? {
        val text = Bridge.remoteFileText(SettingsBridge.SETTINGS_REMOTE_PATH) ?: return null
        val settings = parsePreferencesXml(text) ?: return null
        return settings to Source.RemoteFile
    }

    /**
     * The framework's preferences object for this app's group.
     *
     * Read-only and, on the test device, empty for this app's group - but it costs one call, and a
     * framework that does serve the module's own file would make it the cheapest channel of all.
     * The key set is logged once so a field report says which of the two it was.
     */
    private fun readFrameworkPreferences(): Pair<KeyboardSettings, Source>? {
        val preferences = Bridge.remotePreferences(SettingsBridge.SETTINGS_PREFS_FILE) ?: return null
        if (!preferencesLogged) {
            preferencesLogged = true
            val keys = runCatching { preferences.all.keys.toList() }.getOrDefault(emptyList())
            Log.i("Settings channels: remote preference keys for " +
                "${SettingsBridge.SETTINGS_PREFS_FILE}=$keys")
        }
        val hasWidth = preferences.contains(KeyboardSettings.COLUMN_UNLOCK_WIDTH)
        val hasSingleHand = preferences.contains(KeyboardSettings.COLUMN_UNLOCK_SINGLE_HAND)
        if (!hasWidth && !hasSingleHand) return null
        val settings = KeyboardSettings(
            unlockKeyboardWidth = runCatching {
                preferences.getBoolean(KeyboardSettings.COLUMN_UNLOCK_WIDTH, true)
            }.getOrDefault(true),
            unlockSingleHandMode = runCatching {
                preferences.getBoolean(KeyboardSettings.COLUMN_UNLOCK_SINGLE_HAND, true)
            }.getOrDefault(true)
        )
        return settings to Source.FrameworkPreferences
    }

    /** The exported provider, which fails resolution from the host but costs nothing to try. */
    private fun readFromProvider(): Pair<KeyboardSettings, Source>? = runCatching {
        val application = currentApplication() ?: return@runCatching null
        application.contentResolver
            .query(SettingsProvider.CONTENT_URI, null, null, null, null)
            ?.use { cursor ->
                // Column order is KeyboardSettings.COLUMNS, written by the provider above.
                if (!cursor.moveToFirst() || cursor.columnCount < 2) return@use null
                KeyboardSettings(
                    unlockKeyboardWidth = cursor.getInt(0) != 0,
                    unlockSingleHandMode = cursor.getInt(1) != 0
                ) to Source.Provider
            }
    }.getOrNull()

    /**
     * Registers the receiver this process listens on, once per process.
     *
     * Two things arrive on it, both from the settings app, and both answered here because this is
     * the host process's single inbound channel:
     *
     * * the switches, which go straight into the cache above;
     * * a request for a fresh status report, which is how the diagnostics screen shows what the
     *   module is doing *now* rather than what it did when WeType last started. That request also
     *   carries the log tail, so the user can send their log without granting anyone root.
     *
     * `RECEIVER_EXPORTED` is the point: the sender is a different app, so a receiver registered as
     * not-exported would never be reached. Whether the flag is required depends on the *host's*
     * target SDK, which this module does not control, so both call shapes are handled.
     *
     * Waiting for the real `Application` is not optional. Registering on the system context fails
     * with `SecurityException: Given caller package android is not running in process …` - measured,
     * three times per process, in the first build of this feature - so a process with no
     * `Application` yet simply retries on its next read instead of failing loudly.
     */
    private fun listenOnce() {
        if (listening) return
        synchronized(lock) {
            if (listening) return
            val context = currentApplication() ?: return
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        SettingsBridge.ACTION_SETTINGS ->
                            SettingsBridge.settingsFromIntent(intent)?.let { applyFromApp(it) }

                        SettingsBridge.ACTION_REQUEST_REPORT ->
                            Bridge.publishStatusAsync("requested")
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(SettingsBridge.ACTION_SETTINGS)
                addAction(SettingsBridge.ACTION_REQUEST_REPORT)
            }
            val registered = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    context.registerReceiver(receiver, filter)
                }
            }.onFailure {
                Log.i(
                    "Could not listen for settings: " +
                        "${it.javaClass.simpleName}: ${it.message}"
                )
            }.isSuccess
            if (registered) {
                listening = true
                Log.i("Listening for settings broadcasts in ${context.packageName}")
            }
        }
    }

    /**
     * Asks the settings app for the switches, once per process.
     *
     * Needed because the host can start before - or without - the app having ever pushed anything:
     * installing the module and then restarting the keyboard is the normal order, and the app has
     * no way to know a new host process is up. The reply arrives as a normal
     * [SettingsBridge.ACTION_SETTINGS] broadcast and is applied by the receiver above.
     */
    private fun askOnce() {
        if (asked) return
        val context = currentApplication() ?: return
        asked = true
        runCatching { context.sendBroadcast(SettingsBridge.settingsRequestIntent()) }
            .onSuccess { Log.i("Asked the settings app for the current switches") }
            .onFailure {
                Log.i("Could not ask for settings: ${it.javaClass.simpleName}: ${it.message}")
            }
    }

    /**
     * Pulls the two booleans out of Android's `SharedPreferences` XML.
     *
     * A regex rather than an XML parser, deliberately. The input is one file this project wrote
     * itself, with a shape fixed by the platform - `<boolean name="x" value="true" />` - and it is
     * read once per process at most, so a parser would be more code for the same answer. Anything
     * that does not match simply leaves the key absent, which reads as "channel unavailable".
     */
    private fun parsePreferencesXml(text: String): KeyboardSettings? {
        val values = BOOLEAN_ENTRY.findAll(text)
            .associate { match -> match.groupValues[1] to match.groupValues[2] }
        val hasWidth = values.containsKey(KeyboardSettings.COLUMN_UNLOCK_WIDTH)
        val hasSingleHand = values.containsKey(KeyboardSettings.COLUMN_UNLOCK_SINGLE_HAND)
        if (!hasWidth && !hasSingleHand) return null
        return KeyboardSettings(
            unlockKeyboardWidth = values[KeyboardSettings.COLUMN_UNLOCK_WIDTH]?.toBoolean() ?: true,
            unlockSingleHandMode =
            values[KeyboardSettings.COLUMN_UNLOCK_SINGLE_HAND]?.toBoolean() ?: true
        )
    }

    private val BOOLEAN_ENTRY = Regex("""name="([^"]+)"\s+value="([^"]*)"""")
}
