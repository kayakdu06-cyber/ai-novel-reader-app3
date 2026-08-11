package app.zhijuan.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zhijuan.core.database.connection.ConnectionProfileEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ConnectionDatabaseTest {
    @get:Rule
    val timeout: Timeout = Timeout(20, TimeUnit.SECONDS)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val database = Room.inMemoryDatabaseBuilder(context, ZhijuanDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val dao = database.connectionDao()

    @After
    fun closeDatabase() = database.close()

    @Test
    fun insertSelectEditAndDeleteCurrentUsesDeterministicFallback() = runBlocking {
        val first = fixture("connection-a", "secret-a", "1234", 10)
        val second = fixture("connection-b", "secret-b", "5678", 20)
        dao.insertAndSelectCurrent(first)
        dao.insertAndSelectCurrent(second)

        assertEquals("connection-b", dao.snapshot().currentConnectionId)
        dao.editConnection(
            connectionId = "connection-a",
            displayName = "写作连接",
            selectedModelId = "model-b",
            modelVerification = "DISCOVERED",
            fullVerifiedAt = null,
            updatedAt = 30,
        )
        dao.selectCurrent("connection-a", 31)
        val deleted = dao.deleteAndChooseFallback("connection-a", 32)

        assertEquals("secret-a", deleted.deleted.secretRefId)
        assertEquals("connection-b", deleted.newCurrentConnectionId)
        assertEquals("connection-b", dao.snapshot().currentConnectionId)
        assertEquals(listOf("connection-b"), dao.listConnections().map { it.connectionId })
    }

    @Test
    fun deletingOnlyConnectionClearsCurrentSelection() = runBlocking {
        dao.insertAndSelectCurrent(fixture("connection-a", "secret-a", "1234", 10))

        dao.deleteAndChooseFallback("connection-a", 20)

        assertNull(dao.currentConnectionId())
        assertEquals(emptyList<ConnectionProfileEntity>(), dao.listConnections())
    }

    @Test
    fun dataDisclosureAcceptancePersistsCanonicalEvidenceAndReplays() = runBlocking {
        val connection = fixture("connection-a", "secret-a", "1234", 10).copy(
            baseUrl = "https://API.DeepSeek.com/v1/",
            normalizedDestination = "untrusted-placeholder",
        )
        dao.insertAndSelectCurrent(connection)

        assertThrows(IllegalStateException::class.java) {
            runBlocking { dao.readAcceptedDataDisclosureEvidence(connection.connectionId) }
        }
        val accepted = dao.acceptDataDisclosureForCurrentDestination(connection.connectionId, acceptedAt = 20)
        val replay = dao.readAcceptedDataDisclosureEvidence(connection.connectionId)

        assertEquals("https://api.deepseek.com:443", accepted.normalizedDestination)
        assertEquals("OPENAI_CHAT_COMPAT", accepted.protocolId)
        assertEquals(1, accepted.disclosureVersion)
        assertEquals(20, accepted.acceptedAt)
        assertEquals(accepted, replay)
        assertFalse(accepted.toString().contains("api.deepseek.com"))
        assertFalse(accepted.toString().contains(accepted.bindingHash))
    }

    @Test
    fun currentDestinationOrStoredEvidenceChangeInvalidatesAcceptance() = runBlocking {
        val connection = fixture("connection-a", "secret-a", "1234", 10)
        dao.insertAndSelectCurrent(connection)
        dao.acceptDataDisclosureForCurrentDestination(connection.connectionId, acceptedAt = 20)

        database.openHelper.writableDatabase.execSQL(
            "UPDATE connection_profile SET protocol_id = ? WHERE connection_id = ?",
            arrayOf("OPENAI_RESPONSES", connection.connectionId),
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { dao.readAcceptedDataDisclosureEvidence(connection.connectionId) }
        }
        Unit
    }

    @Test
    fun hostChangeHashTamperAndVersionBumpInvalidateAcceptance() = runBlocking {
        val hostChange = fixture("connection-host", "secret-a", "1234", 10)
        dao.insertAndSelectCurrent(hostChange)
        dao.acceptDataDisclosureForCurrentDestination(hostChange.connectionId, acceptedAt = 20)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE connection_profile SET base_url = ? WHERE connection_id = ?",
            arrayOf("https://other.example.com", hostChange.connectionId),
        )
        assertThrows(IllegalStateException::class.java) {
            runBlocking { dao.readAcceptedDataDisclosureEvidence(hostChange.connectionId) }
        }

        val hashTamper = fixture("connection-hash", "secret-b", "5678", 10)
        dao.insertAndSelectCurrent(hashTamper)
        dao.acceptDataDisclosureForCurrentDestination(hashTamper.connectionId, acceptedAt = 20)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE connection_profile SET data_disclosure_binding_hash = ? WHERE connection_id = ?",
            arrayOf("0".repeat(64), hashTamper.connectionId),
        )
        assertThrows(IllegalStateException::class.java) {
            runBlocking { dao.readAcceptedDataDisclosureEvidence(hashTamper.connectionId) }
        }

        val versionBump = fixture("connection-version", "secret-c", "9012", 10)
        dao.insertAndSelectCurrent(versionBump)
        dao.acceptDataDisclosureForCurrentDestination(versionBump.connectionId, acceptedAt = 20)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE connection_profile SET data_disclosure_version = ? WHERE connection_id = ?",
            arrayOf<Any>(2, versionBump.connectionId),
        )
        assertThrows(IllegalStateException::class.java) {
            runBlocking { dao.readAcceptedDataDisclosureEvidence(versionBump.connectionId) }
        }

        dao.acceptDataDisclosureForCurrentDestination(hostChange.connectionId, acceptedAt = 30)
        val restored = dao.readAcceptedDataDisclosureEvidence(hostChange.connectionId)
        assertEquals("https://other.example.com:443", restored.normalizedDestination)
        assertEquals(30, restored.acceptedAt)
    }

    @Test
    fun connectionEntityStringIsRedacted() {
        val connection = fixture("connection-a", "secret-a", "1234", 10).copy(
            dataDisclosureBindingHash = "a".repeat(64),
        )
        val rendered = connection.toString()

        assertFalse(rendered.contains(connection.baseUrl))
        assertFalse(rendered.contains(connection.secretLastFour))
        assertFalse(rendered.contains(requireNotNull(connection.dataDisclosureBindingHash)))
        assertFalse(rendered.contains(connection.connectionId))
    }

    private fun fixture(
        connectionId: String,
        secretRefId: String,
        tail: String,
        updatedAt: Long,
    ) = ConnectionProfileEntity(
        connectionId = connectionId,
        displayName = "DeepSeek",
        serviceId = "DEEPSEEK",
        protocolId = "OPENAI_CHAT_COMPAT",
        baseUrl = "https://api.deepseek.com",
        normalizedDestination = "https://api.deepseek.com:443",
        secretRefId = secretRefId,
        secretLastFour = tail,
        selectedModelId = "deepseek-chat",
        availableModelsJson = "[\"deepseek-chat\",\"model-b\"]",
        modelVerification = "DISCOVERED",
        basicVerifiedAt = 5,
        fullVerifiedAt = null,
        dataDisclosureVersion = null,
        dataDisclosureAcceptedAt = null,
        dataDisclosureBindingHash = null,
        createdAt = 5,
        updatedAt = updatedAt,
    )
}
