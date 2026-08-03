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
import app.zhijuan.core.model.FailureRequestState
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
) {
    suspend fun execute(
        persistedRequest: PersistedStreamingRequest,
        adapter: ProviderAdapter,
        profile: ProviderConnectionProfile,
        request: GenerationRequest,
        payloadDecoder: ProviderStreamPayloadDecoder = PassthroughProviderStreamPayloadDecoder,
    ): AuditedStreamingExecutionResult {
        val claimed = drafts.claimForProviderOpen(persistedRequest, now())
        val buffer = drafts.openDraftBuffer(claimed)
        return try {
            collectAuthorized(claimed, buffer, adapter, profile, request, payloadDecoder)
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

    private suspend fun collectAuthorized(
        claimed: ClaimedStreamingRequest,
        buffer: StreamingDraftBuffer,
        adapter: ProviderAdapter,
        profile: ProviderConnectionProfile,
        request: GenerationRequest,
        payloadDecoder: ProviderStreamPayloadDecoder,
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
                    adapter.generate(profile, request).collect { rawEvent ->
                        val event = when (val decision = gate.accept(rawEvent)) {
                            is ProviderEventDecision.Emit -> decision.event
                            is ProviderEventDecision.Ignore -> throw ProviderStreamContractException(
                                "Provider stream violated the event contract: ${decision.reason}.",
                            )
                        }
                        when (event) {
                            is ProviderStreamEvent.Started -> {
                                val providerRequestId = event.remoteRequestId?.withValue { it }
                                ensureRequestSent(providerRequestId)
                                ensureStreamStarted()
                            }
                            is ProviderStreamEvent.TextDelta -> {
                                ensureStreamStarted()
                                event.text.withValue { value ->
                                    payloadDecoder.onTextDelta(value).takeIf(String::isNotEmpty)
                                        ?.let { buffer.appendUtf8(it, now()) }
                                }
                            }
                            is ProviderStreamEvent.StructuredDelta -> {
                                ensureStreamStarted()
                                event.fragment.withValue { value ->
                                    payloadDecoder.onStructuredDelta(value).takeIf(String::isNotEmpty)
                                        ?.let { buffer.appendUtf8(it, now()) }
                                }
                            }
                            is ProviderStreamEvent.UsageUpdate -> {
                                ensureStreamStarted()
                                latestUsage = event.usage
                            }
                            is ProviderStreamEvent.Completed -> {
                                if (!streamStarted.get()) {
                                    throw ProviderStreamContractException(
                                        "Provider completed without proving that a response stream started.",
                                    )
                                }
                                val payloadCompletion = payloadDecoder.complete(event.reason)
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
                                ensureRequestSent(providerRequestId = null)
                                terminal = AuditedStreamingExecutionResult.Refused(
                                    category = event.category,
                                    checkpoint = buffer.flush(now()),
                                    latestUsage = latestUsage,
                                )
                            }
                            is ProviderStreamEvent.Failed -> {
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
            throw cancelled
        } catch (error: Throwable) {
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
            return settleControlled(action, claimed, buffer, adapter, profile, request, latestUsage)
        }
        val collected = terminal ?: run {
            val checkpoint = buffer.flush(now())
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
