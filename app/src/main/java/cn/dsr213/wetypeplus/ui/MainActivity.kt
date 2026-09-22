package cn.dsr213.wetypeplus.ui

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.dsr213.wetypeplus.AppSettings
import cn.dsr213.wetypeplus.KeyboardSettings
import cn.dsr213.wetypeplus.R
import cn.dsr213.wetypeplus.UpdateCheck
import cn.dsr213.wetypeplus.UpdateInfo
import cn.dsr213.wetypeplus.UpdateResult
import cn.dsr213.wetypeplus.releasePreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * The settings app.
 *
 * This is an ordinary launcher activity in this app's own process. Nothing here runs inside
 * WeType, and nothing here needs to: the switches are written to this app's preferences and pushed
 * to the host's processes over the broadcast channel described in
 * [cn.dsr213.wetypeplus.bridge.SettingsBridge].
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            MiuixTheme(colors = if (dark) darkColorScheme() else lightColorScheme()) {
                WeTypePlusApp()
            }
        }
    }
}

private enum class Screen {
    Settings,
    Diagnostics,
    Support
}

/**
 * Whether the launch-time update check has already run in this process.
 *
 * A process-level flag rather than a `remember`: a configuration change rebuilds this whole tree,
 * so a `remember`-based guard would fire the request again on every rotation. One check per launch
 * is the intent, and the row in settings is how the user asks for another.
 */
private var launchCheckDone = false

private const val UPDATE_PREFS = "update_check"
private const val KEY_ANNOUNCED = "announced_tag"

/**
 * Whether the launch check has already raised this tag on this device.
 *
 * A one-shot suppressor, so the dialog does not reappear on every launch until the user either
 * updates or learns to dismiss it without reading. The manual check deliberately ignores this:
 * someone who taps "check for updates" is asking to be told, including about a version they have
 * already been shown once.
 */
private fun alreadyAnnounced(context: Context, tag: String): Boolean =
    context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
        .getString(KEY_ANNOUNCED, null) == tag

private fun markAnnounced(context: Context, tag: String) {
    context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_ANNOUNCED, tag)
        .apply()
}

@Composable
private fun WeTypePlusApp() {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(AppSettings.read(context)) }
    var screen by remember { mutableStateOf(Screen.Settings) }

    var offered by remember { mutableStateOf<UpdateInfo?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Opening this app is what repairs a host that is running on defaults. The host reads its
    // switches when its process starts, which is usually *before* this app has ever been launched -
    // so the values have to be pushed again from here, not only on a change.
    LaunchedEffect(Unit) { AppSettings.push(context) }

    // The launch-time check. Silent by construction: a release worth offering is the only outcome
    // that puts anything on screen. "Up to date" and "GitHub unreachable" are equally unremarkable
    // here - this app is offline-first, and a request that never left the device must not be dressed
    // up as an answer.
    LaunchedEffect(Unit) {
        if (launchCheckDone) return@LaunchedEffect
        launchCheckDone = true
        val result = withContext(Dispatchers.IO) { UpdateCheck.check(context) }
        if (result is UpdateResult.Available && !alreadyAnnounced(context, result.info.tag)) {
            markAnnounced(context, result.info.tag)
            offered = result.info
        }
    }

    // The manual check. Here every outcome is spoken for, including the two silent ones above:
    // someone who asked a question deserves an answer, even when the answer is "nothing" or
    // "I could not ask".
    val checkForUpdates: () -> Unit = {
        if (!checking) {
            checking = true
            scope.launch {
                val result = withContext(Dispatchers.IO) { UpdateCheck.check(context) }
                checking = false
                when (result) {
                    is UpdateResult.Available -> offered = result.info

                    UpdateResult.UpToDate ->
                        Toast.makeText(context, R.string.update_latest, Toast.LENGTH_SHORT).show()

                    UpdateResult.Unreachable ->
                        Toast.makeText(context, R.string.update_unreachable, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    BackHandler(enabled = screen != Screen.Settings) {
        screen = Screen.Settings
    }

    when (screen) {
        Screen.Settings -> SettingsScreen(
            settings = settings,
            onSettingsChange = { updated: KeyboardSettings ->
                settings = updated
                AppSettings.write(context, updated)
            },
            onOpenDiagnostics = { screen = Screen.Diagnostics },
            onOpenSupport = { screen = Screen.Support },
            onCheckUpdate = checkForUpdates,
            checkingUpdate = checking
        )

        Screen.Diagnostics -> DiagnosticsScreen(
            onBack = { screen = Screen.Settings }
        )

        Screen.Support -> SupportScreen(
            onBack = { screen = Screen.Settings }
        )
    }

    UpdateDialog(info = offered, installed = appVersionName(), onDismiss = { offered = null })
}

/**
 * The "a newer release exists" dialog.
 *
 * The summary carries the installed-to-newest line plus a trimmed plain-text preview of the release
 * body, so the user can judge whether this is worth opening a browser for. The full notes stay on
 * the release page the button leads to - rendering markdown here would mean shipping a renderer for
 * a screen whose entire job is to hand off.
 */
@Composable
private fun UpdateDialog(info: UpdateInfo?, installed: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val summary = info?.let { release ->
        val headline = stringResource(R.string.update_dialog_summary, installed, release.version)
        val preview = releasePreview(release.notes)
        if (preview.isEmpty()) headline else "$headline\n\n$preview"
    }.orEmpty()

    WindowDialog(
        show = info != null,
        title = stringResource(R.string.update_dialog_title),
        summary = summary,
        onDismissRequest = onDismiss
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextButton(
                text = stringResource(R.string.update_dialog_later),
                onClick = onDismiss,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                text = stringResource(R.string.update_dialog_open),
                onClick = {
                    info?.let { openUrl(context, it.url) }
                    onDismiss()
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}
