package app.zhijuan.core.database.library

import androidx.room.withTransaction
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.database.memory.AggregateStateProjectionEntity
import app.zhijuan.core.database.memory.ChapterSummaryEntity
import app.zhijuan.core.database.memory.ChapterTrackingProjectionEntity
import app.zhijuan.core.database.memory.ForeshadowProjectionRewindEntity
import java.nio.ByteBuffer
import java.security.MessageDigest

data class ChapterEditRebuildExecutionPrepareCommand(
    val plan: ChapterEditRebuildPlan,
    val rewindId: String,
    val preparedAt: Long,
) {
    override fun toString(): String =
        "ChapterEditRebuildExecutionPrepareCommand(preparedAt=$preparedAt, identifiers=redacted, plan=redacted)"
}

class ChapterEditRebuildExecutionPrepareResult internal constructor(
    val executionId: String,
    val firstAffectedChapterIndex: Int,
    val lastAffectedChapterIndex: Int,
    val stepCount: Int,
    val pendingStepCount: Int,
    val satisfiedStepCount: Int,
    val replayed: Boolean,
) {
    override fun toString(): String =
        "ChapterEditRebuildExecutionPrepareResult(range=$firstAffectedChapterIndex..$lastAffectedChapterIndex, " +
            "stepCounts=${listOf(stepCount, pendingStepCount, satisfiedStepCount)}, replayed=$replayed, " +
            "identifiers=redacted)"
}

/**
 * Atomically binds an audited foreshadow rewind to an immutable execution fence and prepared step ledger.
 *
 * This phase deliberately creates no GenerationJob, Stage, Attempt, Usage, Provider request, or derived output.
 * Later execution must create each Stage only after the preceding chapter has produced its real immutable result.
 */
class ChapterEditRebuildExecutionRepository(private val database: ZhijuanDatabase) {
    suspend fun prepare(
        command: ChapterEditRebuildExecutionPrepareCommand,
    ): ChapterEditRebuildExecutionPrepareResult {
        validate(command)
        return database.withTransaction {
            val plan = command.plan
            ForeshadowProjectionRewindRepository(database).rewind(
                ForeshadowProjectionRewindCommand(
                    plan = plan,
                    rewindId = command.rewindId,
                    rewoundAt = command.preparedAt,
                ),
            )
            ChapterEditRebuildPlanRepository(database).requireCurrentMatchesInTransaction(plan)

            val library = database.libraryDao()
            val memory = database.memoryDao()
            val ledger = database.chapterEditRebuildExecutionDao()
            val request = plan.request
            val editedVersion = requireNotNull(library.findChapterVersion(request.editedVersionId)) {
                "Edited chapter version does not exist."
            }
            val replacedVersionId = requireNotNull(editedVersion.parentVersionId) {
                "Edited chapter version does not preserve its replaced parent."
            }
            val rewind = requireNotNull(memory.findForeshadowProjectionRewind(command.rewindId)) {
                "Audited foreshadow rewind was not persisted."
            }
            require(
                rewind.planHash == plan.planHash &&
                    rewind.bookId == request.bookId &&
                    rewind.editedChapterId == request.editedChapterId &&
                    rewind.editedChapterVersionId == request.editedVersionId &&
                    rewind.replacedChapterVersionId == replacedVersionId &&
                    rewind.createdAt == command.preparedAt,
            ) { "Audited foreshadow rewind does not match the prepared execution." }

            val sources = ledger.currentSourcesFromChapter(request.bookId, plan.editedChapterIndex)
            requireSourcesMatchPlan(plan, sources, command.preparedAt)
            val trackingByVersion = memory.validTrackingProjectionsFromChapter(
                request.bookId,
                plan.editedChapterIndex,
            ).associateUniqueBy(ChapterTrackingProjectionEntity::chapterVersionId)
            val aggregateByIndex = memory.validAggregateStatesFromChapter(
                request.bookId,
                plan.editedChapterIndex,
            ).associateUniqueBy(AggregateStateProjectionEntity::throughChapterIndex)
            val summary = memory.findSummaryForVersion(request.editedVersionId)
            require(summary == null || summary.updatedAt <= command.preparedAt) {
                "Prepared execution time precedes its memory baseline."
            }
            require(trackingByVersion.values.all { it.updatedAt <= command.preparedAt }) {
                "Prepared execution time precedes a tracking baseline."
            }
            require(aggregateByIndex.values.all { it.updatedAt <= command.preparedAt }) {
                "Prepared execution time precedes an aggregate baseline."
            }

            val stepDrafts = preparedSteps(
                plan = plan,
                sources = sources,
                summary = summary,
                trackingByVersion = trackingByVersion,
                aggregateByIndex = aggregateByIndex,
                preparedAt = command.preparedAt,
            )
            val stableFenceHash = stableFenceHash(
                plan = plan,
                replacedVersionId = replacedVersionId,
                rewind = rewind,
                sources = sources,
                steps = stepDrafts,
            )
            val executionId = "rebuild-execution-$stableFenceHash"
            val execution = ChapterEditRebuildExecutionEntity(
                executionId = executionId,
                bookId = request.bookId,
                editedChapterId = request.editedChapterId,
                editedChapterVersionId = request.editedVersionId,
                replacedChapterVersionId = replacedVersionId,
                rewindId = command.rewindId,
                firstAffectedChapterIndex = plan.editedChapterIndex,
                lastAffectedChapterIndex = plan.highestCommittedChapterIndex,
                futureChapterPolicy = request.futureChapterPolicy,
                planSchemaVersion = plan.planSchemaVersion,
                initialPlanHash = plan.planHash,
                stableFenceHash = stableFenceHash,
                policyVersion = POLICY_VERSION,
                status = ChapterEditRebuildExecutionStatus.PREPARED,
                preparedAt = command.preparedAt,
            )
            val steps = stepDrafts.map { it.copy(executionId = executionId) }

            val existing = listOfNotNull(
                ledger.findExecution(executionId),
                ledger.findExecutionForEditedVersion(request.editedVersionId),
                ledger.findExecutionForRewind(command.rewindId),
                ledger.findExecutionForStableFence(stableFenceHash),
            ).distinctBy(ChapterEditRebuildExecutionEntity::executionId)
            if (existing.isNotEmpty()) {
                require(existing.size == 1 && existing.single() == execution) {
                    "Prepared execution identity is already bound to different provenance."
                }
                require(ledger.stepsForExecution(executionId) == steps) {
                    "Prepared execution steps no longer match their stable fence."
                }
                return@withTransaction execution.toResult(steps, replayed = true)
            }

            ledger.insertExecution(execution)
            ledger.insertSteps(steps)
            require(
                ledger.findExecution(executionId) == execution &&
                    ledger.stepsForExecution(executionId) == steps,
            ) { "Prepared execution ledger failed its write-after-read verification." }
            execution.toResult(steps, replayed = false)
        }
    }

    private fun preparedSteps(
        plan: ChapterEditRebuildPlan,
        sources: List<ChapterEditRebuildExecutionSourceRow>,
        summary: ChapterSummaryEntity?,
        trackingByVersion: Map<String, ChapterTrackingProjectionEntity>,
        aggregateByIndex: Map<Int, AggregateStateProjectionEntity>,
        preparedAt: Long,
    ): List<ChapterEditRebuildStepEntity> {
        val rows = mutableListOf<ChapterEditRebuildStepEntity>()
        val first = sources.first()
        val memoryPlanStep = plan.requireStep(
            type = ChapterEditRebuildStepType.EXTRACT_EDITED_MEMORY,
            chapterIndex = first.chapterIndex,
        )
        require(memoryPlanStep.state != ChapterEditRebuildStepState.BLOCKED) {
            "Edited memory cannot be prepared while its derived slot is blocked."
        }
        rows += ChapterEditRebuildStepEntity(
            executionId = PENDING_EXECUTION_ID,
            stepOrdinal = rows.size + 1,
            bookId = plan.request.bookId,
            chapterId = first.chapterId,
            chapterIndex = first.chapterIndex,
            sourceChapterVersionId = first.chapterVersionId,
            sourceContentHash = first.contentHash,
            stepType = ChapterEditRebuildExecutionStepType.EDITED_MEMORY,
            needsProvider = true,
            preparedState = if (summary == null) {
                ChapterEditRebuildPreparedStepState.PENDING
            } else {
                ChapterEditRebuildPreparedStepState.SATISFIED
            },
            baselineSummaryId = summary?.chapterSummaryId,
            baselineSummaryFingerprint = summary?.let(::chapterEditRebuildSummaryFingerprint),
            baselineTrackingProjectionId = null,
            baselineTrackingFingerprint = null,
            baselineAggregateStateId = null,
            baselineAggregateFingerprint = null,
            createdAt = preparedAt,
        )

        sources.forEach { source ->
            val tracking = trackingByVersion[source.chapterVersionId]
            val trackingPlanStep = plan.requireStep(
                type = ChapterEditRebuildStepType.REBUILD_STORY_TRACKING,
                chapterIndex = source.chapterIndex,
            )
            rows += ChapterEditRebuildStepEntity(
                executionId = PENDING_EXECUTION_ID,
                stepOrdinal = rows.size + 1,
                bookId = plan.request.bookId,
                chapterId = source.chapterId,
                chapterIndex = source.chapterIndex,
                sourceChapterVersionId = source.chapterVersionId,
                sourceContentHash = source.contentHash,
                stepType = ChapterEditRebuildExecutionStepType.TRACKING,
                needsProvider = true,
                preparedState = trackingPlanStep.toPreparedState(tracking != null),
                baselineSummaryId = null,
                baselineSummaryFingerprint = null,
                baselineTrackingProjectionId = tracking?.projectionId,
                baselineTrackingFingerprint = tracking?.let(::chapterEditRebuildTrackingFingerprint),
                baselineAggregateStateId = null,
                baselineAggregateFingerprint = null,
                createdAt = preparedAt,
            )

            val aggregate = aggregateByIndex[source.chapterIndex]
            val aggregatePlanStep = plan.requireStep(
                type = ChapterEditRebuildStepType.REBUILD_AGGREGATE_STATE,
                chapterIndex = source.chapterIndex,
            )
            rows += ChapterEditRebuildStepEntity(
                executionId = PENDING_EXECUTION_ID,
                stepOrdinal = rows.size + 1,
                bookId = plan.request.bookId,
                chapterId = source.chapterId,
                chapterIndex = source.chapterIndex,
                sourceChapterVersionId = source.chapterVersionId,
                sourceContentHash = source.contentHash,
                stepType = ChapterEditRebuildExecutionStepType.AGGREGATE,
                needsProvider = false,
                preparedState = aggregatePlanStep.toPreparedState(aggregate != null),
                baselineSummaryId = null,
                baselineSummaryFingerprint = null,
                baselineTrackingProjectionId = null,
                baselineTrackingFingerprint = null,
                baselineAggregateStateId = aggregate?.aggregateStateId,
                baselineAggregateFingerprint = aggregate?.let(::chapterEditRebuildAggregateFingerprint),
                createdAt = preparedAt,
            )
        }
        return rows
    }

    private fun stableFenceHash(
        plan: ChapterEditRebuildPlan,
        replacedVersionId: String,
        rewind: ForeshadowProjectionRewindEntity,
        sources: List<ChapterEditRebuildExecutionSourceRow>,
        steps: List<ChapterEditRebuildStepEntity>,
    ): String = executionStableHash(
        POLICY_VERSION,
        plan.planSchemaVersion,
        plan.policyVersion,
        plan.request.bookId,
        plan.request.editedChapterId,
        plan.request.editedVersionId,
        replacedVersionId,
        plan.request.futureChapterPolicy.name,
        plan.editedChapterIndex,
        plan.highestCommittedChapterIndex,
        rewind.rewindId,
        rewind.beforeProjectionSetHash,
        rewind.trustedBaselineSetHash,
        rewind.afterProjectionSetHash,
        rewind.affectedItemCount,
        rewind.baselineItemCount,
        rewind.absentItemCount,
        rewind.staleRevisionCount,
        rewind.staleTransitionCount,
        rewind.policyVersion,
        rewind.createdAt,
        sources.map { source ->
            listOf(
                source.chapterId,
                source.chapterIndex,
                source.chapterStatus.name,
                source.consistencyStatus.name,
                source.chapterVersionId,
                source.contentHash,
                source.versionCreatedAt,
            )
        },
        steps.map { step ->
            listOf(
                step.stepOrdinal,
                step.bookId,
                step.chapterId,
                step.chapterIndex,
                step.sourceChapterVersionId,
                step.sourceContentHash,
                step.stepType.name,
                step.needsProvider,
                step.preparedState.name,
                step.baselineSummaryId,
                step.baselineSummaryFingerprint,
                step.baselineTrackingProjectionId,
                step.baselineTrackingFingerprint,
                step.baselineAggregateStateId,
                step.baselineAggregateFingerprint,
                step.createdAt,
            )
        },
    )

    private fun requireSourcesMatchPlan(
        plan: ChapterEditRebuildPlan,
        sources: List<ChapterEditRebuildExecutionSourceRow>,
        preparedAt: Long,
    ) {
        require(sources.isNotEmpty() && sources.size == plan.frozenChapters.size) {
            "Prepared execution source range no longer matches the frozen plan."
        }
        require(sources.first().chapterId == plan.request.editedChapterId)
        require(sources.last().chapterIndex == plan.highestCommittedChapterIndex)
        sources.zip(plan.frozenChapters).forEach { (source, frozen) ->
            require(
                source.chapterIndex == frozen.chapterIndex &&
                    executionSha256Utf8(source.chapterId) == frozen.chapterIdHash &&
                    executionSha256Utf8(source.chapterVersionId) == frozen.currentVersionIdHash &&
                    source.contentHash == frozen.contentHash &&
                    source.chapterStatus == frozen.status &&
                    source.consistencyStatus == frozen.consistencyStatus &&
                    source.versionCreatedAt <= preparedAt,
            ) { "Prepared execution source changed after planning." }
        }
    }

    private fun validate(command: ChapterEditRebuildExecutionPrepareCommand) {
        require(EXECUTION_IDENTIFIER.matches(command.rewindId) && command.preparedAt >= 0L) {
            "Prepared execution command is invalid."
        }
        require(
            command.plan.planSchemaVersion == SUPPORTED_PLAN_SCHEMA_VERSION &&
                command.plan.policyVersion == SUPPORTED_PLAN_POLICY_VERSION &&
                command.plan.futureChapterPolicy == FutureChapterPolicy.KEEP_EXISTING &&
                command.plan.laterBodiesRetained &&
                EXECUTION_HASH.matches(command.plan.planHash),
        ) { "Prepared execution plan is unsupported or invalid." }
    }

    private companion object {
        const val POLICY_VERSION = "zhijuan.chapter-edit-rebuild-execution.v1"
        const val SUPPORTED_PLAN_SCHEMA_VERSION = 2
        const val SUPPORTED_PLAN_POLICY_VERSION = "zhijuan.chapter-edit-rebuild-plan.v2"
        const val PENDING_EXECUTION_ID = "pending-execution-id"
        val EXECUTION_IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
        val EXECUTION_HASH = Regex("[0-9a-f]{64}")
    }
}

private fun ChapterEditRebuildPlan.requireStep(
    type: ChapterEditRebuildStepType,
    chapterIndex: Int,
): ChapterEditRebuildStep = steps.singleOrNull { it.type == type && it.chapterIndex == chapterIndex }
    ?: error("Frozen rebuild plan lost a required execution step.")

private fun ChapterEditRebuildStep.toPreparedState(
    hasBaseline: Boolean,
): ChapterEditRebuildPreparedStepState {
    if (state == ChapterEditRebuildStepState.ALREADY_SATISFIED) {
        require(hasBaseline) { "Satisfied rebuild step lost its authoritative baseline." }
        return ChapterEditRebuildPreparedStepState.SATISFIED
    }
    require(
        state != ChapterEditRebuildStepState.BLOCKED ||
            blocker in setOf(
                ChapterEditRebuildBlocker.DERIVED_VERSION_SLOT_OCCUPIED,
                ChapterEditRebuildBlocker.TRACKING_ORDER_GUARD,
                ChapterEditRebuildBlocker.DEPENDENCY_BLOCKED,
            ),
    ) { "Rebuild step has an unsupported blocker." }
    return ChapterEditRebuildPreparedStepState.PENDING
}

internal fun chapterEditRebuildSummaryFingerprint(summary: ChapterSummaryEntity): String = executionStableHash(
    "chapter-summary-baseline-v1",
    summary.chapterSummaryId,
    summary.bookId,
    summary.chapterVersionId,
    summary.chapterIndex,
    summary.schemaVersion,
    summary.summaryJson,
    summary.importance,
    summary.status.name,
    summary.modelSnapshotJson,
    summary.createdAt,
    summary.updatedAt,
)

internal fun chapterEditRebuildTrackingFingerprint(tracking: ChapterTrackingProjectionEntity): String = executionStableHash(
    "chapter-tracking-baseline-v1",
    tracking.projectionId,
    tracking.bookId,
    tracking.chapterVersionId,
    tracking.chapterIndex,
    tracking.generationStageId,
    tracking.sourceChapterContentHash,
    tracking.sourceMemorySnapshotHash,
    tracking.priorForeshadowSnapshotHash,
    tracking.outputContentHash,
    tracking.payloadHash,
    tracking.status.name,
    tracking.modelSnapshotJson,
    tracking.timelineEventCount,
    tracking.foreshadowTransitionCount,
    tracking.createdAt,
    tracking.updatedAt,
)

internal fun chapterEditRebuildAggregateFingerprint(aggregate: AggregateStateProjectionEntity): String = executionStableHash(
    "aggregate-state-baseline-v1",
    aggregate.aggregateStateId,
    aggregate.bookId,
    aggregate.throughChapterIndex,
    aggregate.sourceThroughChapterVersionId,
    aggregate.schemaVersion,
    aggregate.stateJson,
    aggregate.contentHash,
    aggregate.status.name,
    aggregate.createdAt,
    aggregate.updatedAt,
)

private fun ChapterEditRebuildExecutionEntity.toResult(
    steps: List<ChapterEditRebuildStepEntity>,
    replayed: Boolean,
) = ChapterEditRebuildExecutionPrepareResult(
    executionId = executionId,
    firstAffectedChapterIndex = firstAffectedChapterIndex,
    lastAffectedChapterIndex = lastAffectedChapterIndex,
    stepCount = steps.size,
    pendingStepCount = steps.count { it.preparedState == ChapterEditRebuildPreparedStepState.PENDING },
    satisfiedStepCount = steps.count { it.preparedState == ChapterEditRebuildPreparedStepState.SATISFIED },
    replayed = replayed,
)

private fun <K, V> Iterable<V>.associateUniqueBy(keySelector: (V) -> K): Map<K, V> {
    val result = linkedMapOf<K, V>()
    forEach { value ->
        require(result.put(keySelector(value), value) == null) {
            "Prepared execution found multiple authoritative baselines for one slot."
        }
    }
    return result
}

private fun executionStableHash(vararg values: Any?): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun add(value: Any?) {
        when (value) {
            is Iterable<*> -> {
                digest.update(2.toByte())
                value.forEach(::add)
                digest.update(3.toByte())
            }
            else -> {
                val bytes = (value?.toString() ?: "<null>").toByteArray(Charsets.UTF_8)
                try {
                    digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
                    digest.update(bytes)
                } finally {
                    bytes.fill(0)
                }
            }
        }
    }
    values.forEach(::add)
    return digest.digest().joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}

private fun executionSha256Utf8(value: String): String {
    val bytes = value.toByteArray(Charsets.UTF_8)
    return try {
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    } finally {
        bytes.fill(0)
    }
}
