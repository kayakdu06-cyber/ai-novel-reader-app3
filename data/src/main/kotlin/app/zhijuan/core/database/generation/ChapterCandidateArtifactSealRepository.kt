package app.zhijuan.core.database.generation

import androidx.room.withTransaction
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.model.RequestAttemptStatus
import app.zhijuan.core.model.UsageLedgerStatus
import app.zhijuan.core.security.AndroidProtectedArtifactStore
import app.zhijuan.core.security.ProtectedArtifactType
import app.zhijuan.core.task.ChapterRevisionNeedsActionReasonV1
import app.zhijuan.core.task.GenerationJobStateMachine
import app.zhijuan.core.task.GenerationStageStateMachine
import app.zhijuan.core.task.JobEvent
import app.zhijuan.core.task.StageIdempotencyKey
import app.zhijuan.core.task.StageEvent
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class ChapterCandidateArtifactRoleV1(
    val schemaId: String,
    internal val allowedPhases: Set<GenerationPhase>,
) {
    BODY("chapter-draft.v1", setOf(GenerationPhase.DRAFT_CHAPTER, GenerationPhase.REVISE_CHAPTER)),
    MEMORY("chapter-memory.v1", setOf(GenerationPhase.EXTRACT_MEMORY)),
    TRACKING("chapter-story-tracking.v1", setOf(GenerationPhase.EXTRACT_MEMORY)),
    CONSISTENCY("chapter-consistency-report.v1", setOf(GenerationPhase.CHECK_CONSISTENCY)),
}

data class ChapterCandidateStageSourceV1(
    val role: ChapterCandidateArtifactRoleV1,
    val candidateChapterVersionId: String,
    val candidateContentHash: String,
    val chapterId: String,
    val chapterIndex: Int,
    val revisionIndex: Int,
    val predecessorStageId: String,
    val routeBindingHash: String? = null,
    val requestSourceBindingHash: String? = null,
)

/** Frozen source envelope for remote stages whose candidate ChapterVersion is not formal yet. */
object ChapterCandidateStageBindingV1 {
    fun stageSetup(
        jobId: String,
        stageId: String,
        phase: GenerationPhase,
        source: ChapterCandidateStageSourceV1,
        maxAttempts: Int,
    ): GenerationStageSetup {
        require(phase in source.role.allowedPhases)
        val inputSources = inputSources(source)
        val inputHash = sha256(listOf(SOURCE_POLICY_VERSION, inputSources).joinToString("\u0000"))
        return GenerationStageSetup(
            stageId = stageId,
            phase = phase,
            targetType = GenerationTargetType.CHAPTER,
            targetId = source.chapterId,
            inputVersionHash = inputHash,
            idempotencyKey = StageIdempotencyKey.create(jobId, phase, source.chapterId, inputHash).value,
            maxAttempts = maxAttempts,
            inputSourcesJson = inputSources,
        )
    }

    internal fun parseIfBound(stage: GenerationStageEntity): ChapterCandidateStageSourceV1? {
        val root = runCatching { STRICT_JSON.parseToJsonElement(stage.inputSourcesJson) }
            .getOrElse { throw IllegalArgumentException("Generation Stage input sources are invalid JSON.") }
        if (root !is JsonObject) return null
        val policyVersion = (root["sourcePolicyVersion"] as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.content
        if (policyVersion != SOURCE_POLICY_VERSION) return null
        return parseAndVerify(stage)
    }

    internal fun parseAndVerify(stage: GenerationStageEntity): ChapterCandidateStageSourceV1 {
        val root = runCatching { STRICT_JSON.parseToJsonElement(stage.inputSourcesJson) as JsonObject }
            .getOrElse { throw IllegalArgumentException("Candidate Stage source is invalid JSON.") }
        require(root.keys == ROOT_KEYS)
        require(root.string("sourcePolicyVersion") == SOURCE_POLICY_VERSION)
        require(root.string("pipelineVersion") == ChapterCandidateArtifactSealRepositoryV1.PIPELINE_VERSION)
        val role = ChapterCandidateArtifactRoleV1.valueOf(root.string("artifactRole"))
        val source = ChapterCandidateStageSourceV1(
            role = role,
            candidateChapterVersionId = root.string("candidateChapterVersionId"),
            candidateContentHash = root.string("candidateContentHash"),
            chapterId = root.string("chapterId"),
            chapterIndex = root.intValue("chapterIndex"),
            revisionIndex = root.intValue("revisionIndex"),
            predecessorStageId = root.string("predecessorStageId"),
            routeBindingHash = root.nullableString("routeBindingHash"),
            requestSourceBindingHash = root.nullableString("requestSourceBindingHash"),
        )
        require(stage.phase in role.allowedPhases && stage.targetType == GenerationTargetType.CHAPTER)
        require(stage.targetId == source.chapterId)
        require(listOf(source.candidateChapterVersionId, source.chapterId, source.predecessorStageId).all(IDENTIFIER::matches))
        require(HASH.matches(source.candidateContentHash) && source.chapterIndex in 1..10_000 && source.revisionIndex in 0..2)
        require(source.routeBindingHash == null || HASH.matches(source.routeBindingHash))
        require(source.requestSourceBindingHash == null || HASH.matches(source.requestSourceBindingHash))
        if (source.role == ChapterCandidateArtifactRoleV1.BODY) {
            require(source.routeBindingHash != null && source.requestSourceBindingHash != null)
        } else {
            require(source.requestSourceBindingHash == null)
        }
        require(
            stage.inputVersionHash == sha256(
                listOf(SOURCE_POLICY_VERSION, stage.inputSourcesJson).joinToString("\u0000"),
            ),
        )
        return source
    }

    private fun inputSources(source: ChapterCandidateStageSourceV1): String = JsonObject(
        linkedMapOf(
            "schemaVersion" to JsonPrimitive(1),
            "sourcePolicyVersion" to JsonPrimitive(SOURCE_POLICY_VERSION),
            "pipelineVersion" to JsonPrimitive(ChapterCandidateArtifactSealRepositoryV1.PIPELINE_VERSION),
            "artifactRole" to JsonPrimitive(source.role.name),
            "candidateChapterVersionId" to JsonPrimitive(source.candidateChapterVersionId),
            "candidateContentHash" to JsonPrimitive(source.candidateContentHash),
            "chapterId" to JsonPrimitive(source.chapterId),
            "chapterIndex" to JsonPrimitive(source.chapterIndex),
            "revisionIndex" to JsonPrimitive(source.revisionIndex),
            "predecessorStageId" to JsonPrimitive(source.predecessorStageId),
            "routeBindingHash" to (source.routeBindingHash?.let(::JsonPrimitive) ?: JsonNull),
            "requestSourceBindingHash" to (source.requestSourceBindingHash?.let(::JsonPrimitive) ?: JsonNull),
        ),
    ).toString()

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
            ?: throw IllegalArgumentException("Candidate Stage string field is missing or invalid: $key")

    private fun JsonObject.intValue(key: String): Int =
        (this[key] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.content?.toIntOrNull()
            ?: throw IllegalArgumentException("Candidate Stage integer field is missing or invalid: $key")

    private fun JsonObject.nullableString(key: String): String? = when (val value = this[key]) {
        JsonNull -> null
        is JsonPrimitive -> value.takeIf(JsonPrimitive::isString)?.content
            ?: throw IllegalArgumentException("Candidate Stage nullable field is invalid: $key")
        else -> throw IllegalArgumentException("Candidate Stage nullable field is missing or invalid: $key")
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    internal const val SOURCE_POLICY_VERSION = "zhijuan.chapter-candidate-stage-source.v1"
    private val ROOT_KEYS = setOf(
        "schemaVersion", "sourcePolicyVersion", "pipelineVersion", "artifactRole",
        "candidateChapterVersionId", "candidateContentHash", "chapterId", "chapterIndex",
        "revisionIndex", "predecessorStageId",
        "routeBindingHash",
        "requestSourceBindingHash",
    )
    private val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
    private val HASH = Regex("[0-9a-f]{64}")
    private val STRICT_JSON = Json { isLenient = false; ignoreUnknownKeys = false }
}

internal data class ChapterCandidateSealedStageEvidenceV1(
    val role: ChapterCandidateArtifactRoleV1,
    val stageId: String,
    val attemptId: String,
    val artifactRefId: String,
    val artifactRevision: Int,
    val rawOutputHash: String,
    val canonicalOutputHash: String,
    val sourceBindingHash: String,
    val candidateChapterVersionId: String,
    val candidateContentHash: String,
    val chapterId: String,
    val chapterIndex: Int,
    val revisionIndex: Int,
    val nextStageId: String,
    val routeBindingHash: String?,
) {
    fun toArtifactEvidence(): ChapterFinalCandidateArtifactEvidenceV1 =
        ChapterFinalCandidateArtifactEvidenceV1(
            role = role,
            stageId = stageId,
            attemptId = attemptId,
            artifactRefId = artifactRefId,
            artifactRevision = artifactRevision,
            rawOutputHash = rawOutputHash,
            canonicalOutputHash = canonicalOutputHash,
            sourceBindingHash = sourceBindingHash,
        )

    override fun toString(): String =
        "ChapterCandidateSealedStageEvidenceV1(role=$role, chapterIndex=$chapterIndex, " +
            "revisionIndex=$revisionIndex, evidence=redacted)"
}

internal object ChapterCandidateSealedStageEvidenceParserV1 {
    fun parseAndVerify(stage: GenerationStageEntity): ChapterCandidateSealedStageEvidenceV1 {
        val root = runCatching {
            STRICT_JSON.parseToJsonElement(
                requireNotNull(stage.outputReferenceJson) { "Candidate predecessor output evidence is missing." },
            ) as JsonObject
        }.getOrElse { throw IllegalArgumentException("Candidate predecessor output evidence is invalid JSON.") }
        require(root.keys == ROOT_KEYS)
        require(root.intValue("schemaVersion") == 1)
        require(root.string("pipelineVersion") == ChapterCandidateArtifactSealRepositoryV1.PIPELINE_VERSION)
        val role = ChapterCandidateArtifactRoleV1.valueOf(root.string("artifactRole"))
        require(root.string("outputSchemaId") == role.schemaId)
        val evidence = ChapterCandidateSealedStageEvidenceV1(
            role = role,
            stageId = stage.stageId,
            attemptId = root.string("attemptId"),
            artifactRefId = root.string("artifactRefId"),
            artifactRevision = root.intValue("artifactRevision"),
            rawOutputHash = root.string("rawOutputHash"),
            canonicalOutputHash = root.string("canonicalOutputHash"),
            sourceBindingHash = root.string("sourceBindingHash"),
            candidateChapterVersionId = root.string("candidateChapterVersionId"),
            candidateContentHash = root.string("candidateContentHash"),
            chapterId = root.string("chapterId"),
            chapterIndex = root.intValue("chapterIndex"),
            revisionIndex = root.intValue("revisionIndex"),
            nextStageId = root.string("nextStageId"),
            routeBindingHash = root.nullableString("routeBindingHash"),
        )
        require(stage.phase in role.allowedPhases && stage.targetType == GenerationTargetType.CHAPTER)
        require(stage.targetId == evidence.chapterId)
        require(
            listOf(
                evidence.stageId,
                evidence.attemptId,
                evidence.artifactRefId,
                evidence.candidateChapterVersionId,
                evidence.chapterId,
                evidence.nextStageId,
            ).all(IDENTIFIER::matches),
        )
        require(evidence.artifactRevision > 0)
        require(
            listOf(
                evidence.rawOutputHash,
                evidence.canonicalOutputHash,
                evidence.sourceBindingHash,
                evidence.candidateContentHash,
            ).all(HASH::matches),
        )
        require(evidence.chapterIndex in 1..10_000 && evidence.revisionIndex in 0..2)
        require(evidence.routeBindingHash == null || HASH.matches(evidence.routeBindingHash))
        return evidence
    }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
            ?: throw IllegalArgumentException("Candidate predecessor string field is missing or invalid: $key")

    private fun JsonObject.intValue(key: String): Int =
        (this[key] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.content?.toIntOrNull()
            ?: throw IllegalArgumentException("Candidate predecessor integer field is missing or invalid: $key")

    private fun JsonObject.nullableString(key: String): String? = when (val value = this[key]) {
        JsonNull -> null
        is JsonPrimitive -> value.takeIf(JsonPrimitive::isString)?.content
            ?: throw IllegalArgumentException("Candidate predecessor nullable field is invalid: $key")
        else -> throw IllegalArgumentException("Candidate predecessor nullable field is missing or invalid: $key")
    }

    private val ROOT_KEYS = setOf(
        "schemaVersion", "pipelineVersion", "artifactRole", "outputSchemaId", "attemptId",
        "artifactRefId", "artifactRevision", "rawOutputHash", "canonicalOutputHash", "sourceBindingHash",
        "candidateChapterVersionId", "candidateContentHash", "chapterId", "chapterIndex", "revisionIndex",
        "nextStageId",
        "routeBindingHash",
    )
    private val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
    private val HASH = Regex("[0-9a-f]{64}")
    private val STRICT_JSON = Json { isLenient = false; ignoreUnknownKeys = false }
}

/** Revalidates the sealed direct predecessor before any candidate Stage opens a Provider. */
internal class ChapterCandidateStageSourceGuard(
    private val database: ZhijuanDatabase,
) {
    suspend fun requireProviderOpenAllowedIfBound(
        stage: GenerationStageEntity,
        job: GenerationJobEntity,
        requestInputHash: String,
    ): Boolean {
        val source = try {
            ChapterCandidateStageBindingV1.parseIfBound(stage)
        } catch (_: IllegalArgumentException) {
            stale("Candidate Stage binding is invalid or stale.")
        } ?: return false
        if (source.requestSourceBindingHash != null && source.requestSourceBindingHash != requestInputHash) {
            stale("Candidate request intent does not match the frozen route input.")
        }
        val chapter = database.libraryDao().findChapter(source.chapterId)
            ?: stale("Candidate source chapter no longer exists.")
        if (chapter.bookId != job.bookId || chapter.chapterIndex != source.chapterIndex) {
            stale("Candidate source chapter no longer matches its Job and frozen index.")
        }

        val visited = linkedSetOf(stage.stageId)
        var lineageDepth = 1
        var root = requireSealedPredecessor(stage, source, job)
        while (true) {
            if (!visited.add(root.stageId)) stale("Candidate Stage lineage contains a cycle.")
            val rootSource = try {
                ChapterCandidateStageBindingV1.parseIfBound(root)
            } catch (_: IllegalArgumentException) {
                stale("Candidate Stage lineage contains invalid binding evidence.")
            } ?: break
            if (++lineageDepth > MAXIMUM_LINEAGE_DEPTH) {
                stale("Candidate Stage lineage exceeds the finite revision bound.")
            }
            root = requireSealedPredecessor(root, rootSource, job)
        }
        if (
            root.jobId != job.jobId || root.phase != GenerationPhase.DRAFT_CHAPTER ||
            root.targetType != GenerationTargetType.CHAPTER || root.targetId != source.chapterId ||
            root.status != GenerationStageStatus.SUCCEEDED
        ) {
            stale("Candidate Stage lineage does not end at a sealed initial chapter body.")
        }
        ChapterProgressionGateRepository(database).requireProviderOpenAllowed(root, job)
        ChapterContextAssemblyRepository(database).requireProviderOpenAllowedIfBound(root, job)
        return true
    }

    private suspend fun requireSealedPredecessor(
        stage: GenerationStageEntity,
        source: ChapterCandidateStageSourceV1,
        job: GenerationJobEntity,
    ): GenerationStageEntity {
        if (stage.jobId != job.jobId) stale("Candidate Stage belongs to another Job.")
        if (source.role == ChapterCandidateArtifactRoleV1.BODY && stage.phase != GenerationPhase.REVISE_CHAPTER) {
            stale("A bound candidate BODY Stage must be a revision.")
        }
        val predecessor = database.generationDao().findStage(source.predecessorStageId)
            ?: stale("Candidate predecessor Stage no longer exists.")
        if (
            predecessor.stageId == stage.stageId || predecessor.jobId != job.jobId ||
            predecessor.targetType != GenerationTargetType.CHAPTER || predecessor.targetId != source.chapterId ||
            predecessor.status != GenerationStageStatus.SUCCEEDED
        ) {
            stale("Candidate predecessor Stage is not the sealed source for this Job and chapter.")
        }
        val evidence = try {
            ChapterCandidateSealedStageEvidenceParserV1.parseAndVerify(predecessor)
        } catch (_: IllegalArgumentException) {
            stale("Candidate predecessor output evidence is invalid or stale.")
        }
        val expectedPredecessorRole = when (source.role) {
            ChapterCandidateArtifactRoleV1.BODY -> ChapterCandidateArtifactRoleV1.CONSISTENCY
            ChapterCandidateArtifactRoleV1.MEMORY -> ChapterCandidateArtifactRoleV1.BODY
            ChapterCandidateArtifactRoleV1.TRACKING -> ChapterCandidateArtifactRoleV1.MEMORY
            ChapterCandidateArtifactRoleV1.CONSISTENCY -> ChapterCandidateArtifactRoleV1.TRACKING
        }
        if (
            evidence.role != expectedPredecessorRole || evidence.nextStageId != stage.stageId ||
            evidence.candidateChapterVersionId != source.candidateChapterVersionId ||
            evidence.candidateContentHash != source.candidateContentHash ||
            evidence.chapterId != source.chapterId || evidence.chapterIndex != source.chapterIndex ||
            evidence.revisionIndex != source.revisionIndex ||
            evidence.routeBindingHash != source.routeBindingHash
        ) {
            stale("Candidate Stage binding does not match its sealed predecessor output.")
        }
        return predecessor
    }

    private fun stale(message: String): Nothing = throw StaleGenerationStateException(message)

    private companion object {
        const val MAXIMUM_LINEAGE_DEPTH = 16
    }
}

data class ChapterCandidateArtifactSealDraftV1(
    val role: ChapterCandidateArtifactRoleV1,
    val candidateChapterVersionId: String,
    val chapterId: String,
    val chapterIndex: Int,
    val candidateContentHash: String,
    val canonicalOutputHash: String,
    val sourceBindingHash: String,
    val revisionIndex: Int,
    val usage: FinalUsageCommit,
    val nextStage: GenerationStageSetup,
    val sealedAt: Long,
    val routeBindingHash: String? = null,
) {
    override fun toString(): String =
        "ChapterCandidateArtifactSealDraftV1(role=$role, chapterIndex=$chapterIndex, revisionIndex=$revisionIndex, content=redacted)"
}

data class ChapterCandidateArtifactSealResultV1(
    val stageId: String,
    val nextStageId: String,
    val role: ChapterCandidateArtifactRoleV1,
    val replayed: Boolean,
    val evidence: ChapterFinalCandidateArtifactEvidenceV1,
)

data class ChapterConsistencyNeedsActionDraftV1(
    val candidateChapterVersionId: String,
    val chapterId: String,
    val chapterIndex: Int,
    val candidateContentHash: String,
    val canonicalOutputHash: String,
    val sourceBindingHash: String,
    val revisionIndex: Int,
    val routeBindingHash: String,
    val reason: ChapterRevisionNeedsActionReasonV1,
    val usage: FinalUsageCommit,
    val settledAt: Long,
) {
    override fun toString(): String =
        "ChapterConsistencyNeedsActionDraftV1(chapterIndex=$chapterIndex, revisionIndex=$revisionIndex, " +
            "reason=$reason, content=redacted)"
}

data class ChapterConsistencyNeedsActionResultV1(
    val stageId: String,
    val attemptId: String,
    val reason: ChapterRevisionNeedsActionReasonV1,
    val replayed: Boolean,
)

/**
 * Seals one validated remote candidate artifact and advances to the next frozen Stage
 * without publishing any ChapterVersion or derived memory rows.
 */
class ChapterCandidateArtifactSealRepositoryV1(
    private val database: ZhijuanDatabase,
    private val artifactStore: AndroidProtectedArtifactStore,
    private val leasePolicy: GenerationLeasePolicy = GenerationLeasePolicy(),
) {
    suspend fun seal(
        permit: ValidatedOutputCommitPermit,
        draft: ChapterCandidateArtifactSealDraftV1,
    ): ChapterCandidateArtifactSealResultV1 {
        validateDraft(draft)
        require(draft.sealedAt >= permit.validatedAt)
        val outputReference = outputReferenceJson(permit, draft)
        val evidence = artifactEvidence(permit, draft)
        if (database.generationDao().findStage(permit.stageId)?.status != GenerationStageStatus.SUCCEEDED) {
            verifyArtifact(permit, draft)
        }
        return database.withTransaction {
            val dao = database.generationDao()
            val stage = requireNotNull(dao.findStage(permit.stageId)) { "Candidate Stage no longer exists." }
            val attempt = requireNotNull(dao.findAttempt(permit.attemptId)) { "Candidate Attempt no longer exists." }
            val job = requireNotNull(dao.findJob(stage.jobId)) { "Candidate Job no longer exists." }
            require(
                stage.phase in draft.role.allowedPhases &&
                    stage.targetType == GenerationTargetType.CHAPTER &&
                    stage.targetId == draft.chapterId &&
                    job.bookId == requireNotNull(dao.findBookIdForStage(stage.stageId)) &&
                    attempt.stageId == stage.stageId &&
                    attempt.status == RequestAttemptStatus.SUCCEEDED &&
                    attempt.standardErrorCode == null &&
                    attempt.inputHash == draft.sourceBindingHash &&
                    attempt.outputHash == permit.rawOutputHash &&
                    attempt.streamDraftRef == permit.artifactRefId &&
                    dao.attemptsForStage(stage.stageId).lastOrNull()?.attemptId == attempt.attemptId,
            ) { "Candidate Stage or Attempt evidence changed before sealing." }
            require(dao.findUsageForAttempt(attempt.attemptId)?.bookId == job.bookId) {
                "Candidate Usage ledger is missing or belongs to another book."
            }
            requireCurrentCandidateBinding(
                stage = stage,
                role = draft.role,
                candidateChapterVersionId = draft.candidateChapterVersionId,
                candidateContentHash = draft.candidateContentHash,
                chapterId = draft.chapterId,
                chapterIndex = draft.chapterIndex,
                revisionIndex = draft.revisionIndex,
            )

            if (stage.status == GenerationStageStatus.SUCCEEDED) {
                require(stage.outputReferenceJson == outputReference) {
                    "Completed candidate Stage does not match the replayed seal."
                }
                val next = requireNotNull(dao.findStage(draft.nextStage.stageId)) {
                    "Replayed candidate seal lost its next Stage."
                }
                require(next.matches(stage.jobId, draft.nextStage))
                dao.recordUsage(attempt.attemptId, draft.usage.toFinalUpdate(draft.sealedAt))
                return@withTransaction ChapterCandidateArtifactSealResultV1(
                    stage.stageId,
                    next.stageId,
                    draft.role,
                    replayed = true,
                    evidence = evidence,
                )
            }

            require(stage.status == GenerationStageStatus.COMMITTING) {
                "A candidate artifact can only seal from COMMITTING."
            }
            require(
                job.status in setOf(GenerationJobStatus.RUNNING, GenerationJobStatus.PAUSING) &&
                    job.currentStageId == stage.stageId,
            ) { "Candidate Job is not running the Stage being sealed." }
            requireActiveLease(stage, permit.leaseToken, draft.sealedAt)
            require(dao.findStage(draft.nextStage.stageId) == null) {
                "A new candidate next Stage already exists before its source was sealed."
            }
            val next = draft.nextStage.toEntity(stage.jobId, draft.sealedAt)
            requireNextCandidateBinding(stage, draft, next)
            dao.insertStages(listOf(next))
            dao.recordUsage(attempt.attemptId, draft.usage.toFinalUpdate(draft.sealedAt))
            check(
                GenerationStageStateMachine.transition(stage.status, StageEvent.COMMIT_SUCCEEDED) ==
                    GenerationStageStatus.SUCCEEDED,
            )
            if (
                dao.compareAndCommitStageOutput(
                    stageId = stage.stageId,
                    leaseOwnerId = permit.leaseToken.ownerId,
                    leaseAcquiredAt = permit.leaseToken.acquiredAt,
                    outputReferenceJson = outputReference,
                    updatedAt = draft.sealedAt,
                ) != 1
            ) throw StaleGenerationStateException("Candidate seal lost the current Stage lease.")
            if (
                dao.compareAndSetStageStatus(
                    stageId = next.stageId,
                    expectedStatus = GenerationStageStatus.PENDING,
                    nextStatus = GenerationStageStatus.READY,
                    errorCode = null,
                    nextRetryAt = null,
                    updatedAt = draft.sealedAt,
                ) != 1 ||
                (if (job.status == GenerationJobStatus.PAUSING) {
                    dao.compareAndPauseJobAfterStage(
                        jobId = job.jobId,
                        expectedCurrentStageId = stage.stageId,
                        nextStageId = next.stageId,
                        updatedAt = draft.sealedAt,
                    )
                } else {
                    dao.compareAndAdvanceJobStage(
                        jobId = job.jobId,
                        expectedCurrentStageId = stage.stageId,
                        nextStageId = next.stageId,
                        updatedAt = draft.sealedAt,
                    )
                }) != 1
            ) throw StaleGenerationStateException("Candidate next-Stage activation lost a concurrent update.")
            ChapterCandidateArtifactSealResultV1(
                stage.stageId,
                next.stageId,
                draft.role,
                replayed = false,
                evidence = evidence,
            )
        }
    }

    /**
     * Persists a valid consistency artifact when the finite revision policy has no
     * remaining automatic route. No successor Stage is created and no candidate
     * ChapterVersion becomes formal.
     */
    suspend fun settleConsistencyNeedsAction(
        permit: ValidatedOutputCommitPermit,
        draft: ChapterConsistencyNeedsActionDraftV1,
    ): ChapterConsistencyNeedsActionResultV1 {
        validateNeedsActionDraft(draft)
        require(draft.settledAt >= permit.validatedAt)
        val persistedReason = "CHAPTER_REVISION:${draft.reason.name}"
        val outputReference = needsActionOutputReferenceJson(permit, draft)
        if (database.generationDao().findStage(permit.stageId)?.status != GenerationStageStatus.NEEDS_ACTION) {
            verifyArtifact(
                permit = permit,
                role = ChapterCandidateArtifactRoleV1.CONSISTENCY,
                canonicalOutputHash = draft.canonicalOutputHash,
                candidateContentHash = draft.candidateContentHash,
            )
        }
        return database.withTransaction {
            val dao = database.generationDao()
            val stage = requireNotNull(dao.findStage(permit.stageId)) { "Consistency Stage no longer exists." }
            val attempt = requireNotNull(dao.findAttempt(permit.attemptId)) { "Consistency Attempt no longer exists." }
            val job = requireNotNull(dao.findJob(stage.jobId)) { "Consistency Job no longer exists." }
            require(
                stage.phase == GenerationPhase.CHECK_CONSISTENCY &&
                    stage.targetType == GenerationTargetType.CHAPTER &&
                    stage.targetId == draft.chapterId &&
                    attempt.stageId == stage.stageId &&
                    attempt.status == RequestAttemptStatus.SUCCEEDED &&
                    attempt.standardErrorCode == null &&
                    attempt.inputHash == draft.sourceBindingHash &&
                    attempt.outputHash == permit.rawOutputHash &&
                    attempt.streamDraftRef == permit.artifactRefId &&
                    dao.attemptsForStage(stage.stageId).lastOrNull()?.attemptId == attempt.attemptId,
            ) { "Consistency Stage or Attempt evidence changed before finite routing." }
            require(dao.findUsageForAttempt(attempt.attemptId)?.bookId == job.bookId) {
                "Consistency Usage ledger is missing or belongs to another book."
            }
            requireCurrentCandidateBinding(
                stage = stage,
                role = ChapterCandidateArtifactRoleV1.CONSISTENCY,
                candidateChapterVersionId = draft.candidateChapterVersionId,
                candidateContentHash = draft.candidateContentHash,
                chapterId = draft.chapterId,
                chapterIndex = draft.chapterIndex,
                revisionIndex = draft.revisionIndex,
            )

            if (stage.status == GenerationStageStatus.NEEDS_ACTION) {
                require(stage.outputReferenceJson == outputReference) {
                    "Replayed consistency outcome does not match the persisted finite route."
                }
                require(job.status in setOf(GenerationJobStatus.NEEDS_ACTION, GenerationJobStatus.PAUSED)) {
                    "Consistency Stage and Job disagree about the needs-action route."
                }
                require(job.pauseOrStopReason == persistedReason) {
                    "Replayed consistency outcome has a conflicting reason."
                }
                require(dao.findUsageForAttempt(attempt.attemptId)?.status == UsageLedgerStatus.FINAL) {
                    "A replayed consistency outcome must already have final Usage."
                }
                dao.recordUsage(attempt.attemptId, draft.usage.toFinalUpdate(draft.settledAt))
                return@withTransaction ChapterConsistencyNeedsActionResultV1(
                    stage.stageId,
                    attempt.attemptId,
                    draft.reason,
                    replayed = true,
                )
            }

            require(stage.status == GenerationStageStatus.COMMITTING) {
                "Finite consistency routing can only settle from COMMITTING."
            }
            require(
                job.status in setOf(GenerationJobStatus.RUNNING, GenerationJobStatus.PAUSING) &&
                    job.currentStageId == stage.stageId,
            ) { "Consistency Job is not running the Stage being settled." }
            requireActiveLease(stage, permit.leaseToken, draft.settledAt)
            require(draft.settledAt >= attempt.updatedAt && draft.settledAt >= job.updatedAt)
            check(
                GenerationStageStateMachine.transition(stage.status, StageEvent.USER_ACTION_REQUIRED) ==
                    GenerationStageStatus.NEEDS_ACTION,
            )
            if (
                dao.compareAndSetStageNeedsActionWithOutput(
                    stageId = stage.stageId,
                    leaseOwnerId = permit.leaseToken.ownerId,
                    leaseAcquiredAt = permit.leaseToken.acquiredAt,
                    outputReferenceJson = outputReference,
                    updatedAt = draft.settledAt,
                ) != 1
            ) throw StaleGenerationStateException("Finite consistency routing lost the current Stage lease.")

            val nextJobStatus = if (job.status == GenerationJobStatus.PAUSING) {
                check(
                    GenerationJobStateMachine.transition(job.status, JobEvent.SAFE_POINT_REACHED) ==
                        GenerationJobStatus.PAUSED,
                )
                GenerationJobStatus.PAUSED
            } else {
                check(
                    GenerationJobStateMachine.transition(job.status, JobEvent.USER_ACTION_REQUIRED) ==
                        GenerationJobStatus.NEEDS_ACTION,
                )
                GenerationJobStatus.NEEDS_ACTION
            }
            if (
                dao.compareAndSetJobControlStatus(
                    jobId = job.jobId,
                    expectedStatus = job.status,
                    nextStatus = nextJobStatus,
                    reason = persistedReason,
                    updatedAt = draft.settledAt,
                ) != 1
            ) throw StaleGenerationStateException("Finite consistency routing lost the current Job state.")

            dao.recordUsage(attempt.attemptId, draft.usage.toFinalUpdate(draft.settledAt))
            ChapterConsistencyNeedsActionResultV1(
                stage.stageId,
                attempt.attemptId,
                draft.reason,
                replayed = false,
            )
        }
    }

    private fun validateDraft(draft: ChapterCandidateArtifactSealDraftV1) {
        require(
            listOf(draft.candidateChapterVersionId, draft.chapterId, draft.nextStage.stageId, draft.nextStage.targetId)
                .all(IDENTIFIER::matches),
        )
        require(draft.chapterIndex in 1..10_000 && draft.revisionIndex in 0..2)
        require(
            listOf(draft.candidateContentHash, draft.canonicalOutputHash, draft.sourceBindingHash).all(HASH::matches),
        )
        require(draft.routeBindingHash == null || HASH.matches(draft.routeBindingHash))
        require(draft.sealedAt >= 0L)
        require(draft.nextStage.targetType == GenerationTargetType.CHAPTER && draft.nextStage.targetId == draft.chapterId)
        require(draft.nextStage.maxAttempts in 1..16)
        require(draft.nextStage.inputVersionHash.length in 1..256)
        require(draft.nextStage.idempotencyKey.length in 1..256)
        requireJsonObject(draft.nextStage.inputSourcesJson)
    }

    private fun validateNeedsActionDraft(draft: ChapterConsistencyNeedsActionDraftV1) {
        require(listOf(draft.candidateChapterVersionId, draft.chapterId).all(IDENTIFIER::matches))
        require(draft.chapterIndex in 1..10_000 && draft.revisionIndex in 0..2)
        require(
            listOf(
                draft.candidateContentHash,
                draft.canonicalOutputHash,
                draft.sourceBindingHash,
                draft.routeBindingHash,
            ).all(HASH::matches),
        )
        require(draft.reason in PRE_REQUEST_EXHAUSTION_REASONS) {
            "Only a request-before finite-revision exhaustion can settle a consistency Stage."
        }
        require(draft.settledAt >= 0L)
    }

    private fun requireNextCandidateBinding(
        currentStage: GenerationStageEntity,
        draft: ChapterCandidateArtifactSealDraftV1,
        next: GenerationStageEntity,
    ) {
        if (next.phase == GenerationPhase.COMMIT_CHAPTER) {
            require(draft.role == ChapterCandidateArtifactRoleV1.CONSISTENCY) {
                "Only a consistency artifact can advance to final commit."
            }
            return
        }
        val source = ChapterCandidateStageBindingV1.parseAndVerify(next)
        if (
            draft.role in setOf(
                ChapterCandidateArtifactRoleV1.MEMORY,
                ChapterCandidateArtifactRoleV1.TRACKING,
            )
        ) {
            val currentSource = ChapterCandidateStageBindingV1.parseAndVerify(currentStage)
            require(currentSource.routeBindingHash == draft.routeBindingHash) {
                "Candidate derived Stage cannot drop or replace its frozen revision-result binding."
            }
        }
        require(
            source.candidateChapterVersionId == draft.candidateChapterVersionId &&
                source.candidateContentHash == draft.candidateContentHash &&
                source.chapterId == draft.chapterId && source.chapterIndex == draft.chapterIndex &&
                source.revisionIndex == draft.revisionIndex && source.predecessorStageId == currentStage.stageId &&
                source.routeBindingHash == draft.routeBindingHash,
        ) { "Candidate next Stage does not bind the sealed source artifact." }
        val allowedNextRole = when (draft.role) {
            ChapterCandidateArtifactRoleV1.BODY -> ChapterCandidateArtifactRoleV1.MEMORY
            ChapterCandidateArtifactRoleV1.MEMORY -> ChapterCandidateArtifactRoleV1.TRACKING
            ChapterCandidateArtifactRoleV1.TRACKING -> ChapterCandidateArtifactRoleV1.CONSISTENCY
            ChapterCandidateArtifactRoleV1.CONSISTENCY -> ChapterCandidateArtifactRoleV1.BODY
        }
        require(source.role == allowedNextRole) { "Candidate Stage order is invalid." }
    }

    private fun requireCurrentCandidateBinding(
        stage: GenerationStageEntity,
        role: ChapterCandidateArtifactRoleV1,
        candidateChapterVersionId: String,
        candidateContentHash: String,
        chapterId: String,
        chapterIndex: Int,
        revisionIndex: Int,
    ) {
        if (role == ChapterCandidateArtifactRoleV1.BODY) {
            if (stage.phase == GenerationPhase.DRAFT_CHAPTER) {
                val source = InitialChapterDraftStageBinding.parseAndVerify(stage)
                val plan = STRICT_JSON.parseToJsonElement(source.canonicalPlanJson) as JsonObject
                val plannedChapterId = (plan["chapterId"] as? JsonPrimitive)
                    ?.takeIf(JsonPrimitive::isString)?.content
                val plannedChapterIndex = (plan["chapterIndex"] as? JsonPrimitive)
                    ?.takeUnless(JsonPrimitive::isString)?.content?.toIntOrNull()
                require(
                    revisionIndex == 0 && source.canonicalPlanHash.isNotBlank() &&
                        plannedChapterId == chapterId && plannedChapterIndex == chapterIndex,
                ) { "Initial BODY output does not match its frozen plan source." }
                return
            }
            val source = ChapterCandidateStageBindingV1.parseAndVerify(stage)
            require(
                stage.phase == GenerationPhase.REVISE_CHAPTER &&
                    source.role == ChapterCandidateArtifactRoleV1.BODY &&
                    source.candidateChapterVersionId != candidateChapterVersionId &&
                    source.candidateContentHash != candidateContentHash &&
                    source.chapterId == chapterId && source.chapterIndex == chapterIndex &&
                    source.revisionIndex + 1 == revisionIndex &&
                    source.routeBindingHash != null && source.requestSourceBindingHash != null,
            ) { "Revised body output does not form a new candidate from its frozen source." }
            return
        }
        val source = ChapterCandidateStageBindingV1.parseAndVerify(stage)
        require(
            source.role == role &&
                source.candidateChapterVersionId == candidateChapterVersionId &&
                source.candidateContentHash == candidateContentHash &&
                source.chapterId == chapterId && source.chapterIndex == chapterIndex &&
                source.revisionIndex == revisionIndex,
        ) { "Candidate output no longer matches the frozen current-Stage source." }
    }

    private fun artifactEvidence(
        permit: ValidatedOutputCommitPermit,
        draft: ChapterCandidateArtifactSealDraftV1,
    ) = ChapterFinalCandidateArtifactEvidenceV1(
        role = draft.role,
        stageId = permit.stageId,
        attemptId = permit.attemptId,
        artifactRefId = permit.artifactRefId,
        artifactRevision = permit.artifactRevision,
        rawOutputHash = permit.rawOutputHash,
        canonicalOutputHash = draft.canonicalOutputHash,
        sourceBindingHash = draft.sourceBindingHash,
    )

    private fun verifyArtifact(
        permit: ValidatedOutputCommitPermit,
        draft: ChapterCandidateArtifactSealDraftV1,
    ) = verifyArtifact(
        permit = permit,
        role = draft.role,
        canonicalOutputHash = draft.canonicalOutputHash,
        candidateContentHash = draft.candidateContentHash,
    )

    private fun verifyArtifact(
        permit: ValidatedOutputCommitPermit,
        role: ChapterCandidateArtifactRoleV1,
        canonicalOutputHash: String,
        candidateContentHash: String,
    ) {
        artifactStore.readBytes(
            artifactRefId = permit.artifactRefId,
            expectedType = ProtectedArtifactType.STREAM_DRAFT,
            maximumBytes = if (role == ChapterCandidateArtifactRoleV1.BODY) MAX_CHAPTER_BYTES else MAX_STRUCTURED_BYTES,
        ).use { lease ->
            require(lease.descriptor.revision == permit.artifactRevision)
            lease.withBytes { bytes ->
                require(sha256(bytes) == permit.rawOutputHash) {
                    "Candidate artifact changed after validation."
                }
                val canonicalHash = if (role == ChapterCandidateArtifactRoleV1.BODY) {
                    permit.rawOutputHash
                } else {
                    val document = runCatching {
                        STRICT_JSON.parseToJsonElement(bytes.decodeToString()) as JsonObject
                    }.getOrElse { throw IllegalArgumentException("Validated candidate artifact is not a JSON object.") }
                    sha256(document.toString())
                    }
                require(canonicalHash == canonicalOutputHash) {
                    "Candidate mapping no longer matches the validated artifact."
                }
                if (role == ChapterCandidateArtifactRoleV1.BODY) {
                    require(canonicalHash == candidateContentHash) {
                        "Sealed chapter body does not match the frozen candidate hash."
                    }
                }
            }
        }
    }

    private fun outputReferenceJson(
        permit: ValidatedOutputCommitPermit,
        draft: ChapterCandidateArtifactSealDraftV1,
    ): String = JsonObject(
        linkedMapOf(
            "schemaVersion" to JsonPrimitive(1),
            "pipelineVersion" to JsonPrimitive(PIPELINE_VERSION),
            "artifactRole" to JsonPrimitive(draft.role.name),
            "outputSchemaId" to JsonPrimitive(draft.role.schemaId),
            "attemptId" to JsonPrimitive(permit.attemptId),
            "artifactRefId" to JsonPrimitive(permit.artifactRefId),
            "artifactRevision" to JsonPrimitive(permit.artifactRevision),
            "rawOutputHash" to JsonPrimitive(permit.rawOutputHash),
            "canonicalOutputHash" to JsonPrimitive(draft.canonicalOutputHash),
            "sourceBindingHash" to JsonPrimitive(draft.sourceBindingHash),
            "candidateChapterVersionId" to JsonPrimitive(draft.candidateChapterVersionId),
            "candidateContentHash" to JsonPrimitive(draft.candidateContentHash),
            "chapterId" to JsonPrimitive(draft.chapterId),
            "chapterIndex" to JsonPrimitive(draft.chapterIndex),
            "revisionIndex" to JsonPrimitive(draft.revisionIndex),
            "nextStageId" to JsonPrimitive(draft.nextStage.stageId),
            "routeBindingHash" to (draft.routeBindingHash?.let(::JsonPrimitive) ?: JsonNull),
        ),
    ).toString()

    private fun needsActionOutputReferenceJson(
        permit: ValidatedOutputCommitPermit,
        draft: ChapterConsistencyNeedsActionDraftV1,
    ): String = JsonObject(
        linkedMapOf(
            "schemaVersion" to JsonPrimitive(1),
            "pipelineVersion" to JsonPrimitive(PIPELINE_VERSION),
            "outcomeType" to JsonPrimitive("NEEDS_ACTION"),
            "artifactRole" to JsonPrimitive(ChapterCandidateArtifactRoleV1.CONSISTENCY.name),
            "outputSchemaId" to JsonPrimitive(ChapterCandidateArtifactRoleV1.CONSISTENCY.schemaId),
            "attemptId" to JsonPrimitive(permit.attemptId),
            "artifactRefId" to JsonPrimitive(permit.artifactRefId),
            "artifactRevision" to JsonPrimitive(permit.artifactRevision),
            "rawOutputHash" to JsonPrimitive(permit.rawOutputHash),
            "canonicalOutputHash" to JsonPrimitive(draft.canonicalOutputHash),
            "sourceBindingHash" to JsonPrimitive(draft.sourceBindingHash),
            "candidateChapterVersionId" to JsonPrimitive(draft.candidateChapterVersionId),
            "candidateContentHash" to JsonPrimitive(draft.candidateContentHash),
            "chapterId" to JsonPrimitive(draft.chapterId),
            "chapterIndex" to JsonPrimitive(draft.chapterIndex),
            "revisionIndex" to JsonPrimitive(draft.revisionIndex),
            "routeBindingHash" to JsonPrimitive(draft.routeBindingHash),
            "needsActionReason" to JsonPrimitive(draft.reason.name),
        ),
    ).toString()

    private fun GenerationStageSetup.toEntity(jobId: String, createdAt: Long) = GenerationStageEntity(
        stageId = stageId,
        jobId = jobId,
        phase = phase,
        targetType = targetType,
        targetId = targetId,
        status = GenerationStageStatus.PENDING,
        inputVersionHash = inputVersionHash,
        idempotencyKey = idempotencyKey,
        maxAttempts = maxAttempts,
        inputSourcesJson = inputSourcesJson,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun GenerationStageEntity.matches(jobId: String, setup: GenerationStageSetup): Boolean =
        this.jobId == jobId && stageId == setup.stageId && phase == setup.phase &&
            targetType == setup.targetType && targetId == setup.targetId &&
            inputVersionHash == setup.inputVersionHash && idempotencyKey == setup.idempotencyKey &&
            maxAttempts == setup.maxAttempts && inputSourcesJson == setup.inputSourcesJson

    private fun requireActiveLease(stage: GenerationStageEntity, token: GenerationLeaseToken, at: Long) {
        require(stage.leaseOwnerId == token.ownerId && stage.leaseAcquiredAt == token.acquiredAt)
        val heartbeatAt = requireNotNull(stage.leaseHeartbeatAt)
        require(at >= stage.updatedAt && at >= heartbeatAt)
        if (leasePolicy.isExpired(heartbeatAt, at)) {
            throw StaleGenerationStateException("Candidate Stage lease expired before sealing.")
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

    private fun requireJsonObject(value: String) {
        require(value.length in 2..65_536)
        runCatching { STRICT_JSON.parseToJsonElement(value) as JsonObject }
            .getOrElse { throw IllegalArgumentException("Candidate next-Stage source must be a JSON object.") }
    }

    private fun sha256(value: String): String = sha256(value.toByteArray(Charsets.UTF_8))

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    companion object {
        const val PIPELINE_VERSION = "zhijuan.chapter-candidate-pipeline.v1"
        private val PRE_REQUEST_EXHAUSTION_REASONS = setOf(
            ChapterRevisionNeedsActionReasonV1.AUTOMATIC_REVISION_LIMIT_REACHED,
            ChapterRevisionNeedsActionReasonV1.STAGE_ATTEMPT_LIMIT_REACHED,
        )
        private val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
        private val HASH = Regex("[0-9a-f]{64}")
        private val STRICT_JSON = Json { isLenient = false; ignoreUnknownKeys = false }
        private const val MAX_CHAPTER_BYTES = 4 * 1_024 * 1_024
        private const val MAX_STRUCTURED_BYTES = 512 * 1_024
    }
}
