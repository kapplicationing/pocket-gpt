package com.pocketagent.android.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertiesAndroid
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalComposeUiApi::class)
class SettingsDestinationComposeContractTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersAsOneOpaqueRootWithContentBoundedBelowHeader() {
        val background = Color(0xFF18334A)

        composeRule.setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    background = background,
                    onBackground = Color.White,
                ),
            ) {
                SettingsDestination(
                    title = "General settings",
                    onClose = {},
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("settings_destination_content_probe"),
                    )
                }
            }
        }

        composeRule.onAllNodes(isRoot(), useUnmergedTree = true).assertCountEquals(1)

        val rootBounds = composeRule.onRoot(useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val destination = composeRule.onNodeWithTag("settings_destination", useUnmergedTree = true)
        val destinationBounds = destination.fetchSemanticsNode().boundsInRoot
        val contentBounds = composeRule.onNodeWithTag("settings_destination_content", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val contentProbeBounds = composeRule
            .onNodeWithTag("settings_destination_content_probe", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot

        assertEquals(rootBounds.left, destinationBounds.left, LAYOUT_TOLERANCE_PX)
        assertEquals(rootBounds.top, destinationBounds.top, LAYOUT_TOLERANCE_PX)
        assertEquals(rootBounds.right, destinationBounds.right, LAYOUT_TOLERANCE_PX)
        assertEquals(rootBounds.bottom, destinationBounds.bottom, LAYOUT_TOLERANCE_PX)
        assertTrue(contentBounds.top > destinationBounds.top)
        assertTrue(contentBounds.height > 0f)
        assertTrue(contentBounds.height < destinationBounds.height)
        assertTrue(contentBounds.bottom <= destinationBounds.bottom)
        assertEquals(contentBounds.left, contentProbeBounds.left, LAYOUT_TOLERANCE_PX)
        assertEquals(contentBounds.top, contentProbeBounds.top, LAYOUT_TOLERANCE_PX)
        assertEquals(contentBounds.right, contentProbeBounds.right, LAYOUT_TOLERANCE_PX)
        assertEquals(contentBounds.bottom, contentProbeBounds.bottom, LAYOUT_TOLERANCE_PX)

        val pixels = destination.captureToImage().toPixelMap()
        assertEquals(background.toArgb(), pixels[0, 0].toArgb())
    }

    @Test
    fun exportsPaneHeadingResourceIdsAndAccessibleCloseAction() {
        val title = "Completion settings"
        val closeLabel = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .getString(com.pocketagent.android.R.string.ui_close)
        var closeCount = 0

        composeRule.setContent {
            MaterialTheme {
                SettingsDestination(
                    title = title,
                    onClose = { closeCount += 1 },
                ) {
                    Box(modifier = Modifier.fillMaxSize())
                }
            }
        }

        composeRule.onNodeWithTag("settings_destination", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, title))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsPropertiesAndroid.TestTagsAsResourceId,
                    true,
                ),
            )
            .assertIsDisplayed()
        composeRule.onNodeWithTag("settings_destination_title", useUnmergedTree = true)
            .assert(isHeading())
            .assertTextEquals(title)
        composeRule.onNodeWithTag("settings_destination_close")
            .assertContentDescriptionEquals(closeLabel)
            .assertIsDisplayed()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(1, closeCount)
        }
    }

    private companion object {
        const val LAYOUT_TOLERANCE_PX = 0.5f
    }
}
