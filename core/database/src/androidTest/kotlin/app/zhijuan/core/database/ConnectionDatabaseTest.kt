package app.zhijuan.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zhijuan.core.database.connection.ConnectionProfileEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        normalizedDestination = "https://api.deepseek.com",
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
