package app.zhijuan.reader.reading

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.zhijuan.core.contract.LibraryBookSummary
import app.zhijuan.core.contract.LibraryChapterSummary
import app.zhijuan.core.model.BookStatus
import app.zhijuan.core.model.ChapterStatus
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.reader.ui.reader.ReaderContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ReaderScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun largeTextLandscapeKeepsDirectoryAndGenerationControlsReachable() {
        val chapters = listOf(
            LibraryChapterSummary("chapter-1", 1, "第一章 雨夜", ChapterStatus.READY, true),
            LibraryChapterSummary("chapter-2", 2, "第二章 余波", ChapterStatus.GENERATING, false),
        )
        var selected = 1
        var paused = 0
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme {
                    Box(Modifier.size(720.dp, 360.dp)) {
                        ReaderContent(
                            book = LibraryBookSummary(
                                "book-1", "暴雨中的重逢", 1, 80, BookStatus.GENERATING,
                                GenerationJobStatus.RUNNING, "job-1",
                            ),
                            chapters = chapters,
                            selectedOrdinal = selected,
                            chapterState = ReaderChapterState.Ready(chapters.first(), "第一段。\n第二段。"),
                            generationStatus = GenerationJobStatus.RUNNING,
                            actionPending = false,
                            onBack = {}, onShelf = {},
                            onSelectChapter = { selected = it.ordinal },
                            onPause = { paused += 1 }, onResume = {}, onStop = {},
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithTag("reader-body").assertIsDisplayed()
        composeRule.onNodeWithTag("reader-pause").performClick()
        composeRule.onNodeWithTag("reader-open-directory").performClick()
        composeRule.onNodeWithTag("directory-chapter-2").performClick()
        composeRule.runOnIdle {
            assertEquals(1, paused)
            assertEquals(2, selected)
        }
    }

    @Test
    fun draftIsExplicitlySeparatedFromFormalChapter() {
        val chapter = LibraryChapterSummary("chapter-2", 2, "第二章", ChapterStatus.GENERATING, false)
        composeRule.setContent {
            MaterialTheme {
                ReaderContent(
                    book = LibraryBookSummary("book-1", "测试书", 1, 80),
                    chapters = listOf(chapter),
                    selectedOrdinal = 2,
                    chapterState = ReaderChapterState.Generating(chapter, "这是受保护的完整草稿段落。", 2),
                    generationStatus = GenerationJobStatus.RUNNING,
                    actionPending = false,
                    onBack = {}, onShelf = {}, onSelectChapter = {},
                    onPause = {}, onResume = {}, onStop = {},
                )
            }
        }
        composeRule.onNodeWithTag("reader-draft-label", useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("这是受保护的完整草稿段落。").assertIsDisplayed()
    }
}
