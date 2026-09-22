package cn.dsr213.wetypeplus

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import cn.dsr213.wetypeplus.bridge.SettingsBridge
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes the file a user sends instead of describing the problem.
 *
 * The diagnostics screen already shows the module's log and every environment reading, but a
 * screenshot of it is a poor bug report: the interesting lines scroll off, the phone clips the
 * string at whatever width it has, and the user has to transcribe the rest by hand. This turns the
 * same material into one attachment that survives a chat app or an email.
 *
 * **Why the log alone is not enough.** A log line says what the module did; it does not say what it
 * ran on. The readings that decide whether a hook *could* work - the framework's API level, WeType's
 * build, the ROM's fingerprint - only exist in the app's own process, so they are added here rather
 * than left for a follow-up question.
 *
 * **What is in the file, and what is not.** Device model, ROM fingerprint, app and host versions,
 * the module's own log, and the two switches. Nothing else: no typed text, no IME content, no
 * account, no identifier of any kind. The user chooses to send it, and it is worth being able to
 * say exactly what was in it.
 *
 * **The sheet is written in English on purpose.** It wraps the module's log lines, which are
 * English, and it is read by whoever maintains the hooks against the host's obfuscated names. A
 * localised skeleton would put two languages in one file.
 */
object ReportExport {

    /**
     * The folder inside the public Downloads directory.
     *
     * Public Downloads rather than this app's own storage, because a file the user cannot find is
     * not an export. The app-private external directory (`/Android/data/…`) is not browsable by
     * file managers on Android 11 or newer, and the internal one is invisible to every other app -
     * so neither can produce an attachment.
     */
    private const val FOLDER = "WeTypePlus"

    private const val FILE_STAMP_PATTERN = "yyyyMMdd-HHmmss"
    private const val HUMAN_STAMP_PATTERN = "yyyy-MM-dd HH:mm:ss Z"

    /**
     * An exported file: where the system says it is, and a path a user can read out over chat.
     *
     * The [uri] is what the share sheet needs; [displayPath] is what the toast shows, because
     * "content://media/external/downloads/1000000143" tells the user nothing about where their file
     * went.
     */
    data class Exported(val uri: Uri, val displayPath: String, val fileName: String)

    /** The whole report as one string. Split out from [export] so it can be read without writing. */
    fun build(context: Context, status: ModuleStatus?, now: Long = System.currentTimeMillis()): String {
        val text = StringBuilder(8192)

        fun line(value: String = "") {
            text.append(value).append('\n')
        }

        fun section(title: String) {
            line()
            line("--- $title " + "-".repeat((56 - title.length).coerceAtLeast(4)))
        }

        line("WeTypePlus diagnostics")
        line("=".repeat(56))
        line("generated    : ${stamp(now, HUMAN_STAMP_PATTERN)}")
        line("module       : ${appVersion(context)}")
        line("package      : ${context.packageName}")
        line()
        line("Send this file as it is: it already carries the module's own log and the")
        line("environment it runs in. Producing it needed no root.")

        // ---- Where it is running. The log says what the module did; only this says what it ran on.
        section("device")
        line("manufacturer : ${Build.MANUFACTURER}")
        line("model        : ${Build.MODEL}")
        line("device       : ${Build.DEVICE}")
        line("android      : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        line("abi          : ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        line("fingerprint  : ${Build.FINGERPRINT}")

        // ---- What the module saw from inside WeType. Absent here is itself the finding.
        section("module report")
        if (status == null) {
            line("NO REPORT: the module has never reported from inside WeType.")
            line("That is what a module the framework did not load looks like - a scope that")
            line("excludes WeType, a framework below libxposed API " +
                "${SettingsBridge.REQUIRED_API_VERSION}, or a WeType")
            line("process that was already running when the module was installed.")
        } else {
            line("reported at  : ${stamp(status.timestamp, HUMAN_STAMP_PATTERN)}")
            line("framework    : ${frameworkText(status) ?: "not reported"}")
            line("manager      : ${managerText(context, status)}")
            line("api level    : ${apiText(status)}")
            line("host         : ${hostText(context, status)}")
            line("process      : ${status.processName.ifBlank { "unknown" }}")
            line("input method : ${EnvironmentProbe.enabledImePackage(context) ?: "unknown"}")
            line("switches/app : ${AppSettings.read(context)}")
            line("switches/host: ${status.settingsSummary.ifBlank { "not reported" }}")
            line("hooks        : ${status.installed.size} installed, ${status.failed.size} failed")
        }

        // ---- Hook outcomes. A failed label names the host method the module went looking for and
        // did not find, which is exactly what an upstream rename looks like from the inside.
        if (status != null && status.installed.isNotEmpty()) {
            section("hooks installed (${status.installed.size})")
            status.installed.forEach { line("  $it") }
        }
        if (status != null && status.failed.isNotEmpty()) {
            section("hooks failed (${status.failed.size})")
            status.failed.forEach { line("  $it") }
        }

        val log = status?.logLines.orEmpty()
        section("module log (${log.size} lines, oldest first)")
        if (log.isEmpty()) {
            line("(empty - no report has arrived yet)")
        } else {
            log.forEach { line(it) }
        }

        return text.toString()
    }

    /**
     * Writes the report into `Downloads/WeTypePlus/` and returns where it landed, or `null`.
     *
     * **MediaStore, not a file path.** Writing straight to `/sdcard/Download/…` needs either
     * `WRITE_EXTERNAL_STORAGE` (obsolete and ignored on Android 11+) or `MANAGE_EXTERNAL_STORAGE`,
     * which is a hand-granted permission for file managers - not something a keyboard module should
     * ask for to save one text file. An insert into `MediaStore.Downloads` needs no permission at
     * all on API 29+, and this app's `minSdk` is 31.
     *
     * **`IS_PENDING` around the write**, so the file is not visible to a media scanner or a file
     * manager halfway through being written: a half-written log submitted by a user would be worse
     * than no log, because it would look complete.
     *
     * Returns `null` rather than throwing, and cleans up after itself when the write fails: the
     * caller is a button handler, and a failed export has to end in a message, not a crash.
     */
    fun export(context: Context, status: ModuleStatus?): Exported? {
        val fileName = "wetypeplus-log-${stamp(System.currentTimeMillis(), FILE_STAMP_PATTERN)}.txt"
        val report = build(context, status)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return null
        val written = runCatching {
            resolver.openOutputStream(uri, "w")?.use { output ->
                output.write(report.toByteArray(Charsets.UTF_8))
                output.flush()
            } != null
        }.getOrDefault(false)
        if (!written) {
            // A pending row with no bytes in it would still show up as a file name in some
            // galleries and file managers, so the failed attempt is removed rather than left.
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        runCatching {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )
        }
        return Exported(uri, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/$fileName", fileName)
    }

    /**
     * Opens the system share sheet for an exported file.
     *
     * Best effort on purpose: the file is already in Downloads before this runs, so a device where
     * the chooser cannot be opened loses a shortcut, not the log.
     *
     * The `clipData` is what carries the read grant to whichever app the user picks - a chooser
     * hands the intent to a target the sender never named, and the flag alone has been measured to
     * be lost on that hop. Without it the receiving app opens a URI it is not allowed to read.
     */
    fun share(context: Context, exported: Exported, chooserTitle: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, exported.uri)
            putExtra(Intent.EXTRA_SUBJECT, exported.fileName)
            clipData = ClipData.newUri(context.contentResolver, exported.fileName, exported.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** This app's own version, read from the package rather than from a generated constant. */
    private fun appVersion(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }.getOrNull().orEmpty().ifBlank { "unknown" }

    /**
     * The framework manager row, phrased the way the diagnostics screen phrases it.
     *
     * A miss means "not installed as an app" and nothing more - LSPosed 2.2 runs its manager out of
     * the KernelSU module - so saying "not detected" in a report the user forwards would send
     * whoever reads it after a fault that is not there.
     */
    private fun managerText(context: Context, status: ModuleStatus): String {
        val manager = EnvironmentProbe.frameworkManager(context)
        if (manager != null) {
            return listOf(manager.packageName, manager.versionName)
                .filter { part -> part.isNotBlank() }
                .joinToString(" ")
        }
        return if (status.frameworkName.contains("lsposed", ignoreCase = true)) {
            "not installed as an app (the LSPosed manager ships inside the framework module)"
        } else {
            "not detected"
        }
    }

    /** The API level as a reading rather than a number: zero means "never reported", not "zero". */
    private fun apiText(status: ModuleStatus): String = when {
        status.apiVersion <= 0 -> "not reported (the module needs ${status.requiredApiVersion})"
        status.frameworkTooOld -> "${status.apiVersion} - requires ${status.requiredApiVersion}"
        else -> "${status.apiVersion} - meets the required ${status.requiredApiVersion}"
    }

    /**
     * WeType's version, from the package probe when it answers and the report when it does not.
     *
     * The package probe reads what is installed right now; the report is a snapshot from whenever
     * WeType last started. A host updated after that snapshot is exactly the case worth catching, so
     * the probe wins when it works - and it needs no framework, so it also works when nothing was
     * ever reported.
     */
    private fun hostText(context: Context, status: ModuleStatus): String {
        val probe = EnvironmentProbe.host(context)
        val version = probe?.versionName?.takeIf { it.isNotBlank() }
            ?: status.hostVersion.takeIf { it.isNotBlank() }
            ?: return "unknown"
        return buildString {
            append(version)
            if (probe != null && probe.versionCode > 0L) append(" (${probe.versionCode})")
            // Same release-level comparison the diagnostics screen uses, so a report and a
            // screenshot of that screen cannot disagree about the same device.
            if (releaseSegment(version) == releaseSegment(SettingsBridge.VERIFIED_HOST_VERSION)) {
                append(" - verified")
            } else {
                append(" - not verified (the hooks target ${SettingsBridge.VERIFIED_HOST_VERSION})")
            }
        }
    }

    private fun stamp(value: Long, pattern: String): String = runCatching {
        SimpleDateFormat(pattern, Locale.US).format(Date(value))
    }.getOrNull().orEmpty()
}
