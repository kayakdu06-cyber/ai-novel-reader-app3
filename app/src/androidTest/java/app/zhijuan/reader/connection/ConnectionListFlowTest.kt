package app.zhijuan.reader.connection

import android.graphics.Bitmap
import android.content.ContentValues
import android.provider.MediaStore
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import app.zhijuan.provider.common.ProviderProtocol
import app.zhijuan.reader.MainActivity
import app.zhijuan.reader.ZhijuanApp
import app.zhijuan.reader.ui.connection.ConnectionListScreen
import app.zhijuan.reader.ui.theme.ZhijuanTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionListFlowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun listEmphasizesCurrentAndOnlyShowsCredentialTail() {
        val fake = FakeConnectionManagementActions()
        showList(fake)

        composeRule.onNodeWithText("模型连接").assertIsDisplayed()
        composeRule.onNodeWithText("当前").assertIsDisplayed()
        composeRule.onNodeWithText("DeepSeek 写作").assertIsDisplayed()
        composeRule.onNodeWithText("密钥 ••••3456", substring = true).assertIsDisplayed()
        composeRule.onAllNodesWithText("fixture-secret-3456").assertCountEquals(0)

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val imageUri = requireNotNull(
            context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(
                        MediaStore.Images.Media.DISPLAY_NAME,
                        "zhijuan-task032-connections-${System.currentTimeMillis()}.png",
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

    @Test
    fun oneTapMakesAnotherConnectionCurrent() {
        val fake = FakeConnectionManagementActions()
        showList(fake)

        composeRule.onNodeWithTag("connection-list").performScrollToIndex(2)
        composeRule.onNodeWithTag("select-connection-b")
            .performScrollTo()
            .performClick()

        composeRule.waitUntil(5_000) { fake.currentId == "connection-b" }
        assertEquals("connection-b", fake.currentId)
    }

    @Test
    fun editSupportsNameAndVerifiedModelWithoutReenteringKey() {
        val fake = FakeConnectionManagementActions()
        showList(fake)

        composeRule.onNodeWithTag("edit-connection-a")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("connection-name").performTextClearance()
        composeRule.onNodeWithTag("connection-name").performTextInput("主要写作")
        composeRule.onNodeWithTag("edit-model-picker").performClick()
        composeRule.onNodeWithText("deepseek-reasoner").performClick()
        composeRule.onNodeWithText("保存").performClick()

        composeRule.waitUntil(5_000) { fake.lastEditedName == "主要写作" }
        assertEquals("deepseek-reasoner", fake.lastEditedModel)
        assertTrue(fake.keyReplacementCalls == 0)
    }

    @Test
    fun deleteRequiresConfirmation() {
        val fake = FakeConnectionManagementActions()
        showList(fake)

        composeRule.onNodeWithTag("edit-connection-a")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("delete-connection")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText("删除这个连接？").assertIsDisplayed()
        assertEquals(0, fake.deleteCalls)
        composeRule.onNodeWithText("确认删除").performClick()

        composeRule.waitUntil(5_000) { fake.deleteCalls == 1 }
    }

    @Test
    fun savedConnectionSkipsDisclosureAndOpensCreationOnNextStartup() {
        val saved = fixture(
            connectionId = "connection-saved",
            displayName = "已保存连接",
            service = ConnectionServiceChoice.DEEPSEEK,
            host = "api.deepseek.com",
            tail = "3456",
            model = "deepseek-chat",
            available = listOf("deepseek-chat"),
            isCurrent = true,
        )
        val fake = FakeStartupGateway(listOf(saved))
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                ZhijuanApp(connectionGateway = fake)
            }
        }

        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("当前连接 · 已保存连接").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("织一本新书").assertIsDisplayed()
        composeRule.onAllNodesWithText("你的故事，先留在这台设备上").assertCountEquals(0)
    }

    @Test
    fun creationFormSurvivesTemporaryConnectionManagement() {
        val saved = fixture(
            connectionId = "connection-saved",
            displayName = "已保存连接",
            service = ConnectionServiceChoice.DEEPSEEK,
            host = "api.deepseek.com",
            tail = "3456",
            model = "deepseek-chat",
            available = listOf("deepseek-chat"),
            isCurrent = true,
        )
        val fake = FakeStartupGateway(listOf(saved))
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                ZhijuanApp(connectionGateway = fake)
            }
        }

        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("织一本新书").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("story-idea").performTextInput("切换页面也不能丢失的故事")
        composeRule.onNodeWithTag("create-book-list").performScrollToNode(hasTestTag("advanced-toggle"))
        composeRule.onNodeWithTag("advanced-toggle").performClick()
        composeRule.onNodeWithTag("create-book-list")
            .performScrollToNode(hasTestTag("advanced-characters"))
        composeRule.onNodeWithTag("advanced-characters").performTextInput("两位主角均为 28 岁")

        composeRule.onNodeWithTag("create-book-list")
            .performScrollToNode(hasTestTag("manage-connections"))
        composeRule.onNodeWithTag("manage-connections").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("返回创作").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("返回创作").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("切换页面也不能丢失的故事", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("create-book-list").performScrollToNode(hasTestTag("advanced-toggle"))
        composeRule.onNodeWithText("已填写 1 项 · 收起").assertIsDisplayed()
        composeRule.onNodeWithTag("create-book-list")
            .performScrollToNode(hasTestTag("advanced-characters"))
        composeRule.onNodeWithText("两位主角均为 28 岁", substring = true).assertIsDisplayed()
    }

    private fun showList(fake: FakeConnectionManagementActions) {
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                ZhijuanTheme(darkTheme = false) {
                    ConnectionListScreen(gateway = fake, onAddConnection = {})
                }
            }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("DeepSeek 写作").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private class FakeConnectionManagementActions : ConnectionManagementActions {
        var currentId = "connection-a"
        var lastEditedName: String? = null
        var lastEditedModel: String? = null
        var deleteCalls = 0
        var keyReplacementCalls = 0
        private val items = mutableListOf(
            fixture(
                connectionId = "connection-a",
                displayName = "DeepSeek 写作",
                service = ConnectionServiceChoice.DEEPSEEK,
                host = "api.deepseek.com",
                tail = "3456",
                model = "deepseek-chat",
                available = listOf("deepseek-chat", "deepseek-reasoner"),
                isCurrent = true,
            ),
            fixture(
                connectionId = "connection-b",
                displayName = "备用中转",
                service = ConnectionServiceChoice.RELAY,
                host = "relay.example",
                tail = "7890",
                model = "relay-chat",
                available = listOf("relay-chat"),
                isCurrent = false,
            ),
        )

        override suspend fun listConnections(): List<SavedConnectionSnapshot> = items.map { item ->
            item.copy(isCurrent = item.connectionId == currentId)
        }.sortedByDescending { it.isCurrent }

        override suspend fun selectCurrent(connectionId: String): ConnectionMutationResult {
            currentId = connectionId
            return ConnectionMutationResult.Success
        }

        override suspend fun editConnection(
            connectionId: String,
            displayName: String,
            selectedModelId: String,
        ): ConnectionMutationResult {
            lastEditedName = displayName
            lastEditedModel = selectedModelId
            val index = items.indexOfFirst { it.connectionId == connectionId }
            items[index] = items[index].copy(
                displayName = displayName,
                selectedModelId = selectedModelId,
            )
            return ConnectionMutationResult.Success
        }

        override suspend fun deleteConnection(connectionId: String): ConnectionMutationResult {
            deleteCalls += 1
            items.removeAll { it.connectionId == connectionId }
            if (currentId == connectionId) currentId = items.firstOrNull()?.connectionId.orEmpty()
            return ConnectionMutationResult.Success
        }
    }

    private class FakeStartupGateway(
        private val saved: List<SavedConnectionSnapshot>,
    ) : ConnectionGatewayActions {
        override suspend fun listConnections(): List<SavedConnectionSnapshot> = saved

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

    private companion object {
        fun fixture(
            connectionId: String,
            displayName: String,
            service: ConnectionServiceChoice,
            host: String,
            tail: String,
            model: String,
            available: List<String>,
            isCurrent: Boolean,
        ) = SavedConnectionSnapshot(
            connectionId = connectionId,
            displayName = displayName,
            service = service,
            protocol = ProviderProtocol.OPENAI_CHAT_COMPAT,
            baseUrl = "https://$host",
            endpointHost = host,
            secretLastFour = tail,
            selectedModelId = model,
            availableModels = available,
            modelVerification = ConnectionModelVerification.DISCOVERED,
            isCurrent = isCurrent,
            updatedAt = if (isCurrent) 2 else 1,
        )
    }
}
