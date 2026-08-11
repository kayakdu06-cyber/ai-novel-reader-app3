package app.zhijuan.core.diagnostics

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

enum class GenerationTimingMilestone {
    CHAPTER_REQUESTED,
    STAGE_QUEUED,
    STAGE_STARTED,
    LOCAL_CONTEXT_READY,
    PROVIDER_OPENED,
    FIRST_BYTE,
    FIRST_FULL_PARAGRAPH,
    BODY_STREAM_ENDED,
    MEMORY_STARTED,
    MEMORY_ENDED,
    TRACKING_STARTED,
    TRACKING_ENDED,
    CONSISTENCY_STARTED,
    CONSISTENCY_ENDED,
    REVISION_STARTED,
    REVISION_ENDED,
    COMMIT_STARTED,
    FORMAL_COMMIT,
    NEXT_CHAPTER_STARTED,
}

enum class GenerationTimingPhase {
    CHAPTER,
    CONTEXT,
    BODY,
    MEMORY,
    TRACKING,
    CONSISTENCY,
    REVISION,
    COMMIT,
}

enum class GenerationTimingOutcome {
    SUCCEEDED,
    FAILED_CLOSED,
    CANCELLED,
    NEEDS_ACTION,
    TRUNCATED,
    UNKNOWN,
}

enum class GenerationTimingFingerprintKind {
    RUN,
    BOOK,
    JOB,
    STAGE,
    ATTEMPT,
    CONNECTION,
    MODEL,
    BOOT,
}

data class GenerationTimingMark(
    val epochMillis: Long,
    val elapsedRealtimeMillis: Long,
    val bootFingerprint: String,
) {
    init {
        require(epochMillis >= 0L) { "Generation timing epoch is invalid." }
        require(elapsedRealtimeMillis >= 0L) { "Generation timing monotonic value is invalid." }
        require(bootFingerprint.matches(FINGERPRINT_PATTERN)) {
            "Generation timing boot fingerprint is invalid."
        }
    }
}

@ConsistentCopyVisibility
data class GenerationTimingCorrelations internal constructor(
    val runFingerprint: String,
    val bookFingerprint: String,
    val jobFingerprint: String?,
    val stageFingerprint: String?,
    val attemptFingerprint: String?,
) {
    init {
        require(runFingerprint.matches(FINGERPRINT_PATTERN))
        require(bookFingerprint.matches(FINGERPRINT_PATTERN))
        require(jobFingerprint == null || jobFingerprint.matches(FINGERPRINT_PATTERN))
        require(stageFingerprint == null || stageFingerprint.matches(FINGERPRINT_PATTERN))
        require(attemptFingerprint == null || attemptFingerprint.matches(FINGERPRINT_PATTERN))
        require(stageFingerprint != null || attemptFingerprint == null) {
            "An attempt fingerprint requires a stage fingerprint."
        }
        require(jobFingerprint != null || stageFingerprint == null) {
            "A stage fingerprint requires a job fingerprint."
        }
    }
}

@ConsistentCopyVisibility
data class GenerationTimingEvent internal constructor(
    val eventId: String,
    val phase: GenerationTimingPhase,
    val milestone: GenerationTimingMilestone,
    val outcome: GenerationTimingOutcome?,
    val mark: GenerationTimingMark,
    val correlations: GenerationTimingCorrelations,
    val attemptNo: Int?,
    val characterCount: Long?,
    val inputTokenCount: Long?,
    val outputTokenCount: Long?,
    val totalTokenCount: Long?,
    val connectionFingerprint: String?,
    val modelFingerprint: String?,
) {
    init {
        require(eventId.matches(EVENT_ID_PATTERN)) { "Generation timing event id is invalid." }
        require(attemptNo == null || attemptNo in 1..MAXIMUM_ATTEMPT_NO) {
            "Generation timing attempt number is invalid."
        }
        require(characterCount == null || characterCount >= 0L)
        require(inputTokenCount == null || inputTokenCount >= 0L)
        require(outputTokenCount == null || outputTokenCount >= 0L)
        require(totalTokenCount == null || totalTokenCount >= 0L)
        require(connectionFingerprint == null || connectionFingerprint.matches(FINGERPRINT_PATTERN))
        require(modelFingerprint == null || modelFingerprint.matches(FINGERPRINT_PATTERN))
        require((attemptNo == null) == (correlations.attemptFingerprint == null)) {
            "Attempt number and fingerprint must be present together."
        }
        if (milestone in ATTEMPT_MILESTONES) {
            require(correlations.attemptFingerprint != null) {
                "Provider timing events require an attempt fingerprint."
            }
        }
        if (milestone in TERMINAL_MILESTONES) {
            require(outcome != null) { "Terminal timing events require a finite outcome." }
        } else {
            require(outcome == null) { "Non-terminal timing events cannot claim an outcome." }
        }
        FIXED_MILESTONE_PHASES[milestone]?.let { expected ->
            require(phase == expected) { "Generation timing milestone uses the wrong phase." }
        }
    }

    override fun toString(): String = buildString {
        append("GenerationTimingEvent(phase=")
        append(phase.name)
        append(", milestone=")
        append(milestone.name)
        append(", outcome=")
        append(outcome?.name)
        append(", attemptNo=")
        append(attemptNo)
        append(", characterCount=")
        append(characterCount)
        append(", tokens=")
        append(totalTokenCount)
        append(", correlations=redacted)")
    }
}

class GenerationTimingEventFactory {
    fun create(
        phase: GenerationTimingPhase,
        milestone: GenerationTimingMilestone,
        mark: GenerationTimingMark,
        runId: String,
        bookId: String,
        jobId: String? = null,
        stageId: String? = null,
        attemptId: String? = null,
        attemptNo: Int? = null,
        outcome: GenerationTimingOutcome? = null,
        characterCount: Long? = null,
        inputTokenCount: Long? = null,
        outputTokenCount: Long? = null,
        totalTokenCount: Long? = null,
        connectionId: String? = null,
        modelId: String? = null,
    ): GenerationTimingEvent {
        require(runId.isNotEmpty() && bookId.isNotEmpty()) {
            "Generation timing run and book identities are required."
        }
        require(jobId != null || stageId == null)
        require(stageId != null || attemptId == null)
        val correlations = GenerationTimingCorrelations(
            runFingerprint = fingerprint(GenerationTimingFingerprintKind.RUN, runId),
            bookFingerprint = fingerprint(GenerationTimingFingerprintKind.BOOK, bookId),
            jobFingerprint = jobId?.let { fingerprint(GenerationTimingFingerprintKind.JOB, it) },
            stageFingerprint = stageId?.let { fingerprint(GenerationTimingFingerprintKind.STAGE, it) },
            attemptFingerprint = attemptId?.let { fingerprint(GenerationTimingFingerprintKind.ATTEMPT, it) },
        )
        val connectionFingerprint = connectionId
            ?.takeIf(String::isNotEmpty)
            ?.let { fingerprint(GenerationTimingFingerprintKind.CONNECTION, it) }
        val modelFingerprint = modelId
            ?.takeIf(String::isNotEmpty)
            ?.let { fingerprint(GenerationTimingFingerprintKind.MODEL, it) }
        return GenerationTimingEvent(
            eventId = eventId(
                phase = phase,
                milestone = milestone,
                correlations = correlations,
                attemptNo = attemptNo,
            ),
            phase = phase,
            milestone = milestone,
            outcome = outcome,
            mark = mark,
            correlations = correlations,
            attemptNo = attemptNo,
            characterCount = characterCount,
            inputTokenCount = inputTokenCount,
            outputTokenCount = outputTokenCount,
            totalTokenCount = totalTokenCount,
            connectionFingerprint = connectionFingerprint,
            modelFingerprint = modelFingerprint,
        )
    }

    fun fingerprint(kind: GenerationTimingFingerprintKind, rawValue: String): String {
        require(rawValue.isNotEmpty()) { "Generation timing correlation cannot be empty." }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(FINGERPRINT_DOMAIN)
        digest.update(0)
        digest.update(kind.name.toByteArray(StandardCharsets.UTF_8))
        digest.update(0)
        digest.update(rawValue.toByteArray(StandardCharsets.UTF_8))
        return digest.digest().copyOf(FINGERPRINT_BYTES).toHex()
    }

    fun restore(
        eventId: String,
        phase: GenerationTimingPhase,
        milestone: GenerationTimingMilestone,
        outcome: GenerationTimingOutcome?,
        mark: GenerationTimingMark,
        runFingerprint: String,
        bookFingerprint: String,
        jobFingerprint: String?,
        stageFingerprint: String?,
        attemptFingerprint: String?,
        attemptNo: Int?,
        characterCount: Long?,
        inputTokenCount: Long?,
        outputTokenCount: Long?,
        totalTokenCount: Long?,
        connectionFingerprint: String?,
        modelFingerprint: String?,
    ): GenerationTimingEvent {
        val correlations = GenerationTimingCorrelations(
            runFingerprint = runFingerprint,
            bookFingerprint = bookFingerprint,
            jobFingerprint = jobFingerprint,
            stageFingerprint = stageFingerprint,
            attemptFingerprint = attemptFingerprint,
        )
        require(eventId == eventId(phase, milestone, correlations, attemptNo)) {
            "Persisted generation timing event identity is invalid."
        }
        return GenerationTimingEvent(
            eventId = eventId,
            phase = phase,
            milestone = milestone,
            outcome = outcome,
            mark = mark,
            correlations = correlations,
            attemptNo = attemptNo,
            characterCount = characterCount,
            inputTokenCount = inputTokenCount,
            outputTokenCount = outputTokenCount,
            totalTokenCount = totalTokenCount,
            connectionFingerprint = connectionFingerprint,
            modelFingerprint = modelFingerprint,
        )
    }

    private fun eventId(
        phase: GenerationTimingPhase,
        milestone: GenerationTimingMilestone,
        correlations: GenerationTimingCorrelations,
        attemptNo: Int?,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(EVENT_ID_DOMAIN)
        listOf(
            correlations.runFingerprint,
            correlations.bookFingerprint,
            correlations.jobFingerprint ?: "-",
            correlations.stageFingerprint ?: "-",
            correlations.attemptFingerprint ?: "-",
            attemptNo?.toString() ?: "-",
            phase.name,
            milestone.name,
        ).forEach { value ->
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    private companion object {
        val FINGERPRINT_DOMAIN = "app.zhijuan.generation-timing.fingerprint.v1"
            .toByteArray(StandardCharsets.UTF_8)
        val EVENT_ID_DOMAIN = "app.zhijuan.generation-timing.event.v1"
            .toByteArray(StandardCharsets.UTF_8)
        const val FINGERPRINT_BYTES = 12
    }
}

fun interface GenerationTimingClock {
    fun capture(): GenerationTimingMark
}

class AndroidGenerationTimingClock(
    context: Context,
    private val epochMillis: () -> Long = System::currentTimeMillis,
    private val elapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime,
    private val processSessionId: String = UUID.randomUUID().toString(),
    private val eventFactory: GenerationTimingEventFactory = GenerationTimingEventFactory(),
) : GenerationTimingClock {
    private val resolver = context.applicationContext.contentResolver

    override fun capture(): GenerationTimingMark {
        val rawBootIdentity = runCatching {
            Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT)
        }.fold(
            onSuccess = { bootCount -> "boot-count:$bootCount" },
            onFailure = { "process-session:$processSessionId" },
        )
        return GenerationTimingMark(
            epochMillis = epochMillis(),
            elapsedRealtimeMillis = elapsedRealtimeMillis(),
            bootFingerprint = eventFactory.fingerprint(GenerationTimingFingerprintKind.BOOT, rawBootIdentity),
        )
    }
}

enum class GenerationTimingUnavailableReason {
    MISSING_EVENT,
    DIFFERENT_BOOT_SESSION,
    MONOTONIC_TIME_REGRESSION,
    TERMINAL_OUTCOME_NOT_SUCCESSFUL,
    CONFLICTING_EVENT,
    DEPENDENCY_UNAVAILABLE,
}

sealed interface GenerationTimingDuration {
    data class Available(val millis: Long) : GenerationTimingDuration {
        init {
            require(millis >= 0L)
        }
    }

    data class Unavailable(
        val reason: GenerationTimingUnavailableReason,
    ) : GenerationTimingDuration

    data object NotApplicable : GenerationTimingDuration
}

data class GenerationTimingReport(
    val queue: GenerationTimingDuration,
    val localPreparation: GenerationTimingDuration,
    val providerToFirstByte: GenerationTimingDuration,
    val providerToFirstParagraph: GenerationTimingDuration,
    val bodyStream: GenerationTimingDuration,
    val memory: GenerationTimingDuration,
    val tracking: GenerationTimingDuration,
    val consistency: GenerationTimingDuration,
    val revision: GenerationTimingDuration,
    val derivedTotal: GenerationTimingDuration,
    val commit: GenerationTimingDuration,
    val total: GenerationTimingDuration,
    val nextChapterDelay: GenerationTimingDuration,
)

class GenerationTimingReporter {
    fun report(events: List<GenerationTimingEvent>): GenerationTimingReport {
        val grouped = events.groupBy { it.phase to it.milestone }
        val runFingerprints = events.map { it.correlations.runFingerprint }.toSet()
        if (runFingerprints.size > 1) return conflictingReport()

        fun first(
            phase: GenerationTimingPhase,
            milestone: GenerationTimingMilestone,
        ): GenerationTimingEvent? = grouped[phase to milestone]
            ?.minByOrNull { it.mark.elapsedRealtimeMillis }

        fun successfulLast(
            phase: GenerationTimingPhase,
            milestone: GenerationTimingMilestone,
        ): GenerationTimingEvent? = grouped[phase to milestone]
                ?.filter { it.outcome == GenerationTimingOutcome.SUCCEEDED }
                ?.maxByOrNull { it.mark.elapsedRealtimeMillis }

        fun firstForStage(
            phase: GenerationTimingPhase,
            milestone: GenerationTimingMilestone,
            stageFingerprint: String?,
        ): GenerationTimingEvent? = stageFingerprint?.let { expected ->
            grouped[phase to milestone]
                ?.filter { it.correlations.stageFingerprint == expected }
                ?.minByOrNull { it.mark.elapsedRealtimeMillis }
        }

        fun firstForAttempt(
            phase: GenerationTimingPhase,
            milestone: GenerationTimingMilestone,
            attemptFingerprint: String?,
        ): GenerationTimingEvent? = attemptFingerprint?.let { expected ->
            grouped[phase to milestone]
                ?.filter { it.correlations.attemptFingerprint == expected }
                ?.minByOrNull { it.mark.elapsedRealtimeMillis }
        }

        val contextReady = first(GenerationTimingPhase.CONTEXT, GenerationTimingMilestone.LOCAL_CONTEXT_READY)
        val contextStageFingerprint = contextReady?.correlations?.stageFingerprint
            ?: first(GenerationTimingPhase.CONTEXT, GenerationTimingMilestone.STAGE_STARTED)
                ?.correlations
                ?.stageFingerprint
            ?: first(GenerationTimingPhase.CONTEXT, GenerationTimingMilestone.STAGE_QUEUED)
                ?.correlations
                ?.stageFingerprint
        val contextStarted = firstForStage(
            GenerationTimingPhase.CONTEXT,
            GenerationTimingMilestone.STAGE_STARTED,
            contextStageFingerprint,
        )
        val queue = duration(
            firstForStage(
                GenerationTimingPhase.CONTEXT,
                GenerationTimingMilestone.STAGE_QUEUED,
                contextStageFingerprint,
            ),
            contextStarted,
        )
        val localPreparation = duration(
            contextStarted,
            contextReady,
        )
        val firstByte = first(GenerationTimingPhase.BODY, GenerationTimingMilestone.FIRST_BYTE)
        val providerToFirstByte = duration(
            firstForAttempt(
                GenerationTimingPhase.BODY,
                GenerationTimingMilestone.PROVIDER_OPENED,
                firstByte?.correlations?.attemptFingerprint,
            ),
            firstByte,
        )
        val firstParagraph = first(
            GenerationTimingPhase.BODY,
            GenerationTimingMilestone.FIRST_FULL_PARAGRAPH,
        )
        val providerToFirstParagraph = duration(
            firstForAttempt(
                GenerationTimingPhase.BODY,
                GenerationTimingMilestone.PROVIDER_OPENED,
                firstParagraph?.correlations?.attemptFingerprint,
            ),
            firstParagraph,
        )
        val bodyEnds = grouped[
            GenerationTimingPhase.BODY to GenerationTimingMilestone.BODY_STREAM_ENDED
        ].orEmpty()
        val successfulBodyEnd = bodyEnds
            .filter { it.outcome == GenerationTimingOutcome.SUCCEEDED }
            .maxByOrNull { it.mark.elapsedRealtimeMillis }
        val bodyStream = if (bodyEnds.isNotEmpty() && successfulBodyEnd == null) {
            GenerationTimingDuration.Unavailable(
                GenerationTimingUnavailableReason.TERMINAL_OUTCOME_NOT_SUCCESSFUL,
            )
        } else {
            duration(
                firstForAttempt(
                    GenerationTimingPhase.BODY,
                    GenerationTimingMilestone.PROVIDER_OPENED,
                    successfulBodyEnd?.correlations?.attemptFingerprint,
                ),
                successfulBodyEnd,
            )
        }
        val memory = successfulPhase(
            grouped,
            GenerationTimingPhase.MEMORY,
            GenerationTimingMilestone.MEMORY_STARTED,
            GenerationTimingMilestone.MEMORY_ENDED,
        )
        val tracking = successfulPhase(
            grouped,
            GenerationTimingPhase.TRACKING,
            GenerationTimingMilestone.TRACKING_STARTED,
            GenerationTimingMilestone.TRACKING_ENDED,
        )
        val consistency = successfulPhase(
            grouped,
            GenerationTimingPhase.CONSISTENCY,
            GenerationTimingMilestone.CONSISTENCY_STARTED,
            GenerationTimingMilestone.CONSISTENCY_ENDED,
        )
        val revision = optionalSuccessfulPhase(
            grouped,
            GenerationTimingPhase.REVISION,
            GenerationTimingMilestone.REVISION_STARTED,
            GenerationTimingMilestone.REVISION_ENDED,
        )
        val derivedTotal = sumDurations(memory, tracking, consistency, revision)
        val commit = duration(
            first(GenerationTimingPhase.COMMIT, GenerationTimingMilestone.COMMIT_STARTED),
            successfulLast(GenerationTimingPhase.COMMIT, GenerationTimingMilestone.FORMAL_COMMIT),
        )
        val formalCommit = successfulLast(GenerationTimingPhase.COMMIT, GenerationTimingMilestone.FORMAL_COMMIT)
        val total = duration(
            first(GenerationTimingPhase.CHAPTER, GenerationTimingMilestone.CHAPTER_REQUESTED),
            formalCommit,
        )
        val nextChapterDelay = optionalDuration(
            formalCommit,
            first(GenerationTimingPhase.CHAPTER, GenerationTimingMilestone.NEXT_CHAPTER_STARTED),
        )
        return GenerationTimingReport(
            queue = queue,
            localPreparation = localPreparation,
            providerToFirstByte = providerToFirstByte,
            providerToFirstParagraph = providerToFirstParagraph,
            bodyStream = bodyStream,
            memory = memory,
            tracking = tracking,
            consistency = consistency,
            revision = revision,
            derivedTotal = derivedTotal,
            commit = commit,
            total = total,
            nextChapterDelay = nextChapterDelay,
        )
    }

    private fun successfulPhase(
        grouped: Map<Pair<GenerationTimingPhase, GenerationTimingMilestone>, List<GenerationTimingEvent>>,
        phase: GenerationTimingPhase,
        start: GenerationTimingMilestone,
        end: GenerationTimingMilestone,
    ): GenerationTimingDuration {
        val startEvent = grouped[phase to start]?.minByOrNull { it.mark.elapsedRealtimeMillis }
        val allEnds = grouped[phase to end].orEmpty()
        val endEvent = allEnds
            .filter { it.outcome == GenerationTimingOutcome.SUCCEEDED }
            .maxByOrNull { it.mark.elapsedRealtimeMillis }
        if (startEvent != null && allEnds.isNotEmpty() && endEvent == null) {
            return GenerationTimingDuration.Unavailable(
                GenerationTimingUnavailableReason.TERMINAL_OUTCOME_NOT_SUCCESSFUL,
            )
        }
        return duration(startEvent, endEvent)
    }

    private fun optionalSuccessfulPhase(
        grouped: Map<Pair<GenerationTimingPhase, GenerationTimingMilestone>, List<GenerationTimingEvent>>,
        phase: GenerationTimingPhase,
        start: GenerationTimingMilestone,
        end: GenerationTimingMilestone,
    ): GenerationTimingDuration {
        val starts = grouped[phase to start].orEmpty()
        val ends = grouped[phase to end].orEmpty()
        if (starts.isEmpty() && ends.isEmpty()) return GenerationTimingDuration.NotApplicable
        return successfulPhase(grouped, phase, start, end)
    }

    private fun duration(
        start: GenerationTimingEvent?,
        end: GenerationTimingEvent?,
    ): GenerationTimingDuration {
        if (start == null || end == null) {
            return GenerationTimingDuration.Unavailable(GenerationTimingUnavailableReason.MISSING_EVENT)
        }
        if (start.mark.bootFingerprint != end.mark.bootFingerprint) {
            return GenerationTimingDuration.Unavailable(
                GenerationTimingUnavailableReason.DIFFERENT_BOOT_SESSION,
            )
        }
        if (end.mark.elapsedRealtimeMillis < start.mark.elapsedRealtimeMillis) {
            return GenerationTimingDuration.Unavailable(
                GenerationTimingUnavailableReason.MONOTONIC_TIME_REGRESSION,
            )
        }
        return GenerationTimingDuration.Available(
            end.mark.elapsedRealtimeMillis - start.mark.elapsedRealtimeMillis,
        )
    }

    private fun optionalDuration(
        start: GenerationTimingEvent?,
        end: GenerationTimingEvent?,
    ): GenerationTimingDuration = when {
        start == null -> GenerationTimingDuration.Unavailable(GenerationTimingUnavailableReason.MISSING_EVENT)
        end == null -> GenerationTimingDuration.NotApplicable
        else -> duration(start, end)
    }

    private fun sumDurations(vararg durations: GenerationTimingDuration): GenerationTimingDuration {
        var total = 0L
        durations.forEach { duration ->
            when (duration) {
                is GenerationTimingDuration.Available -> total = Math.addExact(total, duration.millis)
                is GenerationTimingDuration.Unavailable -> return GenerationTimingDuration.Unavailable(
                    GenerationTimingUnavailableReason.DEPENDENCY_UNAVAILABLE,
                )
                GenerationTimingDuration.NotApplicable -> Unit
            }
        }
        return GenerationTimingDuration.Available(total)
    }

    private fun conflictingReport(): GenerationTimingReport {
        val unavailable = GenerationTimingDuration.Unavailable(
            GenerationTimingUnavailableReason.CONFLICTING_EVENT,
        )
        return GenerationTimingReport(
            queue = unavailable,
            localPreparation = unavailable,
            providerToFirstByte = unavailable,
            providerToFirstParagraph = unavailable,
            bodyStream = unavailable,
            memory = unavailable,
            tracking = unavailable,
            consistency = unavailable,
            revision = unavailable,
            derivedTotal = unavailable,
            commit = unavailable,
            total = unavailable,
            nextChapterDelay = unavailable,
        )
    }
}

class CompleteParagraphTimingTracker {
    var decodedCharacterCount: Long = 0L
        private set
    var firstCompleteParagraphObserved: Boolean = false
        private set

    private var paragraphHasContent = false

    fun observeDecoded(value: String): Boolean {
        if (value.isEmpty()) return false
        decodedCharacterCount = Math.addExact(
            decodedCharacterCount,
            value.codePointCount(0, value.length).toLong(),
        )
        if (firstCompleteParagraphObserved) return false
        value.forEach { character ->
            if (firstCompleteParagraphObserved) return@forEach
            when (character) {
                '\r', '\n' -> if (paragraphHasContent) firstCompleteParagraphObserved = true
                else -> if (!character.isWhitespace()) paragraphHasContent = true
            }
        }
        return firstCompleteParagraphObserved
    }

    fun completeBody(): Boolean {
        if (!firstCompleteParagraphObserved && paragraphHasContent) {
            firstCompleteParagraphObserved = true
            return true
        }
        return false
    }
}

private val FINGERPRINT_PATTERN = Regex("[0-9a-f]{24}")
private val EVENT_ID_PATTERN = Regex("[0-9a-f]{64}")
private const val MAXIMUM_ATTEMPT_NO = 1_000
private val ATTEMPT_MILESTONES = setOf(
    GenerationTimingMilestone.PROVIDER_OPENED,
    GenerationTimingMilestone.FIRST_BYTE,
    GenerationTimingMilestone.FIRST_FULL_PARAGRAPH,
    GenerationTimingMilestone.BODY_STREAM_ENDED,
)
private val TERMINAL_MILESTONES = setOf(
    GenerationTimingMilestone.BODY_STREAM_ENDED,
    GenerationTimingMilestone.MEMORY_ENDED,
    GenerationTimingMilestone.TRACKING_ENDED,
    GenerationTimingMilestone.CONSISTENCY_ENDED,
    GenerationTimingMilestone.REVISION_ENDED,
    GenerationTimingMilestone.FORMAL_COMMIT,
)
private val FIXED_MILESTONE_PHASES = mapOf(
    GenerationTimingMilestone.CHAPTER_REQUESTED to GenerationTimingPhase.CHAPTER,
    GenerationTimingMilestone.NEXT_CHAPTER_STARTED to GenerationTimingPhase.CHAPTER,
    GenerationTimingMilestone.LOCAL_CONTEXT_READY to GenerationTimingPhase.CONTEXT,
    GenerationTimingMilestone.FIRST_FULL_PARAGRAPH to GenerationTimingPhase.BODY,
    GenerationTimingMilestone.MEMORY_STARTED to GenerationTimingPhase.MEMORY,
    GenerationTimingMilestone.MEMORY_ENDED to GenerationTimingPhase.MEMORY,
    GenerationTimingMilestone.TRACKING_STARTED to GenerationTimingPhase.TRACKING,
    GenerationTimingMilestone.TRACKING_ENDED to GenerationTimingPhase.TRACKING,
    GenerationTimingMilestone.CONSISTENCY_STARTED to GenerationTimingPhase.CONSISTENCY,
    GenerationTimingMilestone.CONSISTENCY_ENDED to GenerationTimingPhase.CONSISTENCY,
    GenerationTimingMilestone.REVISION_STARTED to GenerationTimingPhase.REVISION,
    GenerationTimingMilestone.REVISION_ENDED to GenerationTimingPhase.REVISION,
    GenerationTimingMilestone.COMMIT_STARTED to GenerationTimingPhase.COMMIT,
    GenerationTimingMilestone.FORMAL_COMMIT to GenerationTimingPhase.COMMIT,
)
