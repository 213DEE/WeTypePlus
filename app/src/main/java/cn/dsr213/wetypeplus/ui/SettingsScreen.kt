package cn.dsr213.wetypeplus.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
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
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
internal fun SettingsScreen(
    settings: KeyboardSettings,
    onSettingsChange: (KeyboardSettings) -> Unit,
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
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
                        HorizontalDivider()
                        SwitchRow(
                            title = stringResource(R.string.settings_sync_padding_title),
                            description = stringResource(R.string.settings_sync_padding_desc),
                            checked = settings.syncSideMargins,
                            onCheckedChange = {
                                onSettingsChange(settings.copy(syncSideMargins = it))
                            }
                        )
                        HorizontalDivider()
                        SwitchRow(
                            title = stringResource(R.string.settings_exclusive_title),
                            description = stringResource(R.string.settings_exclusive_desc),
                            checked = settings.exclusiveHandSplit,
                            onCheckedChange = {
                                onSettingsChange(settings.copy(exclusiveHandSplit = it))
                            }
                        )
                    }
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
                        endActions = {
                            Icon(
                                imageVector = MiuixIcons.Basic.ArrowRight,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    )
                }
            }

            item {
                SmallTitle(text = stringResource(R.string.section_about))
                Card(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    insideMargin = PaddingValues(0.dp)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        Text(
                            text = stringResource(R.string.about_version, appVersionName()),
                            style = MiuixTheme.textStyles.main
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.about_independent),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
            }
        }
    }

    if (showRestartDialog) {
        RestartDialog(
            restarting = restarting,
            onDismiss = { if (!restarting) showRestartDialog = false },
            onConfirm = {
                if (!restarting) {
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
                }
            }
        )
    }
}

@Composable
private fun RestartDialog(
    restarting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(insideMargin = PaddingValues(0.dp)) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                Text(
                    text = stringResource(R.string.restart_dialog_title),
                    style = MiuixTheme.textStyles.headline1
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.restart_dialog_message),
                    style = MiuixTheme.textStyles.main,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(modifier = Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    BasicComponent(
                        title = stringResource(R.string.dialog_cancel),
                        onClick = onDismiss
                    )
                    BasicComponent(
                        title = stringResource(
                            if (restarting) {
                                R.string.action_restart_running
                            } else {
                                R.string.restart_dialog_confirm
                            }
                        ),
                        onClick = onConfirm
                    )
                }
            }
        }
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
