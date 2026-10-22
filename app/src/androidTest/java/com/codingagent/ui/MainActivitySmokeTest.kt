package com.codingagent.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the installed app launch and first-run setup flows.
 *
 * Isolation: Android Test Orchestrator + clearPackageData is required so each
 * method starts with a clean package (no leaked ModelSettings or mounted project).
 * The dismiss helper below is still conditional so the suite remains safe if
 * Orchestrator is temporarily disabled.
 */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    /**
     * Dismisses the first-run Model Settings dialog only when it is present.
     * Safe to call from any test order; does nothing when the dialog is already gone.
     */
    private fun dismissModelSettingsIfPresent() {
        val cancelNodes = composeRule.onAllNodesWithText("Cancel").fetchSemanticsNodes()
        if (cancelNodes.isNotEmpty()) {
            composeRule.onNodeWithText("Cancel").performScrollTo().performClick()
            composeRule.waitForIdle()
        }
    }

    @Test
    fun launchesWorkbenchAndShowsCoreNavigation() {
        dismissModelSettingsIfPresent()
        composeRule.onNodeWithText("CODING AGENT").assertIsDisplayed()
        composeRule.onNodeWithText("New").assertIsDisplayed()
        // After a clean package the top-bar action is Import; after a prior mount it is Switch.
        // With Orchestrator + clearPackageData the process is always clean, so Import is expected.
        assertTrue(
            "Expected either Import or Switch in the project navigation",
            composeRule.onAllNodesWithText("Import").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Switch").fetchSemanticsNodes().isNotEmpty()
        )
    }

    @Test
    fun newProjectCreatesAndMountsWorkspace() {
        dismissModelSettingsIfPresent()
        composeRule.onNodeWithText("New").performClick()
        // Project creation runs on a background thread; wait until the status text appears.
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Your new project is ready.", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("Your new project is ready.", substring = true).assertIsDisplayed()
    }

    @Test
    fun modelSettingsExposeProviderConfigurationAndSaveAction() {
        // With Orchestrator + clearPackageData every method starts first-run, so the
        // Model Settings dialog is open. No dismiss is performed here.
        composeRule.onNodeWithText("Connect a code helper").assertIsDisplayed()
        composeRule.onNodeWithText("Web address").assertIsDisplayed()
        composeRule.onNodeWithText("Secret password").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Save").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performScrollTo().assertIsDisplayed()
    }
}
