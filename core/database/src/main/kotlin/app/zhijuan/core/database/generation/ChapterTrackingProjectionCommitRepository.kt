package app.zhijuan.core.database.generation

import androidx.room.withTransaction
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.database.memory.ChapterTrackingProjectionEntity
import app.zhijuan.core.database.memory.ForeshadowItemEntity
import app.zhijuan.core.database.memory.ForeshadowTransitionEntity
import app.zhijuan.core.database.memory.TimelineEventEntity
import app.zhijuan.core.database.search.MemorySearchIndexWriterV1
import app.zhijuan.core.model.BookStatus
import app.zhijuan.core.model.DerivedDataStatus
import app.zhijuan.core.model.ForeshadowStatus
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.model.MemorySource
import app.zhijuan.core.model.RequestAttemptStatus
import app.zhijuan.core.model.UsageLedgerStatus
import app.zhijuan.core.security.AndroidProtectedArtifactStore
import app.zhijuan.core.security.ProtectedArtifactType
import app.zhijuan.core.task.GenerationJobStateMachine
import app.zhijuan.core.task.GenerationStageStateMachine
import app.zhijuan.core.task.JobEvent
import app.zhijuan.core.task.StageEvent
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class ChapterTrackingProjectionCommitDraft(
    val source: ChapterTrackingProjectionSourceV1,
    val trackingContentHash: String,
    val projection: ChapterTrackingProjectionEntity,
    val timelineEvents: List<TimelineEventEntity>,
    val newForeshadows: List<ForeshadowItemEntity>,
    val existingForeshadowUpdates: List<ForeshadowProjectionUpdate>,
    val foreshadowTransitions: List<ForeshadowTransitionEntity>,
    val usage: FinalUsageCommit = FinalUsageCommit.UNKNOWN,
    val nextStageId: String? = null,
    val committedAt: Long,
) {
    override fun toString(): String =
        "ChapterTrackingProjectionCommitDraft(timelineCount=${timelineEvents.size}, " +
            "newForeshadowCount=${newForeshadows.size}, transitionCount=${foreshadowTransitions.size}, content=redacted)"
}

data class ChapterTrackingProjectionCommitResult(
    val stageId: String,
    val chapterVersionId: String,
    val timelineEventCount: Int,
    val foreshadowTransitionCount: Int,
    val nextStageId: String?,
    val replayed: Boolean,
)

class ChapterTrackingProjectionCommitRepository(
    private val database: ZhijuanDatabase,
    private val artifactStore: AndroidProtectedArtifactStore,
    private val leasePolicy: GenerationLeasePolicy = GenerationLeasePolicy(),
) {
    suspend fun commit(
        permit: ValidatedOutputCommitPermit,
        draft: ChapterTrackingProjectionCommitDraft,
    ): ChapterTrackingProjectionCommitResult {
        validateDraft(draft)
        require(draft.committedAt >= permit.validatedAt) {
            "Story-tracking commit cannot precede structured validation."
        }
        val outputReference = outputReferenceJson(permit, draft)
        if (database.generationDao().findStage(permit.stageId)?.status != GenerationStageStatus.SUCCEEDED) {
            verifyValidatedArtifact(permit, draft.trackingContentHash)
        }
        return database.withTransaction {
            val generation = database.generationDao()
            val library = database.libraryDao()
            val memory = database.memoryDao()
            val revisionWriter = ForeshadowProjectionRevisionWriterV1(memory)
            val stage = requireNotNull(generation.findStage(permit.stageId)) { "Story-tracking stage no longer exists." }
            val attempt = requireNotNull(generation.findAttempt(permit.attemptId)) { "Story-tracking attempt no longer exists." }
            val job = requireNotNull(generation.findJob(stage.jobId)) { "Story-tracking job no longer exists." }
            val chapter = requireNotNull(library.findChapter(draft.source.chapterId)) { "Story-tracking chapter no longer exists." }
            val version = requireNotNull(library.findChapterVersion(draft.source.chapterVersionId)) { "Story-tracking version no longer exists." }
            val book = requireNotNull(library.findBook(chapter.bookId)) { "Story-tracking book no longer exists." }
            require(
                attempt.stageId == stage.stageId && attempt.status == RequestAttemptStatus.SUCCEEDED &&
                    attempt.standardErrorCode == null && attempt.outputHash == permit.rawOutputHash &&
                    attempt.streamDraftRef == permit.artifactRefId &&
                    generation.attemptsForStage(stage.stageId).lastOrNull()?.attemptId == attempt.attemptId,
            ) { "Validated story-tracking output evidence changed before commit." }
            require(
                stage.phase == GenerationPhase.EXTRACT_MEMORY && stage.targetType == GenerationTargetType.CHAPTER &&
                    stage.targetId == chapter.chapterId && job.bookId == book.bookId &&
                    draft.projection.bookId == book.bookId && draft.projection.generationStageId == stage.stageId &&
                    version.chapterId == chapter.chapterId,
            ) { "Story-tracking stage, target, version, or book is invalid." }
            require(book.status in setOf(BookStatus.DRAFT, BookStatus.GENERATING))
            require(
                chapter.currentVersionId == version.chapterVersionId && chapter.chapterIndex == draft.source.chapterIndex &&
                    version.contentHash == draft.source.chapterContentHash,
            ) { "Story-tracking source is no longer the current frozen chapter version." }
            require(generation.findUsageForAttempt(attempt.attemptId)?.bookId == book.bookId)
            require(draft.projection.modelSnapshotJson == attempt.modelSnapshotJson)
            require(ChapterTrackingProjectionJobFactory.parseAndVerify(stage) == draft.source)
            val rebuildBound = ChapterEditRebuildStageRepository(database).requireCommitAllowedIfBound(
                stage = stage,
                job = job,
                observedAt = draft.committedAt,
            )

            if (stage.status == GenerationStageStatus.SUCCEEDED) {
                require(stage.outputReferenceJson == outputReference) { "Completed story-tracking stage does not match replay." }
                require(memory.findTrackingProjectionForVersion(version.chapterVersionId) == draft.projection)
                require(memory.timelineEventsForVersion(version.chapterVersionId) == draft.timelineEvents.sortedWith(TIMELINE_ORDER))
                require(memory.foreshadowTransitionsForStage(stage.stageId) == draft.foreshadowTransitions.sortedWith(TRANSITION_ORDER))
                revisionWriter.requireStoredAfterStates(
                    bookId = book.bookId,
                    chapterIndex = draft.source.chapterIndex,
                    sourceChapterVersionId = version.chapterVersionId,
                    generationStageId = stage.stageId,
                    transitions = draft.foreshadowTransitions,
                )
                if (rebuildBound) {
                    check(
                        ChapterEditRebuildStageRepository(database).commitAggregateAfterTrackingIfBound(
                            stage = stage,
                            job = job,
                            committedAt = draft.committedAt,
                            replayed = true,
                        ),
                    )
                }
                MemorySearchIndexWriterV1.replaceStoryTrackingTimelines(
                    search = database.memorySearchDao(),
                    chapterIndex = draft.source.chapterIndex,
                    timelineEvents = draft.timelineEvents,
                )
                generation.recordUsage(attempt.attemptId, draft.usage.toFinalUpdate(draft.committedAt))
                return@withTransaction result(stage.stageId, draft, replayed = true)
            }

            require(stage.status == GenerationStageStatus.COMMITTING) { "Story tracking can only commit from COMMITTING." }
            require(
                job.status in setOf(GenerationJobStatus.RUNNING, GenerationJobStatus.PAUSING) &&
                    job.currentStageId == stage.stageId,
            ) { "Story-tracking job is not running the validated stage." }
            requireActiveLease(stage, permit.leaseToken, draft.committedAt)
            val sourceRepository = ChapterTrackingProjectionSourceRepository(database)
            if (rebuildBound) {
                sourceRepository.requireCurrentMatchesForEditRebuild(draft.source, book.bookId)
            } else {
                sourceRepository.requireCurrentMatches(draft.source, book.bookId)
            }
            require(memory.timelineEventsForVersion(version.chapterVersionId).isEmpty()) {
                "The current chapter version already has timeline events without a matching projection header."
            }

            if (draft.timelineEvents.isNotEmpty()) memory.insertTimelineEvents(draft.timelineEvents)
            if (draft.newForeshadows.isNotEmpty()) memory.insertForeshadows(draft.newForeshadows)
            draft.existingForeshadowUpdates.forEach { update ->
                check(
                    memory.compareAndTransitionForeshadow(
                        foreshadowItemId = update.foreshadowItemId,
                        bookId = book.bookId,
                        fromStatus = update.expectedFromStatus.name,
                        toStatus = update.toStatus.name,
                        sourceChapterVersionId = version.chapterVersionId,
                        resolvedChapterVersionId = update.resolvedChapterVersionId,
                        visibleEntityIdsJson = update.visibleEntityIdsJson,
                        importance = update.importance,
                        updatedAt = draft.committedAt,
                    ) == 1,
                ) { "A foreshadow changed after the projection source was frozen." }
            }
            if (draft.foreshadowTransitions.isNotEmpty()) memory.insertForeshadowTransitions(draft.foreshadowTransitions)
            revisionWriter.persistAfterStates(
                bookId = book.bookId,
                chapterIndex = draft.source.chapterIndex,
                sourceChapterVersionId = version.chapterVersionId,
                generationStageId = stage.stageId,
                transitions = draft.foreshadowTransitions,
            )
            memory.insertTrackingProjection(draft.projection)
            MemorySearchIndexWriterV1.replaceStoryTracking(
                search = database.memorySearchDao(),
                chapterIndex = draft.source.chapterIndex,
                timelineEvents = draft.timelineEvents,
                foreshadows = (
                    draft.newForeshadows + draft.existingForeshadowUpdates.map { update ->
                        requireNotNull(memory.findForeshadow(update.foreshadowItemId)) {
                            "Updated foreshadow disappeared during story-tracking commit."
                        }
                    }
                ).sortedBy { it.foreshadowItemId },
            )
            if (rebuildBound) {
                check(
                    ChapterEditRebuildStageRepository(database).commitAggregateAfterTrackingIfBound(
                        stage = stage,
                        job = job,
                        committedAt = draft.committedAt,
                        replayed = false,
                    ),
                )
            }
            generation.recordUsage(attempt.attemptId, draft.usage.toFinalUpdate(draft.committedAt))
            check(
                GenerationStageStateMachine.transition(stage.status, StageEvent.COMMIT_SUCCEEDED) ==
                    GenerationStageStatus.SUCCEEDED,
            )
            if (
                generation.compareAndCommitStageOutput(
                    stageId = stage.stageId,
                    leaseOwnerId = permit.leaseToken.ownerId,
                    leaseAcquiredAt = permit.leaseToken.acquiredAt,
                    outputReferenceJson = outputReference,
                    updatedAt = draft.committedAt,
                ) != 1
            ) throw StaleGenerationStateException("Story-tracking commit lost the current stage lease.")
            finishJobOrAdvance(generation, job, stage, draft)
            result(stage.stageId, draft, replayed = false)
        }
    }

    private suspend fun finishJobOrAdvance(
        generation: GenerationDao,
        job: GenerationJobEntity,
        stage: GenerationStageEntity,
        draft: ChapterTrackingProjectionCommitDraft,
    ) {
        if (draft.nextStageId == null) {
            require(generation.countNonSucceededStages(job.jobId) == 0)
            check(GenerationJobStateMachine.transition(job.status, JobEvent.ALL_STAGES_COMPLETED) == GenerationJobStatus.COMPLETED)
            if (
                generation.compareAndCompleteJobAfterStage(
                    jobId = job.jobId,
                    expectedCurrentStageId = stage.stageId,
                    updatedAt = draft.committedAt,
                ) != 1
            ) throw StaleGenerationStateException("Story-tracking job changed during completion.")
            return
        }
        val next = requireNotNull(generation.findStage(draft.nextStageId))
        require(next.jobId == job.jobId && next.stageId != stage.stageId && next.status == GenerationStageStatus.PENDING)
        if (
            generation.compareAndSetStageStatus(
                stageId = next.stageId,
                expectedStatus = GenerationStageStatus.PENDING,
                nextStatus = GenerationStageStatus.READY,
                errorCode = null,
                nextRetryAt = null,
                updatedAt = draft.committedAt,
            ) != 1 ||
            (if (job.status == GenerationJobStatus.PAUSING) {
                generation.compareAndPauseJobAfterStage(
                    jobId = job.jobId,
                    expectedCurrentStageId = stage.stageId,
                    nextStageId = next.stageId,
                    updatedAt = draft.committedAt,
                )
            } else {
                generation.compareAndAdvanceJobStage(
                    jobId = job.jobId,
                    expectedCurrentStageId = stage.stageId,
                    nextStageId = next.stageId,
                    updatedAt = draft.committedAt,
                )
            }) != 1
        ) throw StaleGenerationStateException("Story-tracking next-stage activation lost a concurrent update.")
    }

    private fun validateDraft(draft: ChapterTrackingProjectionCommitDraft) {
        require(HASH.matches(draft.trackingContentHash))
        require(draft.nextStageId == null || IDENTIFIER.matches(draft.nextStageId))
        require(draft.committedAt >= 0L)
        val projection = draft.projection
        require(
            projection.bookId.isNotBlank() && projection.chapterVersionId == draft.source.chapterVersionId &&
                projection.chapterIndex == draft.source.chapterIndex && projection.generationStageId.isNotBlank() &&
                projection.sourceChapterContentHash == draft.source.chapterContentHash &&
                projection.sourceMemorySnapshotHash == draft.source.memorySnapshotHash &&
                projection.priorForeshadowSnapshotHash == draft.source.priorForeshadowSnapshotHash &&
                projection.outputContentHash == draft.trackingContentHash && projection.status == DerivedDataStatus.VALID &&
                projection.timelineEventCount == draft.timelineEvents.size &&
                projection.foreshadowTransitionCount == draft.foreshadowTransitions.size &&
                projection.createdAt == draft.committedAt && projection.updatedAt == draft.committedAt,
        ) { "Story-tracking projection provenance is invalid." }
        require(HASH.matches(projection.payloadHash) && HASH.matches(projection.sourceMemorySnapshotHash))
        require(projection.modelSnapshotJson.isNotBlank() && projection.modelSnapshotJson.length <= 65_536)
        require(draft.timelineEvents.size <= 64 && draft.foreshadowTransitions.size <= 64)
        require(draft.newForeshadows.size <= draft.foreshadowTransitions.size)
        require(draft.existingForeshadowUpdates.size + draft.newForeshadows.size == draft.foreshadowTransitions.size)
        requireDistinct(draft.timelineEvents.map { it.timelineEventId }, "timeline events")
        requireDistinct(draft.newForeshadows.map { it.foreshadowItemId }, "new foreshadows")
        requireDistinct(draft.existingForeshadowUpdates.map { it.foreshadowItemId }, "foreshadow updates")
        requireDistinct(draft.foreshadowTransitions.map { it.transitionId }, "foreshadow transitions")
        requireDistinct(draft.foreshadowTransitions.map { it.foreshadowItemId }, "transition targets")
        draft.timelineEvents.forEach { event ->
            require(
                event.bookId == projection.bookId && event.sourceChapterVersionId == draft.source.chapterVersionId &&
                    event.status == DerivedDataStatus.VALID && event.createdAt == draft.committedAt,
            )
            requireJson(event.participantsJson)
            requireJson(event.constraintsJson)
        }
        draft.newForeshadows.forEach { item ->
            require(
                item.bookId == projection.bookId && item.foreshadowStatus == ForeshadowStatus.PLANTED &&
                    item.memoryStatus == DerivedDataStatus.VALID && item.source == MemorySource.CHAPTER_EXTRACTION &&
                    item.sourceChapterVersionId == draft.source.chapterVersionId &&
                    item.plantedChapterVersionId == draft.source.chapterVersionId && item.resolvedChapterVersionId == null &&
                    item.importance in 0..100 && item.createdAt == draft.committedAt && item.updatedAt == draft.committedAt,
            )
            requireJson(item.visibleEntityIdsJson)
        }
        draft.existingForeshadowUpdates.forEach { update ->
            require(update.importance in 0..100)
            require(update.expectedFromStatus in ACTIVE_FORESHADOW_STATES)
            require(update.toStatus in setOf(ForeshadowStatus.DEVELOPING, ForeshadowStatus.RESOLVED, ForeshadowStatus.ABANDONED))
            require((update.toStatus == ForeshadowStatus.RESOLVED) == (update.resolvedChapterVersionId == draft.source.chapterVersionId))
            requireJson(update.visibleEntityIdsJson)
        }
        draft.foreshadowTransitions.forEach { transition ->
            require(
                transition.bookId == projection.bookId && transition.sourceChapterVersionId == draft.source.chapterVersionId &&
                    transition.generationStageId == projection.generationStageId && transition.status == DerivedDataStatus.VALID &&
                    transition.createdAt == draft.committedAt && transition.operation in OPERATIONS,
            )
            requireJson(transition.evidenceJson)
        }
        val newById = draft.newForeshadows.associateBy { it.foreshadowItemId }
        val updatesById = draft.existingForeshadowUpdates.associateBy { it.foreshadowItemId }
        val transitionsById = draft.foreshadowTransitions.associateBy { it.foreshadowItemId }
        require(transitionsById.keys == newById.keys + updatesById.keys) {
            "Foreshadow transitions do not match their created or updated projections."
        }
        newById.forEach { (itemId, item) ->
            val transition = requireNotNull(transitionsById[itemId])
            require(
                transition.operation == "PLANT" && transition.fromStatus == null &&
                    transition.toStatus == ForeshadowStatus.PLANTED && item.foreshadowStatus == transition.toStatus,
            ) { "A newly planted foreshadow must own one matching PLANT transition." }
        }
        updatesById.forEach { (itemId, update) ->
            val transition = requireNotNull(transitionsById[itemId])
            val expectedOperation = when (update.toStatus) {
                ForeshadowStatus.DEVELOPING -> "DEVELOP"
                ForeshadowStatus.RESOLVED -> "RESOLVE"
                ForeshadowStatus.ABANDONED -> "ABANDON"
                else -> throw IllegalArgumentException("Unsupported projected foreshadow state.")
            }
            require(
                transition.operation == expectedOperation && transition.fromStatus == update.expectedFromStatus &&
                    transition.toStatus == update.toStatus,
            ) { "An existing foreshadow update must own one matching transition." }
        }
        require(
            projection.payloadHash == ChapterTrackingPayloadHasher.hash(
                draft.timelineEvents,
                draft.newForeshadows,
                draft.existingForeshadowUpdates,
                draft.foreshadowTransitions,
            ),
        ) { "Story-tracking payload hash does not match the mapped rows." }
    }

    private fun outputReferenceJson(
        permit: ValidatedOutputCommitPermit,
        draft: ChapterTrackingProjectionCommitDraft,
    ): String = JsonObject(
        linkedMapOf(
            "schemaVersion" to JsonPrimitive(1),
            "outputSchemaId" to JsonPrimitive(ChapterTrackingProjectionJobFactory.OUTPUT_SCHEMA_ID),
            "attemptId" to JsonPrimitive(permit.attemptId),
            "rawOutputHash" to JsonPrimitive(permit.rawOutputHash),
            "trackingContentHash" to JsonPrimitive(draft.trackingContentHash),
            "payloadHash" to JsonPrimitive(draft.projection.payloadHash),
            "chapterVersionId" to JsonPrimitive(draft.source.chapterVersionId),
            "sourceMemorySnapshotHash" to JsonPrimitive(draft.source.memorySnapshotHash),
            "priorForeshadowSnapshotHash" to JsonPrimitive(draft.source.priorForeshadowSnapshotHash),
            "timelineEventCount" to JsonPrimitive(draft.timelineEvents.size),
            "foreshadowTransitionCount" to JsonPrimitive(draft.foreshadowTransitions.size),
            "nextStageId" to (draft.nextStageId?.let(::JsonPrimitive) ?: JsonNull),
        ),
    ).toString()

    private fun verifyValidatedArtifact(permit: ValidatedOutputCommitPermit, expectedCanonicalHash: String) {
        artifactStore.readBytes(
            artifactRefId = permit.artifactRefId,
            expectedType = ProtectedArtifactType.STREAM_DRAFT,
            maximumBytes = MAX_OUTPUT_BYTES,
        ).use { lease ->
            require(lease.descriptor.revision == permit.artifactRevision)
            lease.withBytes { bytes ->
                require(sha256(bytes) == permit.rawOutputHash)
                val document = runCatching { STRICT_JSON.parseToJsonElement(bytes.decodeToString()) as JsonObject }
                    .getOrElse { throw IllegalArgumentException("Validated story-tracking artifact is not an object.") }
                require(sha256(document.toString()) == expectedCanonicalHash) {
                    "Story-tracking mapping no longer matches the validated artifact."
                }
            }
        }
    }

    private fun requireActiveLease(stage: GenerationStageEntity, token: GenerationLeaseToken, operationAt: Long) {
        require(stage.leaseOwnerId == token.ownerId && stage.leaseAcquiredAt == token.acquiredAt)
        val heartbeatAt = requireNotNull(stage.leaseHeartbeatAt)
        require(operationAt >= stage.updatedAt && operationAt >= heartbeatAt)
        if (leasePolicy.isExpired(heartbeatAt, operationAt)) {
            throw StaleGenerationStateException("Stage lease expired before story-tracking commit.")
        }
    }

    private fun result(stageId: String, draft: ChapterTrackingProjectionCommitDraft, replayed: Boolean) =
        ChapterTrackingProjectionCommitResult(
            stageId = stageId,
            chapterVersionId = draft.source.chapterVersionId,
            timelineEventCount = draft.timelineEvents.size,
            foreshadowTransitionCount = draft.foreshadowTransitions.size,
            nextStageId = draft.nextStageId,
            replayed = replayed,
        )

    private fun FinalUsageCommit.toFinalUpdate(updatedAt: Long) = UsageUpdate(
        source = source,
        status = UsageLedgerStatus.FINAL,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        cachedTokens = cachedTokens,
        reasoningTokens = reasoningTokens,
        totalTokens = totalTokens,
        currency = currency,
        estimatedCostMicros = estimatedCostMicros,
        priceCatalogVersion = priceCatalogVersion,
        updatedAt = updatedAt,
    )

    private fun requireDistinct(ids: List<String>, label: String) {
        require(ids.all(IDENTIFIER::matches) && ids.distinct().size == ids.size) { "Duplicate or invalid $label." }
    }

    private fun requireJson(value: String) {
        require(value.isNotBlank() && value.length <= MAX_JSON_CHARS)
        runCatching { STRICT_JSON.parseToJsonElement(value) }
            .getOrElse { throw IllegalArgumentException("Story-tracking persistence JSON is invalid.") }
    }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun sha256(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return try {
            sha256(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private companion object {
        val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
        val HASH = Regex("[0-9a-f]{64}")
        val STRICT_JSON = Json { isLenient = false }
        val TIMELINE_ORDER = compareBy<TimelineEventEntity>({ it.storyOrder }, { it.timelineEventId })
        val TRANSITION_ORDER = compareBy<ForeshadowTransitionEntity>({ it.storyOrder }, { it.transitionId })
        val ACTIVE_FORESHADOW_STATES = setOf(ForeshadowStatus.PLANNED, ForeshadowStatus.PLANTED, ForeshadowStatus.DEVELOPING)
        val OPERATIONS = setOf("PLANT", "DEVELOP", "RESOLVE", "ABANDON")
        const val MAX_OUTPUT_BYTES = 512 * 1_024
        const val MAX_JSON_CHARS = 256 * 1_024
    }
}
