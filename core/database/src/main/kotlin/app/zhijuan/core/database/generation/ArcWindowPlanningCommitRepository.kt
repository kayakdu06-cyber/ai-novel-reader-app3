package app.zhijuan.core.database.generation

import androidx.room.withTransaction
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.database.memory.OutlineNodeEntity
import app.zhijuan.core.database.memory.OutlineRevisionEntity
import app.zhijuan.core.model.BookStatus
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationJobType
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.model.OutlineNodeType
import app.zhijuan.core.model.RequestAttemptStatus
import app.zhijuan.core.model.RevisionSource
import app.zhijuan.core.model.UsageLedgerStatus
import app.zhijuan.core.security.AndroidProtectedArtifactStore
import app.zhijuan.core.security.ProtectedArtifactType
import app.zhijuan.core.task.ArcPlanningWindowPolicyV1
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

data class ArcWindowPlanningCommitDraft(
    val schemaId: String,
    val policyVersion: String,
    val masterOutlineRevisionId: String,
    val masterOutlineContentHash: String,
    val parentOutlineContentHash: String,
    val revision: OutlineRevisionEntity,
    val nodes: List<OutlineNodeEntity>,
    val usage: FinalUsageCommit = FinalUsageCommit.UNKNOWN,
    val committedAt: Long,
) {
    override fun toString(): String =
        "ArcWindowPlanningCommitDraft(nodeCount=${nodes.size}, content=redacted)"
}

data class ArcWindowPlanningCommitResult(
    val stageId: String,
    val outlineRevisionId: String,
    val windowStartChapter: Int,
    val windowEndChapter: Int,
    val nextWindowStartChapter: Int?,
    val replayed: Boolean,
) {
    override fun toString(): String =
        "ArcWindowPlanningCommitResult(window=$windowStartChapter..$windowEndChapter, replayed=$replayed, identifiers=redacted)"
}

class ArcWindowPlanningCommitRepository(
    private val database: ZhijuanDatabase,
    private val artifactStore: AndroidProtectedArtifactStore,
    private val leasePolicy: GenerationLeasePolicy = GenerationLeasePolicy(),
) {
    suspend fun commit(
        permit: ValidatedOutputCommitPermit,
        draft: ArcWindowPlanningCommitDraft,
    ): ArcWindowPlanningCommitResult {
        val metadata = validateDraft(draft)
        require(draft.committedAt >= permit.validatedAt) {
            "Arc-window commit cannot precede structured validation."
        }
        val outputReference = outputReferenceJson(permit, draft, metadata)
        if (database.generationDao().findStage(permit.stageId)?.status != GenerationStageStatus.SUCCEEDED) {
            verifyValidatedArtifact(permit)
        }
        return database.withTransaction {
            val generation = database.generationDao()
            val memory = database.memoryDao()
            val stage = requireNotNull(generation.findStage(permit.stageId)) {
                "Validated arc-window stage no longer exists."
            }
            val attempt = requireNotNull(generation.findAttempt(permit.attemptId)) {
                "Validated arc-window attempt no longer exists."
            }
            val job = requireNotNull(generation.findJob(stage.jobId)) {
                "Owning arc-window job no longer exists."
            }
            val book = requireNotNull(database.libraryDao().findBook(job.bookId)) {
                "Owning arc-window book no longer exists."
            }
            require(
                attempt.stageId == stage.stageId &&
                    attempt.status == RequestAttemptStatus.SUCCEEDED &&
                    attempt.standardErrorCode == null &&
                    attempt.outputHash == permit.rawOutputHash &&
                    attempt.streamDraftRef == permit.artifactRefId &&
                    generation.attemptsForStage(stage.stageId).lastOrNull()?.attemptId == attempt.attemptId,
            ) { "Validated arc-window output evidence changed before commit." }
            require(
                stage.phase == GenerationPhase.BUILD_ARC_PLAN &&
                    stage.targetType == GenerationTargetType.OUTLINE &&
                    stage.targetId == book.bookId &&
                    job.bookId == book.bookId &&
                    job.jobType == GenerationJobType.CONTINUE_BOOK,
            ) { "Arc-window stage phase, target, job type, or book is invalid." }
            require(job.promptBundleVersion == PromptBundleCatalogV1.BUNDLE_VERSION)
            require(book.status in setOf(BookStatus.DRAFT, BookStatus.GENERATING))
            require(generation.findUsageForAttempt(attempt.attemptId)?.bookId == book.bookId) {
                "Arc-window usage ledger is missing or belongs to another book."
            }
            require(
                draft.revision.bookId == book.bookId &&
                    draft.revision.generationStageId == stage.stageId,
            ) { "Arc-window revision belongs to another book or stage." }
            validateFrozenInput(stage.inputSourcesJson, draft, metadata)

            if (stage.status == GenerationStageStatus.SUCCEEDED) {
                require(stage.outputReferenceJson == outputReference) {
                    "Completed arc-window stage does not match the replayed payload."
                }
                val storedRevision = requireNotNull(memory.findOutlineRevision(draft.revision.outlineRevisionId)) {
                    "Completed arc-window stage is missing its immutable revision."
                }
                require(storedRevision == draft.revision)
                require(memory.findOutlineNodes(draft.revision.outlineRevisionId) == draft.nodes.sortedBy { it.orderKey })
                generation.recordUsage(attempt.attemptId, draft.usage.toFinalUpdate(draft.committedAt))
                return@withTransaction result(stage.stageId, draft, metadata, replayed = true)
            }

            require(stage.status == GenerationStageStatus.COMMITTING) {
                "Arc-window output can only commit from COMMITTING."
            }
            require(
                job.status in setOf(GenerationJobStatus.RUNNING, GenerationJobStatus.PAUSING) &&
                    job.currentStageId == stage.stageId,
            ) { "Arc-window job is not running the validated stage." }
            requireActiveLease(stage, permit.leaseToken, draft.committedAt)

            val master = requireNotNull(memory.findOutlineRevision(draft.masterOutlineRevisionId)) {
                "Frozen master outline no longer exists."
            }
            require(
                master.bookId == book.bookId && master.revisionNo == 1 && master.parentRevisionId == null &&
                    master.contentHash == draft.masterOutlineContentHash,
            ) { "Frozen master outline evidence changed before window commit." }
            val parent = requireNotNull(memory.findOutlineRevision(requireNotNull(draft.revision.parentRevisionId))) {
                "Parent outline revision no longer exists."
            }
            val head = requireNotNull(memory.findMemoryHead(book.bookId)) {
                "Book outline head is missing."
            }
            require(
                parent.bookId == book.bookId && parent.contentHash == draft.parentOutlineContentHash &&
                    head.currentOutlineRevisionId == parent.outlineRevisionId &&
                    draft.revision.revisionNo == parent.revisionNo + 1,
            ) { "Arc-window parent is not the current immutable outline head." }

            memory.createOutlineRevision(draft.revision, draft.nodes)
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
                throw StaleGenerationStateException("Arc-window commit lost the current stage lease.")
            }
            require(generation.countNonSucceededStages(job.jobId) == 0) {
                "Arc-window job contains an unexpected unfinished stage."
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
                throw StaleGenerationStateException("Arc-window job changed during final commit.")
            }
            result(stage.stageId, draft, metadata, replayed = false)
        }
    }

    private fun validateDraft(draft: ArcWindowPlanningCommitDraft): WindowMetadata {
        require(draft.schemaId == "arc-plan.v1")
        require(draft.policyVersion == ArcPlanningWindowPolicyV1.POLICY_VERSION)
        require(
            IDENTIFIER.matches(draft.masterOutlineRevisionId) &&
                HASH.matches(draft.masterOutlineContentHash) && HASH.matches(draft.parentOutlineContentHash),
        )
        require(
            IDENTIFIER.matches(draft.revision.outlineRevisionId) &&
                draft.revision.revisionNo >= 2 && draft.revision.parentRevisionId != null &&
                IDENTIFIER.matches(draft.revision.parentRevisionId) &&
                draft.revision.source == RevisionSource.AI_GENERATED &&
                draft.revision.schemaVersion == 1 && draft.revision.generationStageId != null &&
                draft.revision.createdAt == draft.committedAt && draft.committedAt >= 0L,
        ) { "Arc-window revision metadata is invalid." }
        requireJson(draft.revision.summaryJson)
        require(sha256Utf8(draft.revision.summaryJson) == draft.revision.contentHash) {
            "Arc-window revision hash does not match its canonical payload."
        }
        val summary = JSON.parseToJsonElement(draft.revision.summaryJson) as JsonObject
        require(summary.string("policyVersion") == draft.policyVersion)
        require(summary.string("masterOutlineContentHash") == draft.masterOutlineContentHash)
        require(summary.string("parentOutlineContentHash") == draft.parentOutlineContentHash)
        val arc = summary.objectValue("arc")
        val window = summary.objectValue("chapterWindow")
        val metadata = WindowMetadata(
            targetChapterCount = summary.int("targetChapterCount"),
            arcId = arc.string("arcId"),
            arcStartChapter = arc.int("startChapter"),
            arcEndChapter = arc.int("endChapter"),
            windowId = window.string("windowId"),
            windowStartChapter = window.int("startChapter"),
            windowEndChapter = window.int("endChapter"),
            nextWindowStartChapter = summary.nullableInt("nextWindowStartChapter"),
        )
        require(metadata.targetChapterCount in 80..10_000)
        require(metadata.arcStartChapter in 1..metadata.arcEndChapter)
        require(metadata.arcEndChapter <= metadata.targetChapterCount)
        require(metadata.arcEndChapter - metadata.arcStartChapter + 1 <= ArcPlanningWindowPolicyV1.MAX_ARC_CHAPTERS)
        require(metadata.windowStartChapter in metadata.arcStartChapter..metadata.arcEndChapter)
        require(metadata.windowEndChapter in metadata.windowStartChapter..metadata.arcEndChapter)
        require(metadata.windowEndChapter - metadata.windowStartChapter + 1 <= ArcPlanningWindowPolicyV1.MAX_WINDOW_CHAPTERS)
        require(
            metadata.nextWindowStartChapter ==
                (metadata.windowEndChapter + 1).takeIf { it <= metadata.targetChapterCount },
        )

        require(draft.nodes.size == metadata.windowEndChapter - metadata.windowStartChapter + 3)
        require(draft.nodes.map { it.outlineNodeId }.distinct().size == draft.nodes.size)
        require(draft.nodes.map { it.orderKey }.distinct().size == draft.nodes.size)
        require(draft.nodes.all {
            it.outlineRevisionId == draft.revision.outlineRevisionId && it.createdAt == draft.committedAt &&
                IDENTIFIER.matches(it.outlineNodeId) && it.title.isNotBlank() && HASH.matches(it.contentHash)
        })
        draft.nodes.forEach { node ->
            requireJson(node.planJson)
            require(sha256Utf8(node.planJson) == node.contentHash)
        }
        val root = draft.nodes.single { it.nodeType == OutlineNodeType.BOOK }
        val arcNode = draft.nodes.single { it.nodeType == OutlineNodeType.ARC }
        val chapters = draft.nodes.filter { it.nodeType == OutlineNodeType.CHAPTER }.sortedBy { it.plannedChapterIndex }
        require(
            root.parentNodeId == null && root.orderKey == 0L && root.plannedChapterIndex == null &&
                root.planJson == draft.revision.summaryJson && root.contentHash == draft.revision.contentHash,
        )
        require(arcNode.parentNodeId == root.outlineNodeId && arcNode.plannedChapterIndex == null)
        require(chapters.all { it.parentNodeId == arcNode.outlineNodeId })
        require(
            chapters.mapNotNull { it.plannedChapterIndex } ==
                (metadata.windowStartChapter..metadata.windowEndChapter).toList(),
        )
        return metadata
    }

    private fun validateFrozenInput(
        inputSourcesJson: String,
        draft: ArcWindowPlanningCommitDraft,
        metadata: WindowMetadata,
    ) {
        requireJson(inputSourcesJson)
        val input = JSON.parseToJsonElement(inputSourcesJson) as JsonObject
        require(input.int("schemaVersion") == 1)
        require(input.string("policyVersion") == draft.policyVersion)
        require(input.string("promptBundleVersion") == PromptBundleCatalogV1.BUNDLE_VERSION)
        require(input.string("outputSchemaId") == draft.schemaId)
        require(input.string("masterOutlineRevisionId") == draft.masterOutlineRevisionId)
        require(input.string("masterOutlineContentHash") == draft.masterOutlineContentHash)
        require(input.string("parentOutlineRevisionId") == draft.revision.parentRevisionId)
        require(input.string("parentOutlineContentHash") == draft.parentOutlineContentHash)
        require(input.int("targetChapterCount") == metadata.targetChapterCount)
        require(input.string("arcId") == metadata.arcId)
        require(input.int("arcStartChapter") == metadata.arcStartChapter)
        require(input.int("arcEndChapter") == metadata.arcEndChapter)
        require(input.string("windowId") == metadata.windowId)
        require(input.int("windowStartChapter") == metadata.windowStartChapter)
        require(input.int("windowEndChapter") == metadata.windowEndChapter)
        require(input.nullableInt("nextWindowStartChapter") == metadata.nextWindowStartChapter)
    }

    private fun result(
        stageId: String,
        draft: ArcWindowPlanningCommitDraft,
        metadata: WindowMetadata,
        replayed: Boolean,
    ) = ArcWindowPlanningCommitResult(
        stageId = stageId,
        outlineRevisionId = draft.revision.outlineRevisionId,
        windowStartChapter = metadata.windowStartChapter,
        windowEndChapter = metadata.windowEndChapter,
        nextWindowStartChapter = metadata.nextWindowStartChapter,
        replayed = replayed,
    )

    private fun requireActiveLease(
        stage: GenerationStageEntity,
        token: GenerationLeaseToken,
        operationAt: Long,
    ) {
        require(stage.leaseOwnerId == token.ownerId && stage.leaseAcquiredAt == token.acquiredAt)
        val heartbeatAt = requireNotNull(stage.leaseHeartbeatAt)
        require(operationAt >= stage.updatedAt && operationAt >= heartbeatAt)
        if (leasePolicy.isExpired(heartbeatAt, operationAt)) {
            throw StaleGenerationStateException("Stage lease expired before arc-window commit.")
        }
    }

    private fun verifyValidatedArtifact(permit: ValidatedOutputCommitPermit) {
        artifactStore.readBytes(
            artifactRefId = permit.artifactRefId,
            expectedType = ProtectedArtifactType.STREAM_DRAFT,
            maximumBytes = MAX_OUTPUT_BYTES,
        ).use { lease ->
            require(lease.descriptor.revision == permit.artifactRevision)
            require(lease.withBytes(::sha256) == permit.rawOutputHash)
        }
    }

    private fun outputReferenceJson(
        permit: ValidatedOutputCommitPermit,
        draft: ArcWindowPlanningCommitDraft,
        metadata: WindowMetadata,
    ): String = JsonObject(
        linkedMapOf(
            "schemaVersion" to JsonPrimitive(1),
            "outputSchemaId" to JsonPrimitive(draft.schemaId),
            "policyVersion" to JsonPrimitive(draft.policyVersion),
            "attemptId" to JsonPrimitive(permit.attemptId),
            "rawOutputHash" to JsonPrimitive(permit.rawOutputHash),
            "contentHash" to JsonPrimitive(draft.revision.contentHash),
            "outlineRevisionId" to JsonPrimitive(draft.revision.outlineRevisionId),
            "windowStartChapter" to JsonPrimitive(metadata.windowStartChapter),
            "windowEndChapter" to JsonPrimitive(metadata.windowEndChapter),
            "nextWindowStartChapter" to (metadata.nextWindowStartChapter?.let(::JsonPrimitive) ?: JsonNull),
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
            .getOrElse { throw IllegalArgumentException("Arc-window persistence JSON is invalid.") }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun sha256Utf8(value: String): String = sha256(value.toByteArray(Charsets.UTF_8))

    private data class WindowMetadata(
        val targetChapterCount: Int,
        val arcId: String,
        val arcStartChapter: Int,
        val arcEndChapter: Int,
        val windowId: String,
        val windowStartChapter: Int,
        val windowEndChapter: Int,
        val nextWindowStartChapter: Int?,
    )

    private companion object {
        const val MAX_OUTPUT_BYTES = 512 * 1_024
        val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
        val HASH = Regex("[0-9a-f]{64}")
        val JSON = Json { isLenient = false }
    }
}

private fun JsonObject.string(key: String): String =
    (getValue(key) as JsonPrimitive).takeIf(JsonPrimitive::isString)?.contentOrNull
        ?: throw IllegalArgumentException("Arc-window JSON string field is invalid.")

private fun JsonObject.int(key: String): Int =
    (getValue(key) as JsonPrimitive).takeUnless(JsonPrimitive::isString)?.intOrNull
        ?: throw IllegalArgumentException("Arc-window JSON integer field is invalid.")

private fun JsonObject.nullableInt(key: String): Int? {
    val value = getValue(key)
    if (value is JsonNull) return null
    return (value as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull
        ?: throw IllegalArgumentException("Arc-window JSON nullable integer field is invalid.")
}

private fun JsonObject.objectValue(key: String): JsonObject =
    getValue(key) as? JsonObject
        ?: throw IllegalArgumentException("Arc-window JSON object field is invalid.")
