package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.GenerationRunnerCurrentStageRouteSnapshot
import app.zhijuan.core.database.generation.RequestBudgetReservationDraft
import app.zhijuan.provider.common.ProviderAdapter
import app.zhijuan.provider.common.ProviderConnectionProfile
import app.zhijuan.provider.common.ProviderModelId
import app.zhijuan.provider.common.ProviderTimeoutPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class GenerationBoundRemoteExecution(
    val adapter: ProviderAdapter,
    val profile: ProviderConnectionProfile,
    val modelId: ProviderModelId,
    val connectionSnapshotJson: String,
    val modelSnapshotJson: String,
    val protocolSnapshotJson: String,
    val maximumOutputTokens: Int,
    val timeouts: ProviderTimeoutPolicy,
    val requestMaximumTokens: Long,
    val estimatedTokens: Long,
    val requestMaximumCostMicros: Long? = null,
    val requestCurrency: String? = null,
    val estimatedCostMicros: Long? = null,
    val estimatedCurrency: String? = null,
    val estimateSourceVersion: String? = null,
) {
    init {
        require(adapter.protocol == profile.protocol)
        require(maximumOutputTokens in 512..16_384)
        require(requestMaximumTokens >= maximumOutputTokens && estimatedTokens > 0L)
        require((requestMaximumCostMicros == null) == (requestCurrency == null))
        require((estimatedCostMicros == null) == (estimatedCurrency == null))
        listOf(connectionSnapshotJson, modelSnapshotJson, protocolSnapshotJson).forEach { value ->
            require(value.length in 2..65_536)
            require(runCatching { Json.parseToJsonElement(value) as JsonObject }.isSuccess)
        }
    }

    fun budget(reservationId: String): RequestBudgetReservationDraft = RequestBudgetReservationDraft(
        reservationId = reservationId,
        requestMaxTokens = requestMaximumTokens,
        requestMaxCostMicros = requestMaximumCostMicros,
        requestCurrency = requestCurrency,
        estimatedTokens = estimatedTokens,
        estimatedCostMicros = estimatedCostMicros,
        estimatedCurrency = estimatedCurrency,
        estimateSourceVersion = estimateSourceVersion,
        connectionId = profile.connectionId,
    )

    override fun toString(): String = "GenerationBoundRemoteExecution(content=redacted)"
}

fun interface GenerationBoundRemoteExecutionProvider {
    suspend fun resolve(
        snapshot: GenerationRunnerCurrentStageRouteSnapshot,
        requestedAt: Long,
    ): GenerationBoundRemoteExecution
}
