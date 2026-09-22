package cn.dsr213.wetypeplus

import android.content.Context
import android.os.Bundle
import cn.dsr213.wetypeplus.bridge.SettingsBridge

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
    val failed: List<String>
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
            timestamp = bundle.getLong(SettingsBridge.KEY_TIMESTAMP, 0L),
            frameworkName = bundle.getString(SettingsBridge.KEY_FRAMEWORK_NAME).orEmpty(),
            frameworkVersion = bundle.getString(SettingsBridge.KEY_FRAMEWORK_VERSION).orEmpty(),
            frameworkVersionCode =
                bundle.getLong(SettingsBridge.KEY_FRAMEWORK_VERSION_CODE, 0L),
            apiVersion = bundle.getInt(SettingsBridge.KEY_API_VERSION, 0),
            requiredApiVersion = bundle.getInt(
                SettingsBridge.KEY_MIN_API_VERSION,
                SettingsBridge.REQUIRED_API_VERSION
            ),
            processName = bundle.getString(SettingsBridge.KEY_PROCESS_NAME).orEmpty(),
            hostVersion = bundle.getString(SettingsBridge.KEY_HOST_VERSION).orEmpty(),
            installed = bundle.getStringArrayList(SettingsBridge.KEY_INSTALLED).sanitized(),
            failed = bundle.getStringArrayList(SettingsBridge.KEY_FAILED).sanitized()
        )

        /**
         * Bounds what an unauthenticated binder call can put on screen.
         *
         * The report crosses from another process over a provider any app may call, so neither the
         * number of entries nor their length is trustworthy. These caps sit far above what an
         * honest report carries - the hook installer produces a couple of dozen labels - and exist
         * only so that a hostile bundle cannot turn the diagnostics screen into a memory sink.
         */
        private fun List<String>?.sanitized(): List<String> =
            this.orEmpty().take(MAX_HOOK_LABELS).map { it.take(MAX_LABEL_LENGTH) }

        private const val MAX_HOOK_LABELS = 256
        private const val MAX_LABEL_LENGTH = 200
    }
}

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
 * A plain private `SharedPreferences` file, written by the provider (in this app's process) and
 * read by the settings screen. Nothing here is writable from outside the app beyond the one
 * provider method that produces it, and the values are display-only - the hooks never read this
 * file, so a stale or missing report can never change how the keyboard behaves.
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

    /** Hook labels never contain a newline, so one separator round-trips them losslessly. */
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
            failed = preferences.getString(KEY_FAILED, "").orEmpty().splitToList()
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
        // `commit` rather than `apply`: the caller is a binder thread finishing a one-shot report,
        // and losing it because the process died before the async write landed would leave the user
        // staring at "no report" with no way to know a report had been sent.
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_TIMESTAMP, status.timestamp)
            .putString(KEY_FRAMEWORK_NAME, status.frameworkName)
            .putString(KEY_FRAMEWORK_VERSION, status.frameworkVersion)
            .putLong(KEY_FRAMEWORK_VERSION_CODE, status.frameworkVersionCode)
            .putInt(KEY_API_VERSION, status.apiVersion)
            .putInt(KEY_REQUIRED_API_VERSION, status.requiredApiVersion)
            .putString(KEY_PROCESS_NAME, status.processName)
            .putString(KEY_HOST_VERSION, status.hostVersion)
            .putString(KEY_INSTALLED, status.installed.joinToString(SEPARATOR))
            .putString(KEY_FAILED, status.failed.joinToString(SEPARATOR))
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
