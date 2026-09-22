package cn.dsr213.wetypeplus.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import cn.dsr213.wetypeplus.AppSettings
import cn.dsr213.wetypeplus.KeyboardSettings
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * The settings app.
 *
 * This is an ordinary launcher activity in this app's own process. Nothing here runs inside
 * WeType, and nothing here needs to: the switches are written to this app's preferences and the
 * hooks pick them up over [cn.dsr213.wetypeplus.bridge.SettingsProvider].
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

@Composable
private fun WeTypePlusApp() {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(AppSettings.read(context)) }
    var screen by remember { mutableStateOf(Screen.Settings) }

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
            onOpenSupport = { screen = Screen.Support }
        )

        Screen.Diagnostics -> DiagnosticsScreen(
            onBack = { screen = Screen.Settings }
        )

        Screen.Support -> SupportScreen(
            onBack = { screen = Screen.Settings }
        )
    }
}
