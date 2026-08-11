package app.zhijuan.reader.onboarding

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zhijuan.reader.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirstLaunchFlowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun firstScreenExplainsLocalRemoteAndSecretBoundaries() {
        waitForDisclosure()
        composeRule.onNode(
            hasText("你的故事，先留在这台设备上") and isHeading(),
        ).assertIsDisplayed()
        composeRule.onNodeWithText("默认留在本机")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("远程生成时才会发送")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("密钥单独保护")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("连接测试不会发送小说内容。", substring = true)
            .assertExists()
    }

    @Test
    fun primaryActionSurvivesRecreationAndSystemBackReturnsToDisclosure() {
        waitForDisclosure()
        composeRule.onNodeWithTag("continue-disclosure")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText("连接一个模型服务").assertIsDisplayed()

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("连接一个模型服务").assertIsDisplayed()

        composeRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("你的故事，先留在这台设备上").assertIsDisplayed()
    }

    @Test
    fun skipGoesToTheSameConnectionStepWithoutCompletingOnboarding() {
        waitForDisclosure()
        composeRule.onNodeWithTag("skip-disclosure").performClick()
        composeRule.onNodeWithText("连接一个模型服务").assertIsDisplayed()

        composeRule.onNodeWithText("返回").performClick()
        composeRule.onNodeWithText("你的故事，先留在这台设备上").assertIsDisplayed()
    }

    @Test
    fun visibleActionsMeetAndroidTouchTargetMinimum() {
        waitForDisclosure()
        composeRule.onNodeWithTag("skip-disclosure")
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("continue-disclosure")
            .performScrollTo()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
    }

    private fun waitForDisclosure() {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(
                "你的故事，先留在这台设备上",
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
