package app.zhijuan.provider.capability.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.provider.common.CapabilitySource
import app.zhijuan.provider.common.CapabilitySupport
import app.zhijuan.provider.common.ProviderCapabilityRegistry
import app.zhijuan.provider.common.ProviderCapabilitySnapshot
import app.zhijuan.provider.common.ProviderConnectionProfile
import app.zhijuan.provider.common.ProviderModelId
import app.zhijuan.provider.common.ProviderProtocol
import app.zhijuan.provider.common.ProviderStreamFormat
import app.zhijuan.provider.common.TokenizerFamily
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderCapabilityDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: ZhijuanDatabase
    private lateinit var registry: ProviderCapabilityRegistry
    private val model = ProviderModelId.from("model-a")
    private val profile = ProviderConnectionProfile.create(
        connectionId = "connection-1",
        protocol = ProviderProtocol.OPENAI_RESPONSES,
        baseUrl = "https://relay.example/v1",
    )

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, ZhijuanDatabase::class.java).build()
        registry = ProviderCapabilityRegistry(RoomProviderCapabilityStore(database)) { NOW }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun recordsSurviveARegistryRecreationAndResetRestoresProbe() = runBlocking {
        registry.recordSuccessfulProbe(
            profile,
            evidence(CapabilitySource.PROBED, 8_000, 20_000, CapabilitySupport.UNSUPPORTED),
        )
        registry.setUserOverride(
            profile,
            evidence(CapabilitySource.USER_OVERRIDE, 9_000, null, CapabilitySupport.SUPPORTED),
            riskAcknowledgedAt = 9_000,
        )

        val reopenedRegistry = ProviderCapabilityRegistry(RoomProviderCapabilityStore(database)) { NOW }
        assertEquals(
            CapabilitySupport.SUPPORTED,
            reopenedRegistry.resolve(profile, model, VERSION).seed,
        )
        assertTrue(reopenedRegistry.resetUserOverride(profile, model))
        val automatic = reopenedRegistry.resolveDetailed(profile, model, VERSION)
        assertEquals(CapabilitySupport.UNSUPPORTED, automatic.snapshot.seed)
        assertFalse(automatic.userOverrideActive)
    }

    @Test
    fun olderRefreshCannotOverwriteNewerEvidence() = runBlocking {
        registry.recordSuccessfulProbe(
            profile,
            evidence(CapabilitySource.PROBED, 9_000, 20_000, CapabilitySupport.SUPPORTED),
        )
        registry.recordSuccessfulProbe(
            profile,
            evidence(CapabilitySource.PROBED, 8_000, 20_000, CapabilitySupport.UNSUPPORTED),
        )

        val result = registry.resolve(profile, model, VERSION)

        assertEquals(CapabilitySupport.SUPPORTED, result.seed)
        assertEquals(9_000, result.verifiedAt)
    }

    private fun evidence(
        source: CapabilitySource,
        verifiedAt: Long,
        expiresAt: Long?,
        seed: CapabilitySupport,
    ) = ProviderCapabilitySnapshot(
        protocol = profile.protocol,
        modelId = model,
        streaming = CapabilitySupport.UNKNOWN,
        streamFormat = ProviderStreamFormat.UNKNOWN,
        structuredOutput = CapabilitySupport.UNKNOWN,
        usageInStream = CapabilitySupport.UNKNOWN,
        systemInstruction = CapabilitySupport.UNKNOWN,
        temperature = CapabilitySupport.UNKNOWN,
        topP = CapabilitySupport.UNKNOWN,
        maxOutputTokensParameter = CapabilitySupport.UNKNOWN,
        seed = seed,
        reasoningEffort = CapabilitySupport.UNKNOWN,
        idempotencyKey = CapabilitySupport.UNKNOWN,
        contextLimit = null,
        maxOutputTokens = null,
        tokenizerFamily = TokenizerFamily.UNKNOWN,
        source = source,
        verifiedAt = verifiedAt,
        expiresAt = expiresAt,
        adapterVersion = VERSION,
    )

    private companion object {
        const val NOW = 10_000L
        const val VERSION = "openai-responses-1"
    }
}
