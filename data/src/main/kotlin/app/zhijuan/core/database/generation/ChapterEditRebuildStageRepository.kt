package app.zhijuan.core.database.generation

import androidx.room.withTransaction
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.database.library.ChapterEditRebuildExecutionEntity
import app.zhijuan.core.database.library.ChapterEditRebuildBlocker
import app.zhijuan.core.database.library.ChapterEditRebuildExecutionStatus
import app.zhijuan.core.database.library.ChapterEditRebuildExecutionStepType
import app.zhijuan.core.database.library.ChapterEditRebuildPlanRepository
import app.zhijuan.core.database.library.ChapterEditRebuildPlanRequest
import app.zhijuan.core.database.library.ChapterEditRebuildPreparedStepState
import app.zhijuan.core.database.library.ChapterEditRebuildStepEntity
import app.zhijuan.core.database.library.ChapterEditRebuildTrackingRetirementEntity
import app.zhijuan.core.database.library.ChapterEditRebuildTrackingRetirementEvidenceV1
import app.zhijuan.core.database.library.ChapterEditRebuildStepState
import app.zhijuan.core.database.library.ChapterEditRebuildStepType
import app.zhijuan.core.database.library.FutureChapterPolicy
import app.zhijuan.core.database.library.AggregateStateWriteCommand
import app.zhijuan.core.database.library.AggregateStateWriterRepository
import app.zhijuan.core.database.library.chapterEditRebuildAggregateFingerprint
import app.zhijuan.core.database.library.chapterEditRebuildSummaryFingerprint
import app.zhijuan.core.database.library.chapterEditRebuildTrackingFingerprint
import app.zhijuan.core.database.memory.ChapterTrackingProjectionEntity
import app.zhijuan.core.model.GenerationJobType
import app.zhijuan.core.model.DerivedDataStatus
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.RequestAttemptStatus
import app.zhijuan.core.model.UsageLedgerStatus
import app.zhijuan.core.task.PromptBundleCatalogV1
import app.zhijuan.core.database.search.MemorySearchIndexWriterV1
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

data class ChapterEditRebuildStageBindingV1(
    val executionId: String,
    val stableFenceHash: String,
    val stepOrdinal: Int,
    val stepType: ChapterEditRebuildExecutionStepType,
    val chapterIndex: Int,
    val sourceChapterVersionId: String,
    val sourceContentHash: String,
) {
    init {
        require(IDENTIFIER.matches(executionId) && IDENTIFIER.matches(sourceChapterVersionId))
        require(HASH.matches(stableFenceHash) && HASH.matches(sourceContentHash))
        require(stepOrdinal in 1..MAX_REBUILD_STEPS && chapterIndex in 1..MAX_CHAPTER_INDEX)
    }

    override fun toString(): String =
        "ChapterEditRebuildStageBindingV1(ordinal=$stepOrdinal, type=$stepType, " +
            "chapterIndex=$chapterIndex, identifiers=redacted, hashes=redacted)"

    internal fun toJson(): JsonObject = JsonObject(
        linkedMapOf(
            "policyVersion" to JsonPrimitive(POLICY_VERSION),
            "executionId" to JsonPrimitive(executionId),
            "stableFenceHash" to JsonPrimitive(stableFenceHash),
            "stepOrdinal" to JsonPrimitive(stepOrdinal),
            "stepType" to JsonPrimitive(stepType.name),
            "chapterIndex" to JsonPrimitive(chapterIndex),
            "sourceChapterVersionId" to JsonPrimitive(sourceChapterVersionId),
            "sourceContentHash" to JsonPrimitive(sourceContentHash),
        ),
    )

    internal companion object {
        const val POLICY_VERSION = "zhijuan.chapter-edit-rebuild-stage.v1"
        private val KEYS = setOf(
            "policyVersion",
            "executionId",
            "stableFenceHash",
            "stepOrdinal",
            "stepType",
            "chapterIndex",
            "sourceChapterVersionId",
            "sourceContentHash",
        )

        fun parse(value: JsonObject): ChapterEditRebuildStageBindingV1 {
            require(value.keys == KEYS) { "Chapter-edit rebuild Stage binding has unexpected fields." }
            require(value.string("policyVersion") == POLICY_VERSION) {
                "Chapter-edit rebuild Stage policy is unsupported."
            }
            return ChapterEditRebuildStageBindingV1(
                executionId = value.string("executionId"),
                stableFenceHash = value.string("stableFenceHash"),
                stepOrdinal = value.int("stepOrdinal"),
                stepType = runCatching {
                    ChapterEditRebuildExecutionStepType.valueOf(value.string("stepType"))
                }.getOrElse { throw IllegalArgumentException("Chapter-edit rebuild Stage type is invalid.") },
                chapterIndex = value.int("chapterIndex"),
                sourceChapterVersionId = value.string("sourceChapterVersionId"),
                sourceContentHash = value.string("sourceContentHash"),
            )
        }
    }
}

data class ChapterEditRebuildEditedMemoryStageCommand(
    val executionId: String,
    val userIntentJson: String,
    val budgetSnapshotJson: String,
    val createdAt: Long,
) {
    override fun toString(): String =
        "ChapterEditRebuildEditedMemoryStageCommand(createdAt=$createdAt, identifiers=redacted, payloads=redacted)"
}

class ChapterEditRebuildEditedMemoryStageResult internal constructor(
    val jobId: String,
    val stageId: String,
    val stepOrdinal: Int,
    val chapterIndex: Int,
    val replayed: Boolean,
) {
    override fun toString(): String =
        "ChapterEditRebuildEditedMemoryStageResult(ordinal=$stepOrdinal, chapterIndex=$chapterIndex, " +
            "replayed=$replayed, identifiers=redacted)"
}

data class ChapterEditRebuildTrackingStageCommand(
    val executionId: String,
    val userIntentJson: String,
    val budgetSnapshotJson: String,
    val createdAt: Long,
) {
    override fun toString(): String =
        "ChapterEditRebuildTrackingStageCommand(createdAt=$createdAt, identifiers=redacted, payloads=redacted)"
}

data class ChapterEditRebuildRetainedTrackingStageCommand(
    val executionId: String,
    val targetStepOrdinal: Int,
    val userIntentJson: String,
    val budgetSnapshotJson: String,
    val createdAt: Long,
) {
    override fun toString(): String =
        "ChapterEditRebuildRetainedTrackingStageCommand(targetOrdinal=$targetStepOrdinal, " +
            "createdAt=$createdAt, identifiers=redacted, payloads=redacted)"
}

class ChapterEditRebuildTrackingStageResult internal constructor(
    val jobId: String,
    val stageId: String,
    val stepOrdinal: Int,
    val chapterIndex: Int,
    val replayed: Boolean,
) {
    override fun toString(): String =
        "ChapterEditRebuildTrackingStageResult(ordinal=$stepOrdinal, chapterIndex=$chapterIndex, " +
            "replayed=$replayed, identifiers=redacted)"
}

/**
 * Creates only the first edited-memory Job/Stage for an immutable chapter-edit rebuild execution.
 *
 * Tracking and aggregate progression intentionally remain outside this entry point. The rebuild binding is
 * persisted inside the Stage's immutable hashed input so Provider-open and commit can independently revalidate it.
 */
class ChapterEditRebuildStageRepository(
    private val database: ZhijuanDatabase,
) {
    suspend fun createEditedMemoryStage(
        command: ChapterEditRebuildEditedMemoryStageCommand,
    ): ChapterEditRebuildEditedMemoryStageResult {
        validateCommand(command)
        return database.withTransaction {
            val ready = requireEditedMemoryReady(
                executionId = command.executionId,
                observedAt = command.createdAt,
                requireInitialPlan = true,
            )
            val binding = ready.binding
            val jobId = deterministicJobId(binding)
            val stageId = deterministicStageId(binding)
            val setup = ChapterMemoryExtractionJobFactory.create(
                ChapterMemoryExtractionJobSpec(
                    jobId = jobId,
                    stageId = stageId,
                    bookId = ready.execution.bookId,
                    userIntentJson = command.userIntentJson,
                    budgetSnapshotJson = command.budgetSnapshotJson,
                    source = ready.source,
                    rebuildBinding = binding,
                    createdAt = command.createdAt,
                ),
            )
            val generation = database.generationDao()
            val existingJob = generation.findJob(jobId)
            val existingStage = generation.findStage(stageId)
            val replayed = existingJob != null || existingStage != null
            if (replayed) {
                require(existingJob != null && existingStage != null) {
                    "Chapter-edit rebuild Stage identity is only partially persisted."
                }
                requireExistingMatches(setup, existingJob, existingStage)
            } else {
                GenerationJobSetupRepository(database).create(setup)
            }
            val storedJob = requireNotNull(generation.findJob(jobId)) {
                "Chapter-edit rebuild Job was not persisted."
            }
            val storedStage = requireNotNull(generation.findStage(stageId)) {
                "Chapter-edit rebuild Stage was not persisted."
            }
            requireExistingMatches(setup, storedJob, storedStage)
            require(
                ChapterMemoryExtractionJobFactory.parseRebuildBindingIfPresent(storedStage) == binding &&
                    ChapterMemoryExtractionJobFactory.parseAndVerify(storedStage) == ready.source,
            ) { "Chapter-edit rebuild Stage failed its write-after-read verification." }
            ChapterEditRebuildEditedMemoryStageResult(
                jobId = jobId,
                stageId = stageId,
                stepOrdinal = binding.stepOrdinal,
                chapterIndex = binding.chapterIndex,
                replayed = replayed,
            )
        }
    }

    suspend fun createFirstTrackingStage(
        command: ChapterEditRebuildTrackingStageCommand,
    ): ChapterEditRebuildTrackingStageResult {
        validateCommand(
            executionId = command.executionId,
            userIntentJson = command.userIntentJson,
            budgetSnapshotJson = command.budgetSnapshotJson,
            createdAt = command.createdAt,
        )
        return database.withTransaction {
            val ready = requireFirstTrackingReady(command.executionId, command.createdAt)
            val binding = ready.binding
            val jobId = deterministicJobId(binding)
            val stageId = deterministicStageId(binding)
            val setup = ChapterTrackingProjectionJobFactory.create(
                ChapterTrackingProjectionJobSpec(
                    jobId = jobId,
                    stageId = stageId,
                    bookId = ready.execution.bookId,
                    userIntentJson = command.userIntentJson,
                    budgetSnapshotJson = command.budgetSnapshotJson,
                    source = ready.inputs.source,
                    rebuildBinding = binding,
                    createdAt = command.createdAt,
                ),
            )
            val generation = database.generationDao()
            val existingJob = generation.findJob(jobId)
            val existingStage = generation.findStage(stageId)
            val replayed = existingJob != null || existingStage != null
            if (replayed) {
                require(existingJob != null && existingStage != null) {
                    "Chapter-edit rebuild tracking identity is only partially persisted."
                }
                requireExistingMatches(setup, existingJob, existingStage)
            } else {
                GenerationJobSetupRepository(database).create(setup)
            }
            val storedJob = requireNotNull(generation.findJob(jobId)) {
                "Chapter-edit rebuild tracking Job was not persisted."
            }
            val storedStage = requireNotNull(generation.findStage(stageId)) {
                "Chapter-edit rebuild tracking Stage was not persisted."
            }
            requireExistingMatches(setup, storedJob, storedStage)
            require(
                ChapterTrackingProjectionJobFactory.parseRebuildBindingIfPresent(storedStage) == binding &&
                    ChapterTrackingProjectionJobFactory.parseAndVerify(storedStage) == ready.inputs.source,
            ) { "Chapter-edit rebuild tracking Stage failed its write-after-read verification." }
            ChapterEditRebuildTrackingStageResult(
                jobId = jobId,
                stageId = stageId,
                stepOrdinal = binding.stepOrdinal,
                chapterIndex = binding.chapterIndex,
                replayed = replayed,
            )
        }
    }

    /** Compatibility entry point for the first retained chapter (ordinal 4). */
    suspend fun createNextRetainedTrackingStage(
        command: ChapterEditRebuildTrackingStageCommand,
    ): ChapterEditRebuildTrackingStageResult = createRetainedTrackingStage(
        ChapterEditRebuildRetainedTrackingStageCommand(
            executionId = command.executionId,
            targetStepOrdinal = 4,
            userIntentJson = command.userIntentJson,
            budgetSnapshotJson = command.budgetSnapshotJson,
            createdAt = command.createdAt,
        ),
    )

    /**
     * Retires and replaces one explicitly selected retained tracking step.
     *
     * The target ordinal is part of the command so crash replay never guesses which later chapter should advance.
     * The immutable ledger, not the caller, remains authoritative for the target type, chapter and source.
     */
    suspend fun createRetainedTrackingStage(
        command: ChapterEditRebuildRetainedTrackingStageCommand,
    ): ChapterEditRebuildTrackingStageResult {
        validateCommand(
            executionId = command.executionId,
            userIntentJson = command.userIntentJson,
            budgetSnapshotJson = command.budgetSnapshotJson,
            createdAt = command.createdAt,
        )
        require(command.targetStepOrdinal in 4..MAX_REBUILD_STEPS && command.targetStepOrdinal % 2 == 0) {
            "Retained tracking target ordinal is invalid."
        }
        return database.withTransaction {
            val ready = requireRetainedTrackingReady(
                executionId = command.executionId,
                targetStepOrdinal = command.targetStepOrdinal,
                observedAt = command.createdAt,
            )
            val ledger = database.chapterEditRebuildExecutionDao()
            ledger.findTrackingRetirement(ready.execution.executionId, ready.step.stepOrdinal)?.let { existing ->
                return@withTransaction requireRetainedTrackingReplay(command, ready, existing)
            }

            val memory = database.memoryDao()
            val baseline = requireNotNull(memory.findTrackingProjection(ready.step.baselineTrackingProjectionId!!)) {
                "The retained tracking baseline disappeared before retirement."
            }
            require(
                baseline.status == DerivedDataStatus.VALID &&
                    baseline.projectionId == ready.step.baselineTrackingProjectionId &&
                    chapterEditRebuildTrackingFingerprint(baseline) == ready.step.baselineTrackingFingerprint &&
                    command.createdAt >= baseline.updatedAt,
            ) { "The retained tracking baseline no longer matches its immutable preparation evidence." }
            val timelines = memory.timelineEventsForVersion(ready.step.sourceChapterVersionId)
            require(
                timelines.size == baseline.timelineEventCount &&
                    timelines.all {
                        it.bookId == ready.execution.bookId &&
                            it.sourceChapterVersionId == ready.step.sourceChapterVersionId &&
                            it.status == DerivedDataStatus.VALID &&
                            command.createdAt >= it.createdAt
                    },
            ) { "The retained timeline baseline no longer matches its tracking header." }
            val timelineIdsJson = ChapterEditRebuildTrackingRetirementEvidenceV1.encodeTimelineIds(timelines)
            val timelineFingerprint = ChapterEditRebuildTrackingRetirementEvidenceV1.fingerprint(timelines)
            val searchIdentities = MemorySearchIndexWriterV1.identitiesForTimelineEvents(timelines)

            check(
                memory.retireTrackingProjection(
                    projectionId = baseline.projectionId,
                    chapterVersionId = baseline.chapterVersionId,
                    expectedUpdatedAt = baseline.updatedAt,
                    retiredAt = command.createdAt,
                ) == 1,
            ) { "The retained tracking baseline changed during retirement." }
            if (timelines.isNotEmpty()) {
                check(
                    memory.retireTimelineEvents(
                        chapterVersionId = ready.step.sourceChapterVersionId,
                        timelineEventIds = timelines.map { it.timelineEventId },
                    ) == timelines.size,
                ) { "The retained timeline baseline changed during retirement." }
            }
            database.memorySearchDao().deleteSources(searchIdentities)

            val inputs = ChapterTrackingProjectionSourceRepository(database)
                .loadForEditRebuild(ready.step.chapterId)
            require(
                inputs.source.chapterVersionId == ready.step.sourceChapterVersionId &&
                    inputs.source.chapterContentHash == ready.step.sourceContentHash &&
                    inputs.source.chapterIndex == ready.step.chapterIndex &&
                    inputs.summary.bookId == ready.execution.bookId,
            ) { "The retained chapter tracking source does not match its immutable rebuild step." }
            val binding = ready.step.toBinding(ready.execution)
            val setup = ChapterTrackingProjectionJobFactory.create(
                ChapterTrackingProjectionJobSpec(
                    jobId = deterministicJobId(binding),
                    stageId = deterministicStageId(binding),
                    bookId = ready.execution.bookId,
                    userIntentJson = command.userIntentJson,
                    budgetSnapshotJson = command.budgetSnapshotJson,
                    source = inputs.source,
                    rebuildBinding = binding,
                    createdAt = command.createdAt,
                ),
            )
            val generation = database.generationDao()
            require(generation.findJob(setup.jobId) == null && generation.findStage(setup.stages.single().stageId) == null) {
                "The retained tracking replacement identity is already occupied without retirement evidence."
            }
            GenerationJobSetupRepository(database).create(setup)
            val retired = requireNotNull(memory.findTrackingProjection(baseline.projectionId))
            val retirement = ChapterEditRebuildTrackingRetirementEntity(
                executionId = ready.execution.executionId,
                stepOrdinal = ready.step.stepOrdinal,
                bookId = ready.execution.bookId,
                chapterId = ready.step.chapterId,
                chapterIndex = ready.step.chapterIndex,
                sourceChapterVersionId = ready.step.sourceChapterVersionId,
                baselineTrackingProjectionId = baseline.projectionId,
                baselineTrackingFingerprint = requireNotNull(ready.step.baselineTrackingFingerprint),
                retiredTrackingFingerprint = chapterEditRebuildTrackingFingerprint(retired),
                baselineTimelineEventCount = timelines.size,
                baselineTimelineEventIdsJson = timelineIdsJson,
                baselineTimelineFingerprint = timelineFingerprint,
                replacementJobId = setup.jobId,
                replacementStageId = setup.stages.single().stageId,
                policyVersion = ChapterEditRebuildTrackingRetirementEvidenceV1.POLICY_VERSION,
                retiredAt = command.createdAt,
            )
            ledger.insertTrackingRetirement(retirement)
            requireRetirementEvidence(ready, retirement)
            val storedJob = requireNotNull(generation.findJob(retirement.replacementJobId))
            val storedStage = requireNotNull(generation.findStage(retirement.replacementStageId))
            requireExistingMatches(setup, storedJob, storedStage)
            ChapterEditRebuildTrackingStageResult(
                jobId = retirement.replacementJobId,
                stageId = retirement.replacementStageId,
                stepOrdinal = retirement.stepOrdinal,
                chapterIndex = retirement.chapterIndex,
                replayed = false,
            )
        }
    }

    suspend fun loadTrackingInputsForBoundStage(
        stageId: String,
        observedAt: Long,
    ): ChapterTrackingProjectionInputs {
        require(IDENTIFIER.matches(stageId) && observedAt >= 0L) {
            "Chapter-edit rebuild tracking input request is invalid."
        }
        return database.withTransaction {
            val generation = database.generationDao()
            val stage = requireNotNull(generation.findStage(stageId)) {
                "Chapter-edit rebuild tracking Stage does not exist."
            }
            val job = requireNotNull(generation.findJob(stage.jobId)) {
                "Chapter-edit rebuild tracking Job does not exist."
            }
            val binding = requireNotNull(
                ChapterTrackingProjectionJobFactory.parseRebuildBindingIfPresent(stage),
            ) { "Generation Stage is not a chapter-edit rebuild tracking Stage." }
            requireTrackingStageAllowed(stage, job, binding, observedAt)
            val inputs = ChapterTrackingProjectionSourceRepository(database).loadForEditRebuild(stage.targetId)
            require(
                ChapterTrackingProjectionJobFactory.parseAndVerify(stage) == inputs.source &&
                    inputs.source.chapterVersionId == binding.sourceChapterVersionId &&
                    inputs.source.chapterContentHash == binding.sourceContentHash &&
                    inputs.source.chapterIndex == binding.chapterIndex,
            ) { "Chapter-edit rebuild tracking Stage inputs are no longer authoritative." }
            inputs
        }
    }

    internal suspend fun requireProviderOpenAllowedIfBound(
        stage: GenerationStageEntity,
        job: GenerationJobEntity,
        observedAt: Long,
    ): Boolean = requireAllowedIfBound(stage, job, observedAt)

    internal suspend fun requireCommitAllowedIfBound(
        stage: GenerationStageEntity,
        job: GenerationJobEntity,
        observedAt: Long,
    ): Boolean = requireAllowedIfBound(stage, job, observedAt)

    internal suspend fun commitAggregateAfterTrackingIfBound(
        stage: GenerationStageEntity,
        job: GenerationJobEntity,
        committedAt: Long,
        replayed: Boolean,
    ): Boolean {
        val binding = ChapterTrackingProjectionJobFactory.parseRebuildBindingIfPresent(stage) ?: return false
        database.withTransaction {
            val (execution, steps) = requirePreparedExecution(binding.executionId, committedAt)
            val trackingStep = steps.singleOrNull {
                it.stepOrdinal == binding.stepOrdinal &&
                    it.stepType == ChapterEditRebuildExecutionStepType.TRACKING
            } ?: throw IllegalArgumentException("Chapter-edit rebuild tracking step is missing.")
            if (trackingStep.chapterIndex == execution.firstAffectedChapterIndex) {
                val memoryStep = steps.getOrNull(0)
                    ?: throw IllegalArgumentException("Chapter-edit rebuild memory predecessor is missing.")
                requireFirstAggregateBaselineAvailable(
                    execution,
                    steps,
                    allowCommittedAggregate = replayed,
                )
                requireMemoryPredecessorSatisfied(execution, memoryStep)
            } else {
                val retirement = requireNotNull(
                    database.chapterEditRebuildExecutionDao().findTrackingRetirement(
                        execution.executionId,
                        trackingStep.stepOrdinal,
                    ),
                ) { "Retained tracking commit lost its retirement evidence." }
                val ready = RetainedTrackingAuthorization(execution, trackingStep, trackingStep.toBinding(execution))
                requireRetirementEvidence(ready, retirement)
                requireTrackingAndAggregateCompleted(
                    execution = execution,
                    steps = steps,
                    chapterIndex = Math.subtractExact(trackingStep.chapterIndex, 1),
                    observedAt = committedAt,
                )
                requireRetainedAggregateBaselineAvailable(
                    execution = execution,
                    steps = steps,
                    trackingStep = trackingStep,
                    allowCommittedAggregate = replayed,
                )
            }
            requireCurrentRangeMatches(execution, steps, committedAt)
            require(
                binding == trackingStep.toBinding(execution) &&
                    trackingStep.stepType == ChapterEditRebuildExecutionStepType.TRACKING &&
                    job.jobId == deterministicJobId(binding) &&
                    stage.stageId == deterministicStageId(binding) &&
                    stage.jobId == job.jobId &&
                    job.bookId == execution.bookId &&
                    stage.status == if (replayed) {
                        GenerationStageStatus.SUCCEEDED
                    } else {
                        GenerationStageStatus.COMMITTING
                    },
            ) { "Chapter-edit rebuild tracking aggregate authorization is invalid." }
            val source = ChapterTrackingProjectionJobFactory.parseAndVerify(stage)
            requireStoredTrackingProjectionMatches(trackingStep, stage, source)
            val planRepository = ChapterEditRebuildPlanRepository(database)
            val plan = planRepository.plan(execution.planRequest())
            val trackingPlan = plan.steps.singleOrNull {
                it.type == ChapterEditRebuildStepType.REBUILD_STORY_TRACKING &&
                    it.chapterIndex == trackingStep.chapterIndex
            } ?: throw IllegalArgumentException("Current rebuild plan lost the committed tracking step.")
            val aggregatePlan = plan.steps.singleOrNull {
                it.type == ChapterEditRebuildStepType.REBUILD_AGGREGATE_STATE &&
                    it.chapterIndex == trackingStep.chapterIndex
            } ?: throw IllegalArgumentException("Current rebuild plan lost the tracking aggregate step.")
            require(trackingPlan.state == ChapterEditRebuildStepState.ALREADY_SATISFIED) {
                "Committed rebuild tracking is not authoritative in the current plan."
            }
            if (replayed) {
                require(aggregatePlan.state == ChapterEditRebuildStepState.ALREADY_SATISFIED) {
                    "Completed rebuild tracking Stage lost its aggregate projection."
                }
            } else {
                require(aggregatePlan.state == ChapterEditRebuildStepState.READY) {
                    "The rebuild aggregate step is not ready after tracking commit."
                }
                AggregateStateWriterRepository(database).write(
                    AggregateStateWriteCommand(
                        plan = plan,
                        chapterIndex = trackingStep.chapterIndex,
                        generatedAt = committedAt,
                    ),
                )
                val advanced = planRepository.plan(execution.planRequest())
                require(
                    advanced.steps.single {
                        it.type == ChapterEditRebuildStepType.REBUILD_AGGREGATE_STATE &&
                            it.chapterIndex == trackingStep.chapterIndex
                    }.state == ChapterEditRebuildStepState.ALREADY_SATISFIED,
                ) { "Rebuild aggregate write did not advance the current plan." }
            }
        }
        return true
    }

    internal suspend fun authorizedRetainedTrackingProjectionIdsForPlan(
        request: ChapterEditRebuildPlanRequest,
        trackingByVersion: Map<String, ChapterTrackingProjectionEntity>,
    ): Set<String> {
        val ledger = database.chapterEditRebuildExecutionDao()
        val execution = ledger.findExecutionForEditedVersion(request.editedVersionId) ?: return emptySet()
        if (
            execution.bookId != request.bookId ||
            execution.editedChapterId != request.editedChapterId ||
            execution.futureChapterPolicy != request.futureChapterPolicy ||
            execution.status != ChapterEditRebuildExecutionStatus.PREPARED
        ) return emptySet()
        val steps = ledger.stepsForExecution(execution.executionId)
        if (steps.map(ChapterEditRebuildStepEntity::stepOrdinal) != (1..steps.size).toList()) return emptySet()
        val generation = database.generationDao()
        val authorized = linkedSetOf<String>()
        var expectedStepOrdinal = 4
        var expectedChapterIndex = Math.addExact(execution.firstAffectedChapterIndex, 1)
        var previousRetiredAt = execution.preparedAt
        for (retirement in ledger.trackingRetirementsForExecution(execution.executionId)) {
            if (
                retirement.stepOrdinal != expectedStepOrdinal ||
                retirement.chapterIndex != expectedChapterIndex ||
                retirement.retiredAt < previousRetiredAt
            ) break
            val step = steps.singleOrNull {
                it.stepOrdinal == retirement.stepOrdinal &&
                    it.stepType == ChapterEditRebuildExecutionStepType.TRACKING &&
                    it.chapterIndex > execution.firstAffectedChapterIndex
            } ?: break
            val projection = trackingByVersion[step.sourceChapterVersionId] ?: break
            val binding = step.toBinding(execution)
            val stage = generation.findStage(retirement.replacementStageId) ?: break
            val job = generation.findJob(retirement.replacementJobId) ?: break
            val matches = try {
                requireRetirementEvidence(
                    RetainedTrackingAuthorization(execution, step, binding),
                    retirement,
                )
                require(
                    retirement.replacementJobId == deterministicJobId(binding) &&
                        retirement.replacementStageId == deterministicStageId(binding) &&
                        projection.status == DerivedDataStatus.VALID &&
                        projection.generationStageId == stage.stageId &&
                        stage.jobId == job.jobId &&
                        stage.status in setOf(
                            GenerationStageStatus.COMMITTING,
                            GenerationStageStatus.SUCCEEDED,
                        ) &&
                        job.status in setOf(
                            GenerationJobStatus.RUNNING,
                            GenerationJobStatus.PAUSING,
                            GenerationJobStatus.COMPLETED,
                        ) &&
                        job.currentStageId == stage.stageId &&
                        ChapterTrackingProjectionJobFactory.parseRebuildBindingIfPresent(stage) == binding,
                )
                val source = ChapterTrackingProjectionJobFactory.parseAndVerify(stage)
                requireStoredTrackingProjectionMatches(step, stage, source)
                true
            } catch (_: IllegalArgumentException) {
                false
            } catch (_: IllegalStateException) {
                false
            }
            if (!matches) break
            authorized += projection.projectionId
            expectedStepOrdinal = Math.addExact(expectedStepOrdinal, 2)
            expectedChapterIndex = Math.addExact(expectedChapterIndex, 1)
            previousRetiredAt = retirement.retiredAt
        }
        return authorized
    }

    private suspend fun requireAllowedIfBound(
        stage: GenerationStageEntity,
        job: GenerationJobEntity,
        observedAt: Long,
    ): Boolean {
        val memoryBinding = ChapterMemoryExtractionJobFactory.parseRebuildBindingIfPresent(stage)
        val trackingBinding = ChapterTrackingProjectionJobFactory.parseRebuildBindingIfPresent(stage)
        require(memoryBinding == null || trackingBinding == null) {
            "A generation Stage cannot carry two rebuild bindings."
        }
        val binding = memoryBinding ?: trackingBinding ?: return false
        if (trackingBinding != null) {
            requireTrackingStageAllowed(stage, job, binding, observedAt)
            return true
        }
        database.withTransaction {
            val ready = requireEditedMemoryReady(
                executionId = binding.executionId,
                observedAt = observedAt,
                requireInitialPlan = stage.status != GenerationStageStatus.SUCCEEDED,
            )
            require(binding == ready.binding) {
                "Chapter-edit rebuild Stage binding no longer matches the authorized step."
            }
            require(
                job.jobId == deterministicJobId(binding) &&
                    stage.stageId == deterministicStageId(binding) &&
                    stage.jobId == job.jobId &&
                    job.bookId == ready.execution.bookId &&
                    job.jobType == GenerationJobType.CONTINUE_BOOK &&
                    job.promptBundleVersion == PromptBundleCatalogV1.BUNDLE_VERSION &&
                    ChapterMemoryExtractionJobFactory.parseAndVerify(stage) == ready.source,
            ) { "Chapter-edit rebuild Job or Stage identity is not authoritative." }
        }
        return true
    }

    private suspend fun requireFirstTrackingReady(
        executionId: String,
        observedAt: Long,
    ): TrackingAuthorization {
        val (execution, steps) = requirePreparedExecution(executionId, observedAt)
        require(steps.size >= 3) { "Chapter-edit rebuild execution lost its first chapter steps." }
        val memoryStep = steps[0]
        val trackingStep = steps[1]
        require(
            memoryStep.stepOrdinal == 1 && memoryStep.stepType == ChapterEditRebuildExecutionStepType.EDITED_MEMORY &&
                trackingStep.stepOrdinal == 2 && trackingStep.stepType == ChapterEditRebuildExecutionStepType.TRACKING &&
                trackingStep.preparedState == ChapterEditRebuildPreparedStepState.PENDING &&
                trackingStep.chapterIndex == execution.firstAffectedChapterIndex &&
                trackingStep.sourceChapterVersionId == execution.editedChapterVersionId &&
                trackingStep.baselineTrackingProjectionId == null &&
                trackingStep.baselineTrackingFingerprint == null,
        ) { "The first tracking step is not eligible for dynamic Stage creation." }
        requireFirstAggregateBaselineAvailable(execution, steps, allowCommittedAggregate = false)
        requireMemoryPredecessorSatisfied(execution, memoryStep)
        requireCurrentRangeMatches(execution, steps, observedAt)
        val plan = ChapterEditRebuildPlanRepository(database).plan(execution.planRequest())
        val planStep = plan.steps.singleOrNull {
            it.type == ChapterEditRebuildStepType.REBUILD_STORY_TRACKING &&
                it.chapterIndex == trackingStep.chapterIndex
        } ?: throw IllegalArgumentException("Current rebuild plan lost the first tracking step.")
        require(
            planStep.state == ChapterEditRebuildStepState.READY ||
                (
                    planStep.state == ChapterEditRebuildStepState.BLOCKED &&
                        planStep.blocker == ChapterEditRebuildBlocker.TRACKING_ORDER_GUARD
                    ),
        ) { "The first tracking step has a blocker that the rebuild permit cannot override." }
        val inputs = ChapterTrackingProjectionSourceRepository(database).loadForEditRebuild(trackingStep.chapterId)
        require(
            inputs.source.chapterVersionId == trackingStep.sourceChapterVersionId &&
                inputs.source.chapterContentHash == trackingStep.sourceContentHash &&
                inputs.source.chapterIndex == trackingStep.chapterIndex &&
                inputs.summary.bookId == execution.bookId,
        ) { "The first tracking source does not match its immutable rebuild step." }
        val binding = trackingStep.toBinding(execution)
        return TrackingAuthorization(execution, trackingStep, binding, inputs)
    }

    private suspend fun requireRetainedTrackingReady(
        executionId: String,
        targetStepOrdinal: Int,
        observedAt: Long,
    ): RetainedTrackingAuthorization {
        val (execution, steps) = requirePreparedExecution(executionId, observedAt)
        require(execution.lastAffectedChapterIndex > execution.firstAffectedChapterIndex) {
            "Chapter-edit rebuild has no retained later chapter."
        }
        val target = steps.singleOrNull {
            it.stepOrdinal == targetStepOrdinal && it.stepType == ChapterEditRebuildExecutionStepType.TRACKING
        } ?: throw IllegalArgumentException("Chapter-edit rebuild retained tracking step is missing.")
        val targetAggregate = steps.singleOrNull {
            it.stepOrdinal == Math.addExact(target.stepOrdinal, 1) &&
                it.stepType == ChapterEditRebuildExecutionStepType.AGGREGATE
        } ?: throw IllegalArgumentException("Chapter-edit rebuild retained aggregate step is missing.")
        val expectedTrackingOrdinal = Math.multiplyExact(
            Math.subtractExact(target.chapterIndex, execution.firstAffectedChapterIndex),
            2,
        ) + 2
        require(
            target.chapterIndex > execution.firstAffectedChapterIndex &&
                target.stepOrdinal == expectedTrackingOrdinal &&
                targetAggregate.chapterIndex == target.chapterIndex &&
                target.preparedState == ChapterEditRebuildPreparedStepState.PENDING && target.needsProvider &&
                target.baselineTrackingProjectionId != null && target.baselineTrackingFingerprint != null &&
                targetAggregate.preparedState == ChapterEditRebuildPreparedStepState.PENDING &&
                targetAggregate.baselineAggregateStateId == null &&
                targetAggregate.baselineAggregateFingerprint == null,
        ) { "The retained tracking boundary is not eligible for controlled retirement." }
        requireCurrentRangeMatches(execution, steps, observedAt)
        val plan = requireTrackingAndAggregateCompleted(
            execution = execution,
            steps = steps,
            chapterIndex = Math.subtractExact(target.chapterIndex, 1),
            observedAt = observedAt,
        )
        val targetPlan = plan.steps.single {
            it.type == ChapterEditRebuildStepType.REBUILD_STORY_TRACKING && it.chapterIndex == target.chapterIndex
        }
        val existingRetirement = database.chapterEditRebuildExecutionDao()
            .findTrackingRetirement(execution.executionId, target.stepOrdinal)
        if (existingRetirement == null) {
            require(
                targetPlan.state == ChapterEditRebuildStepState.BLOCKED &&
                    targetPlan.blocker == ChapterEditRebuildBlocker.DERIVED_VERSION_SLOT_OCCUPIED,
            ) { "The retained tracking slot no longer exposes its prepared baseline blocker." }
        } else {
            require(
                targetPlan.state == ChapterEditRebuildStepState.READY ||
                    targetPlan.state == ChapterEditRebuildStepState.ALREADY_SATISFIED ||
                    (
                        targetPlan.state == ChapterEditRebuildStepState.BLOCKED &&
                            targetPlan.blocker == ChapterEditRebuildBlocker.TRACKING_ORDER_GUARD
                        ),
            ) { "The retired tracking slot moved to an unsupported plan state." }
        }
        val aggregatePresent = database.memoryDao().validAggregateStatesFromChapter(
            execution.bookId,
            target.chapterIndex,
        ).any { it.throughChapterIndex == target.chapterIndex }
        require(!aggregatePresent || existingRetirement != null && targetPlan.state == ChapterEditRebuildStepState.ALREADY_SATISFIED) {
            "The retained chapter aggregate slot changed before tracking retirement."
        }
        return RetainedTrackingAuthorization(execution, target, target.toBinding(execution))
    }

    private suspend fun requireRetainedTrackingReplay(
        command: ChapterEditRebuildRetainedTrackingStageCommand,
        ready: RetainedTrackingAuthorization,
        retirement: ChapterEditRebuildTrackingRetirementEntity,
    ): ChapterEditRebuildTrackingStageResult {
        require(command.createdAt == retirement.retiredAt) {
            "Retained tracking retirement replay changed its operation time."
        }
        requireRetirementEvidence(ready, retirement)
        val generation = database.generationDao()
        val job = requireNotNull(generation.findJob(retirement.replacementJobId))
        val stage = requireNotNull(generation.findStage(retirement.replacementStageId))
        val binding = ready.step.toBinding(ready.execution)
        val source = ChapterTrackingProjectionJobFactory.parseAndVerify(stage)
        val setup = ChapterTrackingProjectionJobFactory.create(
            ChapterTrackingProjectionJobSpec(
                jobId = deterministicJobId(binding),
                stageId = deterministicStageId(binding),
                bookId = ready.execution.bookId,
                userIntentJson = command.userIntentJson,
                budgetSnapshotJson = command.budgetSnapshotJson,
                source = source,
                rebuildBinding = binding,
                createdAt = command.createdAt,
            ),
        )
        requireExistingMatches(setup, job, stage)
        if (database.memoryDao().findTrackingProjectionForVersion(ready.step.sourceChapterVersionId) == null) {
            ChapterTrackingProjectionSourceRepository(database).requireCurrentMatchesForEditRebuild(
                source,
                ready.execution.bookId,
            )
        }
        return ChapterEditRebuildTrackingStageResult(
            jobId = retirement.replacementJobId,
            stageId = retirement.replacementStageId,
            stepOrdinal = retirement.stepOrdinal,
            chapterIndex = retirement.chapterIndex,
            replayed = true,
        )
    }

    private suspend fun requireRetirementEvidence(
        ready: RetainedTrackingAuthorization,
        retirement: ChapterEditRebuildTrackingRetirementEntity,
    ) {
        require(
            retirement.executionId == ready.execution.executionId &&
                retirement.stepOrdinal == ready.step.stepOrdinal &&
                retirement.bookId == ready.execution.bookId &&
                retirement.chapterId == ready.step.chapterId &&
                retirement.chapterIndex == ready.step.chapterIndex &&
                retirement.sourceChapterVersionId == ready.step.sourceChapterVersionId &&
                retirement.baselineTrackingProjectionId == ready.step.baselineTrackingProjectionId &&
                retirement.baselineTrackingFingerprint == ready.step.baselineTrackingFingerprint &&
                retirement.replacementJobId == deterministicJobId(ready.binding) &&
                retirement.replacementStageId == deterministicStageId(ready.binding) &&
                retirement.policyVersion == ChapterEditRebuildTrackingRetirementEvidenceV1.POLICY_VERSION,
        ) { "Retained tracking retirement no longer matches its immutable rebuild step." }
        val baseline = requireNotNull(
            database.memoryDao().findTrackingProjection(retirement.baselineTrackingProjectionId),
        )
        require(
            baseline.status == DerivedDataStatus.STALE &&
                baseline.updatedAt == retirement.retiredAt &&
                chapterEditRebuildTrackingFingerprint(baseline) == retirement.retiredTrackingFingerprint,
        ) { "Retired tracking history no longer matches its immutable retirement fingerprint." }
        val timelineIds = ChapterEditRebuildTrackingRetirementEvidenceV1.decodeTimelineIds(
            retirement.baselineTimelineEventIdsJson,
        )
        require(timelineIds.size == retirement.baselineTimelineEventCount)
        val timelines = if (timelineIds.isEmpty()) emptyList() else database.memoryDao().timelineEventsByIds(timelineIds)
        require(
            timelines.size == timelineIds.size &&
                timelines.map { it.timelineEventId } == timelineIds &&
                timelines.all {
                    it.bookId == retirement.bookId &&
                        it.sourceChapterVersionId == retirement.sourceChapterVersionId &&
                        it.status == DerivedDataStatus.STALE
                } &&
                ChapterEditRebuildTrackingRetirementEvidenceV1.fingerprint(timelines) ==
                retirement.baselineTimelineFingerprint,
        ) { "Retired timeline history no longer matches its immutable identity evidence." }
        val search = database.memorySearchDao()
        MemorySearchIndexWriterV1.identitiesForTimelineEvents(timelines).forEach { identity ->
            require(search.findBySource(identity.bookId, identity.sourceType.name, identity.sourceId) == null) {
                "A retired timeline search document is still visible."
            }
        }
    }

    private suspend fun requireTrackingStageAllowed(
        stage: GenerationStageEntity,
        job: GenerationJobEntity,
        binding: ChapterEditRebuildStageBindingV1,
        observedAt: Long,
    ) {
        val execution = requireNotNull(
            database.chapterEditRebuildExecutionDao().findExecution(binding.executionId),
        ) { "Chapter-edit rebuild execution does not exist." }
        if (binding.chapterIndex == execution.firstAffectedChapterIndex) {
            requireFirstTrackingStageAllowed(stage, job, binding, observedAt)
        } else {
            requireRetainedTrackingStageAllowed(stage, job, binding, observedAt)
        }
    }

    private suspend fun requireFirstTrackingStageAllowed(
        stage: GenerationStageEntity,
        job: GenerationJobEntity,
        binding: ChapterEditRebuildStageBindingV1,
        observedAt: Long,
    ) {
        database.withTransaction {
            val (execution, steps) = requirePreparedExecution(binding.executionId, observedAt)
            val memoryStep = steps.getOrNull(0)
                ?: throw IllegalArgumentException("Chapter-edit rebuild memory predecessor is missing.")
            val trackingStep = steps.getOrNull(1)
                ?: throw IllegalArgumentException("Chapter-edit rebuild first tracking step is missing.")
            requireFirstAggregateBaselineAvailable(
                execution,
                steps,
                allowCommittedAggregate = stage.status == GenerationStageStatus.SUCCEEDED,
            )
            requireMemoryPredecessorSatisfied(execution, memoryStep)
            requireCurrentRangeMatches(execution, steps, observedAt)
            require(
                trackingStep.stepOrdinal == 2 &&
                    trackingStep.stepType == ChapterEditRebuildExecutionStepType.TRACKING &&
                    trackingStep.preparedState == ChapterEditRebuildPreparedStepState.PENDING &&
                    trackingStep.baselineTrackingProjectionId == null &&
                    binding == trackingStep.toBinding(execution) &&
                    job.jobId == deterministicJobId(binding) &&
                    stage.stageId == deterministicStageId(binding) &&
                    stage.jobId == job.jobId &&
                    job.bookId == execution.bookId &&
                    job.jobType == GenerationJobType.REBUILD_MEMORY &&
                    job.promptBundleVersion == PromptBundleCatalogV1.BUNDLE_VERSION,
            ) { "Chapter-edit rebuild tracking Job or Stage identity is not authoritative." }
            val source = ChapterTrackingProjectionJobFactory.parseAndVerify(stage)
            if (stage.status == GenerationStageStatus.SUCCEEDED) {
                requireStoredTrackingProjectionMatches(trackingStep, stage, source)
            } else {
                ChapterTrackingProjectionSourceRepository(database).requireCurrentMatchesForEditRebuild(
                    source,
                    execution.bookId,
                )
            }
        }
    }

    private suspend fun requireRetainedTrackingStageAllowed(
        stage: GenerationStageEntity,
        job: GenerationJobEntity,
        binding: ChapterEditRebuildStageBindingV1,
        observedAt: Long,
    ) {
        database.withTransaction {
            val (execution, steps) = requirePreparedExecution(binding.executionId, observedAt)
            val trackingStep = steps.singleOrNull {
                it.stepOrdinal == binding.stepOrdinal &&
                    it.stepType == ChapterEditRebuildExecutionStepType.TRACKING
            } ?: throw IllegalArgumentException("Retained chapter tracking step is missing.")
            val expectedTrackingOrdinal = Math.multiplyExact(
                Math.subtractExact(trackingStep.chapterIndex, execution.firstAffectedChapterIndex),
                2,
            ) + 2
            require(
                trackingStep.chapterIndex > execution.firstAffectedChapterIndex &&
                    trackingStep.stepOrdinal == expectedTrackingOrdinal &&
                    trackingStep.preparedState == ChapterEditRebuildPreparedStepState.PENDING &&
                    trackingStep.baselineTrackingProjectionId != null &&
                    trackingStep.baselineTrackingFingerprint != null &&
                    binding == trackingStep.toBinding(execution),
            ) { "Retained chapter tracking Stage is outside the authorized boundary." }
            val retirement = requireNotNull(
                database.chapterEditRebuildExecutionDao().findTrackingRetirement(
                    execution.executionId,
                    trackingStep.stepOrdinal,
                ),
            ) { "Retained chapter tracking Stage has no retirement evidence." }
            requireRetirementEvidence(
                RetainedTrackingAuthorization(execution, trackingStep, binding),
                retirement,
            )
            requireCurrentRangeMatches(execution, steps, observedAt)
            val plan = requireTrackingAndAggregateCompleted(
                execution = execution,
                steps = steps,
                chapterIndex = Math.subtractExact(trackingStep.chapterIndex, 1),
                observedAt = observedAt,
            )
            val trackingPlan = plan.steps.single {
                it.type == ChapterEditRebuildStepType.REBUILD_STORY_TRACKING &&
                    it.chapterIndex == trackingStep.chapterIndex
            }
            if (stage.status == GenerationStageStatus.SUCCEEDED) {
                require(trackingPlan.state == ChapterEditRebuildStepState.ALREADY_SATISFIED) {
                    "Completed retained tracking Stage is not authoritative in the current plan."
                }
            } else {
                require(
                    trackingPlan.state == ChapterEditRebuildStepState.READY ||
                        (
                            trackingPlan.state == ChapterEditRebuildStepState.BLOCKED &&
                                trackingPlan.blocker == ChapterEditRebuildBlocker.TRACKING_ORDER_GUARD
                            ),
                ) { "Retained tracking Stage has a blocker that its retirement evidence cannot override." }
            }
            requireRetainedAggregateBaselineAvailable(
                execution = execution,
                steps = steps,
                trackingStep = trackingStep,
                allowCommittedAggregate = stage.status == GenerationStageStatus.SUCCEEDED,
            )
            require(
                job.jobId == retirement.replacementJobId &&
                    stage.stageId == retirement.replacementStageId &&
                    job.jobId == deterministicJobId(binding) &&
                    stage.stageId == deterministicStageId(binding) &&
                    stage.jobId == job.jobId &&
                    job.bookId == execution.bookId &&
                    job.jobType == GenerationJobType.REBUILD_MEMORY &&
                    job.promptBundleVersion == PromptBundleCatalogV1.BUNDLE_VERSION,
            ) { "Retained tracking Job or Stage identity is not authoritative." }
            val source = ChapterTrackingProjectionJobFactory.parseAndVerify(stage)
            if (stage.status == GenerationStageStatus.SUCCEEDED) {
                requireStoredTrackingProjectionMatches(trackingStep, stage, source)
            } else {
                ChapterTrackingProjectionSourceRepository(database).requireCurrentMatchesForEditRebuild(
                    source,
                    execution.bookId,
                )
            }
        }
    }

    private suspend fun requireTrackingAndAggregateCompleted(
        execution: ChapterEditRebuildExecutionEntity,
        steps: List<ChapterEditRebuildStepEntity>,
        chapterIndex: Int,
        observedAt: Long,
    ): app.zhijuan.core.database.library.ChapterEditRebuildPlan {
        require(chapterIndex in execution.firstAffectedChapterIndex until execution.lastAffectedChapterIndex) {
            "Chapter-edit rebuild predecessor chapter is outside the affected range."
        }
        val trackingStep = steps.singleOrNull {
            it.stepType == ChapterEditRebuildExecutionStepType.TRACKING &&
                it.chapterIndex == chapterIndex
        } ?: throw IllegalArgumentException("Chapter-edit rebuild tracking predecessor is missing.")
        val aggregateStep = steps.singleOrNull {
            it.stepType == ChapterEditRebuildExecutionStepType.AGGREGATE &&
                it.chapterIndex == chapterIndex &&
                it.stepOrdinal == trackingStep.stepOrdinal + 1
        } ?: throw IllegalArgumentException("Chapter-edit rebuild aggregate predecessor is missing.")
        val projection = requireNotNull(
            database.memoryDao().findTrackingProjectionForVersion(trackingStep.sourceChapterVersionId),
        ) { "The chapter tracking predecessor is not complete." }
        val binding = trackingStep.toBinding(execution)
        val stage = requireNotNull(database.generationDao().findStage(deterministicStageId(binding)))
        val job = requireNotNull(database.generationDao().findJob(deterministicJobId(binding)))
        if (chapterIndex > execution.firstAffectedChapterIndex) {
            val retirement = requireNotNull(
                database.chapterEditRebuildExecutionDao().findTrackingRetirement(
                    execution.executionId,
                    trackingStep.stepOrdinal,
                ),
            ) { "The retained tracking predecessor lost its retirement evidence." }
            requireRetirementEvidence(
                RetainedTrackingAuthorization(execution, trackingStep, binding),
                retirement,
            )
            require(retirement.retiredAt <= observedAt) {
                "The retained tracking predecessor retirement is newer than the observed time."
            }
        }
        require(
            projection.generationStageId == stage.stageId &&
                projection.updatedAt <= observedAt &&
                stage.status == GenerationStageStatus.SUCCEEDED &&
                stage.updatedAt <= observedAt &&
                job.status == GenerationJobStatus.COMPLETED &&
                job.finishedAt != null && job.finishedAt <= observedAt &&
                job.currentStageId == stage.stageId &&
                ChapterTrackingProjectionJobFactory.parseRebuildBindingIfPresent(stage) == binding,
        ) { "The chapter tracking predecessor is not the authorized rebuild result." }
        requireStoredTrackingProjectionMatches(
            trackingStep,
            stage,
            ChapterTrackingProjectionJobFactory.parseAndVerify(stage),
        )
        val plan = ChapterEditRebuildPlanRepository(database).plan(execution.planRequest())
        require(
            plan.steps.single {
                it.type == ChapterEditRebuildStepType.REBUILD_STORY_TRACKING &&
                    it.chapterIndex == chapterIndex
            }.state == ChapterEditRebuildStepState.ALREADY_SATISFIED &&
                plan.steps.single {
                    it.type == ChapterEditRebuildStepType.REBUILD_AGGREGATE_STATE &&
                        it.chapterIndex == chapterIndex
                }.state == ChapterEditRebuildStepState.ALREADY_SATISFIED,
        ) { "The chapter tracking and aggregate predecessor is incomplete." }
        val aggregate = database.memoryDao().validAggregateStatesFromChapter(execution.bookId, chapterIndex)
            .singleOrNull { it.throughChapterIndex == chapterIndex }
            ?: throw IllegalArgumentException("The chapter aggregate predecessor is missing.")
        require(
            aggregateStep.preparedState == ChapterEditRebuildPreparedStepState.PENDING &&
                aggregate.createdAt <= observedAt && aggregate.updatedAt <= observedAt,
        ) { "The chapter aggregate predecessor is newer than the observed time." }
        return plan
    }

    private suspend fun requireRetainedAggregateBaselineAvailable(
        execution: ChapterEditRebuildExecutionEntity,
        steps: List<ChapterEditRebuildStepEntity>,
        trackingStep: ChapterEditRebuildStepEntity,
        allowCommittedAggregate: Boolean,
    ) {
        val aggregateStep = steps.singleOrNull {
            it.stepType == ChapterEditRebuildExecutionStepType.AGGREGATE &&
                it.chapterIndex == trackingStep.chapterIndex
        } ?: throw IllegalArgumentException("Retained chapter aggregate step is missing.")
        require(
            trackingStep.chapterIndex > execution.firstAffectedChapterIndex &&
                aggregateStep.stepOrdinal == trackingStep.stepOrdinal + 1 &&
                aggregateStep.preparedState == ChapterEditRebuildPreparedStepState.PENDING &&
                aggregateStep.baselineAggregateStateId == null &&
                aggregateStep.baselineAggregateFingerprint == null,
        ) { "Retained chapter aggregate step is not eligible for dynamic progression." }
        if (!allowCommittedAggregate) {
            require(
                database.memoryDao().validAggregateStatesFromChapter(
                    execution.bookId,
                    trackingStep.chapterIndex,
                ).none { it.throughChapterIndex == trackingStep.chapterIndex },
            ) { "Retained chapter aggregate slot changed after Stage creation." }
        }
    }

    private suspend fun requireFirstAggregateBaselineAvailable(
        execution: ChapterEditRebuildExecutionEntity,
        steps: List<ChapterEditRebuildStepEntity>,
        allowCommittedAggregate: Boolean,
    ) {
        val aggregateStep = steps.getOrNull(2)
            ?: throw IllegalArgumentException("Chapter-edit rebuild first aggregate step is missing.")
        require(
            aggregateStep.stepOrdinal == 3 &&
                aggregateStep.stepType == ChapterEditRebuildExecutionStepType.AGGREGATE &&
                aggregateStep.chapterIndex == execution.firstAffectedChapterIndex &&
                aggregateStep.sourceChapterVersionId == execution.editedChapterVersionId &&
                aggregateStep.preparedState == ChapterEditRebuildPreparedStepState.PENDING &&
                aggregateStep.baselineAggregateStateId == null &&
                aggregateStep.baselineAggregateFingerprint == null,
        ) { "The first aggregate step is not eligible for dynamic progression." }
        if (!allowCommittedAggregate) {
            require(
                database.memoryDao().validAggregateStatesFromChapter(
                    execution.bookId,
                    aggregateStep.chapterIndex,
                ).none { it.throughChapterIndex == aggregateStep.chapterIndex },
            ) { "The first aggregate slot changed after rebuild preparation." }
        }
    }

    private suspend fun requireStoredTrackingProjectionMatches(
        step: ChapterEditRebuildStepEntity,
        stage: GenerationStageEntity,
        source: ChapterTrackingProjectionSourceV1,
    ) {
        val projection = requireNotNull(
            database.memoryDao().findTrackingProjectionForVersion(step.sourceChapterVersionId),
        ) { "Completed rebuild tracking Stage lost its projection." }
        require(
            projection.generationStageId == stage.stageId &&
                projection.chapterIndex == step.chapterIndex &&
                projection.sourceChapterContentHash == step.sourceContentHash &&
                projection.sourceMemorySnapshotHash == source.memorySnapshotHash &&
                projection.priorForeshadowSnapshotHash == source.priorForeshadowSnapshotHash,
        ) { "Completed rebuild tracking Stage no longer matches its projection." }
    }

    private suspend fun requirePreparedExecution(
        executionId: String,
        observedAt: Long,
    ): Pair<ChapterEditRebuildExecutionEntity, List<ChapterEditRebuildStepEntity>> {
        require(IDENTIFIER.matches(executionId) && observedAt >= 0L) {
            "Chapter-edit rebuild authorization input is invalid."
        }
        val ledger = database.chapterEditRebuildExecutionDao()
        val execution = requireNotNull(ledger.findExecution(executionId)) {
            "Chapter-edit rebuild execution does not exist."
        }
        require(
            execution.status == ChapterEditRebuildExecutionStatus.PREPARED &&
                execution.futureChapterPolicy == FutureChapterPolicy.KEEP_EXISTING &&
                execution.policyVersion == EXECUTION_POLICY_VERSION &&
                observedAt >= execution.preparedAt,
        ) { "Chapter-edit rebuild execution is not eligible for Stage creation." }
        val steps = ledger.stepsForExecution(execution.executionId)
        require(steps.isNotEmpty() && steps.map(ChapterEditRebuildStepEntity::stepOrdinal) == (1..steps.size).toList()) {
            "Chapter-edit rebuild step ledger is incomplete or unordered."
        }
        return execution to steps
    }

    private suspend fun requireMemoryPredecessorSatisfied(
        execution: ChapterEditRebuildExecutionEntity,
        step: ChapterEditRebuildStepEntity,
    ) {
        require(
            step.stepOrdinal == 1 &&
                step.stepType == ChapterEditRebuildExecutionStepType.EDITED_MEMORY &&
                step.chapterIndex == execution.firstAffectedChapterIndex &&
                step.sourceChapterVersionId == execution.editedChapterVersionId,
        ) { "Chapter-edit rebuild memory predecessor is invalid." }
        if (step.preparedState == ChapterEditRebuildPreparedStepState.SATISFIED) {
            require(preparedBaselineStillValid(step)) {
                "Prepared edited-memory baseline no longer matches its immutable fingerprint."
            }
            return
        }
        require(step.preparedState == ChapterEditRebuildPreparedStepState.PENDING)
        requireBoundMemoryCompletion(execution, step)
    }

    private suspend fun requireBoundMemoryCompletion(
        execution: ChapterEditRebuildExecutionEntity,
        step: ChapterEditRebuildStepEntity,
    ) {
        val binding = step.toBinding(execution)
        val generation = database.generationDao()
        val job = requireNotNull(generation.findJob(deterministicJobId(binding))) {
            "Edited-memory predecessor Job has not completed."
        }
        val stage = requireNotNull(generation.findStage(deterministicStageId(binding))) {
            "Edited-memory predecessor Stage has not completed."
        }
        require(
            job.status == GenerationJobStatus.COMPLETED &&
                job.currentStageId == stage.stageId &&
                job.bookId == execution.bookId &&
                stage.status == GenerationStageStatus.SUCCEEDED &&
                stage.jobId == job.jobId &&
                ChapterMemoryExtractionJobFactory.parseRebuildBindingIfPresent(stage) == binding &&
                ChapterMemoryExtractionJobFactory.parseAndVerify(stage) == ChapterMemoryExtractionSourceV1(
                    chapterVersionId = step.sourceChapterVersionId,
                    chapterContentHash = step.sourceContentHash,
                    chapterId = step.chapterId,
                    chapterIndex = step.chapterIndex,
                ),
        ) { "Edited-memory predecessor is not the authorized successful Stage." }
        val output = parseCompletedMemoryOutput(
            requireNotNull(stage.outputReferenceJson) { "Completed edited-memory Stage lost its output reference." },
        )
        val attempt = requireNotNull(generation.findAttempt(output.attemptId)) {
            "Completed edited-memory Stage lost its Attempt."
        }
        val usage = requireNotNull(generation.findUsageForAttempt(output.attemptId)) {
            "Completed edited-memory Stage lost its Usage ledger."
        }
        require(
            attempt.jobId == job.jobId &&
                attempt.stageId == stage.stageId &&
                attempt.status == RequestAttemptStatus.SUCCEEDED &&
                attempt.outputHash == output.rawOutputHash &&
                generation.attemptsForStage(stage.stageId).lastOrNull()?.attemptId == attempt.attemptId &&
                usage.attemptId == attempt.attemptId &&
                usage.bookId == execution.bookId &&
                usage.status == UsageLedgerStatus.FINAL &&
                usage.finalizedAt != null &&
                output.chapterVersionId == step.sourceChapterVersionId &&
                output.sourceChapterContentHash == step.sourceContentHash,
        ) { "Edited-memory predecessor audit evidence is incomplete or stale." }
        val memory = database.memoryDao()
        val summary = requireNotNull(memory.findSummaryForVersion(step.sourceChapterVersionId)) {
            "Completed edited-memory Stage lost its summary."
        }
        val events = memory.entityEventsForVersion(step.sourceChapterVersionId)
        val facts = memory.canonFactsForVersion(step.sourceChapterVersionId)
        require(
            summary.chapterSummaryId == output.summaryId &&
                summary.bookId == execution.bookId &&
                summary.chapterIndex == step.chapterIndex &&
                output.eventCount == events.size &&
                output.factCount == facts.size,
        ) { "Edited-memory predecessor output no longer matches authoritative memory rows." }
    }

    private fun parseCompletedMemoryOutput(value: String): CompletedMemoryOutput {
        val root = runCatching { STRICT_JSON.parseToJsonElement(value) as JsonObject }
            .getOrElse { throw IllegalArgumentException("Completed edited-memory output reference is invalid JSON.") }
        require(root.keys == MEMORY_OUTPUT_KEYS) {
            "Completed edited-memory output reference has unexpected fields."
        }
        require(
            root.int("schemaVersion") == 1 &&
                root.string("outputSchemaId") == ChapterMemoryExtractionJobFactory.OUTPUT_SCHEMA_ID,
        ) { "Completed edited-memory output reference has an unsupported schema." }
        return CompletedMemoryOutput(
            attemptId = root.string("attemptId"),
            rawOutputHash = root.string("rawOutputHash"),
            extractionContentHash = root.string("extractionContentHash"),
            payloadHash = root.string("payloadHash"),
            chapterVersionId = root.string("chapterVersionId"),
            sourceChapterContentHash = root.string("sourceChapterContentHash"),
            summaryId = root.string("summaryId"),
            eventCount = root.int("eventCount"),
            factCount = root.int("factCount"),
        ).also { output ->
            require(
                IDENTIFIER.matches(output.attemptId) && IDENTIFIER.matches(output.chapterVersionId) &&
                    IDENTIFIER.matches(output.summaryId) &&
                    listOf(
                        output.rawOutputHash,
                        output.extractionContentHash,
                        output.payloadHash,
                        output.sourceChapterContentHash,
                    ).all(HASH::matches) &&
                    output.eventCount in 0..MAX_MEMORY_ROWS && output.factCount in 0..MAX_MEMORY_ROWS,
            ) { "Completed edited-memory output reference is invalid." }
        }
    }

    private suspend fun requireEditedMemoryReady(
        executionId: String,
        observedAt: Long,
        requireInitialPlan: Boolean,
    ): EditedMemoryAuthorization {
        require(IDENTIFIER.matches(executionId) && observedAt >= 0L) {
            "Chapter-edit rebuild authorization input is invalid."
        }
        val ledger = database.chapterEditRebuildExecutionDao()
        val execution = requireNotNull(ledger.findExecution(executionId)) {
            "Chapter-edit rebuild execution does not exist."
        }
        require(
            execution.status == ChapterEditRebuildExecutionStatus.PREPARED &&
                execution.futureChapterPolicy == FutureChapterPolicy.KEEP_EXISTING &&
                execution.policyVersion == EXECUTION_POLICY_VERSION &&
                observedAt >= execution.preparedAt,
        ) { "Chapter-edit rebuild execution is not eligible for Stage creation." }
        val steps = ledger.stepsForExecution(execution.executionId)
        require(steps.isNotEmpty() && steps.map(ChapterEditRebuildStepEntity::stepOrdinal) == (1..steps.size).toList()) {
            "Chapter-edit rebuild step ledger is incomplete or unordered."
        }
        val target = steps.firstOrNull { it.preparedState == ChapterEditRebuildPreparedStepState.PENDING }
            ?: throw IllegalArgumentException("Chapter-edit rebuild execution has no pending step.")
        require(
            target.stepType == ChapterEditRebuildExecutionStepType.EDITED_MEMORY &&
                target.needsProvider &&
                target.chapterIndex == execution.firstAffectedChapterIndex &&
                target.sourceChapterVersionId == execution.editedChapterVersionId &&
                target.createdAt == execution.preparedAt,
        ) { "The first pending rebuild step is not the edited-memory step." }
        for (predecessor in steps.takeWhile { it.stepOrdinal < target.stepOrdinal }) {
            require(preparedBaselineStillValid(predecessor)) {
                "A satisfied rebuild predecessor no longer matches its prepared baseline."
            }
        }
        require(target.baselineSummaryId == null && target.baselineSummaryFingerprint == null) {
            "A pending edited-memory step cannot carry a satisfied summary baseline."
        }
        requireCurrentRangeMatches(execution, steps, observedAt)
        require(database.memoryDao().findSummaryForVersion(target.sourceChapterVersionId) == null || !requireInitialPlan) {
            "The edited-memory step was satisfied outside its authorized Stage."
        }
        if (requireInitialPlan) {
            val currentPlan = ChapterEditRebuildPlanRepository(database).plan(
                ChapterEditRebuildPlanRequest(
                    bookId = execution.bookId,
                    editedChapterId = execution.editedChapterId,
                    editedVersionId = execution.editedChapterVersionId,
                    futureChapterPolicy = execution.futureChapterPolicy,
                ),
            )
            require(currentPlan.planHash == execution.initialPlanHash) {
                "Chapter-edit rebuild execution changed before its first Stage opened."
            }
        }
        val binding = ChapterEditRebuildStageBindingV1(
            executionId = execution.executionId,
            stableFenceHash = execution.stableFenceHash,
            stepOrdinal = target.stepOrdinal,
            stepType = target.stepType,
            chapterIndex = target.chapterIndex,
            sourceChapterVersionId = target.sourceChapterVersionId,
            sourceContentHash = target.sourceContentHash,
        )
        return EditedMemoryAuthorization(
            execution = execution,
            binding = binding,
            source = ChapterMemoryExtractionSourceV1(
                chapterVersionId = target.sourceChapterVersionId,
                chapterContentHash = target.sourceContentHash,
                chapterId = target.chapterId,
                chapterIndex = target.chapterIndex,
            ),
        )
    }

    private suspend fun preparedBaselineStillValid(step: ChapterEditRebuildStepEntity): Boolean {
        if (step.preparedState != ChapterEditRebuildPreparedStepState.SATISFIED) return false
        return when (step.stepType) {
            ChapterEditRebuildExecutionStepType.EDITED_MEMORY ->
                database.memoryDao().findSummaryForVersion(step.sourceChapterVersionId)?.let { summary ->
                    summary.chapterSummaryId == step.baselineSummaryId &&
                        chapterEditRebuildSummaryFingerprint(summary) == step.baselineSummaryFingerprint
                } == true
            ChapterEditRebuildExecutionStepType.TRACKING ->
                database.memoryDao().findTrackingProjectionForVersion(step.sourceChapterVersionId)?.let { tracking ->
                    tracking.projectionId == step.baselineTrackingProjectionId &&
                        chapterEditRebuildTrackingFingerprint(tracking) == step.baselineTrackingFingerprint
                } == true
            ChapterEditRebuildExecutionStepType.AGGREGATE ->
                database.memoryDao().validAggregateStatesFromChapter(step.bookId, step.chapterIndex)
                    .singleOrNull { it.throughChapterIndex == step.chapterIndex }
                    ?.let { aggregate ->
                        aggregate.aggregateStateId == step.baselineAggregateStateId &&
                            chapterEditRebuildAggregateFingerprint(aggregate) == step.baselineAggregateFingerprint
                    } == true
        }
    }

    private suspend fun requireCurrentRangeMatches(
        execution: ChapterEditRebuildExecutionEntity,
        steps: List<ChapterEditRebuildStepEntity>,
        observedAt: Long,
    ) {
        val sources = database.chapterEditRebuildExecutionDao().currentSourcesFromChapter(
            execution.bookId,
            execution.firstAffectedChapterIndex,
        )
        val expectedByChapter = steps.groupBy(ChapterEditRebuildStepEntity::chapterIndex)
        require(
            sources.size == execution.lastAffectedChapterIndex - execution.firstAffectedChapterIndex + 1 &&
                expectedByChapter.keys == sources.map { it.chapterIndex }.toSet(),
        ) { "Chapter-edit rebuild current range changed after preparation." }
        sources.forEach { source ->
            val expected = requireNotNull(expectedByChapter[source.chapterIndex]?.firstOrNull())
            require(
                expected.bookId == execution.bookId &&
                    expected.chapterId == source.chapterId &&
                    expected.sourceChapterVersionId == source.chapterVersionId &&
                    expected.sourceContentHash == source.contentHash &&
                    source.versionCreatedAt <= observedAt &&
                    expectedByChapter.getValue(source.chapterIndex).all {
                        it.chapterId == expected.chapterId &&
                            it.sourceChapterVersionId == expected.sourceChapterVersionId &&
                            it.sourceContentHash == expected.sourceContentHash
                    },
            ) { "Chapter-edit rebuild current source no longer matches its immutable step ledger." }
        }
    }

    private fun requireExistingMatches(
        setup: GenerationJobSetup,
        job: GenerationJobEntity,
        stage: GenerationStageEntity,
    ) {
        val expected = setup.stages.single()
        require(
            job.jobId == setup.jobId &&
                job.bookId == setup.bookId &&
                job.jobType == setup.jobType &&
                job.userIntentJson == setup.userIntentJson &&
                job.budgetSnapshotJson == setup.budgetSnapshotJson &&
                job.promptBundleVersion == setup.promptBundleVersion &&
                job.currentStageId == stage.stageId &&
                job.createdAt == setup.createdAt &&
                stage.stageId == expected.stageId &&
                stage.jobId == job.jobId &&
                stage.phase == expected.phase &&
                stage.targetType == expected.targetType &&
                stage.targetId == expected.targetId &&
                stage.inputVersionHash == expected.inputVersionHash &&
                stage.idempotencyKey == expected.idempotencyKey &&
                stage.maxAttempts == expected.maxAttempts &&
                stage.inputSourcesJson == expected.inputSourcesJson &&
                stage.createdAt == setup.createdAt,
        ) { "Chapter-edit rebuild deterministic Job/Stage identity is occupied by different provenance." }
    }

    private fun validateCommand(command: ChapterEditRebuildEditedMemoryStageCommand) {
        validateCommand(
            executionId = command.executionId,
            userIntentJson = command.userIntentJson,
            budgetSnapshotJson = command.budgetSnapshotJson,
            createdAt = command.createdAt,
        )
    }

    private fun validateCommand(
        executionId: String,
        userIntentJson: String,
        budgetSnapshotJson: String,
        createdAt: Long,
    ) {
        require(IDENTIFIER.matches(executionId) && createdAt >= 0L)
        requireStrictJson(userIntentJson, "User intent")
        requireStrictJson(budgetSnapshotJson, "Budget snapshot")
    }

    private fun requireStrictJson(value: String, label: String) {
        require(value.toByteArray(Charsets.UTF_8).size in 2..MAX_JSON_BYTES) { "$label JSON size is invalid." }
        require(runCatching { STRICT_JSON.parseToJsonElement(value) }.isSuccess) { "$label JSON is invalid." }
    }

    private data class EditedMemoryAuthorization(
        val execution: ChapterEditRebuildExecutionEntity,
        val binding: ChapterEditRebuildStageBindingV1,
        val source: ChapterMemoryExtractionSourceV1,
    )

    private data class TrackingAuthorization(
        val execution: ChapterEditRebuildExecutionEntity,
        val step: ChapterEditRebuildStepEntity,
        val binding: ChapterEditRebuildStageBindingV1,
        val inputs: ChapterTrackingProjectionInputs,
    )

    private data class RetainedTrackingAuthorization(
        val execution: ChapterEditRebuildExecutionEntity,
        val step: ChapterEditRebuildStepEntity,
        val binding: ChapterEditRebuildStageBindingV1,
    )

    private data class CompletedMemoryOutput(
        val attemptId: String,
        val rawOutputHash: String,
        val extractionContentHash: String,
        val payloadHash: String,
        val chapterVersionId: String,
        val sourceChapterContentHash: String,
        val summaryId: String,
        val eventCount: Int,
        val factCount: Int,
    )

    private companion object {
        const val EXECUTION_POLICY_VERSION = "zhijuan.chapter-edit-rebuild-execution.v1"
        const val MAX_JSON_BYTES = 65_536
        const val MAX_MEMORY_ROWS = 512
        val STRICT_JSON = Json { isLenient = false }
        val MEMORY_OUTPUT_KEYS = setOf(
            "schemaVersion",
            "outputSchemaId",
            "attemptId",
            "rawOutputHash",
            "extractionContentHash",
            "payloadHash",
            "chapterVersionId",
            "sourceChapterContentHash",
            "summaryId",
            "eventCount",
            "factCount",
        )
    }
}

private fun ChapterEditRebuildExecutionEntity.planRequest() = ChapterEditRebuildPlanRequest(
    bookId = bookId,
    editedChapterId = editedChapterId,
    editedVersionId = editedChapterVersionId,
    futureChapterPolicy = futureChapterPolicy,
)

private fun ChapterEditRebuildStepEntity.toBinding(
    execution: ChapterEditRebuildExecutionEntity,
) = ChapterEditRebuildStageBindingV1(
    executionId = execution.executionId,
    stableFenceHash = execution.stableFenceHash,
    stepOrdinal = stepOrdinal,
    stepType = stepType,
    chapterIndex = chapterIndex,
    sourceChapterVersionId = sourceChapterVersionId,
    sourceContentHash = sourceContentHash,
)

internal fun deterministicJobId(binding: ChapterEditRebuildStageBindingV1): String =
    "rebuild-job-${rebuildStageIdentityHash("job", binding)}"

internal fun deterministicStageId(binding: ChapterEditRebuildStageBindingV1): String =
    "rebuild-stage-${rebuildStageIdentityHash("stage", binding)}"

private fun rebuildStageIdentityHash(
    role: String,
    binding: ChapterEditRebuildStageBindingV1,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    listOf(
        role,
        ChapterEditRebuildStageBindingV1.POLICY_VERSION,
        binding.executionId,
        binding.stableFenceHash,
        binding.stepOrdinal,
        binding.stepType.name,
        binding.chapterIndex,
        binding.sourceChapterVersionId,
        binding.sourceContentHash,
    ).forEach { value ->
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        try {
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        } finally {
            bytes.fill(0)
        }
    }
    return digest.digest().joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}

private fun JsonObject.string(key: String): String =
    (get(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
        ?: throw IllegalArgumentException("Chapter-edit rebuild Stage field is missing: $key")

private fun JsonObject.int(key: String): Int =
    (get(key) as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull
        ?: throw IllegalArgumentException("Chapter-edit rebuild Stage field is missing: $key")

private val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
private val HASH = Regex("[0-9a-f]{64}")
private const val MAX_REBUILD_STEPS = 30_001
private const val MAX_CHAPTER_INDEX = 10_000
