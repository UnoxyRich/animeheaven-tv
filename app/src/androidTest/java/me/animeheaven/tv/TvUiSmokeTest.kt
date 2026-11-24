package me.animeheaven.tv

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvUiSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun fullAppWalkthroughCapturesScreenshots() {
        waitForText("Search", timeoutMs = 15_000L)
        composeRule.onNodeWithContentDescription("Settings & Themes").assertIsDisplayed()
        settle(3_000L)
        saveScreenshot("01-tv-home")

        // New Releases tab
        composeRule.onAllNodesWithText("New").onFirst().performClick()
        settle(2_500L)
        saveScreenshot("02-tv-new")

        // Popular tab
        composeRule.onAllNodesWithText("Popular").onFirst().performClick()
        settle(2_500L)
        saveScreenshot("03-tv-popular")

        // My Bookmarks tab
        composeRule.onAllNodesWithText("My Bookmarks").onFirst().performClick()
        settle(1_500L)
        saveScreenshot("04-tv-bookmarks")

        // Search with custom TV keyboard
        composeRule.onAllNodesWithText("Search").onFirst().performClick()
        waitForText("Q", timeoutMs = 10_000L)
        for (key in listOf("F", "R", "I", "E")) {
            composeRule.onAllNodesWithText(key).onFirst().performClick()
        }
        settle(2_500L)
        saveScreenshot("05-tv-search-keyboard")

        // Return from Search to Schedule
        pressSystemBack()
        waitForText("Search", timeoutMs = 10_000L)
        composeRule.onAllNodesWithText("Schedule").onFirst().performClick()
        settle(1_000L)

        // Settings & Themes (default OLED Dark)
        composeRule.onNodeWithContentDescription("Settings & Themes").performClick()
        waitForText("Settings & Themes", timeoutMs = 10_000L)
        composeRule.onNodeWithText("Settings & Themes").assertIsDisplayed()
        settle(1_200L)
        saveScreenshot("06-tv-settings-dark")

        // Switch theme to Midnight Indigo
        composeRule.onNodeWithText("Midnight Indigo").performClick()
        settle(1_200L)
        saveScreenshot("07-tv-settings-indigo")

        // Return to Home in Midnight Indigo
        composeRule.onNodeWithContentDescription("Back").performClick()
        waitForText("Search", timeoutMs = 10_000L)
        settle(1_500L)
        saveScreenshot("08-tv-home-indigo")
    }

    private fun waitForText(text: String, timeoutMs: Long) {
        composeRule.waitUntil(timeoutMillis = timeoutMs) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun pressSystemBack() {
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun settle(ms: Long) {
        composeRule.waitForIdle()
        Thread.sleep(ms)
        composeRule.waitForIdle()
    }

    private fun saveScreenshot(name: String) {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val path = "/data/local/tmp/ci-screenshots/$name.png"
        val pfd = uiAutomation.executeShellCommand(
            "sh -c 'mkdir -p /data/local/tmp/ci-screenshots && screencap -p $path && ls -l $path'"
        )
        val output = ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString() }
        assertTrue("Screenshot failed for $name: $output", output.contains("$name.png"))
    }
}
