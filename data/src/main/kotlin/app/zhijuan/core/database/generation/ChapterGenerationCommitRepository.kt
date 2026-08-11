package app.zhijuan.core.database.generation

import androidx.room.withTransaction
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.database.library.ChapterVersionEntity
import app.zhijuan.core.database.library.StaleChapterVersionException
import app.zhijuan.core.database.memory.CanonFactEntity
import app.zhijuan.core.database.memory.ChapterSummaryEntity
import app.zhijuan.core.database.memory.EntityEventEntity
import app.zhijuan.core.database.memory.ForeshadowItemEntity
import app.zhijuan.core.database.memory.StaleCascadeResult
import app.zhijuan.core.database.memory.TimelineEventEntity
import app.zhijuan.core.database.search.MemorySearchIndexWriterV1
import app.zhijuan.core.model.BookStatus
import app.zhijuan.core.model.CanonLevel
import app.zhijuan.core.model.ChapterStatus
import app.zhijuan.core.model.ChapterVersionSource
import app.zhijuan.core.model.ConsistencyStatus
import app.zhijuan.core.model.DerivedDataStatus
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.model.UsageLedgerStatus
import app.zhijuan.core.model.UsageSource
import app.zhijuan.core.security.AndroidProtectedArtifactStore
import app.zhijuan.core.security.ProtectedArtifactType
import app.zhijuan.core.task.GenerationJobStateMachine
import app.zhijuan.core.task.GenerationStageStateMachine
import app.zhijuan.core.task.JobEvent
import app.zhijuan.core.task.StageEvent
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class FinalUsageCommit(
    val source: UsageSource,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val cachedTokens: Long?,
    val reasoningTokens: Long?,
    val totalTokens: Long?,
    val currency: String? = null,
    val estimatedCostMicros: Long? = null,
    val priceCatalogVersion: String? = null,
) {
    companion object {
        val UNKNOWN = FinalUsageCommit(
            source = UsageSource.UNKNOWN,
            inputTokens = null,
            outputTokens = null,
            cachedTokens = null,
            reasoningTokens = null,
            totalTokens = null,
        )
    }
}

data class ChapterGenerationCommitDraft(
    val chapterVersionId: String,
    val chapterId: String,
    val expectedCurrentVersionId: String?,
    val content: String,
    val summary: ChapterSummaryEntity,
    val entityEvents: List<EntityEventEntity> = emptyList(),
    val canonFacts: List<CanonFactEntity> = emptyList(),
    val timelineEvents: List<TimelineEventEntity> = emptyList(),
    val foreshadows: List<ForeshadowItemEntity> = emptyList(),
    val usage: FinalUsageCommit = FinalUsageCommit.UNKNOWN,
    val nextStageId: String?,
    val committedAt: Long,
) {
    override fun toString(): String =
        "ChapterGenerationCommitDraft(derivedCounts=${listOf(entityEvents.size, canonFacts.size, timelineEvents.size, foreshadows.size)}, content=redacted)"
}

data class ChapterStaleCascade(
    val summaries: Int,
    val entityEvents: Int,
    val canonFacts: Int,
    val timelineEvents: Int,
    val foreshadows: Int,
    val trackingProjections: Int,
    val foreshadowProjectionRevisions: Int,
    val foreshadowTransitions: Int,
    val aggregateStates: Int,
    val futureContexts: Int,
    val futureReports: Int,
    val futureChapters: Int,
)

class ChapterGenerationCommitResult internal constructor(
    val chapterVersionId: String,
    val chapterId: String,
    val stageId: String,
    val nextStageId: String?,
    val jobCompleted: Boolean,
    val replayed: Boolean,
    val isCurrentVersion: Boolean,
    val staleCascade: ChapterStaleCascade?,
) {
    override fun toString(): String =
        "ChapterGenerationCommitResult(jobCompleted=$jobCompleted, replayed=$replayed, " +
            "isCurrentVersion=$isCurrentVersion, evidence=redacted)"
}

class ChapterGenerationCommitRepository(
    private val database: ZhijuanDatabase,
    private val artifactStore: AndroidProtectedArtifactStore,
    private val leasePolicy: GenerationLeasePolicy = GenerationLeasePolicy(),
) {
    suspend fun commit(
        permit: ValidatedOutputCommitPermit,
        draft: ChapterGenerationCommitDraft,
    ): ChapterGenerationCommitResult {
        validateDraft(draft)
        require(draft.committedAt >= permit.validatedAt) {
            "Chapter commit cannot precede structured validation."
        }
        val contentHash = sha256Utf8(draft.content)
        val payloadHash = commitPayloadHash(draft, contentHash)
        val outputReference = outputReferenceJson(permit, draft, contentHash, payloadHash)
        if (database.generationDao().findStage(permit.stageId)?.status != GenerationStageStatus.SUCCEEDED) {
            verifyValidatedArtifact(permit)
        }

        return database.withTransaction {
            val generation = database.generationDao()
            val library = database.libraryDao()
            val memory = database.memoryDao()
            val stage = requireNotNull(generation.findStage(permit.stageId)) {
                "Validated output stage no longer exists."
            }
            val attempt = requireNotNull(generation.findAttempt(permit.attemptId)) {
                "Validated output attempt no longer exists."
            }
            require(
                attempt.stageId == stage.stageId &&
                    attempt.status == app.zhijuan.core.model.RequestAttemptStatus.SUCCEEDED &&
                    attempt.standardErrorCode == null &&
                    attempt.outputHash == permit.rawOutputHash &&
                    attempt.streamDraftRef == permit.artifactRefId &&
                    generation.attemptsForStage(stage.stageId).lastOrNull()?.attemptId == attempt.attemptId,
            ) { "Validated output evidence changed before chapter commit." }
            val job = requireNotNull(generation.findJob(stage.jobId)) { "Owning generation job no longer exists." }
            val chapter = requireNotNull(library.findChapter(draft.chapterId)) { "Target chapter does not exist." }
            val book = requireNotNull(library.findBook(chapter.bookId)) { "Owning book does not exist." }
            require(stage.targetType == GenerationTargetType.CHAPTER && stage.targetId == chapter.chapterId) {
                "Validated stage does not target the requested chapter."
            }
            require(stage.phase in setOf(
                app.zhijuan.core.model.GenerationPhase.DRAFT_CHAPTER,
                app.zhijuan.core.model.GenerationPhase.REVISE_CHAPTER,
            )) { "Only a validated chapter draft or revision can publish readable chapter content." }
            require(job.bookId == book.bookId) { "Generation job and chapter belong to different books." }
            ChapterProgressionGateRepository(database).requireProviderOpenAllowed(stage, job)
            require(book.status in setOf(BookStatus.DRAFT, BookStatus.GENERATING)) {
                "Archived, completed, paused, or failed books cannot accept a generated chapter commit."
            }
            require(
                draft.summary.bookId == book.bookId &&
                    draft.summary.chapterIndex == chapter.chapterIndex &&
                    draft.summary.modelSnapshotJson == attempt.modelSnapshotJson &&
                    draft.entityEvents.all { it.bookId == book.bookId } &&
                    draft.canonFacts.all { it.bookId == book.bookId } &&
                    draft.timelineEvents.all { it.bookId == book.bookId } &&
                    draft.foreshadows.all { it.bookId == book.bookId },
            ) { "Derived chapter data belongs to another book, chapter, or model snapshot." }

            if (stage.status == GenerationStageStatus.SUCCEEDED) {
                return@withTransaction replayCommitted(
                    stage = stage,
                    job = job,
                    chapterId = chapter.chapterId,
                    draft = draft,
                    contentHash = contentHash,
                    outputReference = outputReference,
                    attemptId = attempt.attemptId,
                )
            }

            require(stage.status == GenerationStageStatus.COMMITTING) {
                "A chapter output can only commit from COMMITTING."
            }
            require(
                job.status in setOf(GenerationJobStatus.RUNNING, GenerationJobStatus.PAUSING) &&
                    job.currentStageId == stage.stageId,
            ) {
                "The generation job is not running or safely pausing this commit stage."
            }
            requireActiveLease(stage, permit.leaseToken, draft.committedAt)
            require(chapter.currentVersionId == draft.expectedCurrentVersionId) {
                "Chapter changed after generation started; refusing to overwrite it."
            }
            require(library.versionsForGenerationStage(stage.stageId).isEmpty()) {
                "The stage already has a chapter version but lacks a completed commit record."
            }
            require(generation.findUsageForAttempt(attempt.attemptId)?.bookId == book.bookId) {
                "Usage ledger is missing or belongs to another book."
            }

            val replacedSearchIdentities = draft.expectedCurrentVersionId?.let { replacedVersionId ->
                MemorySearchIndexWriterV1.identitiesForReplacedChapter(memory, book.bookId, replacedVersionId)
            }
            val staleCascade = draft.expectedCurrentVersionId?.let { replacedVersionId ->
                memory.markDerivedDataStaleForReplacedChapter(
                    bookId = book.bookId,
                    replacedChapterVersionId = replacedVersionId,
                    updatedAt = draft.committedAt,
                )
            }
            val version = ChapterVersionEntity(
                chapterVersionId = draft.chapterVersionId,
                chapterId = chapter.chapterId,
                versionNo = library.maximumVersionNumber(chapter.chapterId) + 1,
                content = draft.content,
                characterCount = draft.content.codePointCount(0, draft.content.length),
                contentHash = contentHash,
                source = ChapterVersionSource.AI_GENERATED,
                parentVersionId = draft.expectedCurrentVersionId,
                generationStageId = stage.stageId,
                modelSnapshotJson = attempt.modelSnapshotJson,
                createdAt = draft.committedAt,
            )
            library.insertChapterVersion(version)
            memory.insertSummary(draft.summary)
            if (draft.entityEvents.isNotEmpty()) memory.insertEntityEvents(draft.entityEvents)
            if (draft.canonFacts.isNotEmpty()) memory.insertCanonFacts(draft.canonFacts)
            if (draft.timelineEvents.isNotEmpty()) memory.insertTimelineEvents(draft.timelineEvents)
            if (draft.foreshadows.isNotEmpty()) memory.insertForeshadows(draft.foreshadows)

            val search = database.memorySearchDao()
            replacedSearchIdentities?.let { search.deleteSources(it) }
            MemorySearchIndexWriterV1.replaceChapterMemory(
                search = search,
                summary = draft.summary,
                entityEvents = draft.entityEvents,
                canonFacts = draft.canonFacts,
            )
            MemorySearchIndexWriterV1.replaceStoryTracking(
                search = search,
                chapterIndex = chapter.chapterIndex,
                timelineEvents = draft.timelineEvents,
                foreshadows = draft.foreshadows,
            )

            if (
                library.compareAndSetGeneratedCurrentVersion(
                    chapterId = chapter.chapterId,
                    expectedCurrentVersionId = draft.expectedCurrentVersionId,
                    newVersionId = version.chapterVersionId,
                    status = ChapterStatus.READY,
                    consistencyStatus = ConsistencyStatus.VALID,
                    updatedAt = draft.committedAt,
                ) != 1
            ) {
                throw StaleChapterVersionException(
                    "Chapter changed while committing; the whole commit will be rolled back.",
                )
            }
            check(
                library.updateBookAfterGeneratedChapter(
                    bookId = book.bookId,
                    completedChapterIncrement = if (draft.expectedCurrentVersionId == null) 1 else 0,
                    status = BookStatus.GENERATING,
                    generationStatusSummary = "CHAPTER_READY:${chapter.chapterIndex}",
                    updatedAt = draft.committedAt,
                ) == 1,
            ) { "Owning book changed while committing a generated chapter." }

            generation.recordUsage(
                attemptId = attempt.attemptId,
                update = draft.usage.toFinalUpdate(draft.committedAt),
            )
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
            ) {
                throw StaleGenerationStateException("Chapter commit lost the current stage lease.")
            }

            val jobCompleted = if (draft.nextStageId == null) {
                require(generation.countNonSucceededStages(job.jobId) == 0) {
                    "A next stage is required while unfinished stages remain."
                }
                check(
                    GenerationJobStateMachine.transition(job.status, JobEvent.ALL_STAGES_COMPLETED) ==
                        GenerationJobStatus.COMPLETED,
                )
                if (
                    generation.compareAndCompleteJobAfterStage(
                        jobId = job.jobId,
                        expectedCurrentStageId = stage.stageId,
                        updatedAt = draft.committedAt,
                    ) != 1
                ) {
                    throw StaleGenerationStateException("Generation job changed during final chapter commit.")
                }
                true
            } else {
                val next = requireNotNull(generation.findStage(draft.nextStageId)) {
                    "The frozen next stage does not exist."
                }
                require(next.jobId == job.jobId && next.stageId != stage.stageId) {
                    "The next stage must be a different stage in the same job."
                }
                require(next.status == GenerationStageStatus.PENDING) {
                    "The frozen next stage is not waiting for dependency completion."
                }
                require(draft.committedAt >= next.updatedAt) {
                    "Next-stage activation time cannot move backwards."
                }
                check(
                    GenerationStageStateMachine.transition(
                        next.status,
                        StageEvent.DEPENDENCIES_SATISFIED,
                    ) == GenerationStageStatus.READY,
                )
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
                ) {
                    throw StaleGenerationStateException("Next-stage activation lost a concurrent update.")
                }
                false
            }
            ChapterGenerationCommitResult(
                chapterVersionId = version.chapterVersionId,
                chapterId = chapter.chapterId,
                stageId = stage.stageId,
                nextStageId = draft.nextStageId,
                jobCompleted = jobCompleted,
                replayed = false,
                isCurrentVersion = true,
                staleCascade = staleCascade?.toPublic(),
            )
        }
    }

    private suspend fun replayCommitted(
        stage: GenerationStageEntity,
        job: GenerationJobEntity,
        chapterId: String,
        draft: ChapterGenerationCommitDraft,
        contentHash: String,
        outputReference: String,
        attemptId: String,
    ): ChapterGenerationCommitResult {
        val generation = database.generationDao()
        val library = database.libraryDao()
        val versions = library.versionsForGenerationStage(stage.stageId)
        require(
            versions.size == 1 &&
                versions.single().chapterVersionId == draft.chapterVersionId &&
                versions.single().chapterId == chapterId &&
                versions.single().contentHash == contentHash &&
                stage.outputReferenceJson == outputReference,
        ) { "Completed stage does not match the replayed chapter commit payload." }
        generation.recordUsage(
            attemptId = attemptId,
            update = draft.usage.toFinalUpdate(draft.committedAt),
        )
        val current = library.findChapter(chapterId)?.currentVersionId == draft.chapterVersionId
        return ChapterGenerationCommitResult(
            chapterVersionId = draft.chapterVersionId,
            chapterId = chapterId,
            stageId = stage.stageId,
            nextStageId = draft.nextStageId,
            jobCompleted = job.status == GenerationJobStatus.COMPLETED,
            replayed = true,
            isCurrentVersion = current,
            staleCascade = null,
        )
    }

    private fun validateDraft(draft: ChapterGenerationCommitDraft) {
        require(IDENTIFIER.matches(draft.chapterVersionId) && IDENTIFIER.matches(draft.chapterId)) {
            "Chapter commit identifiers are invalid."
        }
        require(draft.expectedCurrentVersionId == null || IDENTIFIER.matches(draft.expectedCurrentVersionId)) {
            "Expected chapter version id is invalid."
        }
        require(draft.nextStageId == null || IDENTIFIER.matches(draft.nextStageId)) {
            "Next stage id is invalid."
        }
        require(draft.committedAt >= 0L && draft.content.isNotBlank()) {
            "Chapter commit requires non-empty content and a valid time."
        }
        require(utf8Size(draft.content) <= MAX_CONTENT_BYTES) {
            "Chapter content exceeds the commit limit."
        }
        val summary = draft.summary
        require(
            summary.chapterVersionId == draft.chapterVersionId &&
                summary.status == DerivedDataStatus.VALID &&
                summary.schemaVersion > 0 &&
                summary.importance in 0..100 &&
                summary.createdAt == draft.committedAt &&
                summary.updatedAt == draft.committedAt,
        ) { "Chapter summary provenance or status is invalid." }
        draft.entityEvents.forEach { event ->
            require(
                event.sourceChapterVersionId == draft.chapterVersionId &&
                    event.status == DerivedDataStatus.VALID &&
                    event.canonLevel in setOf(CanonLevel.STORY_CANON, CanonLevel.INFERRED) &&
                    event.confidenceMicros in 0..1_000_000 &&
                    event.createdAt == draft.committedAt,
            ) { "Entity-event provenance or status is invalid." }
        }
        draft.canonFacts.forEach { fact ->
            require(
                fact.sourceChapterVersionId == draft.chapterVersionId &&
                    fact.sourceBibleRevisionId == null &&
                    fact.status == DerivedDataStatus.VALID &&
                    fact.canonLevel in setOf(CanonLevel.STORY_CANON, CanonLevel.INFERRED) &&
                    fact.createdAt == draft.committedAt,
            ) { "Canon-fact provenance or status is invalid." }
        }
        draft.timelineEvents.forEach { event ->
            require(
                event.sourceChapterVersionId == draft.chapterVersionId &&
                    event.status == DerivedDataStatus.VALID &&
                    event.createdAt == draft.committedAt,
            ) { "Timeline-event provenance or status is invalid." }
        }
        draft.foreshadows.forEach { item ->
            require(
                item.sourceChapterVersionId == draft.chapterVersionId &&
                    item.memoryStatus == DerivedDataStatus.VALID &&
                    item.importance in 0..100 &&
                    item.createdAt == draft.committedAt &&
                    item.updatedAt == draft.committedAt,
            ) { "Foreshadow provenance or status is invalid." }
        }
        requireDistinctIds(draft.entityEvents.map(EntityEventEntity::entityEventId), "entity events")
        requireDistinctIds(draft.canonFacts.map(CanonFactEntity::canonFactId), "canon facts")
        requireDistinctIds(draft.timelineEvents.map(TimelineEventEntity::timelineEventId), "timeline events")
        requireDistinctIds(draft.foreshadows.map(ForeshadowItemEntity::foreshadowItemId), "foreshadows")
        val jsonPayloads = buildList {
            add(summary.summaryJson)
            addAll(draft.entityEvents.flatMap { listOfNotNull(it.oldValueJson, it.newValueJson, it.evidenceJson) })
            addAll(draft.canonFacts.flatMap { listOf(it.factPayloadJson, it.scopeJson) })
            addAll(draft.timelineEvents.flatMap { listOf(it.participantsJson, it.constraintsJson) })
            addAll(draft.foreshadows.map(ForeshadowItemEntity::visibleEntityIdsJson))
        }
        require(jsonPayloads.sumOf { it.length.toLong() } <= MAX_DERIVED_JSON_CHARACTERS) {
            "Derived chapter data exceeds the commit limit."
        }
        jsonPayloads.forEach(::requireJson)
    }

    private fun requireActiveLease(
        stage: GenerationStageEntity,
        token: GenerationLeaseToken,
        operationAt: Long,
    ) {
        require(stage.leaseOwnerId == token.ownerId && stage.leaseAcquiredAt == token.acquiredAt) {
            "Chapter commit does not own the current stage lease."
        }
        val heartbeatAt = requireNotNull(stage.leaseHeartbeatAt) { "Commit stage lease is incomplete." }
        require(operationAt >= stage.updatedAt && operationAt >= heartbeatAt) {
            "Chapter commit time cannot move backwards."
        }
        if (leasePolicy.isExpired(heartbeatAt, operationAt)) {
            throw StaleGenerationStateException("Stage lease expired before chapter commit.")
        }
    }

    private fun verifyValidatedArtifact(permit: ValidatedOutputCommitPermit) {
        artifactStore.readBytes(
            artifactRefId = permit.artifactRefId,
            expectedType = ProtectedArtifactType.STREAM_DRAFT,
            maximumBytes = MAX_CONTENT_BYTES,
        ).use { lease ->
            require(lease.descriptor.revision == permit.artifactRevision) {
                "Validated output draft revision changed before commit."
            }
            require(lease.withBytes(::sha256) == permit.rawOutputHash) {
                "Validated output draft hash changed before commit."
            }
        }
    }

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

    private fun outputReferenceJson(
        permit: ValidatedOutputCommitPermit,
        draft: ChapterGenerationCommitDraft,
        contentHash: String,
        payloadHash: String,
    ): String = JsonObject(
        linkedMapOf(
            "schemaVersion" to JsonPrimitive(1),
            "attemptId" to JsonPrimitive(permit.attemptId),
            "rawOutputHash" to JsonPrimitive(permit.rawOutputHash),
            "chapterVersionId" to JsonPrimitive(draft.chapterVersionId),
            "chapterContentHash" to JsonPrimitive(contentHash),
            "commitPayloadHash" to JsonPrimitive(payloadHash),
            "nextStageId" to (draft.nextStageId?.let(::JsonPrimitive) ?: JsonNull),
        ),
    ).toString()

    private fun commitPayloadHash(draft: ChapterGenerationCommitDraft, contentHash: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun put(value: Any?) {
            val bytes = (value?.toString() ?: "<null>").toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
            bytes.fill(0)
        }
        put(1)
        put(draft.chapterVersionId)
        put(draft.chapterId)
        put(draft.expectedCurrentVersionId)
        put(contentHash)
        with(draft.summary) {
            listOf(
                chapterSummaryId,
                bookId,
                chapterVersionId,
                chapterIndex,
                schemaVersion,
                summaryJson,
                importance,
                status.name,
                modelSnapshotJson,
                createdAt,
                updatedAt,
            ).forEach(::put)
        }
        draft.entityEvents.sortedBy(EntityEventEntity::entityEventId).forEach { event ->
            with(event) {
                listOf(
                    entityEventId,
                    bookId,
                    entityId,
                    sourceChapterVersionId,
                    storyOrder,
                    attributeKey,
                    oldValueJson,
                    newValueJson,
                    storyTimeExpression,
                    confidenceMicros,
                    canonLevel.name,
                    evidenceJson,
                    status.name,
                    createdAt,
                ).forEach(::put)
            }
        }
        draft.canonFacts.sortedBy(CanonFactEntity::canonFactId).forEach { fact ->
            with(fact) {
                listOf(
                    canonFactId,
                    bookId,
                    entityId,
                    factText,
                    factPayloadJson,
                    canonLevel.name,
                    scopeJson,
                    sourceChapterVersionId,
                    sourceBibleRevisionId,
                    validFromStoryOrder,
                    validToStoryOrder,
                    conflictGroupId,
                    status.name,
                    createdAt,
                ).forEach(::put)
            }
        }
        draft.timelineEvents.sortedBy(TimelineEventEntity::timelineEventId).forEach { event ->
            with(event) {
                listOf(
                    timelineEventId,
                    bookId,
                    name,
                    participantsJson,
                    locationEntityId,
                    storyTimeExpression,
                    storyOrder,
                    constraintsJson,
                    sourceChapterVersionId,
                    status.name,
                    createdAt,
                ).forEach(::put)
            }
        }
        draft.foreshadows.sortedBy(ForeshadowItemEntity::foreshadowItemId).forEach { item ->
            with(item) {
                listOf(
                    foreshadowItemId,
                    bookId,
                    description,
                    foreshadowStatus.name,
                    memoryStatus.name,
                    targetStartChapterIndex,
                    targetEndChapterIndex,
                    sourceChapterVersionId,
                    plantedChapterVersionId,
                    resolvedChapterVersionId,
                    visibleEntityIdsJson,
                    importance,
                    source.name,
                    createdAt,
                    updatedAt,
                ).forEach(::put)
            }
        }
        put(draft.nextStageId)
        return digest.digest().toHex()
    }

    private fun requireDistinctIds(ids: List<String>, label: String) {
        require(ids.all(IDENTIFIER::matches) && ids.distinct().size == ids.size) {
            "Chapter commit $label contain invalid or duplicate ids."
        }
    }

    private fun requireJson(value: String) {
        require(value.isNotBlank() && value.length <= MAX_SINGLE_JSON_CHARACTERS) {
            "Derived chapter JSON is empty or too large."
        }
        runCatching { STRICT_JSON.parseToJsonElement(value) }
            .getOrElse { throw IllegalArgumentException("Derived chapter data must be valid JSON.") }
    }

    private fun sha256Utf8(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return try {
            sha256(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun utf8Size(value: String): Int {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return try {
            bytes.size
        } finally {
            bytes.fill(0)
        }
    }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .toHex()

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private companion object {
        const val MAX_CONTENT_BYTES = 4 * 1_024 * 1_024
        const val MAX_SINGLE_JSON_CHARACTERS = 512 * 1_024
        const val MAX_DERIVED_JSON_CHARACTERS = 2L * 1_024L * 1_024L
        val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
        val STRICT_JSON = Json { isLenient = false }
    }
}

internal fun StaleCascadeResult.toPublic() = ChapterStaleCascade(
    summaries = summaries,
    entityEvents = entityEvents,
    canonFacts = canonFacts,
    timelineEvents = timelineEvents,
    foreshadows = foreshadows,
    trackingProjections = trackingProjections,
    foreshadowProjectionRevisions = foreshadowProjectionRevisions,
    foreshadowTransitions = foreshadowTransitions,
    aggregateStates = aggregateStates,
    futureContexts = futureContexts,
    futureReports = futureReports,
    futureChapters = futureChapters,
)
