package com.pocketagent.android.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.pocketagent.android.ui.components.AppBottomSheet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class AppBottomSheetBackContractTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun customBackHandlerConsumesDialogBackWithoutDismissingSheet() {
        val backCount = AtomicInteger(0)
        val dismissCount = AtomicInteger(0)
        composeRule.setContent {
            MaterialTheme {
                AppBottomSheet(
                    title = "Back contract",
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    onDismiss = { dismissCount.incrementAndGet() },
                    onBack = { backCount.incrementAndGet() },
                ) {
                    Text("Sheet remains visible")
                }
            }
        }
        composeRule.onNodeWithText("Back contract").assertIsDisplayed()

        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.pressBack()
        composeRule.waitUntil(timeoutMillis = 10_000L) { backCount.get() == 1 }

        composeRule.runOnIdle {
            assertEquals(0, dismissCount.get())
        }
        composeRule.onNodeWithText("Back contract").assertIsDisplayed()
    }

    @Test
    fun modelLibraryBackStepsThroughAdvancedExploreMyModelsThenDismisses() {
        val dismissCount = AtomicInteger(0)
        composeRule.setContent {
            var visible by remember { mutableStateOf(true) }
            var navigationState by remember {
                mutableStateOf(
                    ModelLibraryNavigationState(
                        selectedSection = ModelLibrarySection.EXPLORE,
                        advancedSourcesExpanded = true,
                    ),
                )
            }
            if (visible) {
                MaterialTheme {
                    AppBottomSheet(
                        title = "Model library Back flow",
                        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                        onDismiss = {
                            dismissCount.incrementAndGet()
                            visible = false
                        },
                        onBack = {
                            resolveModelLibraryBackNavigation(navigationState)?.let { nextState ->
                                navigationState = nextState
                            } ?: run {
                                dismissCount.incrementAndGet()
                                visible = false
                            }
                        },
                    ) {
                        Text(
                            when {
                                navigationState.advancedSourcesExpanded -> "Advanced sources open"
                                navigationState.selectedSection == ModelLibrarySection.EXPLORE -> "Explore open"
                                else -> "My models open"
                            },
                        )
                    }
                }
            }
        }
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        composeRule.onNodeWithText("Advanced sources open").assertIsDisplayed()

        device.pressBack()
        composeRule.onNodeWithText("Explore open").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, dismissCount.get()) }

        device.pressBack()
        composeRule.onNodeWithText("My models open").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, dismissCount.get()) }

        device.pressBack()
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule
                .onAllNodesWithText("Model library Back flow")
                .fetchSemanticsNodes()
                .isEmpty()
        }
        composeRule.runOnIdle { assertEquals(1, dismissCount.get()) }
    }
}
