package com.pocketagent.android.ui

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pocketagent.android.ui.theme.PocketAgentDimensions
import com.pocketagent.android.ui.theme.PocketTheme
import com.pocketagent.android.ui.theme.tickLight
import com.pocketagent.android.R
import kotlinx.coroutines.flow.distinctUntilChanged

private data class OnboardingPageData(
    @StringRes val headlineRes: Int,
    @StringRes val bodyRes: Int,
    val icon: ImageVector,
)

private val onboardingPages = listOf(
    OnboardingPageData(
        headlineRes = R.string.ui_onboarding_welcome_headline,
        bodyRes = R.string.ui_onboarding_welcome_body,
        icon = Icons.Filled.PhoneAndroid,
    ),
    OnboardingPageData(
        headlineRes = R.string.ui_onboarding_privacy_headline,
        bodyRes = R.string.ui_onboarding_privacy_body,
        icon = Icons.Filled.Shield,
    ),
    OnboardingPageData(
        headlineRes = R.string.ui_onboarding_download_headline,
        bodyRes = R.string.ui_onboarding_download_body,
        icon = Icons.Filled.CloudDownload,
    ),
)

private const val PAGE_COUNT = 3

/**
 * Full-screen onboarding experience with a horizontal pager, page indicators,
 * and navigation controls. Designed as a drop-in replacement for the existing
 * AlertDialog-based onboarding in ChatApp.kt.
 *
 * All state is provided via parameters -- no ViewModel references.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun OnboardingScreen(
    currentPage: Int,
    onPageChanged: (Int) -> Unit,
    onNextPage: () -> Unit,
    onSkip: () -> Unit,
    onFinish: () -> Unit,
    isDownloading: Boolean = false,
    downloadProgress: Float? = null,
    onStartDownload: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val pagerState = rememberPagerState(
        initialPage = currentPage.coerceIn(0, PAGE_COUNT - 1),
        pageCount = { PAGE_COUNT },
    )

    // Sync external currentPage to pager
    LaunchedEffect(currentPage) {
        val target = currentPage.coerceIn(0, PAGE_COUNT - 1)
        if (pagerState.currentPage != target) {
            pagerState.animateScrollToPage(target)
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { page -> onPageChanged(page) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .semantics { testTagsAsResourceId = true }
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Skip button -- top right, hidden on last page
        if (pagerState.currentPage < PAGE_COUNT - 1) {
            TextButton(
                onClick = {
                    haptic.tickLight()
                    onSkip()
                },
                modifier = Modifier
                    .testTag("onboarding_skip")
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(
                        top = PocketTheme.spacing.lg,
                        end = PocketAgentDimensions.screenPadding,
                    ),
            ) {
                Text(
                    text = stringResource(id = R.string.ui_onboarding_skip),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        // Main pager content
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.weight(1f))

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(3f),
            ) { pageIndex ->
                OnboardingPage(
                    data = onboardingPages[pageIndex],
                    isDownloadPage = pageIndex == PAGE_COUNT - 1,
                    isDownloading = isDownloading,
                    downloadProgress = downloadProgress,
                    onStartDownload = onStartDownload,
                )
            }

            // Page indicator dots
            PageIndicator(
                pageCount = PAGE_COUNT,
                currentPage = pagerState.currentPage,
                modifier = Modifier.padding(bottom = PocketTheme.spacing.lg),
            )

            // Bottom navigation button
            BottomNavigation(
                isLastPage = pagerState.currentPage == PAGE_COUNT - 1,
                isDownloading = isDownloading,
                onNext = onNextPage,
                onFinish = onFinish,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(
                        horizontal = PocketTheme.spacing.xl,
                        vertical = PocketTheme.spacing.lg,
                    ),
            )

            Spacer(modifier = Modifier.height(PocketTheme.spacing.xl))
        }
    }
}

@Composable
private fun OnboardingPage(
    data: OnboardingPageData,
    isDownloadPage: Boolean,
    isDownloading: Boolean,
    downloadProgress: Float?,
    onStartDownload: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = PocketTheme.spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = data.icon,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(PocketTheme.spacing.xl))

        Text(
            text = stringResource(id = data.headlineRes),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(modifier = Modifier.height(PocketTheme.spacing.md))

        Text(
            text = stringResource(id = data.bodyRes),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (isDownloadPage) {
            Spacer(modifier = Modifier.height(PocketTheme.spacing.xl))

            if (isDownloading) {
                if (downloadProgress != null) {
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PocketTheme.spacing.xl)
                            .then(onboardingDownloadProgressSemantics(downloadProgress)),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PocketTheme.spacing.xl)
                            .then(onboardingDownloadProgressSemantics(progress = null)),
                    )
                }
            } else {
                val downloadHaptic = LocalHapticFeedback.current
                Button(onClick = {
                    downloadHaptic.tickLight()
                    onStartDownload()
                }) {
                    Text(
                        text = stringResource(id = R.string.ui_onboarding_download_model),
                    )
                }
            }
        }
    }
}

@Composable
private fun PageIndicator(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier,
) {
    val pageCounterDescription = stringResource(
        id = R.string.ui_onboarding_page_counter,
        currentPage + 1,
        pageCount,
    )
    Row(
        modifier = modifier.semantics {
            contentDescription = pageCounterDescription
        },
        horizontalArrangement = Arrangement.spacedBy(PocketTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { index ->
            val color by animateColorAsState(
                targetValue = if (index == currentPage) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                animationSpec = tween(durationMillis = PocketAgentDimensions.animNormal),
                label = "dotColor",
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

@Composable
private fun BottomNavigation(
    isLastPage: Boolean,
    isDownloading: Boolean,
    onNext: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val buttonModifier = if (isLastPage) {
        modifier.testTag("onboarding_get_started")
    } else {
        modifier.testTag("onboarding_next")
    }
    Button(
        onClick = {
            haptic.tickLight()
            if (isLastPage) onFinish() else onNext()
        },
        modifier = buttonModifier,
        enabled = !isDownloading || !isLastPage,
    ) {
        Text(
            text = when {
                isLastPage && isDownloading -> stringResource(id = R.string.ui_onboarding_downloading)
                isLastPage -> stringResource(id = R.string.ui_onboarding_get_started)
                else -> stringResource(id = R.string.ui_onboarding_next)
            },
        )
    }
}

@Composable
private fun onboardingDownloadProgressSemantics(progress: Float?): Modifier {
    val progressLabel = stringResource(id = R.string.a11y_onboarding_download_progress)
    return Modifier.semantics {
        liveRegion = LiveRegionMode.Polite
        contentDescription = if (progress != null) {
            "$progressLabel ${(progress * 100).toInt()}%"
        } else {
            progressLabel
        }
        progressBarRangeInfo = if (progress != null) {
            androidx.compose.ui.semantics.ProgressBarRangeInfo(
                current = progress,
                range = 0f..1f,
            )
        } else {
            androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun OnboardingScreenPreview() {
    PocketAgentTheme {
        OnboardingScreen(
            currentPage = 0,
            onPageChanged = {},
            onNextPage = {},
            onSkip = {},
            onFinish = {},
            onStartDownload = {},
        )
    }
}

@Preview(showBackground = true, showSystemUi = true, name = "Download Page")
@Composable
private fun OnboardingScreenDownloadPreview() {
    PocketAgentTheme {
        OnboardingScreen(
            currentPage = 2,
            onPageChanged = {},
            onNextPage = {},
            onSkip = {},
            onFinish = {},
            isDownloading = true,
            downloadProgress = 0.45f,
            onStartDownload = {},
        )
    }
}
