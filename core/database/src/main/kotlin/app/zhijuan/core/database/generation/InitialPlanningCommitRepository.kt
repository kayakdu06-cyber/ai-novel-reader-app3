package app.zhijuan.core.database.generation

import androidx.room.withTransaction
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.database.memory.CanonFactEntity
import app.zhijuan.core.database.memory.OutlineNodeEntity
import app.zhijuan.core.database.memory.OutlineRevisionEntity
import app.zhijuan.core.database.memory.StoryBibleRevisionEntity
import app.zhijuan.core.database.memory.StoryEntity
import app.zhijuan.core.database.search.MemorySearchIndexWriterV1
import app.zhijuan.core.model.AdultStatus
import app.zhijuan.core.model.BookStatus
import app.zhijuan.core.model.CanonLevel
import app.zhijuan.core.model.DerivedDataStatus
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.model.OutlineNodeType
import app.zhijuan.core.model.RequestAttemptStatus
import app.zhijuan.core.model.RevisionSource
import app.zhijuan.core.model.StoryEntityType
import app.zhijuan.core.model.UsageLedgerStatus
import app.zhijuan.core.security.AndroidProtectedArtifactStore
import app.zhijuan.core.security.ProtectedArtifactType
import app.zhijuan.core.task.GenerationJobStateMachine
import app.zhijuan.core.task.GenerationStageStateMachine
import app.zhijuan.core.task.JobEvent
import app.zhijuan.core.task.PromptBundleCatalogV1
import app.zhijuan.core.task.StageEvent
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class StorySeedPlanningCommitDraft(
    val schemaId: String,
    val canonicalJson: String,
    val contentHash: String,
    val nextStageId: String,
    val usage: FinalUsageCommit = FinalUsageCommit.UNKNOWN,
    val committedAt: Long,
)

data class StoryBiblePlanningCommitDraft(
    val schemaId: String,
    val revision: StoryBibleRevisionEntity,
    val characters: List<StoryEntity>,
    val hardFacts: List<CanonFactEntity>,
    val nextStageId: String,
    val usage: FinalUsageCommit = FinalUsageCommit.UNKNOWN,
    val committedAt: Long,
) {
    override fun toString(): String =
        "StoryBiblePlanningCommitDraft(characterCount=${characters.size}, hardFactCount=${hardFacts.size}, content=redacted)"
}

data class MasterOutlinePlanningCommitDraft(
    val schemaId: String,
    val revision: OutlineRevisionEntity,
    val nodes: List<OutlineNodeEntity>,
    val usage: FinalUsageCommit = FinalUsageCommit.UNKNOWN,
    val committedAt: Long,
) {
    override fun toString(): String =
        "MasterOutlinePlanningCommitDraft(nodeCount=${nodes.size}, content=redacted)"
}

data class InitialPlanningCommitResult(
    val phase: GenerationPhase,
    val stageId: String,
    val nextStageId: String?,
    val jobCompleted: Boolean,
    val replayed: Boolean,
) {
    override fun toString(): String =
        "InitialPlanningCommitResult(phase=$phase, jobCompleted=$jobCompleted, replayed=$replayed, identifiers=redacted)"
}

class InitialPlanningCommitRepository(
    private val database: ZhijuanDatabase,
    private val artifactStore: AndroidProtectedArtifactStore,
    private val leasePolicy: GenerationLeasePolicy = GenerationLeasePolicy(),
) {
    suspend fun commitStorySeed(
        permit: ValidatedOutputCommitPermit,
        draft: StorySeedPlanningCommitDraft,
    ): InitialPlanningCommitResult {
        validateCommon(draft.schemaId, "story-seed.v1", draft.contentHash, draft.nextStageId, draft.committedAt)
        requireJson(draft.canonicalJson)
        require(sha256Utf8(draft.canonicalJson) == draft.contentHash) {
            "Story-seed content hash does not match its canonical payload."
        }
        return commit(
            permit = permit,
            expectedPhase = GenerationPhase.BUILD_STORY_SEED,
            expectedTargetType = GenerationTargetType.BOOK,
            schemaId = draft.schemaId,
            contentHash = draft.contentHash,
            committedObjectId = null,
            nextStageId = draft.nextStageId,
            expectedNextPhase = GenerationPhase.BUILD_BIBLE,
            usage = draft.usage,
            committedAt = draft.committedAt,
        ) { _, _, _ -> Unit }
    }

    suspend fun commitStoryBible(
        permit: ValidatedOutputCommitPermit,
        draft: StoryBiblePlanningCommitDraft,
    ): InitialPlanningCommitResult {
        validateBibleDraft(draft)
        return commit(
            permit = permit,
            expectedPhase = GenerationPhase.BUILD_BIBLE,
            expectedTargetType = GenerationTargetType.STORY_BIBLE,
            schemaId = draft.schemaId,
            contentHash = draft.revision.contentHash,
            committedObjectId = draft.revision.bibleRevisionId,
            nextStageId = draft.nextStageId,
            expectedNextPhase = GenerationPhase.BUILD_MASTER_OUTLINE,
            usage = draft.usage,
            committedAt = draft.committedAt,
        ) { stage, _, replayed ->
            val memory = database.memoryDao()
            require(
                draft.revision.bookId == stage.targetId &&
                    draft.revision.generationStageId == stage.stageId,
            ) { "Bible revision belongs to another book or generation stage." }
            if (replayed) {
                val revision = requireNotNull(memory.findBibleRevision(draft.revision.bibleRevisionId)) {
                    "Completed Bible stage is missing its immutable revision."
                }
                require(
                    revision.generationStageId == stage.stageId &&
                        revision.contentHash == draft.revision.contentHash,
                ) { "Completed Bible stage does not match the replayed revision." }
            } else {
                memory.createBibleRevision(draft.revision)
                draft.characters.forEach { memory.insertStoryEntity(it) }
                if (draft.hardFacts.isNotEmpty()) memory.insertCanonFacts(draft.hardFacts)
                MemorySearchIndexWriterV1.replaceStoryBible(
                    search = database.memorySearchDao(),
                    storyEntities = draft.characters,
                    canonFacts = draft.hardFacts,
                )
            }
        }
    }

    suspend fun commitMasterOutline(
        permit: ValidatedOutputCommitPermit,
        draft: MasterOutlinePlanningCommitDraft,
    ): InitialPlanningCommitResult {
        validateOutlineDraft(draft)
        return commit(
            permit = permit,
            expectedPhase = GenerationPhase.BUILD_MASTER_OUTLINE,
            expectedTargetType = GenerationTargetType.OUTLINE,
            schemaId = draft.schemaId,
            contentHash = draft.revision.contentHash,
            committedObjectId = draft.revision.outlineRevisionId,
            nextStageId = null,
            expectedNextPhase = null,
            usage = draft.usage,
            committedAt = draft.committedAt,
        ) { stage, _, replayed ->
            val memory = database.memoryDao()
            require(
                draft.revision.bookId == stage.targetId &&
                    draft.revision.generationStageId == stage.stageId,
            ) { "Outline revision belongs to another book or generation stage." }
            if (replayed) {
                val revision = requireNotNull(memory.findOutlineRevision(draft.revision.outlineRevisionId)) {
                    "Completed outline stage is missing its immutable revision."
                }
                require(
                    revision.generationStageId == stage.stageId &&
                        revision.contentHash == draft.revision.contentHash,
                ) { "Completed outline stage does not match the replayed revision." }
            } else {
                memory.createOutlineRevision(draft.revision, draft.nodes)
            }
        }
    }

    private suspend fun commit(
        permit: ValidatedOutputCommitPermit,
        expectedPhase: GenerationPhase,
        expectedTargetType: GenerationTargetType,
        schemaId: String,
        contentHash: String,
        committedObjectId: String?,
        nextStageId: String?,
        expectedNextPhase: GenerationPhase?,
        usage: FinalUsageCommit,
        committedAt: Long,
        persist: suspend (GenerationStageEntity, GenerationJobEntity, Boolean) -> Unit,
    ): InitialPlanningCommitResult {
        require(committedAt >= permit.validatedAt) {
            "Planning commit cannot precede structured validation."
        }
        val outputReference = outputReferenceJson(
            permit = permit,
            schemaId = schemaId,
            contentHash = contentHash,
            committedObjectId = committedObjectId,
            nextStageId = nextStageId,
        )
        if (database.generationDao().findStage(permit.stageId)?.status != GenerationStageStatus.SUCCEEDED) {
            verifyValidatedArtifact(permit)
        }
        return database.withTransaction {
            val generation = database.generationDao()
            val stage = requireNotNull(generation.findStage(permit.stageId)) {
                "Validated planning stage no longer exists."
            }
            val attempt = requireNotNull(generation.findAttempt(permit.attemptId)) {
                "Validated planning attempt no longer exists."
            }
            val job = requireNotNull(generation.findJob(stage.jobId)) {
                "Owning planning job no longer exists."
            }
            val book = requireNotNull(database.libraryDao().findBook(job.bookId)) {
                "Owning planning book no longer exists."
            }
            require(
                attempt.stageId == stage.stageId &&
                    attempt.status == RequestAttemptStatus.SUCCEEDED &&
                    attempt.standardErrorCode == null &&
                    attempt.outputHash == permit.rawOutputHash &&
                    attempt.streamDraftRef == permit.artifactRefId &&
                    generation.attemptsForStage(stage.stageId).lastOrNull()?.attemptId == attempt.attemptId,
            ) { "Validated planning output evidence changed before commit." }
            require(
                stage.phase == expectedPhase &&
                    stage.targetType == expectedTargetType &&
                    stage.targetId == book.bookId &&
                    job.bookId == book.bookId,
            ) { "Planning stage phase, target, or book does not match the commit." }
            require(job.promptBundleVersion == PromptBundleCatalogV1.BUNDLE_VERSION) {
                "Planning job did not freeze the supported Prompt Bundle."
            }
            require(book.status in setOf(BookStatus.DRAFT, BookStatus.GENERATING)) {
                "This book cannot accept generated planning revisions."
            }
            require(generation.findUsageForAttempt(attempt.attemptId)?.bookId == book.bookId) {
                "Planning usage ledger is missing or belongs to another book."
            }

            if (stage.status == GenerationStageStatus.SUCCEEDED) {
                require(stage.outputReferenceJson == outputReference) {
                    "Completed planning stage does not match the replayed payload."
                }
                persist(stage, job, true)
                generation.recordUsage(attempt.attemptId, usage.toFinalUpdate(committedAt))
                return@withTransaction InitialPlanningCommitResult(
                    phase = expectedPhase,
                    stageId = stage.stageId,
                    nextStageId = nextStageId,
                    jobCompleted = job.status == GenerationJobStatus.COMPLETED,
                    replayed = true,
                )
            }

            require(stage.status == GenerationStageStatus.COMMITTING) {
                "Planning output can only commit from COMMITTING."
            }
            require(
                job.status in setOf(GenerationJobStatus.RUNNING, GenerationJobStatus.PAUSING) &&
                    job.currentStageId == stage.stageId,
            ) { "Planning job is not running the validated stage." }
            requireActiveLease(stage, permit.leaseToken, committedAt)
            persist(stage, job, false)
            generation.recordUsage(attempt.attemptId, usage.toFinalUpdate(committedAt))
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
                    updatedAt = committedAt,
                ) != 1
            ) {
                throw StaleGenerationStateException("Planning commit lost the current stage lease.")
            }

            val jobCompleted = if (nextStageId == null) {
                require(expectedNextPhase == null)
                require(generation.countNonSucceededStages(job.jobId) == 0) {
                    "A next planning stage is required while unfinished stages remain."
                }
                check(
                    GenerationJobStateMachine.transition(job.status, JobEvent.ALL_STAGES_COMPLETED) ==
                        GenerationJobStatus.COMPLETED,
                )
                if (
                    generation.compareAndCompleteJobAfterStage(
                        jobId = job.jobId,
                        expectedCurrentStageId = stage.stageId,
                        updatedAt = committedAt,
                    ) != 1
                ) {
                    throw StaleGenerationStateException("Planning job changed during final commit.")
                }
                true
            } else {
                val next = requireNotNull(generation.findStage(nextStageId)) {
                    "Frozen next planning stage does not exist."
                }
                require(
                    next.jobId == job.jobId && next.phase == expectedNextPhase &&
                        next.targetType == targetTypeFor(requireNotNull(expectedNextPhase)) &&
                        next.targetId == book.bookId && next.status == GenerationStageStatus.PENDING,
                ) { "Frozen next planning stage is not the expected dependency." }
                require(committedAt >= next.updatedAt) { "Planning activation time cannot move backwards." }
                check(
                    GenerationStageStateMachine.transition(next.status, StageEvent.DEPENDENCIES_SATISFIED) ==
                        GenerationStageStatus.READY,
                )
                if (
                    generation.compareAndSetStageStatus(
                        stageId = next.stageId,
                        expectedStatus = GenerationStageStatus.PENDING,
                        nextStatus = GenerationStageStatus.READY,
                        errorCode = null,
                        nextRetryAt = null,
                        updatedAt = committedAt,
                    ) != 1 ||
                    (if (job.status == GenerationJobStatus.PAUSING) {
                        generation.compareAndPauseJobAfterStage(
                            jobId = job.jobId,
                            expectedCurrentStageId = stage.stageId,
                            nextStageId = next.stageId,
                            updatedAt = committedAt,
                        )
                    } else {
                        generation.compareAndAdvanceJobStage(
                            jobId = job.jobId,
                            expectedCurrentStageId = stage.stageId,
                            nextStageId = next.stageId,
                            updatedAt = committedAt,
                        )
                    }) != 1
                ) {
                    throw StaleGenerationStateException("Next planning stage activation lost a concurrent update.")
                }
                false
            }
            InitialPlanningCommitResult(
                phase = expectedPhase,
                stageId = stage.stageId,
                nextStageId = nextStageId,
                jobCompleted = jobCompleted,
                replayed = false,
            )
        }
    }

    private fun validateBibleDraft(draft: StoryBiblePlanningCommitDraft) {
        validateCommon(
            draft.schemaId,
            "story-bible.v1",
            draft.revision.contentHash,
            draft.nextStageId,
            draft.committedAt,
        )
        require(
            IDENTIFIER.matches(draft.revision.bibleRevisionId) &&
                draft.revision.revisionNo == 1 && draft.revision.parentRevisionId == null &&
                draft.revision.source == RevisionSource.AI_GENERATED &&
                draft.revision.schemaVersion == 1 && draft.revision.contentControlSchemaVersion == 1 &&
                draft.revision.generationStageId != null &&
                draft.revision.createdAt == draft.committedAt,
        ) { "Initial Bible revision metadata is invalid." }
        requireJson(draft.revision.payloadJson)
        require(sha256Utf8(draft.revision.payloadJson) == draft.revision.contentHash) {
            "Bible content hash does not match its canonical payload."
        }
        require(draft.characters.isNotEmpty() && draft.characters.size <= 64)
        requireDistinct(draft.characters.map(StoryEntity::entityId), "Bible character ids")
        requireDistinct(draft.characters.map(StoryEntity::canonicalName), "Bible character names")
        draft.characters.forEach { character ->
            require(
                character.bookId == draft.revision.bookId &&
                    character.entityType == StoryEntityType.CHARACTER &&
                    character.sourceBibleRevisionId == draft.revision.bibleRevisionId &&
                    character.createdAt == draft.committedAt && character.updatedAt == draft.committedAt &&
                    character.archivedAt == null,
            ) { "Bible character provenance is invalid." }
            require(
                when (character.adultStatus) {
                    AdultStatus.CONFIRMED_ADULT -> character.ageYears != null && character.ageYears >= 18
                    AdultStatus.NOT_ADULT -> character.ageYears != null && character.ageYears in 0..17
                    AdultStatus.UNKNOWN -> character.ageYears == null
                    AdultStatus.NOT_APPLICABLE -> false
                },
            ) { "Bible character age and adult status are inconsistent." }
            requireJson(character.aliasesJson)
            requireJson(character.stableDefinitionJson)
        }
        require(draft.hardFacts.size <= 512)
        requireDistinct(draft.hardFacts.map(CanonFactEntity::canonFactId), "Bible hard-fact ids")
        val characterIds = draft.characters.map(StoryEntity::entityId).toSet()
        draft.hardFacts.forEach { fact ->
            require(
                fact.bookId == draft.revision.bookId &&
                    fact.sourceBibleRevisionId == draft.revision.bibleRevisionId &&
                    fact.sourceChapterVersionId == null &&
                    fact.canonLevel == CanonLevel.HARD_CANON &&
                    fact.status == DerivedDataStatus.VALID && fact.createdAt == draft.committedAt &&
                    fact.validFromStoryOrder == null && fact.validToStoryOrder == null &&
                    fact.conflictGroupId == null &&
                    (fact.entityId == null || fact.entityId in characterIds),
            ) { "Bible hard-fact provenance is invalid." }
            requireJson(fact.factPayloadJson)
            requireJson(fact.scopeJson)
        }
    }

    private fun validateOutlineDraft(draft: MasterOutlinePlanningCommitDraft) {
        validateCommon(
            draft.schemaId,
            "master-outline.v1",
            draft.revision.contentHash,
            null,
            draft.committedAt,
        )
        require(
            IDENTIFIER.matches(draft.revision.outlineRevisionId) &&
                draft.revision.revisionNo == 1 && draft.revision.parentRevisionId == null &&
                draft.revision.source == RevisionSource.AI_GENERATED &&
                draft.revision.schemaVersion == 1 && draft.revision.generationStageId != null &&
                draft.revision.createdAt == draft.committedAt,
        ) { "Initial outline revision metadata is invalid." }
        requireJson(draft.revision.summaryJson)
        require(sha256Utf8(draft.revision.summaryJson) == draft.revision.contentHash) {
            "Outline content hash does not match its canonical payload."
        }
        require(draft.nodes.size == 1)
        val root = draft.nodes.single()
        require(
            root.outlineRevisionId == draft.revision.outlineRevisionId && root.parentNodeId == null &&
                root.nodeType == OutlineNodeType.BOOK && root.orderKey == 0L &&
                root.plannedChapterIndex == null && root.createdAt == draft.committedAt &&
                root.contentHash == draft.revision.contentHash,
        ) { "Initial master-outline root metadata is invalid." }
        requireJson(root.planJson)
        require(root.planJson == draft.revision.summaryJson) {
            "Master-outline root must preserve the validated canonical payload."
        }
    }

    private fun validateCommon(
        schemaId: String,
        expectedSchemaId: String,
        contentHash: String,
        nextStageId: String?,
        committedAt: Long,
    ) {
        require(schemaId == expectedSchemaId)
        require(HASH.matches(contentHash)) { "Planning content hash is invalid." }
        require(nextStageId == null || IDENTIFIER.matches(nextStageId)) { "Next planning stage id is invalid." }
        require(committedAt >= 0L)
    }

    private fun requireActiveLease(
        stage: GenerationStageEntity,
        token: GenerationLeaseToken,
        operationAt: Long,
    ) {
        require(stage.leaseOwnerId == token.ownerId && stage.leaseAcquiredAt == token.acquiredAt) {
            "Planning commit does not own the current stage lease."
        }
        val heartbeatAt = requireNotNull(stage.leaseHeartbeatAt) { "Planning stage lease is incomplete." }
        require(operationAt >= stage.updatedAt && operationAt >= heartbeatAt) {
            "Planning commit time cannot move backwards."
        }
        if (leasePolicy.isExpired(heartbeatAt, operationAt)) {
            throw StaleGenerationStateException("Stage lease expired before planning commit.")
        }
    }

    private fun verifyValidatedArtifact(permit: ValidatedOutputCommitPermit) {
        artifactStore.readBytes(
            artifactRefId = permit.artifactRefId,
            expectedType = ProtectedArtifactType.STREAM_DRAFT,
            maximumBytes = MAX_OUTPUT_BYTES,
        ).use { lease ->
            require(lease.descriptor.revision == permit.artifactRevision) {
                "Validated planning output revision changed before commit."
            }
            require(lease.withBytes(::sha256) == permit.rawOutputHash) {
                "Validated planning output hash changed before commit."
            }
        }
    }

    private fun outputReferenceJson(
        permit: ValidatedOutputCommitPermit,
        schemaId: String,
        contentHash: String,
        committedObjectId: String?,
        nextStageId: String?,
    ): String = JsonObject(
        linkedMapOf(
            "schemaVersion" to JsonPrimitive(1),
            "outputSchemaId" to JsonPrimitive(schemaId),
            "attemptId" to JsonPrimitive(permit.attemptId),
            "rawOutputHash" to JsonPrimitive(permit.rawOutputHash),
            "contentHash" to JsonPrimitive(contentHash),
            "committedObjectId" to (committedObjectId?.let(::JsonPrimitive) ?: JsonNull),
            "nextStageId" to (nextStageId?.let(::JsonPrimitive) ?: JsonNull),
        ),
    ).toString()

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
        require(value.toByteArray(Charsets.UTF_8).size in 2..MAX_OUTPUT_BYTES)
        runCatching { JSON.parseToJsonElement(value) }
            .getOrElse { throw IllegalArgumentException("Planning persistence JSON is invalid.") }
    }

    private fun requireDistinct(values: List<String>, label: String) {
        require(values.distinct().size == values.size) { "$label must be unique." }
    }

    private fun targetTypeFor(phase: GenerationPhase): GenerationTargetType = when (phase) {
        GenerationPhase.BUILD_BIBLE -> GenerationTargetType.STORY_BIBLE
        GenerationPhase.BUILD_MASTER_OUTLINE -> GenerationTargetType.OUTLINE
        else -> error("This phase is not an initial-planning dependency.")
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun sha256Utf8(value: String): String = sha256(value.toByteArray(Charsets.UTF_8))

    private companion object {
        const val MAX_OUTPUT_BYTES = 512 * 1_024
        val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
        val HASH = Regex("[0-9a-f]{64}")
        val JSON = Json { isLenient = false }
    }
}
