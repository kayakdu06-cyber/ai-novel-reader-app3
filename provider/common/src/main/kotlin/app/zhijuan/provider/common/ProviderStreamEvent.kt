package app.zhijuan.provider.common

import app.zhijuan.core.model.StandardErrorCode
import app.zhijuan.core.model.FailureRequestState

enum class ProviderUsageQuality {
    PROVIDER_REPORTED,
    ESTIMATED,
    UNKNOWN,
}

data class ProviderUsage(
    val inputTokens: Long?,
    val outputTokens: Long?,
    val cachedInputTokens: Long?,
    val cachedWriteTokens: Long?,
    val reasoningTokens: Long?,
    val totalTokens: Long?,
    val quality: ProviderUsageQuality,
) {
    init {
        listOf(
            inputTokens,
            outputTokens,
            cachedInputTokens,
            cachedWriteTokens,
            reasoningTokens,
            totalTokens,
        ).forEach { require(it == null || it >= 0) { "Provider usage cannot be negative." } }
        if (quality == ProviderUsageQuality.UNKNOWN) {
            require(
                inputTokens == null &&
                    outputTokens == null &&
                    cachedInputTokens == null &&
                    cachedWriteTokens == null &&
                    reasoningTokens == null &&
                    totalTokens == null,
            ) { "Unknown provider usage must not contain token values." }
        }
        if (totalTokens != null && inputTokens != null && outputTokens != null) {
            require(totalTokens >= inputTokens + outputTokens) {
                "Provider total tokens are smaller than input plus output tokens."
            }
        }
    }
}

enum class ProviderFinishReason {
    STOP,
    LENGTH,
    TOOL_CALL,
    CONTENT_FILTER,
    CANCELLED,
    UNKNOWN,
}

enum class ProviderRefusalCategory {
    POLICY,
    SAFETY,
    UNSUPPORTED_REQUEST,
    UNKNOWN,
}

sealed interface ProviderStreamEvent {
    data class Started(
        val remoteRequestId: ProviderRemoteRequestId? = null,
    ) : ProviderStreamEvent

    data class TextDelta(
        val text: SensitiveProviderText,
    ) : ProviderStreamEvent

    data class StructuredDelta(
        val fragment: SensitiveProviderText,
    ) : ProviderStreamEvent

    data class UsageUpdate(
        val usage: ProviderUsage,
    ) : ProviderStreamEvent

    data class Completed(
        val reason: ProviderFinishReason,
    ) : ProviderStreamEvent

    data class Refused(
        val category: ProviderRefusalCategory,
        val userFacingMessage: SensitiveProviderText? = null,
    ) : ProviderStreamEvent

    data class Failed(
        val code: StandardErrorCode,
        val httpStatus: Int? = null,
        val retryAfterMillis: Long? = null,
        val requestState: FailureRequestState = FailureRequestState.RESULT_UNKNOWN,
    ) : ProviderStreamEvent {
        init {
            require(httpStatus == null || httpStatus in 100..599)
            require(retryAfterMillis == null || retryAfterMillis >= 0)
            require(httpStatus == null || requestState != FailureRequestState.NOT_SENT) {
                "An HTTP response proves that the request reached a provider endpoint."
            }
        }
    }

    data object Heartbeat : ProviderStreamEvent
}

val ProviderStreamEvent.isTerminal: Boolean
    get() = this is ProviderStreamEvent.Completed ||
        this is ProviderStreamEvent.Refused ||
        this is ProviderStreamEvent.Failed

enum class IgnoredProviderEventReason {
    BEFORE_STARTED,
    DUPLICATE_STARTED,
    AFTER_TERMINAL,
}

sealed interface ProviderEventDecision {
    data class Emit(val event: ProviderStreamEvent) : ProviderEventDecision
    data class Ignore(val reason: IgnoredProviderEventReason) : ProviderEventDecision
}

class ProviderEventGate {
    private var started = false
    private var terminal = false

    fun accept(event: ProviderStreamEvent): ProviderEventDecision {
        if (terminal) return ProviderEventDecision.Ignore(IgnoredProviderEventReason.AFTER_TERMINAL)
        if (event is ProviderStreamEvent.Started) {
            if (started) return ProviderEventDecision.Ignore(IgnoredProviderEventReason.DUPLICATE_STARTED)
            started = true
            return ProviderEventDecision.Emit(event)
        }
        if (!started && !event.isTerminal) {
            return ProviderEventDecision.Ignore(IgnoredProviderEventReason.BEFORE_STARTED)
        }
        val normalized = if (
            started &&
            event is ProviderStreamEvent.Failed &&
            event.requestState == FailureRequestState.RESULT_UNKNOWN
        ) {
            event.copy(requestState = FailureRequestState.RESPONSE_STARTED)
        } else {
            event
        }
        if (normalized.isTerminal) terminal = true
        return ProviderEventDecision.Emit(normalized)
    }

    fun terminalForUnexpectedEnd(): ProviderStreamEvent.Failed? {
        if (terminal) return null
        terminal = true
        return ProviderStreamEvent.Failed(
            code = StandardErrorCode.STREAM_INTERRUPTED,
            requestState = if (started) {
                FailureRequestState.RESPONSE_STARTED
            } else {
                FailureRequestState.RESULT_UNKNOWN
            },
        )
    }
}
