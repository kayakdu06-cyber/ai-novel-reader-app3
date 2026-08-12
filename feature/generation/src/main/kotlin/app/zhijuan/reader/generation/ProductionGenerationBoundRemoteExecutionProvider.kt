package app.zhijuan.reader.generation

import android.content.Context
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.database.generation.GenerationBoundExecutionConfigRepository
import app.zhijuan.core.database.generation.GenerationRunnerCurrentStageRouteSnapshot
import app.zhijuan.core.security.AndroidSecretStore
import app.zhijuan.feature.generation.GenerationBoundRemoteExecution
import app.zhijuan.feature.generation.GenerationBoundRemoteExecutionProvider
import app.zhijuan.provider.capability.storage.RoomProviderCapabilityStore
import app.zhijuan.provider.common.ProviderCapabilityRegistry
import app.zhijuan.provider.common.ProviderConnectionProfile
import app.zhijuan.provider.common.ProviderModelId
import app.zhijuan.provider.common.ProviderProtocol
import app.zhijuan.provider.common.ProviderTimeoutPolicy
import app.zhijuan.provider.openai.chat.OpenAiChatAdapter
import app.zhijuan.provider.openai.chat.OpenAiChatCompatibilityMode
import app.zhijuan.provider.openai.chat.OpenAiChatCompatibilityResolver
import app.zhijuan.provider.openai.chat.RegistryBackedOpenAiChatCapabilityResolver
import app.zhijuan.provider.transport.AndroidProviderSecretMaterialSource
import app.zhijuan.provider.transport.SecureProviderHttpTransport
import java.net.URI
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Restores one execution only from the persisted Job budget and current immutable connection. */
internal class ProductionGenerationBoundRemoteExecutionProvider(
    context: Context,
    private val database: ZhijuanDatabase,
) : GenerationBoundRemoteExecutionProvider {
    private val configs = GenerationBoundExecutionConfigRepository(database)
    private val secretStore = AndroidSecretStore(context.applicationContext)
    private val capabilityRegistry = ProviderCapabilityRegistry(RoomProviderCapabilityStore(database))
    private val adapter by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OpenAiChatAdapter(
            transport = SecureProviderHttpTransport(AndroidProviderSecretMaterialSource(secretStore)),
            compatibilityResolver = OpenAiChatCompatibilityResolver { profile ->
                profile.withBaseUrl { baseUrl ->
                    when (URI(baseUrl).host.lowercase()) {
                        "api.openai.com" -> OpenAiChatCompatibilityMode.OPENAI
                        "api.deepseek.com" -> OpenAiChatCompatibilityMode.DEEPSEEK
                        else -> OpenAiChatCompatibilityMode.RELAY_MINIMAL
                    }
                }
            },
            capabilityResolver = RegistryBackedOpenAiChatCapabilityResolver(capabilityRegistry),
        )
    }

    override suspend fun resolve(
        snapshot: GenerationRunnerCurrentStageRouteSnapshot,
        requestedAt: Long,
    ): GenerationBoundRemoteExecution {
        require(requestedAt >= 0L)
        val config = configs.load(snapshot, requestedAt)
        require(config.protocolId == ProviderProtocol.OPENAI_CHAT_COMPAT.name)
        require(config.requestMaximumTokens >= MINIMUM_OUTPUT_TOKENS) {
            "Confirmed request token limit is too small for generation."
        }
        val profile = ProviderConnectionProfile.create(
            connectionId = config.connectionId,
            protocol = ProviderProtocol.OPENAI_CHAT_COMPAT,
            baseUrl = config.baseUrl,
            primarySecretRefId = config.secretRefId,
        )
        val modelId = ProviderModelId.from(config.modelId)
        val capabilities = capabilityRegistry.resolve(
            profile = profile,
            modelId = modelId,
            adapterVersion = adapter.adapterVersion,
            builtIn = null,
        )
        val maximumOutputTokens = minOf(
            config.requestMaximumTokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            capabilities.maxOutputTokens ?: DEFAULT_MAXIMUM_OUTPUT_TOKENS,
            MAXIMUM_OUTPUT_TOKENS,
        ).coerceAtLeast(MINIMUM_OUTPUT_TOKENS)
        val estimatedTokens = minOf(
            config.requestMaximumTokens,
            maximumOutputTokens.toLong() + ESTIMATED_INPUT_TOKENS,
        )
        return GenerationBoundRemoteExecution(
            adapter = adapter,
            profile = profile,
            modelId = modelId,
            connectionSnapshotJson = JsonObject(linkedMapOf(
                "connectionId" to JsonPrimitive(config.connectionId),
                "baseUrl" to JsonPrimitive(config.baseUrl),
                "normalizedDestination" to JsonPrimitive(config.normalizedDestination),
                "disclosureBindingHash" to JsonPrimitive(config.disclosureBindingHash),
            )).toString(),
            modelSnapshotJson = JsonObject(linkedMapOf(
                "modelId" to JsonPrimitive(config.modelId),
                "verification" to JsonPrimitive(config.modelVerification),
            )).toString(),
            protocolSnapshotJson = JsonObject(linkedMapOf(
                "protocolId" to JsonPrimitive(config.protocolId),
                "adapterVersion" to JsonPrimitive(adapter.adapterVersion),
            )).toString(),
            maximumOutputTokens = maximumOutputTokens,
            timeouts = DEFAULT_TIMEOUTS,
            requestMaximumTokens = config.requestMaximumTokens,
            estimatedTokens = estimatedTokens,
        )
    }

    private companion object {
        const val MINIMUM_OUTPUT_TOKENS = 512
        const val DEFAULT_MAXIMUM_OUTPUT_TOKENS = 8_192
        const val MAXIMUM_OUTPUT_TOKENS = 16_384
        const val ESTIMATED_INPUT_TOKENS = 8_192L
        val DEFAULT_TIMEOUTS = ProviderTimeoutPolicy(
            connectMillis = 15_000,
            firstByteMillis = 120_000,
            streamIdleMillis = 120_000,
            totalStageMillis = 900_000,
        )
    }
}
