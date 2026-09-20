package cn.dsr213.wetypeplus.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.dsr213.wetypeplus.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The tip jar.
 *
 * The wording matters as much as the code here. The module is free and every feature is already
 * on by default, so there is no gate to unlock and nothing to buy - the copy says so plainly, in
 * the app and in the README, because a donation that looks like a paywall is a different legal
 * animal from a donation.
 */
@Composable
internal fun SupportScreen(onBack: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior(state = rememberTopAppBarState())
    var showFullSize by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background),
        topBar = {
            TopAppBar(
                title = stringResource(R.string.support_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    // An icon, not a labelled component: the navigation slot is sized for a touch
                    // target, and a full `BasicComponent` there is as tall as the bar itself and
                    // squeezes the title on a narrow screen.
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
                                text = stringResource(R.string.support_intro),
                                style = MiuixTheme.textStyles.main
                            )
                        }
                    }
                }

                item {
                    SmallTitle(text = stringResource(R.string.support_qr_hint))
                    Card(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        insideMargin = PaddingValues(0.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(R.drawable.donate_wechat_qr),
                                contentDescription = stringResource(R.string.support_qr_cd),
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // Source image is 850 x 1180.
                                    .aspectRatio(850f / 1180f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { showFullSize = true }
                            )
                        }
                    }
                }

                item {
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
                                text = stringResource(R.string.support_disclaimer),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }
                }
            }
        }
    }

    // The enlarged code fills the window and is letterboxed by `ContentScale.Fit`, rather than being
    // sized off the width alone. The source is portrait (850 x 1180), so on a landscape foldable a
    // width-driven height would run past the bottom of the screen and clip the very thing the tap
    // was meant to reveal. Tapping anywhere dismisses, because a full-window dialog has no
    // "outside" left to tap.
    if (showFullSize) {
        Dialog(
            onDismissRequest = { showFullSize = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showFullSize = false }
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.donate_wechat_qr),
                    contentDescription = stringResource(R.string.support_qr_cd),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
