package com.codingagent.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the installed app can launch and the two core setup flows are reachable. */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchesWorkbenchAndShowsCoreNavigation() {
        composeRule.onNodeWithText("CODING AGENT").assertIsDisplayed()
        composeRule.onNodeWithText("New").assertIsDisplayed()
        composeRule.onNodeWithText("Import").assertIsDisplayed()
        composeRule.onNodeWithText("Model").assertIsDisplayed()
    }

    @Test
    fun newProjectCreatesAndMountsWorkspace() {
        composeRule.onNodeWithText("New").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("project mounted", substring = true).assertIsDisplayed()
    }

    @Test
    fun modelSettingsExposeProviderConfigurationAndSaveAction() {
        composeRule.onNodeWithText("Model").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Remote model API").assertIsDisplayed()
        composeRule.onNodeWithText("Base URL").assertIsDisplayed()
        composeRule.onNodeWithText("API key").assertIsDisplayed()
        composeRule.onNodeWithText("Save").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
    }
}
