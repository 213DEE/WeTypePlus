package cn.dsr213.wetypeplus.bridge

import android.content.Intent
import android.net.Uri
import cn.dsr213.wetypeplus.KeyboardSettings

/**
 * The contract between the two processes this project runs in.
 *
 * The hooks run inside WeType's process; the settings screen runs in this app's own. They cannot
 * share fields or files, and Android puts real obstacles in the way of the obvious channels, so
 * everything that crosses between them is spelled out here.
 *
 * **What was tried, and why this shape won.** Every channel was measured on a real device
 * (LSPosed 2.2.0 (7854) / Android 16 / WeType 3.5.3 (56201)), not reasoned about:
 *
 * | Channel | Result |
 * |---|---|
 * | `ContentProvider.query` / `.call` from the host | **Fails.** `IllegalArgumentException: Unknown authority`. Android filters provider authority resolution by package visibility, and the host neither lists this app in its `<queries>` nor can be made to. `android:forceQueryable="true"` does not lift it either |
 * | `getRemotePreferences(group)` | **Read-only and empty.** `edit()` throws `UnsupportedOperationException: Read only implementation`, and `all.keys` is `[]` for this app's group - it is the manager's store, not this app's file |
 * | `listRemoteFiles()` / `openRemoteFile(path)` | **Empty.** `listRemoteFiles()` returns `[]`, so there is nothing to open |
 * | `registerReceiver` on the system `Context` | **Fails.** `SecurityException: Given caller package android is not running in process …` - the context's package is not the running one. Only the host's real `Application` can register or send |
 * | Broadcasts, explicit component | **Works, both directions** - and is the only thing that does |
 *
 * The reason broadcasts survive where the others do not: an intent that names its destination needs
 * no resolution, and resolution is the step package visibility filters. So the host reaches this app
 * by naming its receiver exactly, and this app reaches the host by naming its package.
 *
 * **Both directions are unauthenticated, deliberately.** Neither side holds a permission the other
 * could check: the host runs as WeType's uid and cannot be granted a custom permission this app
 * defines. What travels is therefore bounded to the least interesting data in the project - two
 * booleans the user set, and a status report that is display-only (the hooks never read it). A
 * forged message can mislead the diagnostics screen, and can flip the module's own two switches
 * inside the host; it cannot execute anything, and the settings app overwrites the switches every
 * time it is opened.
 */
object SettingsBridge {

    /**
     * Kept for the one legacy path: an older install of the hook half may still query it.
     *
     * Nothing new depends on this authority, and no code should be written against it - see the
     * class comment for what happens when the host tries.
     */
    const val AUTHORITY = "cn.dsr213.wetypeplus.settings"

    val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/settings")

    /** This app, addressable from the host without any visibility rule applying. */
    const val MODULE_PACKAGE = "cn.dsr213.wetypeplus"

    /** See [MODULE_PACKAGE]: an explicit component is what makes the host's intent survive. */
    const val RECEIVER_CLASS = "cn.dsr213.wetypeplus.bridge.BridgeReceiver"

    const val HOST_PACKAGE = "com.tencent.wetype"

    const val HOST_IME_ID = "com.tencent.wetype/.plugin.hld.WxHldService"

    /**
     * The process that actually builds the keyboard. Hook results coming from here are the ones
     * that matter; work seen only in the main process has not reached the surface the user sees.
     */
    const val HOST_KEYBOARD_PROCESS = "com.tencent.wetype:hld"

    /**
     * Framework managers, so the screen can name the version the user is actually running.
     *
     * Note what this list *cannot* answer: whether the user has a usable manager at all. Measured on
     * the test device (LSPosed 2.2.0 / 7854, Zygisk Next 1.5.0): the framework's manager is not an
     * installed package - `pm path org.lsposed.manager` is empty, there is no `/data/app` entry and
     * no `/data/data/org.lsposed.manager` - yet `manager.apk` sits in the KernelSU module directory
     * (`/data/adb/modules/zygisk_lsposed/manager.apk`, verified as `org.lsposed.manager` 2.2.0 /
     * 7854) and the manager does open, launched through an `org.lsposed.manager.LAUNCH_MANAGER`
     * intent redirect. So a miss here means "not installed as an app", which is *not* "the user has
     * no manager", and the diagnostics screen has to phrase it that way.
     */
    val FRAMEWORK_MANAGER_PACKAGES = listOf(
        // LSPosed (current) and the fork that kept its own application id.
        "org.lsposed.manager",
        "io.github.lsposed.manager",
        // The managers the older frameworks ship, for users who are not on LSPosed 2.x yet. They
        // cannot load this module (it declares libxposed API 102), but naming their version is what
        // tells such a user why nothing happened.
        "org.meowcat.edxposed.manager",
        "de.robv.android.xposed.installer"
    )

    /**
     * The WeType release this module was last verified against on a real device.
     *
     * Two fields, because they answer different questions. `versionName` is what a user reads off
     * their own screen, and it is what the diagnostics comparison keys on. `versionCode` is what
     * actually identifies a build (`3.5.3` = `56201`, `3.5.4` = `57201`); recorded for reports and
     * for telling two builds of the same name apart, not used as the comparison key, because one
     * number cannot say "older or newer, by release".
     *
     * ⚠️ **This is a label, not a gate.** Nothing in this module declines to run because the host
     * version differs - there is no version check anywhere in the hook path. The hooks resolve the
     * host's obfuscated class names by member signature (`hook/HostNames.kt`) and fail individually,
     * by name, when a target really is gone. This value only decides whether the diagnostics screen
     * reads "verified" or warns that the running build is untested.
     *
     * History: `3.5.3` through 1.0.26. `3.5.4` renamed the whole `utils` class family
     * (`m1` -> `n1`, `i1` -> `j1`, `Z0` -> `a1`) and cost 12 of 24 hooks until 1.0.27 learned to
     * resolve them; see `HostNames` for the mapping and for why a plain name lookup is not enough.
     */
    const val VERIFIED_HOST_VERSION = "3.5.4"

    const val VERIFIED_HOST_VERSION_CODE = 57201L

    /**
     * The lowest libxposed API this module can run on, mirroring `module.prop`'s `minApiVersion`.
     *
     * Kept in both places on purpose: `module.prop` is what the *framework* reads to decide
     * whether to load the module at all, while this copy is what lets the settings screen explain
     * a framework that is too old. The two must be changed together.
     */
    const val REQUIRED_API_VERSION = 102

    /**
     * The preferences file the switches live in, and its path relative to this app's data dir.
     *
     * One name, two readers: this app opens it with `getSharedPreferences`, while the hooks inside
     * WeType try to open the same file through the framework's `openRemoteFile`. A rename on one
     * side that missed the other would leave the switches silently inert, which is the failure
     * this project already spent a release chasing - so the name is written once, here.
     *
     * The name is `wetype_plus` and **must not change**: it is what existing installs already have
     * on disk, and renaming it would silently reset every user's switches to the defaults.
     */
    const val SETTINGS_PREFS_FILE = "wetype_plus"

    const val SETTINGS_REMOTE_PATH = "shared_prefs/wetype_plus.xml"

    // ------------------------------------------------------------------ actions

    /**
     * This app telling the host what the switches are, or answering [ACTION_REQUEST_SETTINGS].
     *
     * Sent with `setPackage(HOST_PACKAGE)` rather than to a component, because the host's receiver
     * is registered at runtime and has no component name to aim at. The package filter is enough
     * to scope delivery.
     */
    const val ACTION_SETTINGS = "cn.dsr213.wetypeplus.action.SETTINGS"

    /** The host reporting what it saw from inside WeType. Aimed at [RECEIVER_CLASS]. */
    const val ACTION_REPORT = "cn.dsr213.wetypeplus.action.REPORT"

    /** The host asking for [ACTION_SETTINGS], because it started with no value to work from. */
    const val ACTION_REQUEST_SETTINGS = "cn.dsr213.wetypeplus.action.REQUEST_SETTINGS"

    /**
     * This app asking the host for a fresh [ACTION_REPORT].
     *
     * This is what makes the diagnostics screen *current* rather than a snapshot from whenever
     * WeType last started, and it is the only way to see the module's own log without root: the
     * log tail travels inside the report.
     */
    const val ACTION_REQUEST_REPORT = "cn.dsr213.wetypeplus.action.REQUEST_REPORT"

    // ------------------------------------------------------------------ wire format

    /**
     * Bumped whenever the shape of a report changes incompatibly.
     *
     * A report carrying a different value is refused rather than decoded: it means the module half
     * and the app half of an upgrade were installed at different times, and reading it as the
     * current shape would put nonsense on screen.
     */
    const val WIRE_VERSION = 1

    const val KEY_WIRE_VERSION = "wire_version"
    const val KEY_TIMESTAMP = "timestamp"
    const val KEY_FRAMEWORK_NAME = "framework_name"
    const val KEY_FRAMEWORK_VERSION = "framework_version"
    const val KEY_FRAMEWORK_VERSION_CODE = "framework_version_code"
    const val KEY_API_VERSION = "api_version"
    const val KEY_MIN_API_VERSION = "min_api_version"
    const val KEY_PROCESS_NAME = "process_name"
    const val KEY_HOST_VERSION = "host_version"
    const val KEY_INSTALLED = "installed"
    const val KEY_FAILED = "failed"

    /**
     * The module's own recent log lines, carried inside the report.
     *
     * The module cannot write this anywhere the settings app can read - WeType's uid owns neither
     * the app's storage nor `/data/adb/lspd/log` - so the report is the only route that needs no
     * root, no file access and no parsing of another program's log format.
     */
    const val KEY_LOG = "log"

    /**
     * Which channel supplied the switches in force, and what they are - e.g. "… from settings app
     * broadcast". Carried in the report because "my switch does nothing" has a different answer
     * depending on the channel, and only the host process knows which one answered.
     */
    const val KEY_SETTINGS = "settings"

    // ------------------------------------------------------------------ intents

    /**
     * Host to this app. The explicit component is the whole reason this arrives.
     *
     * `FLAG_INCLUDE_STOPPED_PACKAGES` is added because a freshly installed settings app is in the
     * stopped state, and Android drops both implicit and explicit broadcasts to stopped packages
     * unless it is asked not to.
     */
    fun reportIntent(): Intent = Intent(ACTION_REPORT)
        .setClassName(MODULE_PACKAGE, RECEIVER_CLASS)
        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)

    /** Host to this app: "send me the switches". Aimed at [RECEIVER_CLASS]. */
    fun settingsRequestIntent(): Intent = Intent(ACTION_REQUEST_SETTINGS)
        .setClassName(MODULE_PACKAGE, RECEIVER_CLASS)
        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)

    /** This app to the host: "here are the switches". */
    fun settingsIntent(settings: KeyboardSettings): Intent = Intent(ACTION_SETTINGS)
        .setPackage(HOST_PACKAGE)
        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        .putExtra(KeyboardSettings.COLUMN_UNLOCK_WIDTH, settings.unlockKeyboardWidth)
        .putExtra(KeyboardSettings.COLUMN_UNLOCK_SINGLE_HAND, settings.unlockSingleHandMode)

    /** This app to the host: "tell me what you see". */
    fun reportRequestIntent(): Intent = Intent(ACTION_REQUEST_REPORT)
        .setPackage(HOST_PACKAGE)
        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)

    /**
     * Reads [ACTION_SETTINGS] back, or `null` when it carried neither switch.
     *
     * Both keys are optional rather than required so that a future version can add a third switch
     * without an installed base of older hosts rejecting the whole message. A missing key keeps
     * the default, which is what the absent-feature case should mean anyway.
     */
    fun settingsFromIntent(intent: Intent): KeyboardSettings? {
        val extras = intent.extras ?: return null
        val hasWidth = extras.containsKey(KeyboardSettings.COLUMN_UNLOCK_WIDTH)
        val hasSingleHand = extras.containsKey(KeyboardSettings.COLUMN_UNLOCK_SINGLE_HAND)
        if (!hasWidth && !hasSingleHand) return null
        return KeyboardSettings(
            unlockKeyboardWidth = extras.getBoolean(KeyboardSettings.COLUMN_UNLOCK_WIDTH, true),
            unlockSingleHandMode = extras.getBoolean(KeyboardSettings.COLUMN_UNLOCK_SINGLE_HAND, true)
        )
    }
}
