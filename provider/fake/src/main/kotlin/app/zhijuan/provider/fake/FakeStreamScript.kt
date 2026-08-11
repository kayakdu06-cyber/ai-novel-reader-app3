package app.zhijuan.provider.fake

import app.zhijuan.core.model.FailureRequestState
import app.zhijuan.core.model.StandardErrorCode
import app.zhijuan.provider.common.ProviderFinishReason
import app.zhijuan.provider.common.ProviderRefusalCategory
import app.zhijuan.provider.common.SensitiveProviderText

/**
 * Declarative, auditable building blocks for a [FakeStreamScript]. Steps never
 * carry callbacks or hidden behavior; they only describe what the fake provider
 * emits and when.
 *
 * Default [toString] implementations are redacted: text fragments and remote
 * request ids are never rendered.
 */
sealed interface FakeStreamStep {
    /** Advances virtual time by [millis] without emitting an event. */
    data class Wait(val millis: Long) : FakeStreamStep {
        init {
            require(millis >= 0L) { "Fake stream wait duration cannot be negative." }
        }

        override fun toString(): String = "Wait(millis=$millis)"
    }

    /** Emits [ProviderStreamEvent.Started]. */
    data class Started(val remoteRequestId: String? = null) : FakeStreamStep {
        init {
            remoteRequestId?.let {
                require(it.isNotBlank() && it.length <= 512 && it.none(Char::isISOControl)) {
                    "Fake stream remote request id is invalid."
                }
            }
        }

        override fun toString(): String =
            if (remoteRequestId == null) "Started()" else "Started(hasRemoteRequestId=true)"
    }

    /** Emits a text delta carrying [text] as sensitive content. */
    data class Text(val text: String) : FakeStreamStep {
        init {
            require(text.length <= SensitiveProviderText.MAX_CHARACTERS) {
                "Fake stream text exceeds the allowed size."
            }
        }

        override fun toString(): String = "Text(characters=${text.length})"
    }

    /** Emits a structured output delta carrying [fragment] as sensitive content. */
    data class Structured(val fragment: String) : FakeStreamStep {
        init {
            require(fragment.length <= SensitiveProviderText.MAX_CHARACTERS) {
                "Fake stream structured fragment exceeds the allowed size."
            }
        }

        override fun toString(): String = "Structured(characters=${fragment.length})"
    }

    /** Emits a provider-reported usage update. */
    data class Usage(
        val inputTokens: Long? = null,
        val outputTokens: Long? = null,
    ) : FakeStreamStep {
        init {
            require(inputTokens == null || inputTokens >= 0L) { "Input tokens cannot be negative." }
            require(outputTokens == null || outputTokens >= 0L) { "Output tokens cannot be negative." }
            if (inputTokens != null && outputTokens != null) {
                try {
                    Math.addExact(inputTokens, outputTokens)
                } catch (overflow: ArithmeticException) {
                    throw IllegalArgumentException("Fake stream usage total overflows.", overflow)
                }
            }
        }

        override fun toString(): String =
            "Usage(inputTokens=$inputTokens, outputTokens=$outputTokens)"
    }

    /** Emits [ProviderStreamEvent.Heartbeat]. */
    data object Heartbeat : FakeStreamStep

    /** Emits a terminal [ProviderStreamEvent.Completed]. */
    data class Completed(
        val reason: ProviderFinishReason = ProviderFinishReason.STOP,
    ) : FakeStreamStep {
        override fun toString(): String = "Completed(reason=${reason.name})"
    }

    /** Emits a terminal [ProviderStreamEvent.Refused]. */
    data class Refused(
        val category: ProviderRefusalCategory = ProviderRefusalCategory.UNKNOWN,
    ) : FakeStreamStep {
        override fun toString(): String = "Refused(category=${category.name})"
    }

    /** Emits a terminal [ProviderStreamEvent.Failed] with an explicit failure. */
    data class Failed(
        val code: StandardErrorCode = StandardErrorCode.UNKNOWN_RESULT,
        val httpStatus: Int? = null,
        val retryAfterMillis: Long? = null,
        val requestState: FailureRequestState = FailureRequestState.RESULT_UNKNOWN,
    ) : FakeStreamStep {
        init {
            require(httpStatus == null || httpStatus in 100..599) { "HTTP status is invalid." }
            require(retryAfterMillis == null || retryAfterMillis >= 0L) {
                "Retry-after duration cannot be negative."
            }
            require(httpStatus == null || requestState != FailureRequestState.NOT_SENT) {
                "An HTTP response proves that the request reached a provider endpoint."
            }
        }

        override fun toString(): String =
            "Failed(code=${code.name}, httpStatus=$httpStatus, " +
                "retryAfterMillis=$retryAfterMillis, requestState=${requestState.name})"
    }
}

/**
 * Immutable, validated script describing one deterministic fake provider run.
 *
 * Validation (rejected at construction with [IllegalArgumentException]):
 *  - wait durations are non-negative;
 *  - the summed virtual time does not overflow `Long`;
 *  - at most one terminal step ([Completed], [Refused], [Failed]) and, if present,
 *    it is the last step (events after a terminal are rejected);
 *  - content steps (text, structured, usage, heartbeat) require a preceding
 *    [FakeStreamStep.Started];
 *  - at most one [FakeStreamStep.Started].
 *
 * A script with no terminal step is an explicit unexpected EOF: the flow
 * ends naturally and the adapter must not synthesize a terminal event.
 */
class FakeStreamScript private constructor(
    val steps: List<FakeStreamStep>,
    val totalVirtualMillis: Long,
    val hasTerminal: Boolean,
) {
    override fun toString(): String =
        "FakeStreamScript(steps=${steps.size}, virtualMillis=$totalVirtualMillis, " +
            "terminal=$hasTerminal)"

    companion object {
        fun of(vararg steps: FakeStreamStep): FakeStreamScript = from(steps.toList())

        fun from(steps: List<FakeStreamStep>): FakeStreamScript {
            val copied = steps.toList()
            var waitTotal = 0L
            var sawStarted = false
            var terminalIndex = -1
            copied.forEachIndexed { index, step ->
                when (step) {
                    is FakeStreamStep.Wait -> {
                        require(step.millis >= 0L) { "Fake stream wait duration cannot be negative." }
                        waitTotal = try {
                            Math.addExact(waitTotal, step.millis)
                        } catch (overflow: ArithmeticException) {
                            throw IllegalArgumentException(
                                "Fake stream script virtual time overflows.",
                                overflow,
                            )
                        }
                    }
                    is FakeStreamStep.Started -> {
                        require(!sawStarted) {
                            "Fake stream script must not contain more than one Started step."
                        }
                        sawStarted = true
                    }
                    is FakeStreamStep.Text,
                    is FakeStreamStep.Structured,
                    is FakeStreamStep.Usage,
                    FakeStreamStep.Heartbeat,
                    -> {
                        require(sawStarted) {
                            "Fake stream script content steps require a preceding Started step."
                        }
                    }
                    is FakeStreamStep.Completed,
                    is FakeStreamStep.Refused,
                    is FakeStreamStep.Failed,
                    -> {
                        require(terminalIndex == -1) {
                            "Fake stream script must not contain more than one terminal step."
                        }
                        terminalIndex = index
                    }
                }
            }
            if (terminalIndex != -1) {
                require(terminalIndex == copied.lastIndex) {
                    "Fake stream script must not contain steps after its terminal step."
                }
            }
            return FakeStreamScript(copied, waitTotal, terminalIndex != -1)
        }
    }
}

/** Fluent builder for [FakeStreamScript]. Appends steps only; no behavior callbacks. */
class FakeStreamScriptBuilder {
    private val steps = mutableListOf<FakeStreamStep>()

    fun wait(millis: Long): FakeStreamScriptBuilder = apply { steps += FakeStreamStep.Wait(millis) }

    fun started(remoteRequestId: String? = null): FakeStreamScriptBuilder =
        apply { steps += FakeStreamStep.Started(remoteRequestId) }

    fun text(value: String): FakeStreamScriptBuilder = apply { steps += FakeStreamStep.Text(value) }

    fun structured(fragment: String): FakeStreamScriptBuilder =
        apply { steps += FakeStreamStep.Structured(fragment) }

    fun usage(inputTokens: Long? = null, outputTokens: Long? = null): FakeStreamScriptBuilder =
        apply { steps += FakeStreamStep.Usage(inputTokens, outputTokens) }

    fun heartbeat(): FakeStreamScriptBuilder = apply { steps += FakeStreamStep.Heartbeat }

    fun completed(reason: ProviderFinishReason = ProviderFinishReason.STOP): FakeStreamScriptBuilder =
        apply { steps += FakeStreamStep.Completed(reason) }

    fun refused(category: ProviderRefusalCategory = ProviderRefusalCategory.UNKNOWN): FakeStreamScriptBuilder =
        apply { steps += FakeStreamStep.Refused(category) }

    fun failed(
        code: StandardErrorCode = StandardErrorCode.UNKNOWN_RESULT,
        httpStatus: Int? = null,
        retryAfterMillis: Long? = null,
        requestState: FailureRequestState = FailureRequestState.RESULT_UNKNOWN,
    ): FakeStreamScriptBuilder = apply {
        steps += FakeStreamStep.Failed(code, httpStatus, retryAfterMillis, requestState)
    }

    fun build(): FakeStreamScript = FakeStreamScript.from(steps)
}

/** DSL entry point: `fakeStreamScript { started(); text("..."); completed() }`. */
fun fakeStreamScript(builder: FakeStreamScriptBuilder.() -> Unit): FakeStreamScript =
    FakeStreamScriptBuilder().apply(builder).build()
