package com.pocketagent.android.ui

import android.text.format.Formatter
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
import androidx.compose.material3.Card
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
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

private data class OnboardingPrimaryButton(
    @StringRes val labelRes: Int,
    val testTag: String,
    val enabled: Boolean,
    val onClick: () -> Unit,
)

private enum class OnboardingSecondaryAction {
    NONE,
    SET_UP_LATER,
    CONTINUE_IN_BACKGROUND,
    CHOOSE_ANOTHER_MODEL,
}

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
        bodyRes = R.string.ui_onboarding_setup_intro_body,
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
internal fun OnboardingScreen(
    currentPage: Int,
    onPageChanged: (Int) -> Unit,
    onNextPage: () -> Unit,
    onSkip: () -> Unit,
    onFinish: () -> Unit,
    setupState: OnboardingSetupUiState = OnboardingSetupUiState(),
    onStartDownload: () -> Unit,
    onContinueInBackground: () -> Unit = {},
    onReviewSetup: () -> Unit = {},
    onChooseAnotherModel: () -> Unit = {},
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
                    setupState = setupState,
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
                setupState = setupState,
                onNext = onNextPage,
                onFinish = onFinish,
                onStartDownload = onStartDownload,
                onContinueInBackground = onContinueInBackground,
                onReviewSetup = onReviewSetup,
                onChooseAnotherModel = onChooseAnotherModel,
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
    setupState: OnboardingSetupUiState,
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
            OnboardingSetupCard(setupState = setupState)
        }
    }
}

@Composable
private fun OnboardingSetupCard(setupState: OnboardingSetupUiState) {
    val context = LocalContext.current
    val modelName = setupState.modelName
        ?: stringResource(id = R.string.ui_onboarding_recommended_model_fallback)
    val sizeLabel = setupState.totalBytes
        .takeIf { bytes -> bytes > 0L }
        ?.let { bytes -> Formatter.formatShortFileSize(context, bytes) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("onboarding_setup_card"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PocketAgentDimensions.cardPadding),
            verticalArrangement = Arrangement.spacedBy(PocketTheme.spacing.sm),
        ) {
            Text(
                text = stringResource(id = setupState.phase.statusLabelRes()),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = setupState.phase.statusLabelColor(),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Text(
                text = modelName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = setupState.summaryText(sizeLabel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OnboardingSetupProgress(setupState = setupState)
        }
    }
}

@StringRes
private fun OnboardingSetupPhase.statusLabelRes(): Int {
    return when (this) {
        OnboardingSetupPhase.NOT_STARTED -> R.string.ui_onboarding_recommended_starter
        OnboardingSetupPhase.PREPARING -> R.string.ui_onboarding_setup_preparing
        OnboardingSetupPhase.DOWNLOADING -> R.string.ui_onboarding_setup_downloading
        OnboardingSetupPhase.PAUSED -> R.string.ui_onboarding_setup_paused
        OnboardingSetupPhase.CHECKING -> R.string.ui_onboarding_setup_checking
        OnboardingSetupPhase.FINISHING -> R.string.ui_onboarding_setup_finishing
        OnboardingSetupPhase.STARTING -> R.string.ui_onboarding_setup_starting
        OnboardingSetupPhase.READY -> R.string.ui_onboarding_setup_ready
        OnboardingSetupPhase.NEEDS_ATTENTION -> R.string.ui_onboarding_setup_attention
    }
}

@Composable
private fun OnboardingSetupPhase.statusLabelColor() = when (this) {
    OnboardingSetupPhase.NEEDS_ATTENTION -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.primary
}

@Composable
private fun OnboardingSetupUiState.summaryText(sizeLabel: String?): String {
    return when {
        alreadyInstalled && phase == OnboardingSetupPhase.NOT_STARTED ->
            stringResource(id = R.string.ui_onboarding_model_already_installed)
        sizeLabel != null -> stringResource(id = R.string.ui_onboarding_model_summary, sizeLabel)
        else -> stringResource(id = R.string.ui_onboarding_model_summary_no_size)
    }
}

@Composable
private fun OnboardingSetupProgress(setupState: OnboardingSetupUiState) {
    val context = LocalContext.current
    when (setupState.phase) {
        OnboardingSetupPhase.DOWNLOADING -> {
            val progress = setupState.progress
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(onboardingDownloadProgressSemantics(progress)),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(onboardingDownloadProgressSemantics(progress = null)),
                )
            }
            val progressParts = listOfNotNull(
                setupState.downloadedBytes.takeIf { it > 0L }?.let { downloaded ->
                    val downloadedLabel = Formatter.formatShortFileSize(context, downloaded)
                    val totalLabel = Formatter.formatShortFileSize(context, setupState.totalBytes)
                    stringResource(id = R.string.ui_onboarding_download_bytes, downloadedLabel, totalLabel)
                },
                setupState.etaSeconds?.takeIf { it >= 0L }?.let { eta ->
                    val minutes = ((eta + 59L) / 60L).coerceAtLeast(1L)
                    stringResource(id = R.string.ui_onboarding_download_eta_minutes, minutes)
                },
            )
            if (progressParts.isNotEmpty()) {
                Text(
                    text = progressParts.joinToString(separator = " • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        OnboardingSetupPhase.PREPARING,
        OnboardingSetupPhase.CHECKING,
        OnboardingSetupPhase.FINISHING,
        OnboardingSetupPhase.STARTING,
        -> LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .then(onboardingDownloadProgressSemantics(progress = null)),
        )

        OnboardingSetupPhase.PAUSED,
        OnboardingSetupPhase.NEEDS_ATTENTION,
        -> setupState.detail?.takeIf { it.isNotBlank() }?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (setupState.phase == OnboardingSetupPhase.NEEDS_ATTENTION) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        OnboardingSetupPhase.READY -> Text(
            text = stringResource(id = R.string.ui_onboarding_ready_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OnboardingSetupPhase.NOT_STARTED -> Unit
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
    setupState: OnboardingSetupUiState,
    onNext: () -> Unit,
    onFinish: () -> Unit,
    onStartDownload: () -> Unit,
    onContinueInBackground: () -> Unit,
    onReviewSetup: () -> Unit,
    onChooseAnotherModel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val primaryButton = resolveOnboardingPrimaryButton(
        isLastPage = isLastPage,
        setupState = setupState,
        onNext = onNext,
        onFinish = onFinish,
        onStartDownload = onStartDownload,
        onReviewSetup = onReviewSetup,
    )
    val secondaryAction = setupState.secondaryAction(isLastPage)
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PocketTheme.spacing.sm),
    ) {
        Button(
            onClick = {
                haptic.tickLight()
                primaryButton.onClick()
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(primaryButton.testTag),
            enabled = primaryButton.enabled,
        ) {
            Text(text = stringResource(id = primaryButton.labelRes))
        }
        OnboardingSecondaryButton(
            action = secondaryAction,
            haptic = haptic,
            onFinish = onFinish,
            onContinueInBackground = onContinueInBackground,
            onChooseAnotherModel = onChooseAnotherModel,
        )
    }
}

@Suppress("CyclomaticComplexMethod")
private fun resolveOnboardingPrimaryButton(
    isLastPage: Boolean,
    setupState: OnboardingSetupUiState,
    onNext: () -> Unit,
    onFinish: () -> Unit,
    onStartDownload: () -> Unit,
    onReviewSetup: () -> Unit,
): OnboardingPrimaryButton {
    if (!isLastPage) {
        return OnboardingPrimaryButton(
            labelRes = R.string.ui_onboarding_next,
            testTag = "onboarding_next",
            enabled = true,
            onClick = onNext,
        )
    }
    return when (setupState.phase) {
        OnboardingSetupPhase.READY -> OnboardingPrimaryButton(
            labelRes = R.string.ui_onboarding_start_chatting,
            testTag = "onboarding_setup_start_chat",
            enabled = true,
            onClick = onFinish,
        )

        OnboardingSetupPhase.PAUSED -> OnboardingPrimaryButton(
            labelRes = R.string.ui_onboarding_review_setup,
            testTag = "onboarding_setup_review",
            enabled = true,
            onClick = onReviewSetup,
        )

        OnboardingSetupPhase.NEEDS_ATTENTION -> OnboardingPrimaryButton(
            labelRes = R.string.ui_onboarding_retry_setup,
            testTag = "onboarding_setup_retry",
            enabled = true,
            onClick = onStartDownload,
        )

        OnboardingSetupPhase.PREPARING -> OnboardingPrimaryButton(
            labelRes = if (setupState.hasDownloadTask || setupState.setupRequestInFlight) {
                R.string.ui_onboarding_setup_preparing
            } else {
                R.string.ui_onboarding_continue_setup
            },
            testTag = if (setupState.hasDownloadTask || setupState.setupRequestInFlight) {
                "onboarding_setup_in_progress"
            } else {
                "onboarding_setup_download_start"
            },
            enabled = !setupState.hasDownloadTask && !setupState.setupRequestInFlight,
            onClick = onStartDownload,
        )

        OnboardingSetupPhase.DOWNLOADING -> busyOnboardingPrimaryButton(
            R.string.ui_onboarding_setup_downloading,
        )

        OnboardingSetupPhase.CHECKING -> busyOnboardingPrimaryButton(
            R.string.ui_onboarding_setup_checking,
        )

        OnboardingSetupPhase.FINISHING -> busyOnboardingPrimaryButton(
            R.string.ui_onboarding_setup_finishing,
        )

        OnboardingSetupPhase.STARTING -> busyOnboardingPrimaryButton(
            R.string.ui_onboarding_setup_starting,
        )

        OnboardingSetupPhase.NOT_STARTED -> OnboardingPrimaryButton(
            labelRes = if (setupState.alreadyInstalled) {
                R.string.ui_onboarding_use_and_start
            } else {
                R.string.ui_onboarding_download_and_start
            },
            testTag = "onboarding_setup_download_start",
            enabled = true,
            onClick = onStartDownload,
        )
    }
}

private fun busyOnboardingPrimaryButton(@StringRes labelRes: Int): OnboardingPrimaryButton {
    return OnboardingPrimaryButton(
        labelRes = labelRes,
        testTag = "onboarding_setup_in_progress",
        enabled = false,
        onClick = {},
    )
}

private fun OnboardingSetupUiState.secondaryAction(isLastPage: Boolean): OnboardingSecondaryAction {
    if (!isLastPage) {
        return OnboardingSecondaryAction.NONE
    }
    return when {
        phase == OnboardingSetupPhase.NEEDS_ATTENTION -> OnboardingSecondaryAction.CHOOSE_ANOTHER_MODEL
        isBusy() -> OnboardingSecondaryAction.CONTINUE_IN_BACKGROUND
        phase == OnboardingSetupPhase.NOT_STARTED -> OnboardingSecondaryAction.SET_UP_LATER
        else -> OnboardingSecondaryAction.NONE
    }
}

private fun OnboardingSetupUiState.isBusy(): Boolean {
    return when (phase) {
        OnboardingSetupPhase.DOWNLOADING,
        OnboardingSetupPhase.CHECKING,
        OnboardingSetupPhase.FINISHING,
        OnboardingSetupPhase.STARTING,
        -> true

        OnboardingSetupPhase.PREPARING -> hasDownloadTask || setupRequestInFlight
        else -> false
    }
}

@Composable
private fun OnboardingSecondaryButton(
    action: OnboardingSecondaryAction,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    onFinish: () -> Unit,
    onContinueInBackground: () -> Unit,
    onChooseAnotherModel: () -> Unit,
) {
    when (action) {
        OnboardingSecondaryAction.NONE -> Unit
        OnboardingSecondaryAction.SET_UP_LATER -> TextButton(
            onClick = {
                haptic.tickLight()
                onFinish()
            },
            modifier = Modifier.testTag("onboarding_get_started"),
        ) {
            Text(stringResource(id = R.string.ui_onboarding_set_up_later))
        }

        OnboardingSecondaryAction.CONTINUE_IN_BACKGROUND -> TextButton(
            onClick = {
                haptic.tickLight()
                onContinueInBackground()
            },
            modifier = Modifier.testTag("onboarding_continue_in_background"),
        ) {
            Text(stringResource(id = R.string.ui_onboarding_continue_in_background))
        }

        OnboardingSecondaryAction.CHOOSE_ANOTHER_MODEL -> TextButton(
            onClick = {
                haptic.tickLight()
                onChooseAnotherModel()
            },
            modifier = Modifier.testTag("onboarding_setup_choose_model"),
        ) {
            Text(stringResource(id = R.string.ui_onboarding_choose_another_model))
        }
    }
}

@Composable
private fun onboardingDownloadProgressSemantics(progress: Float?): Modifier {
    val progressLabel = stringResource(id = R.string.a11y_onboarding_download_progress)
    return Modifier.semantics {
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
@Suppress("UnusedPrivateMember")
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
@Suppress("UnusedPrivateMember")
private fun OnboardingScreenDownloadPreview() {
    PocketAgentTheme {
        OnboardingScreen(
            currentPage = 2,
            onPageChanged = {},
            onNextPage = {},
            onSkip = {},
            onFinish = {},
            setupState = OnboardingSetupUiState(
                phase = OnboardingSetupPhase.DOWNLOADING,
                modelName = "Qwen 3 0.6B",
                totalBytes = 396_705_472L,
                downloadedBytes = 178_517_462L,
                progress = 0.45f,
                etaSeconds = 120L,
            ),
            onStartDownload = {},
        )
    }
}
