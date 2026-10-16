package com.codingagent.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the installed app launch and first-run setup flows. */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchesWorkbenchAndShowsCoreNavigation() {
        // First launch deliberately opens Model settings when no provider is configured.
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("CODING AGENT").assertIsDisplayed()
        composeRule.onNodeWithText("New").assertIsDisplayed()
        composeRule.onNodeWithText("Import").assertIsDisplayed()
    }

    @Test
    fun newProjectCreatesAndMountsWorkspace() {
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("New").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("project mounted", substring = true).assertIsDisplayed()
    }

    @Test
    fun modelSettingsExposeProviderConfigurationAndSaveAction() {
        // Model settings are already open on first launch until a provider is configured.
        composeRule.onNodeWithText("Remote model API").assertIsDisplayed()
        composeRule.onNodeWithText("Base URL").assertIsDisplayed()
        composeRule.onNodeWithText("API key").assertIsDisplayed()
        composeRule.onNodeWithText("Save").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
    }
}
