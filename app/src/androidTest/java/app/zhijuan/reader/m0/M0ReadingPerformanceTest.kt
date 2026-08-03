package app.zhijuan.reader.m0

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class M0ReadingPerformanceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun twoHundredThousandCharacterReaderComposesOnlyVisibleParagraphs() {
        val state = LazyListState()
        var showReader by mutableIntStateOf(0)
        composeRule.setContent {
            if (showReader == 0) {
                Box(Modifier.requiredSize(1.dp))
            } else {
                M0LongChapterReader(
                    characterCount = 200_000,
                    state = state,
                    modifier = Modifier.testTag("reader-root"),
                )
            }
        }
        composeRule.waitForIdle()
        val beforePssKb = Debug.getPss()
        val initialStartedAt = SystemClock.elapsedRealtimeNanos()
        composeRule.runOnIdle { showReader = 1 }
        composeRule.waitForIdle()
        val initialMillis = elapsedMillis(initialStartedAt)
        val paragraphCount = (200_000 + PARAGRAPH_CHARACTERS - 1) / PARAGRAPH_CHARACTERS
        val initialComposed = composeRule.runOnIdle { state.layoutInfo.visibleItemsInfo.size }
        assertTrue("Lazy reader composed too many paragraphs: $initialComposed", initialComposed < 80)

        val scrollStartedAt = SystemClock.elapsedRealtimeNanos()
        composeRule.runOnIdle { state.requestScrollToItem(paragraphCount - 1) }
        composeRule.waitForIdle()
        val scrollMillis = elapsedMillis(scrollStartedAt)
        composeRule.onNodeWithTag("paragraph-${paragraphCount - 1}").assertIsDisplayed()
        val farComposed = composeRule.runOnIdle { state.layoutInfo.visibleItemsInfo.size }
        assertTrue("Far scroll composed too many paragraphs: $farComposed", farComposed < 80)
        val afterPssKb = Debug.getPss()

        Log.i(
            "ZhijuanM0Reader",
            "characters=200000 paragraphs=$paragraphCount initialMs=$initialMillis " +
                "farScrollMs=$scrollMillis initialNodes=$initialComposed farNodes=$farComposed " +
                "pssDeltaKb=${afterPssKb - beforePssKb}",
        )
    }

    @Test
    fun tenThousandChapterDirectoryUsesStableLazyRowsAndRemainsClickable() {
        val state = LazyListState()
        var selectedIndex by mutableIntStateOf(-1)
        var showDirectory by mutableIntStateOf(0)
        composeRule.setContent {
            MaterialTheme {
                if (showDirectory == 0) {
                    Box(Modifier.requiredSize(1.dp))
                } else {
                    M0ChapterDirectory(
                        chapterCount = 10_000,
                        state = state,
                        selectedIndex = selectedIndex.takeIf { it >= 0 },
                        onChapterSelected = { selectedIndex = it },
                    )
                }
            }
        }
        composeRule.waitForIdle()
        val initialStartedAt = SystemClock.elapsedRealtimeNanos()
        composeRule.runOnIdle { showDirectory = 1 }
        composeRule.waitForIdle()
        val initialMillis = elapsedMillis(initialStartedAt)
        val initialRows = composeRule.runOnIdle { state.layoutInfo.visibleItemsInfo.size }
        assertTrue("Lazy directory composed too many rows: $initialRows", initialRows < 80)

        val scrollStartedAt = SystemClock.elapsedRealtimeNanos()
        composeRule.runOnIdle { state.requestScrollToItem(9_999) }
        composeRule.waitForIdle()
        val scrollMillis = elapsedMillis(scrollStartedAt)
        composeRule.onNodeWithTag("chapter-row-9999")
            .assertIsDisplayed()
            .performClick()
        composeRule.waitForIdle()
        assertEquals(9_999, selectedIndex)
        val farRows = composeRule.runOnIdle { state.layoutInfo.visibleItemsInfo.size }
        assertTrue("Far directory scroll composed too many rows: $farRows", farRows < 80)

        Log.i(
            "ZhijuanM0Directory",
            "chapters=10000 initialMs=$initialMillis farScrollMs=$scrollMillis " +
                "initialNodes=$initialRows farNodes=$farRows selected=$selectedIndex",
        )
    }

    @Test
    fun readerAndDirectoryFitSmallPortraitAndLandscapeSurfaces() {
        val readerState = LazyListState()
        val directoryState = LazyListState()
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(width = 320.dp, height = 480.dp)) {
                    M0LongChapterReader(20_000, readerState)
                }
                Box(Modifier.requiredSize(width = 640.dp, height = 360.dp)) {
                    M0ChapterDirectory(10_000, directoryState, null, {})
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("long-chapter-reader").assertExists()
        composeRule.onNodeWithTag("chapter-directory").assertExists()
    }

    private fun elapsedMillis(startNanos: Long): Double =
        (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000.0
}
