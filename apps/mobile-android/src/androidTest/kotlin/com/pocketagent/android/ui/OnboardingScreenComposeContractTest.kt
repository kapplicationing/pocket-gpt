package com.pocketagent.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingScreenComposeContractTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun downloadProgressAnnouncesDeterminateProgress() {
        composeRule.setContent {
            MaterialTheme {
                OnboardingScreen(
                    currentPage = 2,
                    onPageChanged = {},
                    onNextPage = {},
                    onSkip = {},
                    onFinish = {},
                    setupState = OnboardingSetupUiState(
                        phase = OnboardingSetupPhase.DOWNLOADING,
                        modelName = "Qwen starter",
                        totalBytes = 100L,
                        downloadedBytes = 45L,
                        progress = 0.45f,
                    ),
                    onStartDownload = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Model download progress 45%")
            .assertIsDisplayed()
    }

    @Test
    fun downloadProgressAnnouncesIndeterminateProgress() {
        composeRule.setContent {
            MaterialTheme {
                OnboardingScreen(
                    currentPage = 2,
                    onPageChanged = {},
                    onNextPage = {},
                    onSkip = {},
                    onFinish = {},
                    setupState = OnboardingSetupUiState(
                        phase = OnboardingSetupPhase.CHECKING,
                        modelName = "Qwen starter",
                    ),
                    onStartDownload = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Model download progress")
            .assertIsDisplayed()
    }

    @Test
    fun idleSetupKeepsLegacyBypassSeparateFromTransactionalAction() {
        composeRule.setContent {
            MaterialTheme {
                OnboardingScreen(
                    currentPage = 2,
                    onPageChanged = {},
                    onNextPage = {},
                    onSkip = {},
                    onFinish = {},
                    setupState = OnboardingSetupUiState(
                        phase = OnboardingSetupPhase.NOT_STARTED,
                        modelName = "Qwen starter",
                        totalBytes = 100L,
                    ),
                    onStartDownload = {},
                )
            }
        }

        composeRule.onNodeWithTag("onboarding_setup_download_start").assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding_get_started").assertIsDisplayed()
    }

    @Test
    fun sendReadySetupOffersStartChatAction() {
        composeRule.setContent {
            MaterialTheme {
                OnboardingScreen(
                    currentPage = 2,
                    onPageChanged = {},
                    onNextPage = {},
                    onSkip = {},
                    onFinish = {},
                    setupState = OnboardingSetupUiState(
                        phase = OnboardingSetupPhase.READY,
                        modelName = "Qwen starter",
                        progress = 1f,
                    ),
                    onStartDownload = {},
                )
            }
        }

        composeRule.onNodeWithTag("onboarding_setup_start_chat").assertIsDisplayed()
    }

    @Test
    fun setupRequestInFlightDisablesAnotherTransactionalStart() {
        composeRule.setContent {
            MaterialTheme {
                OnboardingScreen(
                    currentPage = 2,
                    onPageChanged = {},
                    onNextPage = {},
                    onSkip = {},
                    onFinish = {},
                    setupState = OnboardingSetupUiState(
                        phase = OnboardingSetupPhase.PREPARING,
                        modelName = "Qwen starter",
                        setupRequestInFlight = true,
                    ),
                    onStartDownload = {},
                )
            }
        }

        composeRule.onNodeWithTag("onboarding_setup_in_progress")
            .assertIsDisplayed()
            .assertIsNotEnabled()
    }

    @Test
    fun chooseAnotherModelUsesItsOwnRecoveryIntent() {
        var choseAnotherModel = false
        composeRule.setContent {
            MaterialTheme {
                OnboardingScreen(
                    currentPage = 2,
                    onPageChanged = {},
                    onNextPage = {},
                    onSkip = {},
                    onFinish = {},
                    setupState = OnboardingSetupUiState(
                        phase = OnboardingSetupPhase.NEEDS_ATTENTION,
                        modelName = "Qwen starter",
                    ),
                    onStartDownload = {},
                    onChooseAnotherModel = { choseAnotherModel = true },
                )
            }
        }

        composeRule.onNodeWithTag("onboarding_setup_choose_model").performClick()

        assertTrue(choseAnotherModel)
    }
}
