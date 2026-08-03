package app.zhijuan.provider.common

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProviderCapabilityRegistryTest {
    private val now = 10_000L
    private val model = ProviderModelId.from("model-a")
    private val profile = profile("https://relay.example/v1")
    private val store = MemoryCapabilityStore()
    private val registry = ProviderCapabilityRegistry(store) { now }

    @Test
    fun `priority is override then probe then official then built in`() = runBlocking {
        registry.recordOfficialMetadata(
            profile,
            evidence(CapabilitySource.OFFICIAL_METADATA, verifiedAt = 8_000, expiresAt = 20_000, seed = CapabilitySupport.SUPPORTED),
        )
        registry.recordSuccessfulProbe(
            profile,
            evidence(CapabilitySource.PROBED, verifiedAt = 9_000, expiresAt = 20_000, seed = CapabilitySupport.UNSUPPORTED),
        )
        registry.setUserOverride(
            profile,
            evidence(CapabilitySource.USER_OVERRIDE, verifiedAt = 9_500, expiresAt = null, seed = CapabilitySupport.SUPPORTED),
            riskAcknowledgedAt = 9_400,
        )

        val resolution = registry.resolveDetailed(profile, model, VERSION, builtIn())

        assertEquals(CapabilitySupport.SUPPORTED, resolution.snapshot.seed)
        assertEquals(CapabilitySource.USER_OVERRIDE, resolution.snapshot.source)
        assertEquals(
            listOf(
                CapabilitySource.BUILT_IN,
                CapabilitySource.OFFICIAL_METADATA,
                CapabilitySource.PROBED,
                CapabilitySource.USER_OVERRIDE,
            ),
            resolution.appliedSources,
        )
        assertTrue(resolution.userOverrideActive)
    }

    @Test
    fun `unknown evidence fields preserve lower priority verified values`() = runBlocking {
        registry.recordOfficialMetadata(
            profile,
            evidence(
                source = CapabilitySource.OFFICIAL_METADATA,
                verifiedAt = 8_000,
                expiresAt = 20_000,
                seed = CapabilitySupport.SUPPORTED,
                contextLimit = 128_000,
            ),
        )
        registry.recordSuccessfulProbe(
            profile,
            evidence(
                CapabilitySource.PROBED,
                verifiedAt = 9_000,
                expiresAt = 20_000,
                temperature = CapabilitySupport.SUPPORTED,
            ),
        )

        val result = registry.resolve(profile, model, VERSION, builtIn())

        assertEquals(CapabilitySupport.SUPPORTED, result.seed)
        assertEquals(128_000, result.contextLimit)
    }

    @Test
    fun `expired evidence is ignored at the exact expiry instant`() = runBlocking {
        store.upsertNewest(
            StoredProviderCapability(
                ProviderCapabilityStorageKey.from(profile, model),
                evidence(CapabilitySource.PROBED, verifiedAt = 8_000, expiresAt = now, seed = CapabilitySupport.SUPPORTED),
            ),
        )

        val result = registry.resolveDetailed(profile, model, VERSION, builtIn())

        assertEquals(CapabilitySupport.UNKNOWN, result.snapshot.seed)
        assertFalse(CapabilitySource.PROBED in result.appliedSources)
    }

    @Test
    fun `adapter version mismatch fails closed`() = runBlocking {
        store.upsertNewest(
            StoredProviderCapability(
                ProviderCapabilityStorageKey.from(profile, model),
                evidence(CapabilitySource.PROBED, 8_000, 20_000, CapabilitySupport.SUPPORTED, adapterVersion = "old-1"),
            ),
        )

        val result = registry.resolve(profile, model, VERSION, builtIn())

        assertEquals(CapabilitySupport.UNKNOWN, result.seed)
    }

    @Test
    fun `base url change gives a different non printable cache identity`() {
        val first = ProviderEndpointFingerprint.from(profile)
        val second = ProviderEndpointFingerprint.from(profile("https://other.example/v1"))

        assertNotEquals(first, second)
        assertEquals("<endpoint-fingerprint>", first.toString())
    }

    @Test
    fun `one click reset exposes the automatic probe again`() = runBlocking {
        registry.recordSuccessfulProbe(
            profile,
            evidence(CapabilitySource.PROBED, 8_000, 20_000, CapabilitySupport.UNSUPPORTED),
        )
        registry.setUserOverride(
            profile,
            evidence(CapabilitySource.USER_OVERRIDE, 9_000, null, CapabilitySupport.SUPPORTED),
            9_000,
        )
        assertTrue(registry.resetUserOverride(profile, model))

        val result = registry.resolveDetailed(profile, model, VERSION, builtIn())

        assertEquals(CapabilitySupport.UNSUPPORTED, result.snapshot.seed)
        assertFalse(result.userOverrideActive)
    }

    @Test
    fun `automatic evidence must be fresh and user override must be acknowledged`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                registry.recordSuccessfulProbe(
                    profile,
                    evidence(CapabilitySource.PROBED, 8_000, now, CapabilitySupport.SUPPORTED),
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                registry.setUserOverride(
                    profile,
                    evidence(CapabilitySource.USER_OVERRIDE, 9_000, null, CapabilitySupport.SUPPORTED),
                    now + 1,
                )
            }
        }
    }

    @Test
    fun `storage failures degrade to conservative baseline`() = runBlocking {
        val failing = ProviderCapabilityRegistry(
            store = object : ProviderCapabilityStore {
                override suspend fun load(key: ProviderCapabilityStorageKey): List<StoredProviderCapability> =
                    error("unavailable")

                override suspend fun upsertNewest(record: StoredProviderCapability) = Unit

                override suspend fun delete(key: ProviderCapabilityStorageKey, source: CapabilitySource) = false
            },
            clock = { now },
        )

        val result = failing.resolve(profile, model, VERSION)

        assertEquals(CapabilitySource.CONSERVATIVE_DEFAULT, result.source)
        assertEquals(CapabilitySupport.UNKNOWN, result.structuredOutput)
    }

    @Test
    fun `storage cancellation is never converted into a conservative success`() {
        val cancelling = ProviderCapabilityRegistry(
            store = object : ProviderCapabilityStore {
                override suspend fun load(key: ProviderCapabilityStorageKey): List<StoredProviderCapability> =
                    throw CancellationException("cancelled")

                override suspend fun upsertNewest(record: StoredProviderCapability) = Unit

                override suspend fun delete(key: ProviderCapabilityStorageKey, source: CapabilitySource) = false
            },
            clock = { now },
        )

        assertThrows(CancellationException::class.java) {
            runBlocking { cancelling.resolve(profile, model, VERSION) }
        }
    }

    private fun profile(url: String) = ProviderConnectionProfile.create(
        connectionId = "connection-1",
        protocol = ProviderProtocol.GEMINI_GENERATE_CONTENT,
        baseUrl = url,
    )

    private fun builtIn() = evidence(
        source = CapabilitySource.BUILT_IN,
        verifiedAt = 1_000,
        expiresAt = null,
    )

    private fun evidence(
        source: CapabilitySource,
        verifiedAt: Long,
        expiresAt: Long?,
        seed: CapabilitySupport = CapabilitySupport.UNKNOWN,
        contextLimit: Int? = null,
        adapterVersion: String = VERSION,
        temperature: CapabilitySupport = CapabilitySupport.UNKNOWN,
    ) = ProviderCapabilitySnapshot(
        protocol = profile.protocol,
        modelId = model,
        streaming = CapabilitySupport.UNKNOWN,
        streamFormat = ProviderStreamFormat.UNKNOWN,
        structuredOutput = CapabilitySupport.UNKNOWN,
        usageInStream = CapabilitySupport.UNKNOWN,
        systemInstruction = CapabilitySupport.UNKNOWN,
        temperature = temperature,
        topP = CapabilitySupport.UNKNOWN,
        maxOutputTokensParameter = CapabilitySupport.UNKNOWN,
        seed = seed,
        reasoningEffort = CapabilitySupport.UNKNOWN,
        idempotencyKey = CapabilitySupport.UNKNOWN,
        contextLimit = contextLimit,
        maxOutputTokens = null,
        tokenizerFamily = TokenizerFamily.UNKNOWN,
        source = source,
        verifiedAt = verifiedAt,
        expiresAt = expiresAt,
        adapterVersion = adapterVersion,
    )

    private class MemoryCapabilityStore : ProviderCapabilityStore {
        private val records = mutableMapOf<Pair<ProviderCapabilityStorageKey, CapabilitySource>, StoredProviderCapability>()

        override suspend fun load(key: ProviderCapabilityStorageKey): List<StoredProviderCapability> =
            records.filterKeys { it.first == key }.values.toList()

        override suspend fun upsertNewest(record: StoredProviderCapability) {
            val key = record.key to record.snapshot.source
            val existing = records[key]
            if (existing == null || record.snapshot.verifiedAt >= existing.snapshot.verifiedAt) records[key] = record
        }

        override suspend fun delete(key: ProviderCapabilityStorageKey, source: CapabilitySource): Boolean =
            records.remove(key to source) != null
    }

    private companion object {
        const val VERSION = "gemini-1"
    }
}
