package me.animeheaven.tv

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvUiSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appLaunchesAndRendersTvHeader() {
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            composeRule.onAllNodesWithText("Search").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Search").onFirst().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Settings & Themes").assertIsDisplayed()
    }

    @Test
    fun navigateToSettingsSwitchThemeAndReturn() {
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            composeRule.onAllNodesWithText("Search").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Settings & Themes").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodesWithText("Settings & Themes").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Settings & Themes").assertIsDisplayed()
        composeRule.onNodeWithText("Midnight Indigo").performClick()
        composeRule.onNodeWithContentDescription("Back").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodesWithText("Search").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun openTvCustomKeyboardSearchScreen() {
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            composeRule.onAllNodesWithText("Search").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Search").onFirst().performClick()

        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodesWithText("Q").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Q").onFirst().assertIsDisplayed()
        composeRule.onAllNodesWithText("Q").onFirst().performClick()
    }
}
