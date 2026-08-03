package app.zhijuan.core.database.generation

import androidx.room.withTransaction
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.database.memory.CanonFactEntity
import app.zhijuan.core.database.memory.ChapterSummaryEntity
import app.zhijuan.core.database.memory.EntityEventEntity
import app.zhijuan.core.model.BookStatus
import app.zhijuan.core.model.CanonLevel
import app.zhijuan.core.model.DerivedDataStatus
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.GenerationTargetType
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class ChapterMemoryExtractionCommitDraft(
    val source: ChapterMemoryExtractionSourceV1,
    val extractionContentHash: String,
    val summary: ChapterSummaryEntity,
    val entityEvents: List<EntityEventEntity>,
    val canonFacts: List<CanonFactEntity>,
    val usage: FinalUsageCommit = FinalUsageCommit.UNKNOWN,
    val nextStageId: String? = null,
    val committedAt: Long,
) {
    override fun toString(): String =
        "ChapterMemoryExtractionCommitDraft(eventCount=${entityEvents.size}, " +
            "factCount=${canonFacts.size}, content=redacted)"
}

data class ChapterMemoryExtractionCommitResult(
    val stageId: String,
    val chapterVersionId: String,
    val eventCount: Int,
    val factCount: Int,
    val nextStageId: String?,
    val replayed: Boolean,
) {
    override fun toString(): String =
        "ChapterMemoryExtractionCommitResult(eventCount=$eventCount, factCount=$factCount, " +
            "replayed=$replayed, identifiers=redacted)"
}

class ChapterMemoryExtractionCommitRepository(
    private val database: ZhijuanDatabase,
    private val artifactStore: AndroidProtectedArtifactStore,
    private val leasePolicy: GenerationLeasePolicy = GenerationLeasePolicy(),
) {
    suspend fun commit(
        permit: ValidatedOutputCommitPermit,
        draft: ChapterMemoryExtractionCommitDraft,
    ): ChapterMemoryExtractionCommitResult {
        validateDraft(draft)
        require(draft.committedAt >= permit.validatedAt) {
            "Chapter-memory commit cannot precede structured validation."
        }
        val payloadHash = payloadHash(draft)
        val outputReference = outputReferenceJson(permit, draft, payloadHash)
        if (database.generationDao().findStage(permit.stageId)?.status != GenerationStageStatus.SUCCEEDED) {
            verifyValidatedArtifact(permit, draft.extractionContentHash)
        }
        return database.withTransaction {
            val generation = database.generationDao()
            val library = database.libraryDao()
            val memory = database.memoryDao()
            val stage = requireNotNull(generation.findStage(permit.stageId)) {
                "Validated chapter-memory stage no longer exists."
            }
            val attempt = requireNotNull(generation.findAttempt(permit.attemptId)) {
                "Validated chapter-memory attempt no longer exists."
            }
            val job = requireNotNull(generation.findJob(stage.jobId)) {
                "Owning chapter-memory job no longer exists."
            }
            val chapter = requireNotNull(library.findChapter(draft.source.chapterId)) {
                "Chapter-memory source chapter no longer exists."
            }
            val version = requireNotNull(library.findChapterVersion(draft.source.chapterVersionId)) {
                "Chapter-memory source version no longer exists."
            }
            val book = requireNotNull(library.findBook(chapter.bookId)) {
                "Chapter-memory source book no longer exists."
            }
            require(
                attempt.stageId == stage.stageId && attempt.status == RequestAttemptStatus.SUCCEEDED &&
                    attempt.standardErrorCode == null && attempt.outputHash == permit.rawOutputHash &&
                    attempt.streamDraftRef == permit.artifactRefId &&
                    generation.attemptsForStage(stage.stageId).lastOrNull()?.attemptId == attempt.attemptId,
            ) { "Validated chapter-memory output evidence changed before commit." }
            require(
                stage.phase == GenerationPhase.EXTRACT_MEMORY &&
                    stage.targetType == GenerationTargetType.CHAPTER && stage.targetId == chapter.chapterId &&
                    job.bookId == book.bookId && draft.summary.bookId == book.bookId &&
                    version.chapterId == chapter.chapterId,
            ) { "Chapter-memory stage, target, version, or book is invalid." }
            require(book.status in setOf(BookStatus.DRAFT, BookStatus.GENERATING))
            require(
                chapter.currentVersionId == version.chapterVersionId &&
                    chapter.chapterIndex == draft.source.chapterIndex &&
                    version.contentHash == draft.source.chapterContentHash,
            ) { "Chapter-memory source is no longer the current frozen chapter version." }
            require(generation.findUsageForAttempt(attempt.attemptId)?.bookId == book.bookId) {
                "Chapter-memory usage ledger is missing or belongs to another book."
            }
            require(draft.summary.modelSnapshotJson == attempt.modelSnapshotJson) {
                "Chapter-memory summary model snapshot does not match the extraction Attempt."
            }
            require(ChapterMemoryExtractionJobFactory.parseAndVerify(stage) == draft.source)

            if (stage.status == GenerationStageStatus.SUCCEEDED) {
                require(stage.outputReferenceJson == outputReference) {
                    "Completed chapter-memory stage does not match the replayed payload."
                }
                require(memory.findSummaryForVersion(version.chapterVersionId) == draft.summary)
                require(memory.entityEventsForVersion(version.chapterVersionId) == draft.entityEvents.sortedWith(EVENT_ORDER))
                require(memory.canonFactsForVersion(version.chapterVersionId) == draft.canonFacts.sortedBy { it.canonFactId })
                generation.recordUsage(attempt.attemptId, draft.usage.toFinalUpdate(draft.committedAt))
                return@withTransaction result(stage.stageId, draft, replayed = true)
            }

            require(stage.status == GenerationStageStatus.COMMITTING) {
                "Chapter-memory output can only commit from COMMITTING."
            }
            require(
                job.status in setOf(GenerationJobStatus.RUNNING, GenerationJobStatus.PAUSING) &&
                    job.currentStageId == stage.stageId,
            ) { "Chapter-memory job is not running the validated stage." }
            requireActiveLease(stage, permit.leaseToken, draft.committedAt)
            require(memory.findSummaryForVersion(version.chapterVersionId) == null) {
                "The current chapter version already has a different extraction."
            }

            memory.insertSummary(draft.summary)
            if (draft.entityEvents.isNotEmpty()) memory.insertEntityEvents(draft.entityEvents)
            if (draft.canonFacts.isNotEmpty()) memory.insertCanonFacts(draft.canonFacts)
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
            ) {
                throw StaleGenerationStateException("Chapter-memory commit lost the current stage lease.")
            }
            finishJobOrAdvance(generation, job, stage, draft)
            result(stage.stageId, draft, replayed = false)
        }
    }

    private suspend fun finishJobOrAdvance(
        generation: GenerationDao,
        job: GenerationJobEntity,
        stage: GenerationStageEntity,
        draft: ChapterMemoryExtractionCommitDraft,
    ) {
        if (draft.nextStageId == null) {
            require(generation.countNonSucceededStages(job.jobId) == 0)
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
                throw StaleGenerationStateException("Chapter-memory job changed during completion.")
            }
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
        ) {
            throw StaleGenerationStateException("Chapter-memory next-stage activation lost a concurrent update.")
        }
    }

    private fun validateDraft(draft: ChapterMemoryExtractionCommitDraft) {
        require(HASH.matches(draft.extractionContentHash))
        require(draft.nextStageId == null || IDENTIFIER.matches(draft.nextStageId))
        require(draft.committedAt >= 0L)
        require(
            draft.summary.bookId.isNotBlank() &&
                draft.summary.chapterVersionId == draft.source.chapterVersionId &&
                draft.summary.chapterIndex == draft.source.chapterIndex &&
                draft.summary.schemaVersion == 1 && draft.summary.importance in 0..100 &&
                draft.summary.status == DerivedDataStatus.VALID &&
                draft.summary.createdAt == draft.committedAt && draft.summary.updatedAt == draft.committedAt,
        ) { "Chapter-memory summary provenance is invalid." }
        requireJson(draft.summary.summaryJson)
        require(draft.entityEvents.size <= 128 && draft.canonFacts.size <= 128)
        require(draft.entityEvents.map { it.entityEventId }.distinct().size == draft.entityEvents.size)
        require(draft.canonFacts.map { it.canonFactId }.distinct().size == draft.canonFacts.size)
        draft.entityEvents.forEach { event ->
            require(
                event.bookId == draft.summary.bookId &&
                    event.sourceChapterVersionId == draft.source.chapterVersionId &&
                    event.status == DerivedDataStatus.VALID &&
                    event.canonLevel in ALLOWED_DERIVED_CANON &&
                    event.confidenceMicros in 0..1_000_000 && event.createdAt == draft.committedAt,
            ) { "Chapter-memory entity-event provenance is invalid." }
            requireJson(event.newValueJson)
            event.oldValueJson?.let(::requireJson)
            requireJson(event.evidenceJson)
        }
        draft.canonFacts.forEach { fact ->
            require(
                fact.bookId == draft.summary.bookId &&
                    fact.sourceChapterVersionId == draft.source.chapterVersionId &&
                    fact.sourceBibleRevisionId == null && fact.status == DerivedDataStatus.VALID &&
                    fact.canonLevel in ALLOWED_DERIVED_CANON && fact.createdAt == draft.committedAt,
            ) { "Chapter-memory canon-fact provenance is invalid." }
            requireJson(fact.factPayloadJson)
            requireJson(fact.scopeJson)
        }
    }

    private fun payloadHash(draft: ChapterMemoryExtractionCommitDraft): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: Any?) {
            val bytes = (value?.toString() ?: "<null>").toByteArray(Charsets.UTF_8)
            try {
                digest.update(bytes)
                digest.update(0.toByte())
            } finally {
                bytes.fill(0)
            }
        }
        add("zhijuan.chapter-memory-payload.v1")
        add(draft.source.chapterVersionId)
        add(draft.source.chapterContentHash)
        add(draft.source.chapterId)
        add(draft.source.chapterIndex)
        add(draft.extractionContentHash)
        with(draft.summary) {
            add(chapterSummaryId)
            add(bookId)
            add(chapterVersionId)
            add(chapterIndex)
            add(schemaVersion)
            add(summaryJson)
            add(importance)
            add(status.name)
            add(modelSnapshotJson)
            add(createdAt)
            add(updatedAt)
        }
        draft.entityEvents.sortedWith(EVENT_ORDER).forEach { event ->
            add(event.entityEventId)
            add(event.bookId)
            add(event.entityId)
            add(event.sourceChapterVersionId)
            add(event.storyOrder)
            add(event.attributeKey)
            add(event.oldValueJson)
            add(event.newValueJson)
            add(event.storyTimeExpression)
            add(event.confidenceMicros)
            add(event.canonLevel.name)
            add(event.evidenceJson)
            add(event.status.name)
            add(event.createdAt)
        }
        draft.canonFacts.sortedBy { it.canonFactId }.forEach { fact ->
            add(fact.canonFactId)
            add(fact.bookId)
            add(fact.entityId)
            add(fact.factText)
            add(fact.factPayloadJson)
            add(fact.canonLevel.name)
            add(fact.scopeJson)
            add(fact.sourceChapterVersionId)
            add(fact.sourceBibleRevisionId)
            add(fact.validFromStoryOrder)
            add(fact.validToStoryOrder)
            add(fact.conflictGroupId)
            add(fact.status.name)
            add(fact.createdAt)
        }
        add(draft.nextStageId)
        return digest.digest().toHex()
    }

    private fun outputReferenceJson(
        permit: ValidatedOutputCommitPermit,
        draft: ChapterMemoryExtractionCommitDraft,
        payloadHash: String,
    ): String = JsonObject(
        linkedMapOf(
            "schemaVersion" to JsonPrimitive(1),
            "outputSchemaId" to JsonPrimitive(ChapterMemoryExtractionJobFactory.OUTPUT_SCHEMA_ID),
            "attemptId" to JsonPrimitive(permit.attemptId),
            "rawOutputHash" to JsonPrimitive(permit.rawOutputHash),
            "extractionContentHash" to JsonPrimitive(draft.extractionContentHash),
            "payloadHash" to JsonPrimitive(payloadHash),
            "chapterVersionId" to JsonPrimitive(draft.source.chapterVersionId),
            "sourceChapterContentHash" to JsonPrimitive(draft.source.chapterContentHash),
            "summaryId" to JsonPrimitive(draft.summary.chapterSummaryId),
            "eventCount" to JsonPrimitive(draft.entityEvents.size),
            "factCount" to JsonPrimitive(draft.canonFacts.size),
        ),
    ).toString()

    private fun verifyValidatedArtifact(
        permit: ValidatedOutputCommitPermit,
        expectedCanonicalHash: String,
    ) {
        artifactStore.readBytes(
            artifactRefId = permit.artifactRefId,
            expectedType = ProtectedArtifactType.STREAM_DRAFT,
            maximumBytes = MAX_OUTPUT_BYTES,
        ).use { lease ->
            require(lease.descriptor.revision == permit.artifactRevision)
            lease.withBytes { bytes ->
                require(sha256(bytes) == permit.rawOutputHash)
                val document = runCatching { STRICT_JSON.parseToJsonElement(bytes.decodeToString()) as JsonObject }
                    .getOrElse { throw IllegalArgumentException("Validated chapter-memory artifact is not an object.") }
                require(sha256(document.toString()) == expectedCanonicalHash) {
                    "Chapter-memory mapping no longer matches the validated artifact."
                }
            }
        }
    }

    private fun requireActiveLease(
        stage: GenerationStageEntity,
        token: GenerationLeaseToken,
        operationAt: Long,
    ) {
        require(stage.leaseOwnerId == token.ownerId && stage.leaseAcquiredAt == token.acquiredAt)
        val heartbeatAt = requireNotNull(stage.leaseHeartbeatAt)
        require(operationAt >= stage.updatedAt && operationAt >= heartbeatAt)
        if (leasePolicy.isExpired(heartbeatAt, operationAt)) {
            throw StaleGenerationStateException("Stage lease expired before chapter-memory commit.")
        }
    }

    private fun result(
        stageId: String,
        draft: ChapterMemoryExtractionCommitDraft,
        replayed: Boolean,
    ) = ChapterMemoryExtractionCommitResult(
        stageId = stageId,
        chapterVersionId = draft.source.chapterVersionId,
        eventCount = draft.entityEvents.size,
        factCount = draft.canonFacts.size,
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

    private fun requireJson(value: String) {
        require(value.isNotBlank() && value.length <= MAX_JSON_CHARS)
        runCatching { STRICT_JSON.parseToJsonElement(value) }
            .getOrElse { throw IllegalArgumentException("Chapter-memory persistence JSON is invalid.") }
    }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .toHex()

    private fun sha256(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return try {
            sha256(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private companion object {
        val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
        val HASH = Regex("[0-9a-f]{64}")
        val ALLOWED_DERIVED_CANON = setOf(CanonLevel.STORY_CANON, CanonLevel.INFERRED)
        val STRICT_JSON = Json { isLenient = false }
        val EVENT_ORDER = compareBy<EntityEventEntity>({ it.storyOrder }, { it.entityEventId })
        const val MAX_OUTPUT_BYTES = 512 * 1_024
        const val MAX_JSON_CHARS = 256 * 1_024
    }
}
