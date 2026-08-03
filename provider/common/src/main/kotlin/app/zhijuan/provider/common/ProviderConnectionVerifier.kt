package app.zhijuan.provider.common

import app.zhijuan.core.model.FailureRequestState
import app.zhijuan.core.model.StandardErrorCode
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeout

enum class SelectedModelVerification {
    LISTED,
    MINIMAL_GENERATION,
    UNVERIFIED_MANUAL,
}

data class SelectedModelState(
    val modelId: ProviderModelId,
    val verification: SelectedModelVerification,
)

sealed interface ConnectionModelList {
    data class Available(
        val models: List<ProviderModelSummary>,
        val fetchedAt: Long,
    ) : ConnectionModelList {
        init {
            require(fetchedAt >= 0)
            require(models.size <= 10_000)
            require(models.map(ProviderModelSummary::id).distinct().size == models.size)
        }
    }

    data class Unavailable(
        val failure: ProviderCallFailure,
    ) : ConnectionModelList
}

enum class GenerationProbeUnavailableReason {
    CAPABILITY_LOOKUP_FAILED,
    HARD_OUTPUT_LIMIT_NOT_SUPPORTED,
}

sealed interface MinimalGenerationProbeResult {
    data object NotRequested : MinimalGenerationProbeResult

    data class Verified(
        val verifiedAt: Long,
        val usageObserved: Boolean,
        val evidence: ProviderCapabilitySnapshot,
    ) : MinimalGenerationProbeResult {
        init {
            require(verifiedAt >= 0)
            require(evidence.source == CapabilitySource.PROBED)
            require(evidence.verifiedAt == verifiedAt)
        }
    }

    data class NotSafelyAvailable(
        val reason: GenerationProbeUnavailableReason,
        val capabilityFailure: ProviderCallFailure? = null,
    ) : MinimalGenerationProbeResult {
        init {
            require(
                (reason == GenerationProbeUnavailableReason.CAPABILITY_LOOKUP_FAILED) ==
                    (capabilityFailure != null),
            ) { "Only a capability lookup failure may carry a provider failure." }
        }
    }

    data class Failed(
        val failure: ProviderCallFailure,
    ) : MinimalGenerationProbeResult
}

data class ConnectionVerificationReport(
    val checkedAt: Long,
    val modelList: ConnectionModelList,
    val selectedModel: SelectedModelState?,
    val minimalGeneration: MinimalGenerationProbeResult,
) {
    init {
        require(checkedAt >= 0)
        when (selectedModel?.verification) {
            SelectedModelVerification.LISTED -> {
                val available = modelList as? ConnectionModelList.Available
                require(available?.models?.any { it.id == selectedModel.modelId } == true) {
                    "A listed selection must occur in the returned model list."
                }
            }
            SelectedModelVerification.MINIMAL_GENERATION -> {
                require(minimalGeneration is MinimalGenerationProbeResult.Verified) {
                    "A generation-verified model requires successful probe evidence."
                }
            }
            SelectedModelVerification.UNVERIFIED_MANUAL -> {
                require(modelList is ConnectionModelList.Unavailable) {
                    "A manual unverified model is only valid when model discovery is unavailable."
                }
            }
            null -> Unit
        }
    }

    val modelListVerified: Boolean
        get() = modelList is ConnectionModelList.Available

    val minimalGenerationVerified: Boolean
        get() = minimalGeneration is MinimalGenerationProbeResult.Verified

    val usageObserved: Boolean
        get() = (minimalGeneration as? MinimalGenerationProbeResult.Verified)?.usageObserved == true

    val connectionVerified: Boolean
        get() = modelListVerified || minimalGenerationVerified

    val canSave: Boolean
        get() = connectionVerified ||
            selectedModel?.verification == SelectedModelVerification.UNVERIFIED_MANUAL
}

sealed interface ConnectionVerificationResult {
    data class Completed(
        val report: ConnectionVerificationReport,
    ) : ConnectionVerificationResult

    data class Failure(
        val failure: ProviderCallFailure,
    ) : ConnectionVerificationResult

    data class TimedOut(
        val timeoutMillis: Long,
    ) : ConnectionVerificationResult {
        init {
            require(timeoutMillis in 1..MAXIMUM_CONNECTION_TEST_MILLIS)
        }
    }
}

data class ConnectionVerificationRequest(
    val profile: ProviderConnectionProfile,
    val selectedModelId: ProviderModelId? = null,
    val verifyMinimalGeneration: Boolean = false,
    val minimalGenerationCostAcknowledged: Boolean = false,
    val totalTimeoutMillis: Long = MAXIMUM_CONNECTION_TEST_MILLIS,
) {
    init {
        require(totalTimeoutMillis in 1..MAXIMUM_CONNECTION_TEST_MILLIS) {
            "Connection verification must finish within 60 seconds."
        }
        require(!verifyMinimalGeneration || selectedModelId != null) {
            "Minimal generation verification requires a selected model."
        }
        require(!verifyMinimalGeneration || minimalGenerationCostAcknowledged) {
            "Minimal generation verification requires explicit cost acknowledgement."
        }
        require(verifyMinimalGeneration || !minimalGenerationCostAcknowledged) {
            "Cost acknowledgement is only valid for a requested generation probe."
        }
    }
}

class ProviderConnectionVerifier(
    private val adapters: ProviderAdapterRegistry,
    private val capabilityRegistry: ProviderCapabilityRegistry,
    private val clock: () -> Long = System::currentTimeMillis,
    private val monotonicClockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val requestSequence: AtomicLong = AtomicLong(),
) {
    suspend fun verify(request: ConnectionVerificationRequest): ConnectionVerificationResult {
        val startedAt = monotonicClockMillis()
        val deadline = if (startedAt > Long.MAX_VALUE - request.totalTimeoutMillis) {
            Long.MAX_VALUE
        } else {
            startedAt + request.totalTimeoutMillis
        }
        return try {
            withTimeout(request.totalTimeoutMillis) {
                verifyWithinDeadline(request, deadline)
            }
        } catch (_: TimeoutCancellationException) {
            ConnectionVerificationResult.TimedOut(request.totalTimeoutMillis)
        } catch (_: ConnectionVerificationDeadlineExceeded) {
            ConnectionVerificationResult.TimedOut(request.totalTimeoutMillis)
        }
    }

    private suspend fun verifyWithinDeadline(
        request: ConnectionVerificationRequest,
        deadlineMillis: Long,
    ): ConnectionVerificationResult {
        val adapter = adapters.adapterFor(request.profile)
        val listResult = try {
            adapter.listModels(request.profile)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return ConnectionVerificationResult.Failure(
                ProviderCallFailure(StandardErrorCode.UNKNOWN_RESULT),
            )
        }

        val modelList: ConnectionModelList
        val initialSelection: SelectedModelState?
        when (listResult) {
            is ModelListResult.Success -> {
                modelList = ConnectionModelList.Available(listResult.models, listResult.fetchedAt)
                recordModelMetadata(request.profile, adapter, listResult)
                initialSelection = request.selectedModelId?.let { selected ->
                    if (listResult.models.none { it.id == selected }) {
                        return ConnectionVerificationResult.Failure(
                            ProviderCallFailure(
                                code = StandardErrorCode.MODEL_NOT_FOUND,
                                requestState = FailureRequestState.NOT_SENT,
                            ),
                        )
                    }
                    SelectedModelState(selected, SelectedModelVerification.LISTED)
                }
            }
            is ModelListResult.Failure -> {
                if (!listResult.failure.allowsManualModelFallback()) {
                    return ConnectionVerificationResult.Failure(listResult.failure)
                }
                modelList = ConnectionModelList.Unavailable(listResult.failure)
                initialSelection = request.selectedModelId?.let {
                    SelectedModelState(it, SelectedModelVerification.UNVERIFIED_MANUAL)
                }
            }
        }

        if (!request.verifyMinimalGeneration) {
            return completed(modelList, initialSelection, MinimalGenerationProbeResult.NotRequested)
        }

        val selectedModel = checkNotNull(request.selectedModelId)
        val probeResult = runMinimalGenerationProbe(
            adapter = adapter,
            profile = request.profile,
            modelId = selectedModel,
            deadlineMillis = deadlineMillis,
        )
        val finalSelection = if (probeResult is MinimalGenerationProbeResult.Verified) {
            SelectedModelState(selectedModel, SelectedModelVerification.MINIMAL_GENERATION)
        } else {
            initialSelection
        }
        return completed(modelList, finalSelection, probeResult)
    }

    private suspend fun runMinimalGenerationProbe(
        adapter: ProviderAdapter,
        profile: ProviderConnectionProfile,
        modelId: ProviderModelId,
        deadlineMillis: Long,
    ): MinimalGenerationProbeResult {
        val capabilities = try {
            when (val result = adapter.getCapabilities(profile, modelId)) {
                is CapabilityResult.Success -> result.snapshot
                is CapabilityResult.Failure -> return MinimalGenerationProbeResult.NotSafelyAvailable(
                    reason = GenerationProbeUnavailableReason.CAPABILITY_LOOKUP_FAILED,
                    capabilityFailure = result.failure,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return MinimalGenerationProbeResult.NotSafelyAvailable(
                reason = GenerationProbeUnavailableReason.CAPABILITY_LOOKUP_FAILED,
                capabilityFailure = ProviderCallFailure(StandardErrorCode.UNKNOWN_RESULT),
            )
        }
        if (capabilities.protocol != profile.protocol || capabilities.modelId != modelId) {
            return MinimalGenerationProbeResult.NotSafelyAvailable(
                reason = GenerationProbeUnavailableReason.CAPABILITY_LOOKUP_FAILED,
                capabilityFailure = ProviderCallFailure(
                    code = StandardErrorCode.PROTOCOL_MISMATCH,
                    requestState = FailureRequestState.NOT_SENT,
                ),
            )
        }
        if (!capabilities.maySend(ProviderRequestField.MAX_OUTPUT_TOKENS)) {
            return MinimalGenerationProbeResult.NotSafelyAvailable(
                GenerationProbeUnavailableReason.HARD_OUTPUT_LIMIT_NOT_SUPPORTED,
            )
        }

        val useStreaming = capabilities.maySend(ProviderRequestField.STREAMING)
        val generationRequest = minimalRequest(
            modelId = modelId,
            stream = useStreaming,
            timeouts = remainingProbeTimeouts(deadlineMillis),
        )
        var started = false
        var usageObserved = false
        var terminal: ProviderStreamEvent? = null
        try {
            adapter.generate(profile, generationRequest).collect { event ->
                when (event) {
                    is ProviderStreamEvent.Started -> started = true
                    is ProviderStreamEvent.UsageUpdate -> usageObserved = true
                    is ProviderStreamEvent.Completed,
                    is ProviderStreamEvent.Refused,
                    is ProviderStreamEvent.Failed,
                    -> if (terminal == null) terminal = event
                    is ProviderStreamEvent.TextDelta,
                    is ProviderStreamEvent.StructuredDelta,
                    ProviderStreamEvent.Heartbeat,
                    -> Unit
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return MinimalGenerationProbeResult.Failed(
                ProviderCallFailure(
                    code = StandardErrorCode.UNKNOWN_RESULT,
                    requestState = if (started) {
                        FailureRequestState.RESPONSE_STARTED
                    } else {
                        FailureRequestState.RESULT_UNKNOWN
                    },
                ),
            )
        }

        return when (val observedTerminal = terminal) {
            is ProviderStreamEvent.Completed -> when (observedTerminal.reason) {
                ProviderFinishReason.STOP,
                ProviderFinishReason.LENGTH,
                -> recordSuccessfulProbe(
                    profile = profile,
                    adapter = adapter,
                    modelId = modelId,
                    capabilities = capabilities,
                    usedStreaming = useStreaming,
                    usageObserved = usageObserved,
                )
                ProviderFinishReason.CONTENT_FILTER -> MinimalGenerationProbeResult.Failed(
                    ProviderCallFailure(
                        code = StandardErrorCode.POLICY_REFUSAL,
                        requestState = FailureRequestState.RESPONSE_STARTED,
                    ),
                )
                ProviderFinishReason.TOOL_CALL -> MinimalGenerationProbeResult.Failed(
                    ProviderCallFailure(
                        code = StandardErrorCode.PROTOCOL_MISMATCH,
                        requestState = FailureRequestState.RESPONSE_STARTED,
                    ),
                )
                ProviderFinishReason.CANCELLED,
                ProviderFinishReason.UNKNOWN,
                -> MinimalGenerationProbeResult.Failed(
                    ProviderCallFailure(
                        code = StandardErrorCode.UNKNOWN_RESULT,
                        requestState = if (started) {
                            FailureRequestState.RESPONSE_STARTED
                        } else {
                            FailureRequestState.RESULT_UNKNOWN
                        },
                    ),
                )
            }
            is ProviderStreamEvent.Refused -> MinimalGenerationProbeResult.Failed(
                ProviderCallFailure(
                    code = StandardErrorCode.POLICY_REFUSAL,
                    requestState = if (started) {
                        FailureRequestState.RESPONSE_STARTED
                    } else {
                        FailureRequestState.PROVIDER_REJECTED
                    },
                ),
            )
            is ProviderStreamEvent.Failed -> MinimalGenerationProbeResult.Failed(
                ProviderCallFailure(
                    code = observedTerminal.code,
                    httpStatus = observedTerminal.httpStatus,
                    retryAfterMillis = observedTerminal.retryAfterMillis,
                    requestState = observedTerminal.requestState,
                ),
            )
            else -> MinimalGenerationProbeResult.Failed(
                ProviderCallFailure(
                    code = StandardErrorCode.STREAM_INTERRUPTED,
                    requestState = if (started) {
                        FailureRequestState.RESPONSE_STARTED
                    } else {
                        FailureRequestState.RESULT_UNKNOWN
                    },
                ),
            )
        }
    }

    private suspend fun recordSuccessfulProbe(
        profile: ProviderConnectionProfile,
        adapter: ProviderAdapter,
        modelId: ProviderModelId,
        capabilities: ProviderCapabilitySnapshot,
        usedStreaming: Boolean,
        usageObserved: Boolean,
    ): MinimalGenerationProbeResult.Verified {
        val verifiedAt = safeEvidenceTime(clock())
        val evidence = ProviderCapabilityProbeEvidence(
            streaming = if (usedStreaming) {
                CapabilityProbeOutcome.SUPPORTED
            } else {
                CapabilityProbeOutcome.INCONCLUSIVE
            },
            observedStreamFormat = if (usedStreaming) {
                capabilities.streamFormat
            } else {
                ProviderStreamFormat.UNKNOWN
            },
            usageInStream = if (usedStreaming && usageObserved) {
                CapabilityProbeOutcome.SUPPORTED
            } else {
                CapabilityProbeOutcome.INCONCLUSIVE
            },
            maxOutputTokensParameter = CapabilityProbeOutcome.SUPPORTED,
        ).toSnapshot(
            protocol = profile.protocol,
            modelId = modelId,
            adapterVersion = adapter.adapterVersion,
            verifiedAt = verifiedAt,
            validForMillis = ProviderCapabilityProbeEvidence.DEFAULT_TTL_MILLIS,
        )
        try {
            capabilityRegistry.recordSuccessfulProbe(profile, evidence)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A cache write failure must not turn a completed provider call into a second request.
        }
        return MinimalGenerationProbeResult.Verified(
            verifiedAt = verifiedAt,
            usageObserved = usageObserved,
            evidence = evidence,
        )
    }

    private suspend fun recordModelMetadata(
        profile: ProviderConnectionProfile,
        adapter: ProviderAdapter,
        result: ModelListResult.Success,
    ) {
        if (result.fetchedAt > Long.MAX_VALUE - ProviderCapabilityProbeEvidence.DEFAULT_TTL_MILLIS) return
        result.models.forEach { model ->
            if (model.contextLimitHint == null && model.maxOutputTokensHint == null) return@forEach
            val snapshot = ProviderCapabilitySnapshot(
                protocol = profile.protocol,
                modelId = model.id,
                streaming = CapabilitySupport.UNKNOWN,
                streamFormat = ProviderStreamFormat.UNKNOWN,
                structuredOutput = CapabilitySupport.UNKNOWN,
                usageInStream = CapabilitySupport.UNKNOWN,
                systemInstruction = CapabilitySupport.UNKNOWN,
                temperature = CapabilitySupport.UNKNOWN,
                topP = CapabilitySupport.UNKNOWN,
                maxOutputTokensParameter = CapabilitySupport.UNKNOWN,
                seed = CapabilitySupport.UNKNOWN,
                reasoningEffort = CapabilitySupport.UNKNOWN,
                idempotencyKey = CapabilitySupport.UNKNOWN,
                contextLimit = model.contextLimitHint,
                maxOutputTokens = model.maxOutputTokensHint,
                tokenizerFamily = TokenizerFamily.UNKNOWN,
                source = CapabilitySource.OFFICIAL_METADATA,
                verifiedAt = result.fetchedAt,
                expiresAt = result.fetchedAt + ProviderCapabilityProbeEvidence.DEFAULT_TTL_MILLIS,
                adapterVersion = adapter.adapterVersion,
            )
            try {
                capabilityRegistry.recordOfficialMetadata(profile, snapshot)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Discovery remains useful even if optional metadata persistence is unavailable.
            }
        }
    }

    private fun minimalRequest(
        modelId: ProviderModelId,
        stream: Boolean,
        timeouts: ProviderTimeoutPolicy,
    ): GenerationRequest {
        val sequence = requestSequence.incrementAndGet()
        val suffix = sequence.toString()
        return GenerationRequest(
            requestId = "connection-probe-$suffix",
            generationId = "connection-check-$suffix",
            stageId = "minimal-generation-$suffix",
            attemptId = "attempt-$suffix",
            modelId = modelId,
            prompt = ProviderPrompt(
                listOf(
                    PromptPart(
                        PromptLayer.STAGE_CONTRACT,
                        SensitiveProviderText.from(MINIMAL_PROBE_TEXT),
                    ),
                ),
            ),
            parameters = GenerationParameters(maxOutputTokens = MAXIMUM_PROBE_OUTPUT_TOKENS),
            structuredOutputSchema = null,
            stream = stream,
            timeouts = timeouts,
            idempotencyKey = null,
        )
    }

    private fun completed(
        modelList: ConnectionModelList,
        selectedModel: SelectedModelState?,
        probe: MinimalGenerationProbeResult,
    ): ConnectionVerificationResult.Completed = ConnectionVerificationResult.Completed(
        ConnectionVerificationReport(
            checkedAt = clock().coerceAtLeast(0),
            modelList = modelList,
            selectedModel = selectedModel,
            minimalGeneration = probe,
        ),
    )

    private fun ProviderCallFailure.allowsManualModelFallback(): Boolean =
        requestState == FailureRequestState.PROVIDER_REJECTED &&
            code in setOf(StandardErrorCode.MODEL_NOT_FOUND, StandardErrorCode.PROTOCOL_MISMATCH)

    private fun safeEvidenceTime(value: Long): Long = value.coerceIn(
        minimumValue = 0,
        maximumValue = Long.MAX_VALUE - ProviderCapabilityProbeEvidence.DEFAULT_TTL_MILLIS,
    )

    private fun remainingProbeTimeouts(deadlineMillis: Long): ProviderTimeoutPolicy {
        val remaining = (deadlineMillis - monotonicClockMillis())
            .coerceAtMost(MAXIMUM_CONNECTION_TEST_MILLIS)
        if (remaining < MINIMUM_NETWORK_TIMEOUT_MILLIS) {
            throw ConnectionVerificationDeadlineExceeded()
        }
        val connect = minOf(5_000L, remaining)
        val firstByte = minOf(15_000L, remaining).coerceAtLeast(connect)
        val idle = minOf(15_000L, remaining).coerceAtLeast(MINIMUM_NETWORK_TIMEOUT_MILLIS)
        return ProviderTimeoutPolicy(
            connectMillis = connect,
            firstByteMillis = firstByte,
            streamIdleMillis = idle,
            totalStageMillis = remaining,
        )
    }

    private companion object {
        const val MAXIMUM_PROBE_OUTPUT_TOKENS = 16
        const val MINIMUM_NETWORK_TIMEOUT_MILLIS = 1_000L
        const val MINIMAL_PROBE_TEXT =
            "Connection verification only. Reply with exactly OK and no other text."
    }
}

private class ConnectionVerificationDeadlineExceeded : RuntimeException()

const val MAXIMUM_CONNECTION_TEST_MILLIS = 60_000L
