package app.zhijuan.reader.onboarding

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.material3.MaterialTheme
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zhijuan.core.model.StandardErrorCode
import app.zhijuan.provider.common.ProviderProtocol
import app.zhijuan.reader.connection.ConnectionServiceChoice
import app.zhijuan.reader.connection.ConnectionCommitResult
import app.zhijuan.reader.connection.ConnectionModelVerification
import app.zhijuan.reader.connection.ConnectionWizardActions
import app.zhijuan.reader.connection.ConnectionWizardCheckResult
import app.zhijuan.reader.connection.ConnectionWizardInput
import app.zhijuan.reader.connection.FullConnectionCheckResult
import app.zhijuan.reader.connection.PendingConnectionSnapshot
import app.zhijuan.reader.connection.SavedConnectionSnapshot
import app.zhijuan.reader.ui.onboarding.ConnectionWizardScreen
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionWizardFlowTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun officialFlowGetsModelsAndUsesRecommendedModel() {
        val fake = FakeConnectionWizardActions(
            checkResult = ConnectionWizardCheckResult.Ready(
                pending = pending(ConnectionServiceChoice.DEEPSEEK),
                models = listOf("deepseek-reasoner", "deepseek-chat"),
                recommendedModel = "deepseek-chat",
            ),
        )
        val selectedModel = AtomicReference<String?>(null)
        showWizard(fake) { connection -> selectedModel.set(connection.selectedModelId) }

        composeRule.onNodeWithTag("api-key").performTextInput("fixture-key-123456")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("check-connection")
            .performScrollTo()
            .assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitUntil(TEST_UI_TIMEOUT_MS) { fake.checkCalls == 1 }

        composeRule.onNodeWithText("基础检查已通过")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("deepseek-chat")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("使用此连接")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitUntil(TEST_UI_TIMEOUT_MS) { selectedModel.get() != null }

        assertEquals(ConnectionServiceChoice.DEEPSEEK, fake.lastInput?.service)
        assertEquals("deepseek-chat", selectedModel.get())
        assertTrue(fake.receivedKeyWasClearedAfterReturn)
    }

    @Test
    fun relayOnlyShowsManualModelAfterExplicitListRejection() {
        val fake = FakeConnectionWizardActions(
            checkResult = ConnectionWizardCheckResult.ManualModelAllowed(
                pending(ConnectionServiceChoice.RELAY),
            ),
        )
        showWizard(fake)

        composeRule.onNodeWithText("兼容中转")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithTag("relay-base-url").performTextInput("https://relay.example/v1")
        composeRule.onNodeWithTag("api-key").performTextInput("fixture-key-123456")
        composeRule.onNodeWithTag("check-connection")
            .performScrollTo()
            .assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitUntil(TEST_UI_TIMEOUT_MS) { fake.checkCalls == 1 }

        composeRule.onNodeWithText("可以继续，但模型尚未验证")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("manual-model")
            .performScrollTo()
            .assertIsDisplayed()
        assertEquals(ConnectionServiceChoice.RELAY, fake.lastInput?.service)
        assertEquals("https://relay.example/v1", fake.lastInput?.relayBaseUrl)
    }

    @Test
    fun authenticationFailureRetainsOnlyCredentialTailAndDoesNotOfferManualModel() {
        val fake = FakeConnectionWizardActions(
            checkResult = ConnectionWizardCheckResult.Failed(
                error = StandardErrorCode.AUTH_FAILED,
                pendingCredential = pending(ConnectionServiceChoice.DEEPSEEK),
            ),
        )
        showWizard(fake)

        composeRule.onNodeWithTag("api-key").performTextInput("fixture-key-123456")
        composeRule.onNodeWithTag("check-connection")
            .performScrollTo()
            .assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitUntil(TEST_UI_TIMEOUT_MS) { fake.checkCalls == 1 }

        composeRule.onNodeWithText("密钥没有通过验证，请更换密钥后重试。")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("••••3456")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onAllNodesWithTag("manual-model").assertCountEquals(0)
    }

    @Test
    fun fullCheckRequiresCostConfirmationBeforeCallingGateway() {
        val fake = FakeConnectionWizardActions(
            checkResult = ConnectionWizardCheckResult.Ready(
                pending = pending(ConnectionServiceChoice.DEEPSEEK),
                models = listOf("deepseek-chat"),
                recommendedModel = "deepseek-chat",
            ),
            fullResult = FullConnectionCheckResult.Verified(usageObserved = true),
        )
        showWizard(fake)

        composeRule.onNodeWithTag("api-key").performTextInput("fixture-key-123456")
        composeRule.onNodeWithTag("check-connection")
            .performScrollTo()
            .assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitUntil(TEST_UI_TIMEOUT_MS) { fake.checkCalls == 1 }
        composeRule.onNodeWithText("高级：做一次更完整的验证")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)

        composeRule.onNodeWithText("确认完整验证").assertIsDisplayed()
        assertEquals(0, fake.fullCheckCalls)
        composeRule.onNodeWithText("确认并验证")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitUntil(TEST_UI_TIMEOUT_MS) { fake.fullCheckCalls == 1 }
        composeRule.onNodeWithText("完整验证已通过，并收到了用量信息。")
            .performScrollTo()
            .assertIsDisplayed()
    }

    private fun showWizard(
        fake: FakeConnectionWizardActions,
        onSaved: (SavedConnectionSnapshot) -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                ConnectionWizardScreen(
                    gateway = fake,
                    onBack = {},
                    onConnectionSaved = onSaved,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private class FakeConnectionWizardActions(
        private val checkResult: ConnectionWizardCheckResult,
        private val fullResult: FullConnectionCheckResult = FullConnectionCheckResult.TimedOut,
    ) : ConnectionWizardActions {
        @Volatile
        var checkCalls = 0

        @Volatile
        var fullCheckCalls = 0
        var lastInput: ConnectionWizardInput? = null
        var receivedKeyWasClearedAfterReturn = false

        override suspend fun check(
            input: ConnectionWizardInput,
            newApiKey: CharArray?,
        ): ConnectionWizardCheckResult {
            checkCalls += 1
            lastInput = input
            newApiKey?.fill('\u0000')
            receivedKeyWasClearedAfterReturn = newApiKey?.all { it == '\u0000' } == true
            return checkResult
        }

        override suspend fun runFullCheck(modelId: String): FullConnectionCheckResult {
            fullCheckCalls += 1
            return fullResult
        }

        override suspend fun commitPending(modelId: String): ConnectionCommitResult {
            val pending = checkNotNull(
                when (checkResult) {
                    is ConnectionWizardCheckResult.Ready -> checkResult.pending
                    is ConnectionWizardCheckResult.ManualModelAllowed -> checkResult.pending
                    else -> null
                },
            )
            return ConnectionCommitResult.Saved(
                SavedConnectionSnapshot(
                    connectionId = pending.connectionId,
                    displayName = "DeepSeek",
                    service = pending.service,
                    protocol = pending.protocol,
                    baseUrl = pending.baseUrl,
                    endpointHost = pending.endpointHost,
                    secretLastFour = pending.secretLastFour,
                    selectedModelId = modelId,
                    availableModels = if (checkResult is ConnectionWizardCheckResult.Ready) {
                        checkResult.models
                    } else {
                        emptyList()
                    },
                    modelVerification = ConnectionModelVerification.DISCOVERED,
                    isCurrent = true,
                    updatedAt = 1,
                ),
            )
        }

        override suspend fun discardPending() = Unit
    }

    private companion object {
        fun pending(service: ConnectionServiceChoice) = PendingConnectionSnapshot(
            connectionId = "connection-fixture",
            service = service,
            protocol = if (service == ConnectionServiceChoice.RELAY) {
                ProviderProtocol.OPENAI_CHAT_COMPAT
            } else {
                ProviderProtocol.OPENAI_CHAT_COMPAT
            },
            baseUrl = if (service == ConnectionServiceChoice.RELAY) {
                "https://relay.example/v1"
            } else {
                "https://api.deepseek.com"
            },
            endpointHost = if (service == ConnectionServiceChoice.RELAY) {
                "relay.example"
            } else {
                "api.deepseek.com"
            },
            secretLastFour = "3456",
        )
    }
}

private const val TEST_UI_TIMEOUT_MS = 30_000L
