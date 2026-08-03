package app.zhijuan.provider.capability.storage

import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.database.capability.ProviderCapabilityEntity
import app.zhijuan.provider.common.CapabilitySource
import app.zhijuan.provider.common.CapabilitySupport
import app.zhijuan.provider.common.ProviderCapabilitySnapshot
import app.zhijuan.provider.common.ProviderCapabilityStorageKey
import app.zhijuan.provider.common.ProviderCapabilityStore
import app.zhijuan.provider.common.ProviderEndpointFingerprint
import app.zhijuan.provider.common.ProviderModelId
import app.zhijuan.provider.common.ProviderProtocol
import app.zhijuan.provider.common.ProviderStreamFormat
import app.zhijuan.provider.common.StoredProviderCapability
import app.zhijuan.provider.common.TokenizerFamily

class RoomProviderCapabilityStore(
    database: ZhijuanDatabase,
) : ProviderCapabilityStore {
    private val dao = database.providerCapabilityDao()

    override suspend fun load(key: ProviderCapabilityStorageKey): List<StoredProviderCapability> {
        val endpointFingerprint = key.endpointFingerprint.withValue { it }
        val modelId = key.modelId.withValue { it }
        return dao.load(
            connectionId = key.connectionId,
            endpointFingerprint = endpointFingerprint,
            protocolId = key.protocol.name,
            modelId = modelId,
        ).mapNotNull { it.toDomainOrNull() }
    }

    override suspend fun upsertNewest(record: StoredProviderCapability) {
        dao.upsertNewest(record.toEntity())
    }

    override suspend fun delete(
        key: ProviderCapabilityStorageKey,
        source: CapabilitySource,
    ): Boolean = dao.delete(
        connectionId = key.connectionId,
        endpointFingerprint = key.endpointFingerprint.withValue { it },
        protocolId = key.protocol.name,
        modelId = key.modelId.withValue { it },
        source = source.name,
    ) > 0

    private fun StoredProviderCapability.toEntity(): ProviderCapabilityEntity = ProviderCapabilityEntity(
        connectionId = key.connectionId,
        endpointFingerprint = key.endpointFingerprint.withValue { it },
        protocolId = key.protocol.name,
        modelId = key.modelId.withValue { it },
        capabilitySource = snapshot.source.name,
        streamingSupport = snapshot.streaming.name,
        streamFormat = snapshot.streamFormat.name,
        structuredOutputSupport = snapshot.structuredOutput.name,
        streamUsageSupport = snapshot.usageInStream.name,
        systemInstructionSupport = snapshot.systemInstruction.name,
        temperatureSupport = snapshot.temperature.name,
        topPSupport = snapshot.topP.name,
        maxOutputTokensParameterSupport = snapshot.maxOutputTokensParameter.name,
        seedSupport = snapshot.seed.name,
        reasoningEffortSupport = snapshot.reasoningEffort.name,
        idempotencyKeySupport = snapshot.idempotencyKey.name,
        contextLimit = snapshot.contextLimit,
        maxOutputTokens = snapshot.maxOutputTokens,
        tokenizerFamily = snapshot.tokenizerFamily.name,
        verifiedAt = snapshot.verifiedAt,
        expiresAt = snapshot.expiresAt,
        adapterVersion = snapshot.adapterVersion,
        riskAcknowledgedAt = riskAcknowledgedAt,
    )

    private fun ProviderCapabilityEntity.toDomainOrNull(): StoredProviderCapability? = runCatching {
        val protocol = enumValueOf<ProviderProtocol>(protocolId)
        val model = ProviderModelId.from(modelId)
        StoredProviderCapability(
            key = ProviderCapabilityStorageKey(
                connectionId = connectionId,
                endpointFingerprint = ProviderEndpointFingerprint.fromEncoded(endpointFingerprint),
                protocol = protocol,
                modelId = model,
            ),
            snapshot = ProviderCapabilitySnapshot(
                protocol = protocol,
                modelId = model,
                streaming = enumValueOf<CapabilitySupport>(streamingSupport),
                streamFormat = enumValueOf<ProviderStreamFormat>(streamFormat),
                structuredOutput = enumValueOf<CapabilitySupport>(structuredOutputSupport),
                usageInStream = enumValueOf<CapabilitySupport>(streamUsageSupport),
                systemInstruction = enumValueOf<CapabilitySupport>(systemInstructionSupport),
                temperature = enumValueOf<CapabilitySupport>(temperatureSupport),
                topP = enumValueOf<CapabilitySupport>(topPSupport),
                maxOutputTokensParameter = enumValueOf<CapabilitySupport>(maxOutputTokensParameterSupport),
                seed = enumValueOf<CapabilitySupport>(seedSupport),
                reasoningEffort = enumValueOf<CapabilitySupport>(reasoningEffortSupport),
                idempotencyKey = enumValueOf<CapabilitySupport>(idempotencyKeySupport),
                contextLimit = contextLimit,
                maxOutputTokens = maxOutputTokens,
                tokenizerFamily = enumValueOf<TokenizerFamily>(tokenizerFamily),
                source = enumValueOf<CapabilitySource>(capabilitySource),
                verifiedAt = verifiedAt,
                expiresAt = expiresAt,
                adapterVersion = adapterVersion,
            ),
            riskAcknowledgedAt = riskAcknowledgedAt,
        )
    }.getOrNull()
}
