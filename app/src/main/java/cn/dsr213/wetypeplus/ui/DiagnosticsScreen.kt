package cn.dsr213.wetypeplus.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cn.dsr213.wetypeplus.AppSettings
import cn.dsr213.wetypeplus.EnvironmentProbe
import cn.dsr213.wetypeplus.ModuleStatus
import cn.dsr213.wetypeplus.ModuleStatusStore
import cn.dsr213.wetypeplus.R
import cn.dsr213.wetypeplus.bridge.SettingsBridge
import cn.dsr213.wetypeplus.releaseSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Upper bound on how many log lines are rendered.
 *
 * The report carries up to four hundred, which is the right amount to *keep* and the wrong amount
 * to lay out: they are rendered inside a single card, so every one of them is composed whether or
 * not it is visible. The tail is what matters - the module reports once, early, and the newest
 * boot is the interesting one - and the copy action still copies everything.
 */
private const val MAX_RENDERED_LOG_LINES = 150

/**
 * How long to wait for the host's answer before reading the report back.
 *
 * The reply is a broadcast, and the host builds it on a thread of its own so that a cold start of
 * WeType is never held up by reporting. That is normally tens of milliseconds, plus however long
 * Android takes to schedule the delivery; a second is generous without making the button feel
 * broken, and the cost of waiting too long is only a slightly stale screen.
 */
private const val REPORT_WAIT_MS = 1_200L

/**
 * The diagnostics page.
 *
 * It exists because of one specific failure, the one that produced every "installed it, nothing
 * happens" report: the module can be present, enabled and switched on in the framework manager,
 * and still do nothing at all. Nothing crashes, nothing is logged to the user, and from the
 * outside it is indistinguishable from a module that was never installed. The causes are a
 * framework below the declared `minApiVersion` (which declines to load the module, silently), a
 * host process that was already running when the module was installed (the hooks are injected at
 * process fork, so they never arrive), and a host build whose internal method names have moved.
 *
 * None of that is visible from this app's own process, which is why the module reports what it saw
 * from *inside* WeType and this screen reads that report rather than guessing. When no report has
 * arrived, that absence is itself the finding, and the screen says which of the causes to check.
 *
 * The report is also where the module's log comes from. Its lines also land in the framework's own
 * log file, but those are root-only, and a user asking for help should not have to hand an app
 * root to send a few lines of text.
 */
@Composable
internal fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior(state = rememberTopAppBarState())
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf(ModuleStatusStore.read(context)) }
    var refreshing by remember { mutableStateOf(false) }
    var refreshToken by remember { mutableStateOf(0) }

    // The screen's one refresh path, and it runs on open as well as on the button: what the module
    // did when WeType last started is history, and the question people actually arrive with is
    // whether it is working *now*. The stored report is shown first, so the page is never blank
    // while the answer is in flight.
    LaunchedEffect(refreshToken) {
        refreshing = true
        AppSettings.askHostForReport(context)
        delay(REPORT_WAIT_MS)
        status = withContext(Dispatchers.IO) { ModuleStatusStore.read(context) }
        refreshing = false
    }

    val manager = remember(refreshToken) { EnvironmentProbe.frameworkManager(context) }
    val host = remember(refreshToken) { EnvironmentProbe.host(context) }
    val imePackage = remember(refreshToken) { EnvironmentProbe.enabledImePackage(context) }
    val hostIsIme = remember(refreshToken) { EnvironmentProbe.hostHasIme(context) }

    // The package probe first: it reads what is installed right now, whereas the report is a
    // snapshot from whenever WeType last started. A host updated after that snapshot is exactly
    // the case the hint needs to catch.
    val actualHostVersion = host?.versionName?.takeIf { it.isNotBlank() }
        ?: status?.hostVersion?.takeIf { it.isNotBlank() }
    val hostMismatch = actualHostVersion != null &&
        releaseSegment(actualHostVersion) != releaseSegment(SettingsBridge.VERIFIED_HOST_VERSION)
    val logLines = status?.logLines.orEmpty()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background),
        topBar = {
            TopAppBar(
                title = stringResource(R.string.diagnostics_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = stringResource(R.string.dialog_cancel),
                            tint = MiuixTheme.colorScheme.onSurface
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxHeight()
                    .widthIn(max = ContentMaxWidth),
                contentPadding = PaddingValues(
                    top = paddingValues.calculateTopPadding(),
                    bottom = 32.dp
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ---- Whether the module is running where it needs to run. ----------------------

                item {
                    Card(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        insideMargin = PaddingValues(0.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 18.dp)
                        ) {
                            Text(
                                text = stringResource(
                                    if (status == null) {
                                        R.string.diagnostics_state_missing
                                    } else {
                                        R.string.diagnostics_state_active
                                    }
                                ),
                                style = MiuixTheme.textStyles.main
                            )
                            status?.let { reported ->
                                Text(
                                    text = stringResource(
                                        R.string.diagnostics_state_reported,
                                        formatTimestamp(reported.timestamp)
                                    ),
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                        }
                    }
                }

                item {
                    SmallTitle(text = stringResource(R.string.section_diagnostics))
                    Card(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        insideMargin = PaddingValues(0.dp)
                    ) {
                        Column {
                            val requiredApi =
                                status?.requiredApiVersion ?: SettingsBridge.REQUIRED_API_VERSION

                            InfoRow(
                                label = stringResource(R.string.diagnostics_label_framework),
                                value = status?.let {
                                    listOf(it.frameworkName, it.frameworkVersion)
                                        .filter { part -> part.isNotBlank() }
                                        .joinToString(" ")
                                        .ifBlank { stringResource(R.string.diagnostics_unknown) }
                                } ?: stringResource(R.string.diagnostics_unknown)
                            )
                            HorizontalDivider()
                            InfoRow(
                                label = stringResource(R.string.diagnostics_label_manager),
                                value = manager?.versionName
                                    ?.takeIf { it.isNotBlank() }
                                    ?: stringResource(R.string.diagnostics_manager_absent)
                            )
                            HorizontalDivider()
                            InfoRow(
                                label = stringResource(R.string.diagnostics_label_api),
                                value = status?.let {
                                    if (it.frameworkTooOld) {
                                        stringResource(
                                            R.string.diagnostics_api_low,
                                            it.apiVersion,
                                            requiredApi
                                        )
                                    } else {
                                        stringResource(
                                            R.string.diagnostics_api_ok,
                                            it.apiVersion,
                                            requiredApi
                                        )
                                    }
                                } ?: stringResource(R.string.diagnostics_unknown)
                            )
                            HorizontalDivider()
                            InfoRow(
                                label = stringResource(R.string.diagnostics_label_host),
                                value = hostValue(actualHostVersion)
                            )
                            HorizontalDivider()
                            InfoRow(
                                label = stringResource(R.string.diagnostics_label_process),
                                value = status?.processName
                                    ?.takeIf { it.isNotBlank() }
                                    ?: stringResource(R.string.diagnostics_none)
                            )
                            HorizontalDivider()
                            InfoRow(
                                label = stringResource(R.string.diagnostics_label_ime),
                                value = imePackage ?: stringResource(R.string.diagnostics_unknown)
                            )
                            HorizontalDivider()
                            InfoRow(
                                label = stringResource(R.string.diagnostics_label_hooks),
                                value = status?.let {
                                    stringResource(
                                        R.string.diagnostics_hooks_summary,
                                        it.installed.size,
                                        it.failed.size
                                    )
                                } ?: stringResource(R.string.diagnostics_unknown)
                            )
                            HorizontalDivider()
                            // Which channel the switches actually reached the keyboard through. A
                            // switch that is stored but never read is exactly the bug this row is
                            // here to make visible, and only the host process can answer it.
                            InfoRow(
                                label = stringResource(R.string.diagnostics_label_switches),
                                value = status?.settingsSummary
                                    ?.takeIf { it.isNotBlank() }
                                    ?: stringResource(R.string.diagnostics_unknown)
                            )
                        }
                    }
                }

                // ---- What to do about it. -----------------------------------------------------

                val hints = collectHints(
                    status = status,
                    imePackage = imePackage,
                    hostIsIme = hostIsIme,
                    hostMismatch = hostMismatch
                )
                if (hints.isNotEmpty()) {
                    item {
                        SmallTitle(text = stringResource(R.string.diagnostics_fix_title))
                        Card(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            insideMargin = PaddingValues(0.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                hints.forEach { hint ->
                                    Text(
                                        text = hint.text(
                                            requiredApiOrDeclared(status),
                                            actualHostVersion
                                        ),
                                        style = MiuixTheme.textStyles.body2,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    )
                                }
                            }
                        }
                    }
                }

                // ---- The hooks that did not take. ---------------------------------------------

                // A failed hook is the most actionable thing the report can carry: it names the
                // host method the module went looking for and did not find, which is exactly what
                // an upstream rename looks like from the inside.
                val failed = status?.failed.orEmpty()
                if (failed.isNotEmpty()) {
                    item {
                        SmallTitle(text = stringResource(R.string.diagnostics_label_failed))
                        Card(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            insideMargin = PaddingValues(0.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                failed.forEach { label ->
                                    Text(
                                        text = label,
                                        style = MiuixTheme.textStyles.body2,
                                        fontFamily = FontFamily.Monospace,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Card(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        insideMargin = PaddingValues(0.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            TextButton(
                                text = stringResource(
                                    if (refreshing) {
                                        R.string.diagnostics_refreshing
                                    } else {
                                        R.string.diagnostics_refresh
                                    }
                                ),
                                onClick = { refreshToken += 1 },
                                modifier = Modifier.weight(1f),
                                enabled = !refreshing
                            )
                            TextButton(
                                text = stringResource(R.string.diagnostics_clear),
                                onClick = {
                                    ModuleStatusStore.clear(context)
                                    status = null
                                    Toast.makeText(
                                        context,
                                        R.string.diagnostics_cleared,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // ---- The module's own log. ----------------------------------------------------

                item {
                    SmallTitle(text = stringResource(R.string.section_log))
                    Card(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        insideMargin = PaddingValues(0.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.log_desc),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                TextButton(
                                    text = stringResource(
                                        if (refreshing) {
                                            R.string.diagnostics_refreshing
                                        } else {
                                            R.string.log_refresh
                                        }
                                    ),
                                    onClick = { refreshToken += 1 },
                                    modifier = Modifier.weight(1f),
                                    enabled = !refreshing
                                )
                                TextButton(
                                    text = stringResource(R.string.log_copy),
                                    onClick = {
                                        // The whole log, not the rendered tail: the copy action is
                                        // what travels into a bug report, and a report that stops
                                        // at whatever fitted on screen is worse than useless.
                                        copyToClipboard(
                                            context,
                                            context.getString(R.string.section_log),
                                            logLines.joinToString("\n")
                                        )
                                        Toast.makeText(
                                            context,
                                            R.string.log_copied,
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    modifier = Modifier.weight(1f),
                                    enabled = logLines.isNotEmpty()
                                )
                            }
                        }
                    }
                }

                if (logLines.isEmpty()) {
                    item { LogMessage(stringResource(R.string.log_empty)) }
                } else {
                    val shown = logLines.takeLast(MAX_RENDERED_LOG_LINES)
                    item {
                        Card(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            insideMargin = PaddingValues(0.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (shown.size < logLines.size) {
                                    Text(
                                        text = stringResource(
                                            R.string.log_truncated,
                                            shown.size
                                        ),
                                        style = MiuixTheme.textStyles.body2,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    )
                                }
                                shown.forEach { line ->
                                    Text(
                                        text = line,
                                        style = MiuixTheme.textStyles.body2,
                                        fontFamily = FontFamily.Monospace,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Which of the known causes the current evidence points at.
 *
 * Returned as an enum rather than as formatted text because the strings need resources, and this
 * runs inside a `LazyColumn` item where resolving them lazily per hint is the only way to keep the
 * formatting in `strings.xml` where the translators can see it.
 */
private enum class Hint {
    FrameworkTooOld,
    Scope,
    Restart,
    HooksMissing,
    HostMismatch,
    NotDefaultIme
}

private fun collectHints(
    status: ModuleStatus?,
    imePackage: String?,
    hostIsIme: Boolean,
    hostMismatch: Boolean
): List<Hint> = buildList {
    if (status == null) {
        // No report at all. Two readings, and the screen cannot tell them apart from this side:
        // either the framework declined to load the module, or it loaded somewhere that never
        // reports. The first advice covers both, so the second is only about the timing.
        add(Hint.Scope)
        add(Hint.Restart)
    } else {
        if (status.frameworkTooOld) add(Hint.FrameworkTooOld)
        // A report from the host's main process is a half-answer: the hooks were installed, but in
        // the wrong process - the keyboard lives in `:hld`, which was already running.
        if (!status.fromKeyboardProcess) add(Hint.Restart)
        // Named separately from the process hint because the reading is different: these hooks did
        // reach the process they belong to and could not be installed once they got there, which is
        // what a module updated under a running WeType looks like from the inside.
        if (status.failed.isNotEmpty()) add(Hint.HooksMissing)
    }
    // Read off whichever source answered, report or package probe, so a stale report cannot hide a
    // host that was updated after it was written.
    if (hostMismatch) add(Hint.HostMismatch)
    // Independent of the module: if WeType is not the selected input method, no keyboard feature
    // can show, however healthy everything else is.
    if (!hostIsIme && imePackage != null && imePackage != SettingsBridge.HOST_PACKAGE) {
        add(Hint.NotDefaultIme)
    }
}

@Composable
private fun Hint.text(requiredApi: Int, actualHostVersion: String?): String = when (this) {
    Hint.FrameworkTooOld -> stringResource(R.string.diagnostics_fix_framework, requiredApi)
    Hint.Scope -> stringResource(R.string.diagnostics_fix_scope, requiredApi)
    Hint.Restart -> stringResource(R.string.diagnostics_fix_restart)
    Hint.HooksMissing -> stringResource(R.string.diagnostics_fix_hooks)
    Hint.HostMismatch -> stringResource(
        R.string.diagnostics_fix_host,
        SettingsBridge.VERIFIED_HOST_VERSION,
        actualHostVersion.orEmpty().ifBlank { SettingsBridge.VERIFIED_HOST_VERSION }
    )

    Hint.NotDefaultIme -> stringResource(R.string.diagnostics_fix_ime)
}

private fun requiredApiOrDeclared(status: ModuleStatus?): Int =
    status?.requiredApiVersion ?: SettingsBridge.REQUIRED_API_VERSION

/**
 * The one version row that needs both sources.
 *
 * The probe is authoritative when it works, because it reads the package actually installed right
 * now; the report is authoritative when it does not, because Android 11+ hides packages this app
 * has not declared and a failed lookup is indistinguishable from an absent app.
 */
@Composable
private fun hostValue(version: String?): String {
    val name = version ?: return stringResource(R.string.diagnostics_unknown)
    // Same release comparison the hint above uses, so the row and the hint cannot disagree.
    return if (releaseSegment(name) == releaseSegment(SettingsBridge.VERIFIED_HOST_VERSION)) {
        stringResource(R.string.diagnostics_host_verified, name)
    } else {
        stringResource(R.string.diagnostics_host_unverified, name)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.main,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.3f)
        )
    }
}

/** A sentence in place of the log, for the states where there is no log to show. */
@Composable
private fun LogMessage(text: String) {
    Card(
        modifier = Modifier.padding(horizontal = 16.dp),
        insideMargin = PaddingValues(0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Text(
                text = text,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
}

private fun formatTimestamp(value: Long): String =
    if (value <= 0L) "" else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        .format(Date(value))

/**
 * Copies the log out, so it can be pasted into a bug report.
 *
 * The whole point of the log is that it travels; without a copy action the user would be asked to
 * transcribe it by hand or to reach for a desktop.
 */
private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return
    runCatching { clipboard.setPrimaryClip(ClipData.newPlainText(label, text)) }
}
