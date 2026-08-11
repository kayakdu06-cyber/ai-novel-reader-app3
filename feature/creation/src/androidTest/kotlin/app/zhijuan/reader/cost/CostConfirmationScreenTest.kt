package app.zhijuan.reader.cost

import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zhijuan.core.model.BookLengthMode
import app.zhijuan.reader.creation.BookCreationConfirmation
import app.zhijuan.reader.ui.cost.CostConfirmationScreen
import app.zhijuan.reader.ui.cost.CostEstimateState
import app.zhijuan.reader.ui.cost.UsageConfirmationRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CostConfirmationScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsFrozenScaleModelAndHonestUnknownPriceState() {
        showScreen()

        composeRule.onNodeWithText("开始前确认").assertIsDisplayed()
        composeRule.onNodeWithText("雨夜重逢", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("中篇").assertIsDisplayed()
        composeRule.onNodeWithText("300 章（最低 300 章）").assertIsDisplayed()
        composeRule.onNodeWithText("deepseek-chat").assertIsDisplayed()
        composeRule.onNodeWithText("费用暂时无法可靠估算").assertIsDisplayed()
        composeRule.onAllNodesWithText("0 元").assertCountEquals(0)
        composeRule.onAllNodesWithText("¥", substring = true).assertCountEquals(0)
        captureVisualReference()
    }

    @Test
    fun confirmationEmitsOnlyFrozenReferencesThenLocks() {
        var captured: UsageConfirmationRequest? = null
        var message by mutableStateOf<String?>(null)
        composeRule.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                CostConfirmationScreen(
                    confirmation = confirmation(),
                    onBack = {},
                    onConfirm = {
                        captured = it
                        message = "信息已确认。生成尚未开始。"
                    },
                    confirmationMessage = message,
                )
            }
        }

        composeRule.onNodeWithTag("cost-confirmation-list")
            .performScrollToNode(hasTestTag("confirm-usage"))
        composeRule.onNodeWithTag("confirm-usage").performClick()
        composeRule.onNodeWithText("信息已确认", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("confirm-usage").assertIsNotEnabled()
        composeRule.runOnIdle {
            val request = captured
            assertNotNull(request)
            assertEquals("book-1", request?.bookId)
            assertEquals("snapshot-1", request?.snapshotId)
            assertEquals("a".repeat(64), request?.snapshotContentHash)
            assertEquals(CostEstimateState.PRICE_CATALOG_UNAVAILABLE, request?.priceState)
        }
    }

    @Test
    fun actionsMeetTouchTargetsAndBackIsVisible() {
        var backCalls = 0
        showScreen(onBack = { backCalls += 1 })

        composeRule.onNodeWithTag("cost-confirmation-back")
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.runOnIdle { assertEquals(1, backCalls) }
        composeRule.onNodeWithTag("cost-confirmation-list")
            .performScrollToNode(hasTestTag("confirm-usage"))
        composeRule.onNodeWithTag("confirm-usage").assertHeightIsAtLeast(56.dp)
    }

    @Test
    fun narrowViewportAndDarkThemeCanReachPrimaryAction() {
        composeRule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.requiredSize(width = 375.dp, height = 480.dp)) {
                    CostConfirmationScreen(
                        confirmation = confirmation(),
                        onBack = {},
                        onConfirm = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag("cost-confirmation-list")
            .performScrollToNode(hasTestTag("confirm-usage"))
        composeRule.onNodeWithTag("confirm-usage").assertIsDisplayed()
        composeRule.onNodeWithText("本阶段的确认不会调用模型", substring = true).assertIsDisplayed()
    }

    private fun showScreen(onBack: () -> Unit = {}) {
        composeRule.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                CostConfirmationScreen(
                    confirmation = confirmation(),
                    onBack = onBack,
                    onConfirm = {},
                )
            }
        }
    }

    private fun confirmation() = BookCreationConfirmation(
        bookId = "book-1",
        snapshotId = "snapshot-1",
        title = "雨夜重逢",
        lengthMode = BookLengthMode.MEDIUM,
        minimumChapterCount = 300,
        targetChapterCount = 300,
        modelId = "deepseek-chat",
        contentHash = "a".repeat(64),
    )

    private fun captureVisualReference() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val imageUri = requireNotNull(
            context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(
                        MediaStore.Images.Media.DISPLAY_NAME,
                        "zhijuan-task037-cost-confirmation-${System.currentTimeMillis()}.png",
                    )
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ZhijuanTests")
                },
            ),
        )
        requireNotNull(context.contentResolver.openOutputStream(imageUri)).use { output ->
            composeRule.onRoot().captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, output)
        }
    }
}
