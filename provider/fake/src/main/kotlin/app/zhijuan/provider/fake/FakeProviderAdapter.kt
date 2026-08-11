package app.zhijuan.provider.fake

import app.zhijuan.core.model.FailureRequestState
import app.zhijuan.core.model.StandardErrorCode
import app.zhijuan.provider.common.CapabilityResult
import app.zhijuan.provider.common.CapabilitySource
import app.zhijuan.provider.common.CapabilitySupport
import app.zhijuan.provider.common.ConnectionTestResult
import app.zhijuan.provider.common.GenerationRequest
import app.zhijuan.provider.common.ModelListResult
import app.zhijuan.provider.common.ProviderAdapter
import app.zhijuan.provider.common.ProviderCancellationResult
import app.zhijuan.provider.common.ProviderCapabilitySnapshot
import app.zhijuan.provider.common.ProviderConnectionProfile
import app.zhijuan.provider.common.ProviderModelId
import app.zhijuan.provider.common.ProviderModelSummary
import app.zhijuan.provider.common.ProviderProtocol
import app.zhijuan.provider.common.ProviderRemoteRequestId
import app.zhijuan.provider.common.ProviderRequestRecoveryCapability
import app.zhijuan.provider.common.ProviderRequestRecoveryResult
import app.zhijuan.provider.common.ProviderStreamEvent
import app.zhijuan.provider.common.ProviderStreamFormat
import app.zhijuan.provider.common.ProviderUsage
import app.zhijuan.provider.common.ProviderUsageQuality
import app.zhijuan.provider.common.SensitiveProviderText
import app.zhijuan.provider.common.TokenizerFamily
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Deterministic, offline [ProviderAdapter] that replays a [FakeStreamScript]
 * through an injectable [FakeStreamClock].
 *
 * Contract:
 *  - the profile protocol must match [protocol] and the request must be a valid
 *    streaming request (checked before any event is emitted);
 *  - the script is replayed verbatim: the adapter never synthesizes, reorders or
 *    repairs terminal events, so a script without a terminal step ends as an
 *    explicit no-terminal stall;
 *  - flow collection cancelled by the coroutine is recorded separately from
 *    [cancel] invocations; repeated [cancel] calls return the configured result
 *    deterministically and each call is counted;
 *  - all statistics are redacted aggregates (see [FakeProviderCallStats]) and no
 *    network, secret or real provider is touched.
 */
class FakeProviderAdapter(
    val script: FakeStreamScript,
    override val protocol: ProviderProtocol = ProviderProtocol.OLLAMA_NATIVE,
    override val adapterVersion: String = DEFAULT_ADAPTER_VERSION,
    private val clock: FakeStreamClock = VirtualFakeStreamClock(),
    private val cancellationResult: ProviderCancellationResult =
        ProviderCancellationResult.CANCELLED_LOCALLY,
    val stats: FakeProviderCallStats = FakeProviderCallStats(),
) : ProviderAdapter {

    override val requestRecoveryCapability: ProviderRequestRecoveryCapability
        get() = ProviderRequestRecoveryCapability.NOT_SUPPORTED

    override fun generate(
        profile: ProviderConnectionProfile,
        request: GenerationRequest,
    ): Flow<ProviderStreamEvent> {
        require(profile.protocol == protocol) {
            "Fake provider protocol mismatch: expected " + protocol.name + "."
        }
        require(request.stream) { "Fake provider only serves streaming generation requests." }
        require(request.requestId.isNotBlank()) { "Fake provider request identity is invalid." }
        return flow {
            stats.recordGenerate()
            var completedVirtualWaitMillis = 0L
            try {
                for (step in script.steps) {
                    when (step) {
                        is FakeStreamStep.Wait -> {
                            clock.await(step.millis)
                            completedVirtualWaitMillis = Math.addExact(
                                completedVirtualWaitMillis,
                                step.millis,
                            )
                        }
                        is FakeStreamStep.Started -> {
                            stats.recordEvent(FakeProviderEventKind.STARTED)
                            emit(
                                ProviderStreamEvent.Started(
                                    step.remoteRequestId?.let(ProviderRemoteRequestId::from),
                                ),
                            )
                        }
                        is FakeStreamStep.Text -> {
                            stats.recordEvent(FakeProviderEventKind.TEXT)
                            stats.recordTextCharacters(step.text.length.toLong())
                            emit(ProviderStreamEvent.TextDelta(SensitiveProviderText.from(step.text)))
                        }
                        is FakeStreamStep.Structured -> {
                            stats.recordEvent(FakeProviderEventKind.STRUCTURED)
                            stats.recordStructuredCharacters(step.fragment.length.toLong())
                            emit(
                                ProviderStreamEvent.StructuredDelta(
                                    SensitiveProviderText.from(step.fragment),
                                ),
                            )
                        }
                        is FakeStreamStep.Usage -> {
                            stats.recordEvent(FakeProviderEventKind.USAGE)
                            step.inputTokens?.let(stats::recordInputTokens)
                            step.outputTokens?.let(stats::recordOutputTokens)
                            emit(ProviderStreamEvent.UsageUpdate(step.toProviderUsage()))
                        }
                        FakeStreamStep.Heartbeat -> {
                            stats.recordEvent(FakeProviderEventKind.HEARTBEAT)
                            emit(ProviderStreamEvent.Heartbeat)
                        }
                        is FakeStreamStep.Completed -> {
                            stats.recordEvent(FakeProviderEventKind.COMPLETED)
                            emit(ProviderStreamEvent.Completed(step.reason))
                        }
                        is FakeStreamStep.Refused -> {
                            stats.recordEvent(FakeProviderEventKind.REFUSED)
                            emit(ProviderStreamEvent.Refused(step.category))
                        }
                        is FakeStreamStep.Failed -> {
                            stats.recordEvent(FakeProviderEventKind.FAILED)
                            emit(
                                ProviderStreamEvent.Failed(
                                    code = step.code,
                                    httpStatus = step.httpStatus,
                                    retryAfterMillis = step.retryAfterMillis,
                                    requestState = step.requestState,
                                ),
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                stats.recordCollectionCancelled()
                throw cancelled
            } finally {
                stats.recordVirtualMillis(completedVirtualWaitMillis)
            }
        }
    }

    override suspend fun testConnection(
        profile: ProviderConnectionProfile,
    ): ConnectionTestResult {
        require(profile.protocol == protocol) {
            "Fake provider protocol mismatch: expected " + protocol.name + "."
        }
        stats.recordTestConnection()
        return ConnectionTestResult.Success(
            verifiedAt = clock.nowMillis(),
            minimalGenerationVerified = false,
            usageObserved = false,
        )
    }

    override suspend fun listModels(
        profile: ProviderConnectionProfile,
    ): ModelListResult {
        require(profile.protocol == protocol) {
            "Fake provider protocol mismatch: expected " + protocol.name + "."
        }
        stats.recordListModels()
        return ModelListResult.Success(
            models = listOf(ProviderModelSummary(id = DEFAULT_MODEL_ID)),
            fetchedAt = clock.nowMillis(),
        )
    }

    override suspend fun getCapabilities(
        profile: ProviderConnectionProfile,
        modelId: ProviderModelId,
    ): CapabilityResult {
        require(profile.protocol == protocol) {
            "Fake provider protocol mismatch: expected " + protocol.name + "."
        }
        stats.recordGetCapabilities()
        return CapabilityResult.Success(
            ProviderCapabilitySnapshot(
                protocol = protocol,
                modelId = modelId,
                streaming = CapabilitySupport.SUPPORTED,
                streamFormat = ProviderStreamFormat.SSE,
                structuredOutput = CapabilitySupport.SUPPORTED,
                usageInStream = CapabilitySupport.SUPPORTED,
                systemInstruction = CapabilitySupport.UNKNOWN,
                temperature = CapabilitySupport.UNKNOWN,
                topP = CapabilitySupport.UNKNOWN,
                maxOutputTokensParameter = CapabilitySupport.UNKNOWN,
                seed = CapabilitySupport.UNKNOWN,
                reasoningEffort = CapabilitySupport.UNKNOWN,
                idempotencyKey = CapabilitySupport.UNKNOWN,
                contextLimit = null,
                maxOutputTokens = null,
                tokenizerFamily = TokenizerFamily.UNKNOWN,
                source = CapabilitySource.BUILT_IN,
                verifiedAt = clock.nowMillis(),
                expiresAt = null,
                adapterVersion = adapterVersion,
            ),
        )
    }

    override suspend fun cancel(
        profile: ProviderConnectionProfile,
        requestId: String,
    ): ProviderCancellationResult {
        require(profile.protocol == protocol) {
            "Fake provider protocol mismatch: expected " + protocol.name + "."
        }
        require(requestId.isNotBlank()) { "Fake provider cancellation identity is invalid." }
        stats.recordCancel(cancellationResult)
        return cancellationResult
    }

    override suspend fun queryRequestRecovery(
        profile: ProviderConnectionProfile,
        remoteRequestId: ProviderRemoteRequestId,
    ): ProviderRequestRecoveryResult {
        require(profile.protocol == protocol) {
            "Fake provider protocol mismatch: expected " + protocol.name + "."
        }
        stats.recordRecoveryQuery()
        return ProviderRequestRecoveryResult.NotSupported
    }

    private fun FakeStreamStep.Usage.toProviderUsage(): ProviderUsage = ProviderUsage(
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        cachedInputTokens = null,
        cachedWriteTokens = null,
        reasoningTokens = null,
        totalTokens = if (inputTokens != null && outputTokens != null) {
            Math.addExact(inputTokens, outputTokens)
        } else {
            null
        },
        quality = if (inputTokens == null && outputTokens == null) {
            ProviderUsageQuality.UNKNOWN
        } else {
            ProviderUsageQuality.PROVIDER_REPORTED
        },
    )

    companion object {
        const val DEFAULT_ADAPTER_VERSION = "fake-1"
        val DEFAULT_MODEL_ID: ProviderModelId = ProviderModelId.from("fake-model")
    }
}
