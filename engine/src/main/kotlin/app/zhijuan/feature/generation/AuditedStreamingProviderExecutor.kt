package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.ClaimedStreamingRequest
import app.zhijuan.core.database.generation.CompletedStreamingResponse
import app.zhijuan.core.database.generation.FinalUsageCommit
import app.zhijuan.core.database.generation.GenerationControlResult
import app.zhijuan.core.database.generation.GenerationExecutionControl
import app.zhijuan.core.database.generation.GenerationOutputValidationRepository
import app.zhijuan.core.database.generation.GenerationStreamingDraftRepository
import app.zhijuan.core.database.generation.PersistedStreamingRequest
import app.zhijuan.core.database.generation.StaleGenerationStateException
import app.zhijuan.core.diagnostics.GenerationTimingClock
import app.zhijuan.core.diagnostics.GenerationTimingOutcome
import app.zhijuan.core.model.FailureRequestState
import app.zhijuan.core.model.ProviderOpenDestinationEvidence
import app.zhijuan.core.model.StandardErrorCode
import app.zhijuan.core.model.UsageSource
import app.zhijuan.core.security.StreamingDraftBuffer
import app.zhijuan.core.security.StreamingDraftWriteResult
import app.zhijuan.provider.common.GenerationRequest
import app.zhijuan.provider.common.ProviderAdapter
import app.zhijuan.provider.common.ProviderCancellationResult
import app.zhijuan.provider.common.ProviderConnectionProfile
import app.zhijuan.provider.common.ProviderEventDecision
import app.zhijuan.provider.common.ProviderEventGate
import app.zhijuan.provider.common.ProviderFinishReason
import app.zhijuan.provider.common.ProviderRefusalCategory
import app.zhijuan.provider.common.ProviderStreamEvent
import app.zhijuan.provider.common.ProviderUsage
import app.zhijuan.provider.common.ProviderUsageQuality
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface GenerationExecutionClock {
    fun nowMillis(): Long
}

object SystemGenerationExecutionClock : GenerationExecutionClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

sealed interface AuditedStreamingExecutionResult {
    val checkpoint: StreamingDraftWriteResult
    val latestUsage: ProviderUsage?

    data class Completed(
        val reason: ProviderFinishReason,
        val response: CompletedStreamingResponse?,
        val payloadCompletion: ProviderPayloadCompletion,
        override val checkpoint: StreamingDraftWriteResult,
        override val latestUsage: ProviderUsage?,
    ) : AuditedStreamingExecutionResult

    data class Refused(
        val category: ProviderRefusalCategory,
        override val checkpoint: StreamingDraftWriteResult,
        override val latestUsage: ProviderUsage?,
    ) : AuditedStreamingExecutionResult

    data class Failed(
        val code: StandardErrorCode,
        val httpStatus: Int?,
        val retryAfterMillis: Long?,
        val requestState: FailureRequestState,
        override val checkpoint: StreamingDraftWriteResult,
        override val latestUsage: ProviderUsage?,
    ) : AuditedStreamingExecutionResult

    data class Interrupted(
        override val checkpoint: StreamingDraftWriteResult,
        override val latestUsage: ProviderUsage?,
    ) : AuditedStreamingExecutionResult

    data class Controlled(
        val action: GenerationExecutionControl,
        val cancellation: ProviderCancellationResult,
        val controlResult: GenerationControlResult,
        override val checkpoint: StreamingDraftWriteResult,
        override val latestUsage: ProviderUsage?,
    ) : AuditedStreamingExecutionResult
}

class ProviderStreamContractException(message: String) : IllegalStateException(message)

private class GenerationExecutionControlSignal(
    val action: GenerationExecutionControl,
) : IllegalStateException("Generation execution reached a persisted control request.")

class AuditedStreamingProviderExecutor(
    private val drafts: GenerationStreamingDraftRepository,
    private val outputs: GenerationOutputValidationRepository,
    private val clock: GenerationExecutionClock = SystemGenerationExecutionClock,
    private val timingClock: GenerationTimingClock? = null,
    private val timingRecorder: GenerationTimingEventRecorder = NoOpGenerationTimingEventRecorder,
) {
    suspend fun execute(
        persistedRequest: PersistedStreamingRequest,
        adapter: ProviderAdapter,
        profile: ProviderConnectionProfile,
        request: GenerationRequest,
        payloadDecoder: ProviderStreamPayloadDecoder = PassthroughProviderStreamPayloadDecoder,
        timingContext: GenerationTimingExecutionContext? = null,
    ): AuditedStreamingExecutionResult {
        require(profile.protocol == adapter.protocol) {
            "Provider profile protocol must match adapter protocol."
        }
        val timing = timingContext?.let { context ->
            ProviderGenerationTimingTracker(
                context = context,
                clock = requireNotNull(timingClock) {
                    "A generation timing context requires a monotonic timing clock."
                },
                recorder = timingRecorder,
            )
        }
        val destination = destinationEvidence(profile)
        val claimed = drafts.claimForProviderOpen(persistedRequest, now(), destination)
        check(claimed.isBoundTo(destinationEvidence(profile))) {
            "Provider profile changed after send authorization."
        }
        val buffer = drafts.openDraftBuffer(claimed)
        return try {
            collectAuthorized(claimed, buffer, adapter, profile, request, payloadDecoder, timing)
        } catch (error: CancellationException) {
            runCatching { buffer.flush(now()) }
            throw error
        } catch (error: Throwable) {
            runCatching { buffer.flush(now()) }
            throw error
        } finally {
            buffer.close()
        }
    }

    private fun destinationEvidence(
        profile: ProviderConnectionProfile,
    ): ProviderOpenDestinationEvidence = profile.withBaseUrl { baseUrl ->
        ProviderOpenDestinationEvidence.create(
            connectionId = profile.connectionId,
            baseUrl = baseUrl,
            protocolId = profile.protocol.name,
        )
    }

    private suspend fun collectAuthorized(
        claimed: ClaimedStreamingRequest,
        buffer: StreamingDraftBuffer,
        adapter: ProviderAdapter,
        profile: ProviderConnectionProfile,
        request: GenerationRequest,
        payloadDecoder: ProviderStreamPayloadDecoder,
        timing: ProviderGenerationTimingTracker?,
    ): AuditedStreamingExecutionResult {
        val gate = ProviderEventGate()
        val sent = AtomicBoolean(false)
        val streamStarted = AtomicBoolean(false)
        val persistenceLock = Mutex()
        var latestUsage: ProviderUsage? = null
        var terminal: AuditedStreamingExecutionResult? = null

        suspend fun ensureRequestSent(providerRequestId: String?) {
            persistenceLock.withLock {
                if (!sent.compareAndSet(false, true)) return@withLock
                drafts.markRequestSent(claimed, providerRequestId, now())
            }
        }

        suspend fun ensureStreamStarted() {
            persistenceLock.withLock {
                if (!streamStarted.compareAndSet(false, true)) return@withLock
                if (sent.compareAndSet(false, true)) {
                    drafts.markRequestSent(claimed, providerRequestId = null, sentAt = now())
                }
                drafts.markStreamStarted(claimed, now())
            }
        }

        try {
            coroutineScope {
                val heartbeat = launch {
                    while (isActive) {
                        delay(drafts.heartbeatIntervalMillis)
                        persistenceLock.withLock {
                            drafts.heartbeat(claimed, now())
                        }
                    }
                }
                val controlMonitor = launch {
                    while (isActive) {
                        drafts.executionControl(claimed)?.let { action ->
                            throw GenerationExecutionControlSignal(action)
                        }
                        delay(drafts.controlPollIntervalMillis)
                    }
                }
                try {
                    drafts.executionControl(claimed)?.let { action ->
                        throw GenerationExecutionControlSignal(action)
                    }
                    timing?.providerOpened()
                    adapter.generate(profile, request).collect { rawEvent ->
                        val event = when (val decision = gate.accept(rawEvent)) {
                            is ProviderEventDecision.Emit -> decision.event
                            is ProviderEventDecision.Ignore -> throw ProviderStreamContractException(
                                "Provider stream violated the event contract: ${decision.reason}.",
                            )
                        }
                        when (event) {
                            is ProviderStreamEvent.Started -> {
                                timing?.firstByte()
                                val providerRequestId = event.remoteRequestId?.withValue { it }
                                ensureRequestSent(providerRequestId)
                                ensureStreamStarted()
                            }
                            is ProviderStreamEvent.TextDelta -> {
                                timing?.firstByte()
                                ensureStreamStarted()
                                val decoded = event.text.withValue(payloadDecoder::onTextDelta)
                                if (decoded.isNotEmpty()) {
                                    timing?.decodedBody(decoded)
                                    buffer.appendUtf8(decoded, now())
                                }
                            }
                            is ProviderStreamEvent.StructuredDelta -> {
                                timing?.firstByte()
                                ensureStreamStarted()
                                val decoded = event.fragment.withValue(payloadDecoder::onStructuredDelta)
                                if (decoded.isNotEmpty()) {
                                    timing?.decodedBody(decoded)
                                    buffer.appendUtf8(decoded, now())
                                }
                            }
                            is ProviderStreamEvent.UsageUpdate -> {
                                timing?.firstByte()
                                ensureStreamStarted()
                                latestUsage = event.usage
                            }
                            is ProviderStreamEvent.Completed -> {
                                timing?.firstByte()
                                if (!streamStarted.get()) {
                                    throw ProviderStreamContractException(
                                        "Provider completed without proving that a response stream started.",
                                    )
                                }
                                val payloadCompletion = payloadDecoder.complete(event.reason)
                                timing?.bodyEnded(payloadCompletion, event.reason, latestUsage)
                                val checkpoint = buffer.flush(now())
                                terminal = AuditedStreamingExecutionResult.Completed(
                                    reason = event.reason,
                                    response = null,
                                    payloadCompletion = payloadCompletion,
                                    checkpoint = checkpoint,
                                    latestUsage = latestUsage,
                                )
                            }
                            is ProviderStreamEvent.Refused -> {
                                timing?.firstByte()
                                ensureRequestSent(providerRequestId = null)
                                timing?.settleIfOpen(GenerationTimingOutcome.FAILED_CLOSED, latestUsage)
                                terminal = AuditedStreamingExecutionResult.Refused(
                                    category = event.category,
                                    checkpoint = buffer.flush(now()),
                                    latestUsage = latestUsage,
                                )
                            }
                            is ProviderStreamEvent.Failed -> {
                                if (event.requestState == FailureRequestState.RESPONSE_STARTED) {
                                    timing?.firstByte()
                                }
                                if (streamStarted.get() && event.requestState == FailureRequestState.NOT_SENT) {
                                    throw ProviderStreamContractException(
                                        "Provider cannot report NOT_SENT after a response stream started.",
                                    )
                                }
                                if (event.requestState != FailureRequestState.NOT_SENT) {
                                    ensureRequestSent(providerRequestId = null)
                                }
                                if (event.requestState == FailureRequestState.RESPONSE_STARTED) {
                                    ensureStreamStarted()
                                }
                                timing?.settleIfOpen(
                                    outcome = if (
                                        event.code == StandardErrorCode.UNKNOWN_RESULT ||
                                        event.requestState == FailureRequestState.RESULT_UNKNOWN
                                    ) {
                                        GenerationTimingOutcome.UNKNOWN
                                    } else {
                                        GenerationTimingOutcome.FAILED_CLOSED
                                    },
                                    usage = latestUsage,
                                )
                                terminal = AuditedStreamingExecutionResult.Failed(
                                    code = event.code,
                                    httpStatus = event.httpStatus,
                                    retryAfterMillis = event.retryAfterMillis,
                                    requestState = event.requestState,
                                    checkpoint = buffer.flush(now()),
                                    latestUsage = latestUsage,
                                )
                            }
                            ProviderStreamEvent.Heartbeat -> Unit
                        }
                    }
                } finally {
                    controlMonitor.cancelAndJoin()
                    heartbeat.cancelAndJoin()
                }
            }
        } catch (signal: GenerationExecutionControlSignal) {
            timing?.settleIfOpen(
                outcome = when (signal.action) {
                    GenerationExecutionControl.PAUSE -> GenerationTimingOutcome.NEEDS_ACTION
                    GenerationExecutionControl.CANCEL_CURRENT,
                    GenerationExecutionControl.STOP,
                    -> GenerationTimingOutcome.CANCELLED
                },
                usage = latestUsage,
            )
            return settleControlled(
                action = signal.action,
                claimed = claimed,
                buffer = buffer,
                adapter = adapter,
                profile = profile,
                request = request,
                latestUsage = latestUsage,
            )
        } catch (cancelled: CancellationException) {
            runCatching {
                timing?.settleIfOpen(GenerationTimingOutcome.CANCELLED, latestUsage)
            }.onFailure(cancelled::addSuppressed)
            throw cancelled
        } catch (error: Throwable) {
            runCatching {
                timing?.settleIfOpen(GenerationTimingOutcome.UNKNOWN, latestUsage)
            }.onFailure(error::addSuppressed)
            runCatching { buffer.flush(now()) }
            runCatching {
                drafts.markLiveAttemptUnknown(
                    request = claimed,
                    usage = latestUsage.toFinalUsageCommit(),
                    updatedAt = now(),
                )
            }.onFailure(error::addSuppressed)
            throw error
        }
        drafts.executionControl(claimed)?.let { action ->
            timing?.settleIfOpen(
                outcome = when (action) {
                    GenerationExecutionControl.PAUSE -> GenerationTimingOutcome.NEEDS_ACTION
                    GenerationExecutionControl.CANCEL_CURRENT,
                    GenerationExecutionControl.STOP,
                    -> GenerationTimingOutcome.CANCELLED
                },
                usage = latestUsage,
            )
            return settleControlled(action, claimed, buffer, adapter, profile, request, latestUsage)
        }
        val collected = terminal ?: run {
            val checkpoint = buffer.flush(now())
            timing?.settleIfOpen(GenerationTimingOutcome.UNKNOWN, latestUsage)
            drafts.markLiveAttemptUnknown(
                request = claimed,
                usage = latestUsage.toFinalUsageCommit(),
                updatedAt = now(),
            )
            return AuditedStreamingExecutionResult.Interrupted(
                checkpoint = checkpoint,
                latestUsage = latestUsage,
            )
        }
        if (
            collected is AuditedStreamingExecutionResult.Failed &&
            (collected.code == StandardErrorCode.UNKNOWN_RESULT ||
                collected.requestState == FailureRequestState.RESULT_UNKNOWN)
        ) {
            drafts.markLiveAttemptUnknown(
                request = claimed,
                usage = latestUsage.toFinalUsageCommit(),
                updatedAt = now(),
            )
        }
        return if (collected is AuditedStreamingExecutionResult.Completed &&
            collected.reason in setOf(ProviderFinishReason.STOP, ProviderFinishReason.LENGTH)
        ) {
            try {
                collected.copy(
                    response = outputs.recordSuccessfulResponse(
                        request = claimed,
                        checkpoint = collected.checkpoint,
                        completedAt = now(),
                        pendingValidationError = when {
                            collected.payloadCompletion == ProviderPayloadCompletion.INVALID ->
                                StandardErrorCode.FORMAT_INVALID
                            collected.reason == ProviderFinishReason.LENGTH ->
                                StandardErrorCode.OUTPUT_TRUNCATED
                            else -> null
                        },
                    ),
                )
            } catch (stale: StaleGenerationStateException) {
                val action = drafts.executionControl(claimed) ?: throw stale
                settleControlled(action, claimed, buffer, adapter, profile, request, latestUsage)
            }
        } else {
            collected
        }
    }

    private suspend fun settleControlled(
        action: GenerationExecutionControl,
        claimed: ClaimedStreamingRequest,
        buffer: StreamingDraftBuffer,
        adapter: ProviderAdapter,
        profile: ProviderConnectionProfile,
        request: GenerationRequest,
        latestUsage: ProviderUsage?,
    ): AuditedStreamingExecutionResult.Controlled {
        val cancellation = runCatching { adapter.cancel(profile, request.requestId) }
            .getOrDefault(ProviderCancellationResult.NOT_SUPPORTED)
        val checkpoint = buffer.flush(now())
        val controlResult = drafts.settleExecutionControl(
            request = claimed,
            action = action,
            usage = latestUsage.toFinalUsageCommit(),
            settledAt = now(),
        )
        return AuditedStreamingExecutionResult.Controlled(
            action = action,
            cancellation = cancellation,
            controlResult = controlResult,
            checkpoint = checkpoint,
            latestUsage = latestUsage,
        )
    }

    private fun now(): Long = clock.nowMillis().also { value ->
        require(value >= 0L) { "Generation execution clock returned an invalid time." }
    }
}
