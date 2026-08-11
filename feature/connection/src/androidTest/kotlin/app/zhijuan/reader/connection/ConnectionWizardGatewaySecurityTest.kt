package app.zhijuan.reader.connection

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zhijuan.core.security.AndroidSecretStore
import app.zhijuan.core.security.SecretPurpose
import app.zhijuan.core.security.SecretRecordState
import app.zhijuan.provider.common.ProviderProtocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionWizardGatewaySecurityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun previousProcessPendingSecretIsRevokedBeforeWizardReuse() = runBlocking {
        val store = AndroidSecretStore(context)
        val descriptor = store.createAndClear(
            purpose = SecretPurpose.API_KEY,
            secret = "fixture-stale-key-1234".encodeToByteArray(),
            now = 10,
        )
        val preferences = context.getSharedPreferences("pending-connection-wizard", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().putString("pending-secret-ref", descriptor.secretRefId).commit())

        ConnectionWizardGateway(context).listConnections()

        assertEquals(SecretRecordState.REVOKED, store.descriptor(descriptor.secretRefId).state)
        assertFalse(preferences.contains("pending-secret-ref"))
    }

    @Test
    fun remoteCleartextRelayIsRejectedAndInputBufferIsCleared() = runBlocking {
        val gateway = ConnectionWizardGateway(context)
        val key = "fixture-key-123456".toCharArray()

        val result = gateway.check(
            input = ConnectionWizardInput(
                service = ConnectionServiceChoice.RELAY,
                relayBaseUrl = "http://relay.example/v1",
            ),
            newApiKey = key,
        )

        assertEquals(
            ConnectionWizardCheckResult.InvalidInput(ConnectionWizardInputError.BASE_URL_INVALID),
            result,
        )
        assertTrue(key.all { it == '\u0000' })
        gateway.discardPending()
    }

    @Test
    fun stalePendingMarkerCannotRevokeASecretAlreadyCommittedToEncryptedDatabase() = runBlocking {
        val store = AndroidSecretStore(context)
        val descriptor = store.createAndClear(
            purpose = SecretPurpose.API_KEY,
            secret = "fixture-committed-key-6789".encodeToByteArray(),
            now = 20,
        )
        val repository = PersistentConnectionRepository(context)
        val connectionId = "connection-committed-${System.nanoTime()}"
        repository.insertAndSelectCurrent(
            PersistentConnectionDraft(
                connectionId = connectionId,
                displayName = "Committed fixture",
                service = ConnectionServiceChoice.DEEPSEEK,
                protocol = ProviderProtocol.OPENAI_CHAT_COMPAT,
                baseUrl = "https://api.deepseek.com",
                secretRefId = descriptor.secretRefId,
                secretLastFour = descriptor.lastFour,
                selectedModelId = "deepseek-chat",
                availableModels = listOf("deepseek-chat"),
                modelVerification = ConnectionModelVerification.DISCOVERED,
                basicVerifiedAt = 20,
                fullVerifiedAt = null,
                createdAt = 20,
            ),
        )
        val preferences = context.getSharedPreferences("pending-connection-wizard", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().putString("pending-secret-ref", descriptor.secretRefId).commit())

        val gateway = ConnectionWizardGateway(context)
        gateway.listConnections()

        assertEquals(SecretRecordState.ACTIVE, store.descriptor(descriptor.secretRefId).state)
        assertFalse(preferences.contains("pending-secret-ref"))
        assertEquals(ConnectionMutationResult.Success, gateway.deleteConnection(connectionId))
        assertEquals(SecretRecordState.REVOKED, store.descriptor(descriptor.secretRefId).state)
    }
}
