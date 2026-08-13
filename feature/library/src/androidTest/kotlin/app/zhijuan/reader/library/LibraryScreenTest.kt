package app.zhijuan.reader.library

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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.zhijuan.core.contract.LibraryBookSummary
import app.zhijuan.core.model.BookStatus
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.reader.ui.library.LibraryContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LibraryScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun landscapeAndLargeTextKeepPrimaryActionsReachable() {
        var opened = 0
        composeRule.setContent {
            val current = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(current.density, 2f)) {
                MaterialTheme {
                    Box(Modifier.size(720.dp, 360.dp)) {
                        LibraryContent(
                            books = listOf(LibraryBookSummary(
                                bookId = "book-1",
                                title = "暴雨中的重逢",
                                completedChapterCount = 2,
                                targetChapterCount = 80,
                                status = BookStatus.GENERATING,
                                generationStatus = GenerationJobStatus.RUNNING,
                                generationJobId = "job-1",
                            )),
                            loadFailed = false,
                            onOpenBook = { opened += 1 },
                            onCreateBook = {},
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("书架").assertIsDisplayed()
        composeRule.onNodeWithText("正在生成").assertIsDisplayed()
        composeRule.onNodeWithTag("library-book-book-1").performClick()
        composeRule.runOnIdle { assertEquals(1, opened) }
    }
}
