package app.zhijuan.reader.creation

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zhijuan.provider.common.ProviderProtocol
import app.zhijuan.core.model.BookLengthMode
import app.zhijuan.reader.FirstLaunchDestination
import app.zhijuan.reader.ZhijuanApp
import app.zhijuan.reader.connection.ConnectionCommitResult
import app.zhijuan.reader.connection.ConnectionGatewayActions
import app.zhijuan.reader.connection.ConnectionModelVerification
import app.zhijuan.reader.connection.ConnectionMutationResult
import app.zhijuan.reader.connection.ConnectionServiceChoice
import app.zhijuan.reader.connection.ConnectionWizardCheckResult
import app.zhijuan.reader.connection.ConnectionWizardInput
import app.zhijuan.reader.connection.ConnectionWizardInputError
import app.zhijuan.reader.connection.FullConnectionCheckResult
import app.zhijuan.reader.connection.SavedConnectionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookCreationAppFlowTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun startPersistsOnceThenLoadsFrozenConfirmationWithoutFakePrice() {
        val actions = RecordingCreationActions()
        composeRule.setContent {
            ZhijuanApp(
                connectionGateway = SingleConnectionGateway,
                bookCreationActions = actions,
                initialDestination = FirstLaunchDestination.CREATE_BOOK,
            )
        }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("story-idea").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("story-idea").performTextInput("雨夜重逢后，两个人被困在海边旧旅馆。")
        composeRule.onNodeWithTag("create-book-list").performScrollToNode(hasTestTag("start-book"))
        composeRule.onNodeWithTag("start-book").performClick()

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("cost-confirmation-summary")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("开始前确认").assertIsDisplayed()
        composeRule.onNodeWithText("雨夜重逢", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("300 章", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("deepseek-chat").assertIsDisplayed()
        composeRule.onNodeWithTag("cost-estimate-unavailable").assertIsDisplayed()
        composeRule.onAllNodesWithText("0 元").assertCountEquals(0)
        composeRule.onNodeWithTag("confirm-usage").performClick()
        composeRule.onNodeWithText("当前没有调用模型", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("confirm-usage").assertIsNotEnabled()
        composeRule.runOnIdle {
            assertEquals(1, actions.callCount)
            assertEquals(1, actions.loadCallCount)
            assertEquals(300, actions.lastDraft?.minimumChapterCount)
            assertEquals(300, actions.lastDraft?.targetChapterCount)
        }
    }

    @Test
    fun unreadableSnapshotStopsAtRecoverableErrorWithoutBypassingConfirmation() {
        val actions = RecordingCreationActions(loadFails = true)
        composeRule.setContent {
            ZhijuanApp(
                connectionGateway = SingleConnectionGateway,
                bookCreationActions = actions,
                initialDestination = FirstLaunchDestination.CREATE_BOOK,
            )
        }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("story-idea").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("story-idea").performTextInput("一段无法绕过确认的故事。")
        composeRule.onNodeWithTag("create-book-list").performScrollToNode(hasTestTag("start-book"))
        composeRule.onNodeWithTag("start-book").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("cost-confirmation-load-error")
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("暂时无法读取确认信息").assertIsDisplayed()
        composeRule.onAllNodesWithTag("confirm-usage").assertCountEquals(0)
        composeRule.onNodeWithTag("cost-confirmation-error-back").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("start-book").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("start-book").assertIsNotEnabled()
        composeRule.runOnIdle {
            assertEquals(1, actions.callCount)
            assertEquals(1, actions.loadCallCount)
        }
    }

    @Test
    fun confirmationReloadsFromBookIdAfterStateRestoration() {
        val actions = RecordingCreationActions()
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            ZhijuanApp(
                connectionGateway = SingleConnectionGateway,
                bookCreationActions = actions,
                initialDestination = FirstLaunchDestination.CREATE_BOOK,
            )
        }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("story-idea").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("story-idea").performTextInput("重建后仍从本地快照恢复。")
        composeRule.onNodeWithTag("create-book-list").performScrollToNode(hasTestTag("start-book"))
        composeRule.onNodeWithTag("start-book").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("cost-confirmation-summary")
                .fetchSemanticsNodes().isNotEmpty()
        }

        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("cost-confirmation-summary")
                .fetchSemanticsNodes().isNotEmpty() && actions.loadCallCount >= 2
        }
        composeRule.onNodeWithText("雨夜重逢", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("300 章", substring = true).assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(1, actions.callCount)
            assertEquals(2, actions.loadCallCount)
        }
    }

    private class RecordingCreationActions(
        private val loadFails: Boolean = false,
    ) : BookCreationActions {
        @Volatile
        var callCount = 0

        @Volatile
        var loadCallCount = 0

        @Volatile
        var lastDraft: MinimalBookDraft? = null

        override suspend fun create(
            draft: MinimalBookDraft,
            connection: CreationConnectionSelection,
        ): BookCreationResult {
            callCount += 1
            lastDraft = draft
            return BookCreationResult.Created("book-1")
        }

        override suspend fun loadConfirmation(bookId: String): BookCreationConfirmation? {
            loadCallCount += 1
            if (loadFails || bookId != "book-1") return null
            return BookCreationConfirmation(
                bookId = "book-1",
                snapshotId = "snapshot-1",
                title = "雨夜重逢",
                lengthMode = BookLengthMode.MEDIUM,
                minimumChapterCount = 300,
                targetChapterCount = 300,
                modelId = "deepseek-chat",
                contentHash = "a".repeat(64),
            )
        }
    }

    private object SingleConnectionGateway : ConnectionGatewayActions {
        override suspend fun listConnections(): List<SavedConnectionSnapshot> = listOf(
            SavedConnectionSnapshot(
                connectionId = "connection-1",
                displayName = "DeepSeek 写作",
                service = ConnectionServiceChoice.DEEPSEEK,
                protocol = ProviderProtocol.OPENAI_CHAT_COMPAT,
                baseUrl = "https://api.deepseek.com",
                endpointHost = "api.deepseek.com",
                secretLastFour = "0000",
                selectedModelId = "deepseek-chat",
                availableModels = listOf("deepseek-chat"),
                modelVerification = ConnectionModelVerification.DISCOVERED,
                isCurrent = true,
                updatedAt = 1,
            ),
        )

        override suspend fun check(
            input: ConnectionWizardInput,
            newApiKey: CharArray?,
        ): ConnectionWizardCheckResult {
            newApiKey?.fill('\u0000')
            return ConnectionWizardCheckResult.InvalidInput(ConnectionWizardInputError.API_KEY_REQUIRED)
        }

        override suspend fun runFullCheck(modelId: String): FullConnectionCheckResult =
            FullConnectionCheckResult.InvalidInput(ConnectionWizardInputError.NO_PENDING_CONNECTION)

        override suspend fun commitPending(modelId: String): ConnectionCommitResult =
            ConnectionCommitResult.InvalidInput(ConnectionWizardInputError.NO_PENDING_CONNECTION)

        override suspend fun discardPending() = Unit

        override suspend fun selectCurrent(connectionId: String): ConnectionMutationResult =
            ConnectionMutationResult.Success

        override suspend fun editConnection(
            connectionId: String,
            displayName: String,
            selectedModelId: String,
        ): ConnectionMutationResult = ConnectionMutationResult.Success

        override suspend fun deleteConnection(connectionId: String): ConnectionMutationResult =
            ConnectionMutationResult.Success
    }
}
