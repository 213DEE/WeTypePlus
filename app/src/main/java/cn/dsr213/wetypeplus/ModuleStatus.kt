package cn.dsr213.wetypeplus

import android.content.Context
import android.os.Bundle
import cn.dsr213.wetypeplus.bridge.SettingsBridge
import kotlin.math.abs

/**
 * The last thing the module said about itself from inside WeType's process.
 *
 * This is the answer to a question the settings app otherwise cannot ask: *is the module running
 * where it needs to run?* Everything the UI can inspect on its own - the APK, this app's own
 * preferences - is true whether or not the hooks ever loaded. Only the host process knows, and
 * this is what it reports back.
 *
 * A `null` [ModuleStatus] is meaningful, not missing data: it says the module has never reported
 * from inside WeType, which is the signature of a framework that declined to load it, a scope that
 * does not include the host, or a host process that predates the install.
 */
data class ModuleStatus(
    val timestamp: Long,
    val frameworkName: String,
    val frameworkVersion: String,
    val frameworkVersionCode: Long,
    val apiVersion: Int,
    val requiredApiVersion: Int,
    val processName: String,
    val hostVersion: String,
    val installed: List<String>,
    val failed: List<String>,
    /**
     * The module's own recent log lines, oldest first, exactly as the host's log holds them.
     *
     * These travel inside the report because the module has nowhere else to put them that this app
     * can reach: WeType's uid owns neither this app's storage nor the framework's log directory.
     * Carrying them here is what makes a usable bug report possible without root.
     */
    val logLines: List<String> = emptyList(),
    /**
     * Which channel supplied the switches in force inside the host, and what they are.
     *
     * Empty when the report came from a module version that did not send it, which reads the same
     * as "unknown" on screen - the honest answer for an older module half.
     */
    val settingsSummary: String = ""
) {
    /**
     * True when the framework is older than the module's own `minApiVersion`.
     *
     * A framework in this state normally refuses to load the module at all, so a report carrying
     * it means the framework loaded us despite the declaration - worth flagging either way, since
     * the declared level is what the next release will keep assuming.
     */
    val frameworkTooOld: Boolean
        get() = apiVersion in 1 until requiredApiVersion

    /**
     * The layout runs in WeType's `:hld` process, so a report from anywhere else has not reached
     * the part of the host the hooks exist for.
     */
    val fromKeyboardProcess: Boolean
        get() = processName == SettingsBridge.HOST_KEYBOARD_PROCESS

    companion object {
        fun fromReport(bundle: Bundle): ModuleStatus = ModuleStatus(
            timestamp = bundle.longOr(SettingsBridge.KEY_TIMESTAMP, 0L),
            frameworkName = bundle.stringOr(SettingsBridge.KEY_FRAMEWORK_NAME),
            frameworkVersion = bundle.stringOr(SettingsBridge.KEY_FRAMEWORK_VERSION),
            frameworkVersionCode = bundle.longOr(SettingsBridge.KEY_FRAMEWORK_VERSION_CODE, 0L),
            apiVersion = bundle.intOr(SettingsBridge.KEY_API_VERSION, 0),
            requiredApiVersion = bundle.intOr(
                SettingsBridge.KEY_MIN_API_VERSION,
                SettingsBridge.REQUIRED_API_VERSION
            ),
            processName = bundle.stringOr(SettingsBridge.KEY_PROCESS_NAME),
            hostVersion = bundle.stringOr(SettingsBridge.KEY_HOST_VERSION),
            installed = bundle.stringListOr(SettingsBridge.KEY_INSTALLED),
            failed = bundle.stringListOr(SettingsBridge.KEY_FAILED),
            logLines = bundle.logListOr(SettingsBridge.KEY_LOG),
            settingsSummary = bundle.stringOr(SettingsBridge.KEY_SETTINGS)
                .take(MAX_LABEL_LENGTH)
        )
    }
}

/**
 * Bounds on what an unauthenticated report can put on screen.
 *
 * The report crosses from another app's process, over a channel any app can send to, so neither
 * the number of entries nor their length is trustworthy. These caps sit far above what an honest
 * report carries - a couple of dozen hook labels, a few hundred log lines - and exist only so that
 * a hostile bundle cannot turn the diagnostics screen into a memory sink.
 */
private const val MAX_HOOK_LABELS = 256
private const val MAX_LABEL_LENGTH = 200
private const val MAX_LOG_LINES = 400
private const val MAX_LOG_LINE_LENGTH = 400

/**
 * How close in time two reports must be to count as answers to the same request.
 *
 * Reports that belong to one request arrive within milliseconds of each other; anything further
 * apart is a separate reading and is compared on its own merits. See [ModuleStatusStore.write].
 */
private const val STALE_REPORT_WINDOW_MS = 15_000L

/**
 * Readers that cannot throw, for a bundle written by another process.
 *
 * `Bundle.get*` throws on a type mismatch rather than returning the default, and a mismatch is
 * entirely reachable here: any app can send to this receiver, and an older or newer module half
 * legitimately writes a different shape. A malformed report should degrade to an empty row, not
 * take down the screen that exists to explain problems.
 */
private fun Bundle.stringOr(key: String): String =
    runCatching { getString(key) }.getOrNull().orEmpty()

private fun Bundle.longOr(key: String, fallback: Long): Long =
    runCatching { getLong(key, fallback) }.getOrDefault(fallback)

private fun Bundle.intOr(key: String, fallback: Int): Int =
    runCatching { getInt(key, fallback) }.getOrDefault(fallback)

private fun Bundle.stringListOr(key: String): List<String> =
    runCatching { getStringArrayList(key) }.getOrNull()
        .orEmpty()
        .take(MAX_HOOK_LABELS)
        .map { it.take(MAX_LABEL_LENGTH) }

private fun Bundle.logListOr(key: String): List<String> =
    runCatching { getStringArrayList(key) }.getOrNull()
        .orEmpty()
        .take(MAX_LOG_LINES)
        .map { it.take(MAX_LOG_LINE_LENGTH) }

/**
 * The release part of a version string: its first three dot-separated segments.
 *
 * The comparison this exists for is "is this the WeType build our host-dependent hooks were
 * written against". Doing it on the whole string would be wrong in a way that raises a false
 * alarm: WeType's `versionName` carries a fourth, build-level segment (`3.5.3.56201`), and
 * `3.5.3` names the same release - the one that ships inside it. The release is the right unit
 * because a release change is exactly when the host's obfuscated class and member names can move
 * out from under the hooks; a build suffix cannot.
 *
 * A string with fewer than three segments is returned whole rather than padded, so an
 * unparseable version compares as itself instead of collapsing to a prefix that matches
 * everything.
 */
internal fun releaseSegment(version: String): String =
    version.split('.').take(3).joinToString(".")

/**
 * Persists the module's last report.
 *
 * A plain private `SharedPreferences` file, written by [cn.dsr213.wetypeplus.bridge.BridgeReceiver]
 * and read by the settings screen. Nothing here is writable from outside the app beyond that one
 * receiver, and every value is display-only - the hooks never read this file, so a stale or forged
 * report can never change how the keyboard behaves.
 */
object ModuleStatusStore {
    private const val PREFS_NAME = "wetype_plus_status"

    private const val KEY_TIMESTAMP = "timestamp"
    private const val KEY_FRAMEWORK_NAME = "framework_name"
    private const val KEY_FRAMEWORK_VERSION = "framework_version"
    private const val KEY_FRAMEWORK_VERSION_CODE = "framework_version_code"
    private const val KEY_API_VERSION = "api_version"
    private const val KEY_REQUIRED_API_VERSION = "required_api_version"
    private const val KEY_PROCESS_NAME = "process_name"
    private const val KEY_HOST_VERSION = "host_version"
    private const val KEY_INSTALLED = "installed"
    private const val KEY_FAILED = "failed"
    private const val KEY_LOG = "log"
    private const val KEY_SETTINGS = "settings"

    /** Labels and log lines never contain a newline, so one separator round-trips them losslessly. */
    private const val SEPARATOR = "\n"

    fun read(context: Context): ModuleStatus? {
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val timestamp = preferences.getLong(KEY_TIMESTAMP, 0L)
        if (timestamp == 0L) return null
        return ModuleStatus(
            timestamp = timestamp,
            frameworkName = preferences.getString(KEY_FRAMEWORK_NAME, "").orEmpty(),
            frameworkVersion = preferences.getString(KEY_FRAMEWORK_VERSION, "").orEmpty(),
            frameworkVersionCode = preferences.getLong(KEY_FRAMEWORK_VERSION_CODE, 0L),
            apiVersion = preferences.getInt(KEY_API_VERSION, 0),
            requiredApiVersion = preferences.getInt(
                KEY_REQUIRED_API_VERSION,
                SettingsBridge.REQUIRED_API_VERSION
            ),
            processName = preferences.getString(KEY_PROCESS_NAME, "").orEmpty(),
            hostVersion = preferences.getString(KEY_HOST_VERSION, "").orEmpty(),
            installed = preferences.getString(KEY_INSTALLED, "").orEmpty().splitToList(),
            failed = preferences.getString(KEY_FAILED, "").orEmpty().splitToList(),
            logLines = preferences.getString(KEY_LOG, "").orEmpty().splitToList(),
            settingsSummary = preferences.getString(KEY_SETTINGS, "").orEmpty()
        )
    }

    fun write(context: Context, status: ModuleStatus) {
        // A report from the keyboard process is the one that answers "did this work". The host's
        // main process installs the same hooks and reports too, but it never builds a keyboard -
        // so letting its report land second would bury the interesting one under a less
        // interesting one. Keyboard reports always win; a report from anywhere else only fills an
        // empty slot.
        val existing = read(context)
        if (existing != null && existing.fromKeyboardProcess && !status.fromKeyboardProcess) {
            return
        }
        // A report with no hook results at all must not displace a recent report that has them.
        //
        // After a hot reload the retired generations keep their broadcast receivers - they were
        // registered against the host's `Application`, and only the new generation's code can know
        // to stay quiet about it. One report request therefore produces several: measured after an
        // update under a running WeType, three reports from the same `:hld` process arrived within
        // two milliseconds - the live one with "24 hooks installed" and two stale ones with "0 hooks
        // installed" - and with plain last-write-wins the screen showed an empty hook list.
        //
        // The rule is deliberately order-independent, because arrival order is not guaranteed: an
        // empty report is only refused while a *recent* report that has hooks is already stored, so
        // whichever order they arrive in, the report with the hook list is what ends up on screen. A
        // genuine all-failed install still lands (that report carries entries in `failed`), and so
        // does an empty report that is the only thing we have.
        val incomingHasNoResults = status.installed.isEmpty() && status.failed.isEmpty()
        val existingIsRecentAndHasResults = existing != null &&
            existing.installed.isNotEmpty() &&
            abs(status.timestamp - existing.timestamp) <= STALE_REPORT_WINDOW_MS
        if (incomingHasNoResults && existingIsRecentAndHasResults) {
            return
        }
        // The framework's identity is carried forward, not overwritten with a blank.
        //
        // A report can legitimately arrive without it: the module is loaded by the framework once
        // per process, and a hot reload starts a generation that never got that hand-over. Writing
        // that blank through would replace a known-good "LSPosed 2.2.0 / API 102" with "未知 /
        // API 0" on screen - the row would get *worse* the longer the module ran. A non-blank value
        // in the incoming report always wins, so a genuine framework switch still shows up.
        val frameworkName = status.frameworkName.ifBlank { existing?.frameworkName.orEmpty() }
        val frameworkVersion = status.frameworkVersion.ifBlank { existing?.frameworkVersion.orEmpty() }
        val frameworkVersionCode = status.frameworkVersionCode
            .takeIf { it > 0L } ?: existing?.frameworkVersionCode ?: 0L
        val apiVersion = status.apiVersion
            .takeIf { it > 0 } ?: existing?.apiVersion ?: 0
        // `commit` rather than `apply`: the caller is a broadcast receiver that is about to return,
        // after which this process may be frozen, and losing the report there would leave the user
        // staring at "no report" with no way to know one had arrived.
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_TIMESTAMP, status.timestamp)
            .putString(KEY_FRAMEWORK_NAME, frameworkName)
            .putString(KEY_FRAMEWORK_VERSION, frameworkVersion)
            .putLong(KEY_FRAMEWORK_VERSION_CODE, frameworkVersionCode)
            .putInt(KEY_API_VERSION, apiVersion)
            .putInt(KEY_REQUIRED_API_VERSION, status.requiredApiVersion)
            .putString(KEY_PROCESS_NAME, status.processName)
            .putString(KEY_HOST_VERSION, status.hostVersion)
            .putString(KEY_INSTALLED, status.installed.joinToString(SEPARATOR))
            .putString(KEY_FAILED, status.failed.joinToString(SEPARATOR))
            .putString(KEY_LOG, status.logLines.joinToString(SEPARATOR))
            .putString(KEY_SETTINGS, status.settingsSummary)
            .commit()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun String.splitToList(): List<String> =
        if (isEmpty()) emptyList() else split(SEPARATOR).filter { it.isNotBlank() }
}

/**
 * What the settings app can find out on its own, without the module having loaded.
 *
 * This exists for the case the report cannot cover: if the framework never loads the module, no
 * report ever arrives, and the user needs *something* to act on. Package versions are that
 * something, and they need no framework at all.
 *
 * Every lookup is best-effort. Android 11+ hides packages this app has not declared in its
 * `<queries>`, and a hidden package is indistinguishable from an absent one - so a `null` here
 * means "not visible", which the screen phrases accordingly instead of claiming the app is
 * missing.
 */
object EnvironmentProbe {

    data class PackageVersion(
        val packageName: String,
        val versionName: String,
        val versionCode: Long
    )

    fun packageVersion(context: Context, packageName: String): PackageVersion? = runCatching {
        val info = context.packageManager.getPackageInfo(packageName, 0)
        PackageVersion(
            packageName = packageName,
            versionName = info.versionName.orEmpty(),
            // minSdk is 31, so `longVersionCode` is always available.
            versionCode = info.longVersionCode
        )
    }.getOrNull()

    /** The framework manager, whichever of the known package names this device happens to use. */
    fun frameworkManager(context: Context): PackageVersion? =
        SettingsBridge.FRAMEWORK_MANAGER_PACKAGES.firstNotNullOfOrNull { name ->
            packageVersion(context, name)
        }

    fun host(context: Context): PackageVersion? =
        packageVersion(context, SettingsBridge.HOST_PACKAGE)

    /** Whether the host declares an input-method service, i.e. is installed as an IME at all. */
    fun hostHasIme(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager ?: return@runCatching false
        manager.inputMethodList.any { it.packageName == SettingsBridge.HOST_PACKAGE }
    }.getOrDefault(false)

    /** Name of the input method currently selected by the user, e.g. `com.tencent.wetype`. */
    fun enabledImePackage(context: Context): String? = runCatching {
        android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.DEFAULT_INPUT_METHOD
        )?.substringBefore('/')
    }.getOrNull()
}
