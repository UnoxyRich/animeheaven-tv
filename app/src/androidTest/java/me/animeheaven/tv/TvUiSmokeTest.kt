package me.animeheaven.tv

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import me.animeheaven.tv.ui.components.AhImageLoadTracker
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
        waitForText("Watch Now", timeoutMs = 25_000L)
        waitForImagesLoaded(minCount = 5, timeoutMs = 15_000L)
        settle(800L)
        saveScreenshot("01-tv-home")

        // New Releases tab
        val imagesBeforeNew = AhImageLoadTracker.loadedCount.get()
        composeRule.onAllNodesWithText("New").onFirst().performClick()
        runCatching { waitForText("New Episodes", timeoutMs = 12_000L) }
        waitForImagesLoaded(minCount = imagesBeforeNew + 2, timeoutMs = 10_000L)
        settle(1_200L)
        saveScreenshot("02-tv-new")

        // Popular tab
        val imagesBeforePopular = AhImageLoadTracker.loadedCount.get()
        composeRule.onAllNodesWithText("Popular").onFirst().performClick()
        runCatching { waitForText("Popular Today", timeoutMs = 12_000L) }
        waitForImagesLoaded(minCount = imagesBeforePopular + 2, timeoutMs = 10_000L)
        settle(1_200L)
        saveScreenshot("03-tv-popular")

        // My Bookmarks tab
        composeRule.onAllNodesWithText("My Bookmarks").onFirst().performClick()
        settle(1_500L)
        saveScreenshot("04-tv-bookmarks")

        // Search with custom TV keyboard
        composeRule.onAllNodesWithText("Search").onFirst().performClick()
        waitForText("Q", timeoutMs = 10_000L)
        val imagesBeforeSearch = AhImageLoadTracker.loadedCount.get()
        for (key in listOf("F", "R", "I", "E", "R", "E", "N")) {
            composeRule.onAllNodesWithText(key).onFirst().performClick()
        }
        runCatching {
            composeRule.waitUntil(timeoutMillis = 15_000L) {
                composeRule.onAllNodesWithText("Frieren", substring = true, ignoreCase = true)
                    .fetchSemanticsNodes().size >= 2
            }
        }
        waitForImagesLoaded(minCount = imagesBeforeSearch + 2, timeoutMs = 10_000L)
        settle(800L)
        saveScreenshot("05-tv-search-keyboard")

        // Return from Search to Schedule
        pressSystemBack()
        waitForText("Search", timeoutMs = 10_000L)
        composeRule.onAllNodesWithText("Schedule").onFirst().performClick()
        settle(1_000L)

        // Settings & Themes (default Cinema Dark)
        composeRule.onNodeWithContentDescription("Settings & Themes").performClick()
        waitForText("Settings & Themes", timeoutMs = 10_000L)
        composeRule.onNodeWithText("Settings & Themes").assertIsDisplayed()
        settle(1_200L)
        saveScreenshot("06-tv-settings-dark")

        // Switch theme to Midnight Indigo
        composeRule.onNodeWithText("Midnight Indigo").performClick()
        composeRule.onNodeWithText("Midnight Indigo").assertIsSelected()
        composeRule.onNodeWithText("Cinema Dark").assertIsNotSelected()
        settle(2_000L)
        saveScreenshot("07-tv-settings-indigo")

        // Return to Home in Midnight Indigo
        composeRule.onNodeWithContentDescription("Back").performClick()
        waitForText("Search", timeoutMs = 10_000L)
        settle(1_500L)
        saveScreenshot("08-tv-home-indigo")

        // Bonus 1: Classic Light theme verification in Settings
        composeRule.onNodeWithContentDescription("Settings & Themes").performClick()
        waitForText("Classic Light", timeoutMs = 10_000L)
        composeRule.onNodeWithText("Classic Light").performClick()
        composeRule.onNodeWithText("Classic Light").assertIsSelected()
        settle(2_000L)
        saveScreenshot("09-tv-settings-light")

        // Bonus 2: Anime Detail screen verification (hero framing + Classic Light contrast)
        composeRule.onNodeWithContentDescription("Back").performClick()
        waitForText("More Info", timeoutMs = 10_000L)
        settle(600L)
        val imagesBeforeDetail = AhImageLoadTracker.loadedCount.get()
        composeRule.onAllNodesWithText("More Info").onFirst().performClick()
        waitForTextSubstring("My List", timeoutMs = 20_000L)
        waitForImagesLoaded(minCount = imagesBeforeDetail + 2, timeoutMs = 10_000L)
        settle(800L)
        saveScreenshot("10-tv-detail")
    }

    private fun waitForText(text: String, timeoutMs: Long) {
        composeRule.waitUntil(timeoutMillis = timeoutMs) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForTextSubstring(text: String, timeoutMs: Long) {
        composeRule.waitUntil(timeoutMillis = timeoutMs) {
            composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForImagesLoaded(minCount: Int, timeoutMs: Long) {
        runCatching {
            composeRule.waitUntil(timeoutMillis = timeoutMs) {
                AhImageLoadTracker.loadedCount.get() >= minCount
            }
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

    private fun runShell(cmd: String): String {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        return ParcelFileDescriptor.AutoCloseInputStream(
            uiAutomation.executeShellCommand(cmd)
        ).use { it.readBytes().decodeToString() }
    }

    private fun saveScreenshot(name: String) {
        val dir = "/data/local/tmp/ci-screenshots"
        val path = "$dir/$name.png"
        runShell("mkdir -p $dir")
        val capOut = runShell("screencap -p $path")
        var lsOut = runShell("ls -l $path")
        if (!lsOut.contains("$name.png")) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            if (bitmap != null) {
                val baseDir = instrumentation.targetContext.externalCacheDir
                    ?: instrumentation.targetContext.cacheDir
                val tmp = File(baseDir, "$name.png")
                FileOutputStream(tmp).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                bitmap.recycle()
                runShell("cp ${tmp.absolutePath} $path")
                lsOut = runShell("ls -l $path")
            }
        }
        assertTrue("Screenshot failed for $name (capOut='$capOut', lsOut='$lsOut')", lsOut.contains("$name.png"))
    }
}
