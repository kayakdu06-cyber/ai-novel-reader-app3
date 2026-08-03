package app.zhijuan.provider.common

import app.zhijuan.core.model.StandardErrorCode
import app.zhijuan.core.model.FailureRequestState
import kotlinx.coroutines.flow.Flow

data class ProviderCallFailure(
    val code: StandardErrorCode,
    val httpStatus: Int? = null,
    val retryAfterMillis: Long? = null,
    val requestState: FailureRequestState = FailureRequestState.RESULT_UNKNOWN,
) {
    init {
        require(httpStatus == null || httpStatus in 100..599)
        require(retryAfterMillis == null || retryAfterMillis >= 0)
        require(httpStatus == null || requestState != FailureRequestState.NOT_SENT) {
            "An HTTP response proves that the request reached a provider endpoint."
        }
    }
}

sealed interface ConnectionTestResult {
    data class Success(
        val verifiedAt: Long,
        val minimalGenerationVerified: Boolean,
        val usageObserved: Boolean,
    ) : ConnectionTestResult {
        init {
            require(verifiedAt >= 0)
        }
    }

    data class Failure(val failure: ProviderCallFailure) : ConnectionTestResult
}

data class ProviderModelSummary(
    val id: ProviderModelId,
    val contextLimitHint: Int? = null,
    val maxOutputTokensHint: Int? = null,
) {
    init {
        require(contextLimitHint == null || contextLimitHint >= 1_024)
        require(maxOutputTokensHint == null || maxOutputTokensHint > 0)
        require(
            contextLimitHint == null || maxOutputTokensHint == null ||
                maxOutputTokensHint <= contextLimitHint,
        ) { "Model output limit cannot exceed its context limit." }
    }
}

sealed interface ModelListResult {
    data class Success(
        val models: List<ProviderModelSummary>,
        val fetchedAt: Long,
    ) : ModelListResult {
        init {
            require(fetchedAt >= 0)
            require(models.size <= 10_000)
            require(models.map(ProviderModelSummary::id).distinct().size == models.size)
        }
    }

    data class Failure(val failure: ProviderCallFailure) : ModelListResult
}

sealed interface CapabilityResult {
    data class Success(val snapshot: ProviderCapabilitySnapshot) : CapabilityResult
    data class Failure(val failure: ProviderCallFailure) : CapabilityResult
}

enum class ProviderCancellationResult {
    CANCELLED_LOCALLY,
    REMOTE_CANCELLATION_REQUESTED,
    ALREADY_TERMINAL,
    NOT_SUPPORTED,
}

enum class ProviderRequestRecoveryCapability {
    NOT_SUPPORTED,
    STATUS_QUERY,
}

sealed interface ProviderRequestRecoveryResult {
    data object NotSupported : ProviderRequestRecoveryResult

    /** The provider can answer, but the current evidence is not authoritative. */
    data object Inconclusive : ProviderRequestRecoveryResult

    /** The original paid request is still running; callers may poll but must not replace it. */
    data object InProgress : ProviderRequestRecoveryResult

    /** Authoritative provider evidence says the request was not executed and incurred no usage. */
    data object ConfirmedNotExecuted : ProviderRequestRecoveryResult

    /** The provider completed the request, but this adapter cannot safely reconstruct local output. */
    data class CompletedWithoutLocalOutput(
        val usage: ProviderUsage? = null,
    ) : ProviderRequestRecoveryResult
}

interface ProviderAdapter {
    val protocol: ProviderProtocol
    val adapterVersion: String

    val requestRecoveryCapability: ProviderRequestRecoveryCapability
        get() = ProviderRequestRecoveryCapability.NOT_SUPPORTED

    suspend fun testConnection(profile: ProviderConnectionProfile): ConnectionTestResult

    suspend fun listModels(profile: ProviderConnectionProfile): ModelListResult

    suspend fun getCapabilities(
        profile: ProviderConnectionProfile,
        modelId: ProviderModelId,
    ): CapabilityResult

    fun generate(
        profile: ProviderConnectionProfile,
        request: GenerationRequest,
    ): Flow<ProviderStreamEvent>

    suspend fun cancel(
        profile: ProviderConnectionProfile,
        requestId: String,
    ): ProviderCancellationResult

    suspend fun queryRequestRecovery(
        profile: ProviderConnectionProfile,
        remoteRequestId: ProviderRemoteRequestId,
    ): ProviderRequestRecoveryResult = ProviderRequestRecoveryResult.NotSupported
}

class ProviderAdapterRegistry(adapters: Iterable<ProviderAdapter>) {
    private val adaptersByProtocol: Map<ProviderProtocol, ProviderAdapter>

    init {
        val collected = adapters.toList()
        require(collected.isNotEmpty()) { "At least one provider adapter is required." }
        require(collected.all { it.adapterVersion.matches(VERSION_PATTERN) }) {
            "Provider adapter version is invalid."
        }
        adaptersByProtocol = collected.associateBy(ProviderAdapter::protocol)
        require(adaptersByProtocol.size == collected.size) {
            "Only one provider adapter may be registered for each protocol."
        }
    }

    fun adapterFor(profile: ProviderConnectionProfile): ProviderAdapter =
        checkNotNull(adaptersByProtocol[profile.protocol]) {
            "No provider adapter is registered for " + profile.protocol.name + "."
        }

    private companion object {
        val VERSION_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    }
}
