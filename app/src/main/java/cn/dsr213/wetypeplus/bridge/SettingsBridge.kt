package cn.dsr213.wetypeplus.bridge

import android.net.Uri

/**
 * The contract between the two processes this project runs in.
 *
 * The hooks run inside WeType's process; the settings screen runs in this app's own. They cannot
 * share fields or files, so everything that crosses between them is spelled out here: one
 * authority, one method id, and one key per value.
 *
 * **Why a `ContentProvider.call()` rather than the framework's own remote preferences.**
 * libxposed can expose the module's preferences to a hooked process, but only when the framework
 * advertises `PROP_CAP_REMOTE` - which is an optional capability, not something a module can count
 * on. A plain provider call needs neither the capability nor any permission, and it is the ordinary
 * Android mechanism for exactly this shape of problem.
 *
 * **Direction.** Data flows *out of* the host process and *into* the settings app, never back:
 * the hooks read the user's switches through [SettingsProvider]'s `query`, and report what they
 * did through `call`. There is nothing here that lets the host side influence this app.
 *
 * This class is loaded in both processes, so it must not reference anything that needs an
 * `Application` to exist - `Uri.parse` at class-initialisation time is fine, and is the only work
 * it does.
 */
object SettingsBridge {
    /**
     * The shape of the report bundle, bumped by hand whenever a key below changes meaning.
     *
     * A mismatch means the two halves of an upgrade were installed at different times, which the
     * settings screen reports instead of decoding a stale bundle as if it were current.
     */
    const val WIRE_VERSION = 1

    const val AUTHORITY = "cn.dsr213.wetypeplus.settings"

    val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/settings")

    /** `ContentProvider.call()` method id carrying the module's status report. */
    const val METHOD_REPORT = "report_status"

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
     * The lowest libxposed API this module can run on, mirroring `module.prop`'s `minApiVersion`.
     *
     * Kept in both places on purpose: `module.prop` is what the *framework* reads to decide
     * whether to load the module at all, while this copy is what lets the settings screen explain
     * a framework that is too old. The two must be changed together.
     */
    const val REQUIRED_API_VERSION = 102

    const val HOST_PACKAGE = "com.tencent.wetype"

    const val HOST_IME_ID = "com.tencent.wetype/.plugin.hld.WxHldService"

    /**
     * The process that actually builds the keyboard. Install results coming from here are the ones
     * that matter; a report from the main process alone means the hooks never reached the surface
     * the user sees.
     */
    const val HOST_KEYBOARD_PROCESS = "com.tencent.wetype:hld"

    /** Framework managers, so the screen can name the version the user is actually running. */
    val FRAMEWORK_MANAGER_PACKAGES = listOf(
        "org.lsposed.manager",
        "io.github.lsposed.manager"
    )

    /**
     * The WeType build every host-dependent hook in this project was reverse-engineered against.
     *
     * The hooks address the host by its obfuscated names, which change between host releases, so a
     * different version is a genuine question rather than a cosmetic one - the screen says so
     * instead of leaving the user to guess why a feature went quiet.
     */
    const val VERIFIED_HOST_VERSION = "3.5.3.56201"
}
