package cn.dsr213.wetypeplus.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.dsr213.wetypeplus.KeyboardSettings
import cn.dsr213.wetypeplus.R
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * Upper bound on the settings column, in dp.
 *
 * On an unfolded foldable or a tablet the window can be 800-1000dp wide. Letting each row stretch
 * that far produces a title on the far left and its switch on the far right, which is both hard to
 * read and easy to mis-tap. The list is centred and capped instead; below the cap nothing changes,
 * so a phone still fills its own width edge to edge.
 */
internal val ContentMaxWidth = 640.dp

/**
 * The settings page.
 *
 * Only the two switches that are genuinely optional appear here. Margin centring and hand/split
 * exclusivity used to sit alongside them; they are structural rather than optional, so they are now
 * unconditional in the hooks and are deliberately not shown. Making them visible again would mean
 * offering the user a way to break the keyboard.
 */
@Composable
internal fun SettingsScreen(
    settings: KeyboardSettings,
    onSettingsChange: (KeyboardSettings) -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSupport: () -> Unit
) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior(state = rememberTopAppBarState())
    var showRestartDialog by remember { mutableStateOf(false) }
    var restarting by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background),
        topBar = {
            TopAppBar(
                title = stringResource(R.string.app_name),
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxHeight()
                    .widthIn(max = ContentMaxWidth)
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    top = paddingValues.calculateTopPadding(),
                    bottom = 32.dp
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    SmallTitle(text = stringResource(R.string.section_keyboard))
                    Card(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        insideMargin = PaddingValues(0.dp)
                    ) {
                        Column {
                            SwitchRow(
                                title = stringResource(R.string.settings_unlock_width_title),
                                description = stringResource(R.string.settings_unlock_width_desc),
                                checked = settings.unlockKeyboardWidth,
                                onCheckedChange = {
                                    onSettingsChange(settings.copy(unlockKeyboardWidth = it))
                                }
                            )
                            HorizontalDivider()
                            SwitchRow(
                                title = stringResource(R.string.settings_single_hand_title),
                                description = stringResource(R.string.settings_single_hand_desc),
                                checked = settings.unlockSingleHandMode,
                                onCheckedChange = {
                                    onSettingsChange(settings.copy(unlockSingleHandMode = it))
                                }
                            )
                        }
                    }
                }

                item {
                    SmallTitle(text = stringResource(R.string.section_diagnostics))
                    Card(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        insideMargin = PaddingValues(0.dp)
                    ) {
                        BasicComponent(
                            title = stringResource(R.string.diagnostics_title),
                            summary = stringResource(R.string.diagnostics_desc),
                            onClick = onOpenDiagnostics,
                            endActions = { ChevronIcon() }
                        )
                    }
                }

                item {
                    SmallTitle(text = stringResource(R.string.section_actions))
                    Card(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        insideMargin = PaddingValues(0.dp)
                    ) {
                        BasicComponent(
                            title = stringResource(R.string.action_restart_wetype),
                            summary = stringResource(R.string.action_restart_wetype_desc),
                            onClick = { showRestartDialog = true }
                        )
                    }
                }

                item {
                    SmallTitle(text = stringResource(R.string.section_support))
                    Card(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        insideMargin = PaddingValues(0.dp)
                    ) {
                        BasicComponent(
                            title = stringResource(R.string.support_title),
                            summary = stringResource(R.string.support_desc),
                            onClick = onOpenSupport,
                            endActions = { ChevronIcon() }
                        )
                    }
                }

                item {
                    SmallTitle(text = stringResource(R.string.section_about))
                    Card(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        insideMargin = PaddingValues(0.dp)
                    ) {
                        Column {
                            Column(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.about_version, appVersionName()),
                                    style = MiuixTheme.textStyles.main
                                )
                            }
                            HorizontalDivider()
                            BasicComponent(
                                title = stringResource(R.string.about_github_title),
                                summary = stringResource(R.string.about_github_summary),
                                onClick = {
                                    openUrl(context, context.getString(R.string.about_github_url))
                                },
                                endActions = { ChevronIcon() }
                            )
                        }
                    }
                }
            }
        }
    }

    // Miuix's own dialog rather than a bare `Dialog` around a `Card`. It brings the things a
    // hand-rolled one kept getting wrong: a 420dp ceiling so the card cannot outgrow a tablet, the
    // right margins, insets and dim, and a layout that sits at the bottom edge on a phone but
    // centres itself once the window is large (>= 840 x 480dp). The two actions are laid out as
    // equal halves of the available width, so neither can push the other past the card edge - the
    // "重启 sticks out of the dialog" bug - no matter how narrow the screen gets.
    WindowDialog(
        show = showRestartDialog,
        title = stringResource(R.string.restart_dialog_title),
        summary = stringResource(R.string.restart_dialog_message),
        onDismissRequest = { if (!restarting) showRestartDialog = false }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextButton(
                text = stringResource(R.string.dialog_cancel),
                onClick = { showRestartDialog = false },
                modifier = Modifier.weight(1f),
                enabled = !restarting
            )
            TextButton(
                text = stringResource(
                    if (restarting) {
                        R.string.action_restart_running
                    } else {
                        R.string.restart_dialog_confirm
                    }
                ),
                onClick = {
                    if (restarting) return@TextButton
                    restarting = true
                    restartHostIme(context) { succeeded ->
                        restarting = false
                        showRestartDialog = false
                        Toast.makeText(
                            context,
                            context.getString(
                                if (succeeded) {
                                    R.string.action_restart_done
                                } else {
                                    R.string.action_restart_failed
                                }
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !restarting
            )
        }
    }
}

@Composable
private fun ChevronIcon() {
    Icon(
        imageVector = MiuixIcons.Basic.ArrowRight,
        contentDescription = null,
        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
    )
}

/**
 * Opens an external link, tolerating a device with no browser at all. The failure path is a toast
 * rather than a crash: an `ActivityNotFoundException` here would take down the settings page for
 * what is only a broken link.
 */
private fun openUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(context, R.string.about_link_failed, Toast.LENGTH_SHORT).show()
    }
}

/**
 * One switch row. `Switch` alone would leave the whole row dead to the touch, and a row that only
 * responds on the tiny switch itself is a bad target, so the row body toggles as well.
 */
@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    BasicComponent(
        title = title,
        summary = description,
        onClick = { onCheckedChange(!checked) },
        endActions = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    )
}

@Composable
private fun appVersionName(): String {
    val context = LocalContext.current
    return remember(context) {
        @Suppress("DEPRECATION")
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: ""
    }
}
