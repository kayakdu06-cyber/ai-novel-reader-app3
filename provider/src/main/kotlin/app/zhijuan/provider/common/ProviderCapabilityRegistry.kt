package app.zhijuan.provider.common

import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ProviderEndpointFingerprint private constructor(
    private val value: String,
) {
    fun <T> withValue(block: (String) -> T): T = block(value)

    override fun toString(): String = "<endpoint-fingerprint>"

    override fun equals(other: Any?): Boolean =
        other is ProviderEndpointFingerprint && value == other.value

    override fun hashCode(): Int = value.hashCode()

    companion object {
        fun from(profile: ProviderConnectionProfile): ProviderEndpointFingerprint {
            val canonical = profile.withBaseUrl { it } + "\n" + profile.protocol.name
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
            val encoded = digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
            digest.fill(0)
            return ProviderEndpointFingerprint(encoded)
        }

        fun fromEncoded(value: String): ProviderEndpointFingerprint {
            require(value.matches(Regex("[0-9a-f]{64}"))) { "Endpoint fingerprint is invalid." }
            return ProviderEndpointFingerprint(value)
        }
    }
}

data class ProviderCapabilityStorageKey(
    val connectionId: String,
    val endpointFingerprint: ProviderEndpointFingerprint,
    val protocol: ProviderProtocol,
    val modelId: ProviderModelId,
) {
    companion object {
        fun from(
            profile: ProviderConnectionProfile,
            modelId: ProviderModelId,
        ): ProviderCapabilityStorageKey = ProviderCapabilityStorageKey(
            connectionId = profile.connectionId,
            endpointFingerprint = ProviderEndpointFingerprint.from(profile),
            protocol = profile.protocol,
            modelId = modelId,
        )
    }
}

data class StoredProviderCapability(
    val key: ProviderCapabilityStorageKey,
    val snapshot: ProviderCapabilitySnapshot,
    val riskAcknowledgedAt: Long? = null,
) {
    init {
        require(snapshot.protocol == key.protocol) { "Stored capability protocol does not match its key." }
        require(snapshot.modelId == key.modelId) { "Stored capability model does not match its key." }
        require(snapshot.source in PERSISTED_SOURCES) { "Capability source is not persistable." }
        require((snapshot.source == CapabilitySource.USER_OVERRIDE) == (riskAcknowledgedAt != null)) {
            "Only an acknowledged user override may carry override risk acknowledgement."
        }
        require(riskAcknowledgedAt == null || riskAcknowledgedAt >= 0) {
            "Override risk acknowledgement time is invalid."
        }
        require(
            snapshot.source == CapabilitySource.USER_OVERRIDE || snapshot.expiresAt != null,
        ) { "Automatic capability evidence must expire." }
        require(hasMaterialEvidence(snapshot)) { "Stored capability evidence is empty." }
        require(streamStateIsSelfContained(snapshot)) { "Stored stream capability evidence is inconsistent." }
    }

    companion object {
        val PERSISTED_SOURCES = setOf(
            CapabilitySource.OFFICIAL_METADATA,
            CapabilitySource.PROBED,
            CapabilitySource.USER_OVERRIDE,
        )

        private fun hasMaterialEvidence(snapshot: ProviderCapabilitySnapshot): Boolean =
            ProviderRequestField.entries.any { snapshot.supportFor(it) != CapabilitySupport.UNKNOWN } ||
                snapshot.contextLimit != null ||
                snapshot.maxOutputTokens != null ||
                snapshot.tokenizerFamily != TokenizerFamily.UNKNOWN

        private fun streamStateIsSelfContained(snapshot: ProviderCapabilitySnapshot): Boolean = when (snapshot.streaming) {
            CapabilitySupport.SUPPORTED -> snapshot.streamFormat in setOf(
                ProviderStreamFormat.SSE,
                ProviderStreamFormat.NDJSON,
            )
            CapabilitySupport.UNSUPPORTED -> snapshot.streamFormat == ProviderStreamFormat.NONE
            CapabilitySupport.UNKNOWN -> snapshot.streamFormat == ProviderStreamFormat.UNKNOWN
        }
    }
}

interface ProviderCapabilityStore {
    suspend fun load(key: ProviderCapabilityStorageKey): List<StoredProviderCapability>

    suspend fun upsertNewest(record: StoredProviderCapability)

    suspend fun delete(
        key: ProviderCapabilityStorageKey,
        source: CapabilitySource,
    ): Boolean
}

class InMemoryProviderCapabilityStore : ProviderCapabilityStore {
    private val mutex = Mutex()
    private val records = mutableMapOf<RecordKey, StoredProviderCapability>()

    override suspend fun load(key: ProviderCapabilityStorageKey): List<StoredProviderCapability> = mutex.withLock {
        records.filterKeys { it.storageKey == key }.values.toList()
    }

    override suspend fun upsertNewest(record: StoredProviderCapability) {
        mutex.withLock {
            val key = RecordKey(record.key, record.snapshot.source)
            val current = records[key]
            if (current == null || record.snapshot.verifiedAt >= current.snapshot.verifiedAt) {
                records[key] = record
            }
        }
    }

    override suspend fun delete(
        key: ProviderCapabilityStorageKey,
        source: CapabilitySource,
    ): Boolean = mutex.withLock {
        records.remove(RecordKey(key, source)) != null
    }

    private data class RecordKey(
        val storageKey: ProviderCapabilityStorageKey,
        val source: CapabilitySource,
    )
}

data class ProviderCapabilityResolution(
    val snapshot: ProviderCapabilitySnapshot,
    val appliedSources: List<CapabilitySource>,
    val userOverrideActive: Boolean,
    val nextRefreshAt: Long?,
)

class ProviderCapabilityRegistry(
    private val store: ProviderCapabilityStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun resolve(
        profile: ProviderConnectionProfile,
        modelId: ProviderModelId,
        adapterVersion: String,
        builtIn: ProviderCapabilitySnapshot? = null,
    ): ProviderCapabilitySnapshot = resolveDetailed(
        profile = profile,
        modelId = modelId,
        adapterVersion = adapterVersion,
        builtIn = builtIn,
    ).snapshot

    suspend fun resolveDetailed(
        profile: ProviderConnectionProfile,
        modelId: ProviderModelId,
        adapterVersion: String,
        builtIn: ProviderCapabilitySnapshot? = null,
    ): ProviderCapabilityResolution {
        val now = clock().coerceAtLeast(0)
        val key = ProviderCapabilityStorageKey.from(profile, modelId)
        val conservative = ProviderCapabilitySnapshot.conservativeUnknown(
            protocol = profile.protocol,
            modelId = modelId,
            verifiedAt = now,
            adapterVersion = adapterVersion,
        )
        val validBuiltIn = builtIn?.takeIf {
            it.protocol == profile.protocol &&
                it.modelId == modelId &&
                it.adapterVersion == adapterVersion &&
                it.verifiedAt <= now &&
                !it.isExpiredAt(now) &&
                it.source in setOf(CapabilitySource.BUILT_IN, CapabilitySource.CONSERVATIVE_DEFAULT)
        }
        val persisted = try {
            store.load(key)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
            .filter { record ->
                record.key == key &&
                    record.snapshot.adapterVersion == adapterVersion &&
                    !record.snapshot.isExpiredAt(now) &&
                    record.snapshot.verifiedAt <= now &&
                    (record.riskAcknowledgedAt == null || record.riskAcknowledgedAt <= now)
            }
            .groupBy { it.snapshot.source }
            .mapValues { (_, records) -> records.maxBy { it.snapshot.verifiedAt } }

        var resolved = validBuiltIn?.let { merge(conservative, it) } ?: conservative
        val sources = mutableListOf<CapabilitySource>()
        if (validBuiltIn != null && validBuiltIn.source == CapabilitySource.BUILT_IN) {
            sources += CapabilitySource.BUILT_IN
        }
        RESOLUTION_ORDER.forEach { source ->
            persisted[source]?.let { record ->
                resolved = merge(resolved, record.snapshot)
                sources += source
            }
        }
        val refreshAt = persisted.values
            .mapNotNull { it.snapshot.expiresAt }
            .minOrNull()
        return ProviderCapabilityResolution(
            snapshot = resolved,
            appliedSources = sources.distinct(),
            userOverrideActive = CapabilitySource.USER_OVERRIDE in sources,
            nextRefreshAt = refreshAt,
        )
    }

    suspend fun recordOfficialMetadata(
        profile: ProviderConnectionProfile,
        snapshot: ProviderCapabilitySnapshot,
    ) = recordAutomatic(profile, snapshot, CapabilitySource.OFFICIAL_METADATA)

    suspend fun recordSuccessfulProbe(
        profile: ProviderConnectionProfile,
        snapshot: ProviderCapabilitySnapshot,
    ) = recordAutomatic(profile, snapshot, CapabilitySource.PROBED)

    suspend fun setUserOverride(
        profile: ProviderConnectionProfile,
        snapshot: ProviderCapabilitySnapshot,
        riskAcknowledgedAt: Long,
    ) {
        val now = clock().coerceAtLeast(0)
        require(snapshot.source == CapabilitySource.USER_OVERRIDE) { "User override has the wrong source." }
        require(riskAcknowledgedAt in 0..now) { "User override risk must be acknowledged explicitly." }
        requireSnapshotMatches(profile, snapshot, now)
        require(snapshot.expiresAt == null || snapshot.expiresAt > now) {
            "User override is already expired."
        }
        store.upsertNewest(
            StoredProviderCapability(
                key = ProviderCapabilityStorageKey.from(profile, snapshot.modelId),
                snapshot = snapshot,
                riskAcknowledgedAt = riskAcknowledgedAt,
            ),
        )
    }

    suspend fun resetUserOverride(
        profile: ProviderConnectionProfile,
        modelId: ProviderModelId,
    ): Boolean = store.delete(
        key = ProviderCapabilityStorageKey.from(profile, modelId),
        source = CapabilitySource.USER_OVERRIDE,
    )

    private suspend fun recordAutomatic(
        profile: ProviderConnectionProfile,
        snapshot: ProviderCapabilitySnapshot,
        expectedSource: CapabilitySource,
    ) {
        val now = clock().coerceAtLeast(0)
        require(snapshot.source == expectedSource) { "Automatic capability evidence has the wrong source." }
        requireSnapshotMatches(profile, snapshot, now)
        requireNotNull(snapshot.expiresAt).also {
            require(it > now) { "Automatic capability evidence must still be fresh when recorded." }
        }
        store.upsertNewest(
            StoredProviderCapability(
                key = ProviderCapabilityStorageKey.from(profile, snapshot.modelId),
                snapshot = snapshot,
            ),
        )
    }

    private fun requireSnapshotMatches(
        profile: ProviderConnectionProfile,
        snapshot: ProviderCapabilitySnapshot,
        now: Long,
    ) {
        require(snapshot.protocol == profile.protocol) { "Capability protocol does not match the connection." }
        require(snapshot.verifiedAt <= now) { "Capability evidence is dated in the future." }
    }

    private fun merge(
        base: ProviderCapabilitySnapshot,
        evidence: ProviderCapabilitySnapshot,
    ): ProviderCapabilitySnapshot {
        fun choose(
            old: CapabilitySupport,
            new: CapabilitySupport,
        ) = new.takeUnless { it == CapabilitySupport.UNKNOWN } ?: old

        val streaming = choose(base.streaming, evidence.streaming)
        val streamFormat = when (streaming) {
            CapabilitySupport.SUPPORTED -> evidence.streamFormat.takeIf {
                it in setOf(ProviderStreamFormat.SSE, ProviderStreamFormat.NDJSON)
            } ?: base.streamFormat.takeIf {
                it in setOf(ProviderStreamFormat.SSE, ProviderStreamFormat.NDJSON)
            } ?: ProviderStreamFormat.UNKNOWN
            CapabilitySupport.UNSUPPORTED -> ProviderStreamFormat.NONE
            CapabilitySupport.UNKNOWN -> ProviderStreamFormat.UNKNOWN
        }
        val selectedContextLimit = evidence.contextLimit ?: base.contextLimit
        val selectedMaxOutputTokens = (evidence.maxOutputTokens ?: base.maxOutputTokens)
            ?.takeIf { maximum -> selectedContextLimit == null || maximum <= selectedContextLimit }
        return ProviderCapabilitySnapshot(
            protocol = base.protocol,
            modelId = base.modelId,
            streaming = streaming,
            streamFormat = streamFormat,
            structuredOutput = choose(base.structuredOutput, evidence.structuredOutput),
            usageInStream = choose(base.usageInStream, evidence.usageInStream),
            systemInstruction = choose(base.systemInstruction, evidence.systemInstruction),
            temperature = choose(base.temperature, evidence.temperature),
            topP = choose(base.topP, evidence.topP),
            maxOutputTokensParameter = choose(base.maxOutputTokensParameter, evidence.maxOutputTokensParameter),
            seed = choose(base.seed, evidence.seed),
            reasoningEffort = choose(base.reasoningEffort, evidence.reasoningEffort),
            idempotencyKey = choose(base.idempotencyKey, evidence.idempotencyKey),
            contextLimit = selectedContextLimit,
            maxOutputTokens = selectedMaxOutputTokens,
            tokenizerFamily = evidence.tokenizerFamily.takeUnless { it == TokenizerFamily.UNKNOWN }
                ?: base.tokenizerFamily,
            source = evidence.source,
            verifiedAt = evidence.verifiedAt,
            expiresAt = evidence.expiresAt,
            adapterVersion = base.adapterVersion,
        )
    }

    private fun ProviderCapabilitySnapshot.isExpiredAt(now: Long): Boolean =
        expiresAt?.let { it <= now } == true

    private companion object {
        val RESOLUTION_ORDER = listOf(
            CapabilitySource.OFFICIAL_METADATA,
            CapabilitySource.PROBED,
            CapabilitySource.USER_OVERRIDE,
        )
    }
}
