package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.GenerationTimingRepository
import app.zhijuan.core.diagnostics.GenerationTimingClock
import app.zhijuan.core.diagnostics.GenerationTimingEventFactory
import app.zhijuan.core.diagnostics.GenerationTimingMark
import app.zhijuan.core.diagnostics.GenerationTimingMilestone
import app.zhijuan.core.diagnostics.GenerationTimingOutcome
import app.zhijuan.core.diagnostics.GenerationTimingPhase

class GenerationTimingExecutionContext(
    val runId: String,
    val bookId: String,
    val phase: GenerationTimingPhase,
    val jobId: String,
    val stageId: String,
    val attemptId: String,
    val attemptNo: Int,
    val connectionId: String,
    val modelId: String,
) {
    init {
        require(runId.isNotEmpty() && bookId.isNotEmpty())
        require(jobId.isNotEmpty() && stageId.isNotEmpty() && attemptId.isNotEmpty())
        require(attemptNo in 1..1_000)
        require(connectionId.isNotEmpty() && modelId.isNotEmpty())
    }

    override fun toString(): String =
        "GenerationTimingExecutionContext(phase=${phase.name}, attemptNo=$attemptNo, identities=redacted)"
}

data class GenerationTimingObservation(
    val context: GenerationTimingExecutionContext,
    val milestone: GenerationTimingMilestone,
    val mark: GenerationTimingMark,
    val outcome: GenerationTimingOutcome? = null,
    val characterCount: Long? = null,
    val inputTokenCount: Long? = null,
    val outputTokenCount: Long? = null,
    val totalTokenCount: Long? = null,
) {
    override fun toString(): String = buildString {
        append("GenerationTimingObservation(milestone=")
        append(milestone.name)
        append(", outcome=")
        append(outcome?.name)
        append(", characterCount=")
        append(characterCount)
        append(", totalTokenCount=")
        append(totalTokenCount)
        append(", identities=redacted)")
    }
}

fun interface GenerationTimingEventRecorder {
    suspend fun record(observation: GenerationTimingObservation)
}

object NoOpGenerationTimingEventRecorder : GenerationTimingEventRecorder {
    override suspend fun record(observation: GenerationTimingObservation) = Unit
}

class DatabaseGenerationTimingEventRecorder(
    private val repository: GenerationTimingRepository,
    private val eventFactory: GenerationTimingEventFactory = GenerationTimingEventFactory(),
) : GenerationTimingEventRecorder {
    override suspend fun record(observation: GenerationTimingObservation) {
        val context = observation.context
        repository.record(
            eventFactory.create(
                phase = context.phase,
                milestone = observation.milestone,
                mark = observation.mark,
                runId = context.runId,
                bookId = context.bookId,
                jobId = context.jobId,
                stageId = context.stageId,
                attemptId = context.attemptId,
                attemptNo = context.attemptNo,
                outcome = observation.outcome,
                characterCount = observation.characterCount,
                inputTokenCount = observation.inputTokenCount,
                outputTokenCount = observation.outputTokenCount,
                totalTokenCount = observation.totalTokenCount,
                connectionId = context.connectionId,
                modelId = context.modelId,
            ),
        )
    }
}

internal class ProviderGenerationTimingTracker(
    private val context: GenerationTimingExecutionContext,
    private val clock: GenerationTimingClock,
    private val recorder: GenerationTimingEventRecorder,
) {
    init {
        require(context.phase == GenerationTimingPhase.BODY) {
            "The paragraph timing tracker only accepts BODY phase executions."
        }
    }

    private val paragraph = app.zhijuan.core.diagnostics.CompleteParagraphTimingTracker()
    private var providerOpened = false
    private var firstByte = false
    private var firstParagraph = false
    private var bodyEnded = false

    suspend fun providerOpened() {
        check(!providerOpened) { "Provider timing open was recorded twice." }
        providerOpened = true
        record(GenerationTimingMilestone.PROVIDER_OPENED)
    }

    suspend fun firstByte() {
        check(providerOpened) { "First-byte timing requires Provider-open evidence." }
        if (firstByte) return
        firstByte = true
        record(GenerationTimingMilestone.FIRST_BYTE)
    }

    suspend fun decodedBody(value: String) {
        check(providerOpened && firstByte) { "Decoded timing requires first-byte evidence." }
        if (!firstParagraph && paragraph.observeDecoded(value)) {
            firstParagraph = true
            record(
                milestone = GenerationTimingMilestone.FIRST_FULL_PARAGRAPH,
                characterCount = paragraph.decodedCharacterCount,
            )
        }
    }

    suspend fun bodyEnded(
        completion: ProviderPayloadCompletion,
        finishReason: app.zhijuan.provider.common.ProviderFinishReason,
        usage: app.zhijuan.provider.common.ProviderUsage?,
    ) {
        check(providerOpened && firstByte) { "Body-end timing requires Provider-open and first-byte evidence." }
        check(!bodyEnded) { "Body-end timing was recorded twice." }
        if (!firstParagraph && completion == ProviderPayloadCompletion.COMPLETE && paragraph.completeBody()) {
            firstParagraph = true
            record(
                milestone = GenerationTimingMilestone.FIRST_FULL_PARAGRAPH,
                characterCount = paragraph.decodedCharacterCount,
            )
        }
        recordTerminal(
            outcome = when {
                completion == ProviderPayloadCompletion.COMPLETE &&
                    finishReason == app.zhijuan.provider.common.ProviderFinishReason.STOP ->
                    GenerationTimingOutcome.SUCCEEDED
                completion == ProviderPayloadCompletion.TRUNCATED_SAFE_PREFIX ||
                    finishReason == app.zhijuan.provider.common.ProviderFinishReason.LENGTH ->
                    GenerationTimingOutcome.TRUNCATED
                else -> GenerationTimingOutcome.FAILED_CLOSED
            },
            usage = usage,
        )
    }

    suspend fun settleIfOpen(
        outcome: GenerationTimingOutcome,
        usage: app.zhijuan.provider.common.ProviderUsage?,
    ) {
        require(outcome != GenerationTimingOutcome.SUCCEEDED) {
            "A successful body timing requires a completed Provider payload."
        }
        if (!providerOpened || bodyEnded) return
        recordTerminal(outcome, usage)
    }

    private suspend fun recordTerminal(
        outcome: GenerationTimingOutcome,
        usage: app.zhijuan.provider.common.ProviderUsage?,
    ) {
        check(providerOpened) { "Body-end timing requires Provider-open evidence." }
        check(!bodyEnded) { "Body-end timing was recorded twice." }
        record(
            milestone = GenerationTimingMilestone.BODY_STREAM_ENDED,
            outcome = outcome,
            characterCount = paragraph.decodedCharacterCount,
            inputTokenCount = usage?.inputTokens,
            outputTokenCount = usage?.outputTokens,
            totalTokenCount = usage?.totalTokens,
        )
        bodyEnded = true
    }

    private suspend fun record(
        milestone: GenerationTimingMilestone,
        outcome: GenerationTimingOutcome? = null,
        characterCount: Long? = null,
        inputTokenCount: Long? = null,
        outputTokenCount: Long? = null,
        totalTokenCount: Long? = null,
    ) {
        recorder.record(
            GenerationTimingObservation(
                context = context,
                milestone = milestone,
                mark = clock.capture(),
                outcome = outcome,
                characterCount = characterCount,
                inputTokenCount = inputTokenCount,
                outputTokenCount = outputTokenCount,
                totalTokenCount = totalTokenCount,
            ),
        )
    }
}
