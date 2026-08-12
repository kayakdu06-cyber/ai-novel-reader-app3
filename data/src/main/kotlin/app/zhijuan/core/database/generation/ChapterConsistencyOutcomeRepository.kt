package app.zhijuan.core.database.generation

import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.security.AndroidProtectedArtifactStore
import app.zhijuan.core.task.ChapterRevisionPolicyDecisionV1
import app.zhijuan.core.task.ChapterRevisionPolicyInputV1
import app.zhijuan.core.task.ChapterRevisionPolicyV1
import app.zhijuan.core.task.StageIdempotencyKey
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class ChapterConsistencyOutcomeDraftV1(
    val candidateChapterVersionId: String,
    val chapterId: String,
    val chapterIndex: Int,
    val candidateContentHash: String,
    val canonicalOutputHash: String,
    val sourceBindingHash: String,
    val revisionIndex: Int,
    val nextStageId: String,
    val expectedCurrentVersionId: String? = null,
    val candidateRouteBindingHash: String? = null,
    val revisionRequestSourceBindingHash: String? = null,
    val usage: FinalUsageCommit,
    val routedAt: Long,
    val consistencyMappingSnapshotJson: String? = null,
    val consistencyMappingSnapshotContentHash: String? = null,
    val artifactRole: ChapterCandidateArtifactRoleV1 = ChapterCandidateArtifactRoleV1.CONSISTENCY,
) {
    override fun toString(): String =
        "ChapterConsistencyOutcomeDraftV1(chapterIndex=$chapterIndex, revisionIndex=$revisionIndex, content=redacted)"
}

sealed interface ChapterConsistencyOutcomeResultV1 {
    data class CommitReady(
        val seal: ChapterCandidateArtifactSealResultV1,
    ) : ChapterConsistencyOutcomeResultV1

    data class RevisionReady(
        val seal: ChapterCandidateArtifactSealResultV1,
        val plan: ChapterRevisionPolicyDecisionV1.ReviseAutomatically,
    ) : ChapterConsistencyOutcomeResultV1

    data class NeedsAction(
        val settlement: ChapterConsistencyNeedsActionResultV1,
    ) : ChapterConsistencyOutcomeResultV1
}

/** Frozen source envelope of the final COMMIT Stage for one accepted candidate. */
data class ChapterFinalCommitStageSourceV1(
    val candidateChapterVersionId: String,
    val candidateContentHash: String,
    val chapterId: String,
    val chapterIndex: Int,
    val revisionIndex: Int,
    val predecessorStageId: String,
    val routeBindingHash: String,
    val expectedCurrentVersionId: String?,
    val maximumAutomaticRevisions: Int,
    val candidateContentHashHistory: List<String>,
    val consistencyRequestSourceBindingHash: String,
    val consistencyMappingSnapshotJson: String,
    val consistencyMappingSnapshotContentHash: String,
) {
    override fun toString(): String =
        "ChapterFinalCommitStageSourceV1(chapterIndex=$chapterIndex, revisionIndex=$revisionIndex, " +
            "maximumAutomaticRevisions=$maximumAutomaticRevisions, historySize=${candidateContentHashHistory.size})"
}

/** Builds and strictly revalidates the final COMMIT Stage source envelope. */
object ChapterFinalCommitStageBindingV1 {
    fun stageSetup(
        jobId: String,
        stageId: String,
        source: ChapterFinalCommitStageSourceV1,
    ): GenerationStageSetup {
        requireValid(source)
        val inputSources = inputSources(source)
        require(inputSources.toByteArray(Charsets.UTF_8).size in 2..65_536) {
            "Final commit Stage source exceeds the 64 KiB bound."
        }
        val inputHash = sha256(listOf(SOURCE_POLICY_VERSION, inputSources).joinToString("\u0000"))
        return GenerationStageSetup(
            stageId = stageId,
            phase = GenerationPhase.COMMIT_CHAPTER,
            targetType = GenerationTargetType.CHAPTER,
            targetId = source.chapterId,
            inputVersionHash = inputHash,
            idempotencyKey = StageIdempotencyKey.create(
                jobId,
                GenerationPhase.COMMIT_CHAPTER,
                source.chapterId,
                inputHash,
            ).value,
            maxAttempts = 1,
            inputSourcesJson = inputSources,
        )
    }

    internal fun parseAndVerify(stage: GenerationStageEntity): ChapterFinalCommitStageSourceV1 {
        val root = runCatching { STRICT_JSON.parseToJsonElement(stage.inputSourcesJson) as JsonObject }
            .getOrElse { throw IllegalArgumentException("Final commit Stage source is invalid JSON.") }
        require(root.keys == ROOT_KEYS)
        require(root.intValue("schemaVersion") == 1)
        require(root.string("sourcePolicyVersion") == SOURCE_POLICY_VERSION)
        require(root.string("pipelineVersion") == ChapterCandidateArtifactSealRepositoryV1.PIPELINE_VERSION)
        val snapshot = root.snapshotObject("consistencyMappingSnapshot")
        val source = ChapterFinalCommitStageSourceV1(
            candidateChapterVersionId = root.string("candidateChapterVersionId"),
            candidateContentHash = root.string("candidateContentHash"),
            chapterId = root.string("chapterId"),
            chapterIndex = root.intValue("chapterIndex"),
            revisionIndex = root.intValue("revisionIndex"),
            predecessorStageId = root.string("predecessorStageId"),
            routeBindingHash = root.string("routeBindingHash"),
            expectedCurrentVersionId = root.nullableString("expectedCurrentVersionId"),
            maximumAutomaticRevisions = root.intValue("maximumAutomaticRevisions"),
            candidateContentHashHistory = root.stringArray("candidateContentHashHistory"),
            consistencyRequestSourceBindingHash = root.string("consistencyRequestSourceBindingHash"),
            consistencyMappingSnapshotJson = snapshot.toString(),
            consistencyMappingSnapshotContentHash = root.string("consistencyMappingSnapshotContentHash"),
        )
        require(stage.phase == GenerationPhase.COMMIT_CHAPTER && stage.targetType == GenerationTargetType.CHAPTER)
        require(stage.targetId == source.chapterId && stage.maxAttempts == 1)
        requireValid(source)
        require(stage.inputSourcesJson.toByteArray(Charsets.UTF_8).size in 2..65_536) {
            "Final commit Stage source exceeds the 64 KiB bound."
        }
        require(
            stage.inputVersionHash == sha256(
                listOf(SOURCE_POLICY_VERSION, stage.inputSourcesJson).joinToString("\u0000"),
            ),
        ) { "Final commit Stage input hash is stale." }
        return source
    }

    private fun requireValid(source: ChapterFinalCommitStageSourceV1) {
        require(
            listOf(source.candidateChapterVersionId, source.chapterId, source.predecessorStageId)
                .all(IDENTIFIER::matches),
        )
        require(source.expectedCurrentVersionId == null || IDENTIFIER.matches(source.expectedCurrentVersionId))
        require(HASH.matches(source.candidateContentHash) && HASH.matches(source.routeBindingHash))
        require(HASH.matches(source.consistencyRequestSourceBindingHash))
        require(HASH.matches(source.consistencyMappingSnapshotContentHash))
        require(source.chapterIndex in 1..10_000)
        require(source.maximumAutomaticRevisions in 1..2)
        require(source.revisionIndex in 0..source.maximumAutomaticRevisions)
        val history = source.candidateContentHashHistory
        require(
            history.size == source.revisionIndex + 1 &&
                history.all(HASH::matches) &&
                history.distinct().size == history.size &&
                history.last() == source.candidateContentHash,
        ) { "Final commit candidate history is incomplete, invalid, or not the current body." }
        val snapshotJson = source.consistencyMappingSnapshotJson
        require(snapshotJson.toByteArray(Charsets.UTF_8).size in 2..49_152) {
            "Final commit consistency mapping snapshot size is out of bounds."
        }
        val snapshot = runCatching { STRICT_JSON.parseToJsonElement(snapshotJson) as JsonObject }
            .getOrElse {
                throw IllegalArgumentException("Final commit consistency mapping snapshot is not a strict JSON object.")
            }
        val mappingVersion = snapshot.intValue("schemaVersion")
        val mappingSchemaId = snapshot.string("schemaId")
        require(
            (mappingVersion == 1 && mappingSchemaId == MAPPING_SCHEMA_ID_V1 &&
                snapshot.keys == MAPPING_ROOT_KEYS_V1) ||
                (mappingVersion == 2 && mappingSchemaId == MAPPING_SCHEMA_ID_V2 &&
                    snapshot.keys == MAPPING_ROOT_KEYS_V2),
        ) { "Final commit consistency mapping snapshot schema identity is invalid." }
        require(snapshot.string("consistencyRequestSourceBindingHash") == source.consistencyRequestSourceBindingHash) {
            "Final commit consistency mapping source binding is stale."
        }
        require(sha256(snapshotJson) == source.consistencyMappingSnapshotContentHash) {
            "Final commit consistency mapping snapshot hash is stale."
        }
    }

    private fun inputSources(source: ChapterFinalCommitStageSourceV1): String {
        val mappingSnapshot = runCatching {
            STRICT_JSON.parseToJsonElement(source.consistencyMappingSnapshotJson) as JsonObject
        }.getOrElse {
            throw IllegalArgumentException("Final commit consistency mapping snapshot is not a strict JSON object.")
        }
        return JsonObject(
            linkedMapOf(
                "schemaVersion" to JsonPrimitive(1),
                "sourcePolicyVersion" to JsonPrimitive(SOURCE_POLICY_VERSION),
                "pipelineVersion" to JsonPrimitive(ChapterCandidateArtifactSealRepositoryV1.PIPELINE_VERSION),
                "candidateChapterVersionId" to JsonPrimitive(source.candidateChapterVersionId),
                "candidateContentHash" to JsonPrimitive(source.candidateContentHash),
                "chapterId" to JsonPrimitive(source.chapterId),
                "chapterIndex" to JsonPrimitive(source.chapterIndex),
                "revisionIndex" to JsonPrimitive(source.revisionIndex),
                "predecessorStageId" to JsonPrimitive(source.predecessorStageId),
                "routeBindingHash" to JsonPrimitive(source.routeBindingHash),
                "expectedCurrentVersionId" to (source.expectedCurrentVersionId?.let(::JsonPrimitive) ?: JsonNull),
                "maximumAutomaticRevisions" to JsonPrimitive(source.maximumAutomaticRevisions),
                "candidateContentHashHistory" to JsonArray(source.candidateContentHashHistory.map(::JsonPrimitive)),
                "consistencyRequestSourceBindingHash" to JsonPrimitive(source.consistencyRequestSourceBindingHash),
                "consistencyMappingSnapshotContentHash" to JsonPrimitive(
                    source.consistencyMappingSnapshotContentHash,
                ),
                "consistencyMappingSnapshot" to mappingSnapshot,
            ),
        ).toString()
    }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
            ?: throw IllegalArgumentException("Final commit Stage string field is missing or invalid: $key")

    private fun JsonObject.intValue(key: String): Int =
        (this[key] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.content?.toIntOrNull()
            ?: throw IllegalArgumentException("Final commit Stage integer field is missing or invalid: $key")

    private fun JsonObject.nullableString(key: String): String? = when (val value = this[key]) {
        JsonNull -> null
        is JsonPrimitive -> value.takeIf(JsonPrimitive::isString)?.content
            ?: throw IllegalArgumentException("Final commit Stage nullable field is invalid: $key")
        else -> throw IllegalArgumentException("Final commit Stage nullable field is missing or invalid: $key")
    }

    private fun JsonObject.stringArray(key: String): List<String> = when (val value = this[key]) {
        is JsonArray -> value.map { element ->
            (element as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                ?: throw IllegalArgumentException("Final commit Stage history entry is invalid: $key")
        }
        else -> throw IllegalArgumentException("Final commit Stage history field is missing or invalid: $key")
    }

    private fun JsonObject.snapshotObject(key: String): JsonObject =
        (this[key] as? JsonObject)
            ?: throw IllegalArgumentException("Final commit Stage snapshot field is missing or invalid: $key")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    internal const val SOURCE_POLICY_VERSION = "zhijuan.chapter-final-commit-stage-source.v3"
    private const val MAPPING_SCHEMA_ID_V1 = "zhijuan.chapter-final-consistency-mapping.v1"
    private const val MAPPING_SCHEMA_ID_V2 = "zhijuan.chapter-final-post-analysis-mapping.v2"
    private val ROOT_KEYS = setOf(
        "schemaVersion", "sourcePolicyVersion", "pipelineVersion",
        "candidateChapterVersionId", "candidateContentHash", "chapterId", "chapterIndex",
        "revisionIndex", "predecessorStageId", "routeBindingHash",
        "expectedCurrentVersionId", "maximumAutomaticRevisions", "candidateContentHashHistory",
        "consistencyRequestSourceBindingHash", "consistencyMappingSnapshotContentHash",
        "consistencyMappingSnapshot",
    )
    private val MAPPING_ROOT_KEYS_V1 = setOf(
        "schemaVersion", "schemaId", "consistencyRequestSourceBindingHash",
        "minimumBodyCodePoints", "totalRevisionAttemptsUsed", "revisionStageMaximumAttempts",
        "localReport", "expectation", "sceneContract",
    )
    private val MAPPING_ROOT_KEYS_V2 = MAPPING_ROOT_KEYS_V1 + "narrativeExpectation"
    private val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
    private val HASH = Regex("[0-9a-f]{64}")
    private val STRICT_JSON = Json { isLenient = false; ignoreUnknownKeys = false }
}

/**
 * Applies the finite revision policy to one validated consistency artifact and
 * persists exactly one route: local final commit, one more revision, or a
 * terminal needs-action settlement. The caller cannot choose a route separately
 * from the policy input.
 */
class ChapterConsistencyOutcomeRepositoryV1(
    private val database: ZhijuanDatabase,
    artifactStore: AndroidProtectedArtifactStore,
) {
    private val artifacts = ChapterCandidateArtifactSealRepositoryV1(database, artifactStore)

    suspend fun route(
        permit: ValidatedOutputCommitPermit,
        draft: ChapterConsistencyOutcomeDraftV1,
        policyInput: ChapterRevisionPolicyInputV1,
    ): ChapterConsistencyOutcomeResultV1 {
        validate(draft, policyInput)
        val decision = ChapterRevisionPolicyV1.evaluate(policyInput)
        val routeBindingHash = ChapterRevisionPolicyV1.routingBindingHash(policyInput)
        val stage = requireNotNull(database.generationDao().findStage(permit.stageId)) {
            "Consistency Stage no longer exists."
        }
        val job = requireNotNull(database.generationDao().findJob(stage.jobId)) {
            "Consistency Job no longer exists."
        }
        val stageSource = ChapterCandidateStageBindingV1.parseAndVerify(stage)
        require(stageSource.routeBindingHash == draft.candidateRouteBindingHash) {
            "Consistency outcome lost or replaced the current candidate revision-result binding."
        }
        return when (decision) {
            is ChapterRevisionPolicyDecisionV1.AcceptCandidate -> {
                require(decision.candidateContentHash == draft.candidateContentHash)
                require(draft.revisionRequestSourceBindingHash == null)
                val mappingSnapshotJson = requireNotNull(draft.consistencyMappingSnapshotJson) {
                    "An accepted consistency outcome must freeze the final mapping snapshot."
                }
                val mappingSnapshotContentHash = requireNotNull(draft.consistencyMappingSnapshotContentHash) {
                    "An accepted consistency outcome must freeze the final mapping snapshot hash."
                }
                val next = ChapterFinalCommitStageBindingV1.stageSetup(
                    jobId = job.jobId,
                    stageId = draft.nextStageId,
                    source = ChapterFinalCommitStageSourceV1(
                        candidateChapterVersionId = draft.candidateChapterVersionId,
                        candidateContentHash = draft.candidateContentHash,
                        chapterId = draft.chapterId,
                        chapterIndex = draft.chapterIndex,
                        revisionIndex = draft.revisionIndex,
                        predecessorStageId = stage.stageId,
                        routeBindingHash = routeBindingHash,
                        expectedCurrentVersionId = draft.expectedCurrentVersionId,
                        maximumAutomaticRevisions = decision.maximumAutomaticRevisions,
                        candidateContentHashHistory = policyInput.candidateContentHashHistory,
                        consistencyRequestSourceBindingHash = draft.sourceBindingHash,
                        consistencyMappingSnapshotJson = mappingSnapshotJson,
                        consistencyMappingSnapshotContentHash = mappingSnapshotContentHash,
                    ),
                )
                ChapterConsistencyOutcomeResultV1.CommitReady(
                    artifacts.seal(permit, draft.toSealDraft(next, routeBindingHash)),
                )
            }
            is ChapterRevisionPolicyDecisionV1.ReviseAutomatically -> {
                require(decision.sourceCandidateContentHash == draft.candidateContentHash)
                require(decision.revisionIndex == draft.revisionIndex + 1)
                require(
                    draft.consistencyMappingSnapshotJson == null &&
                        draft.consistencyMappingSnapshotContentHash == null,
                ) { "A revision route must not carry the final mapping snapshot." }
                val requestSourceBindingHash = requireNotNull(draft.revisionRequestSourceBindingHash) {
                    "A revision route must freeze the exact request source binding before Stage creation."
                }
                val next = ChapterCandidateStageBindingV1.stageSetup(
                    jobId = job.jobId,
                    stageId = draft.nextStageId,
                    phase = GenerationPhase.REVISE_CHAPTER,
                    source = ChapterCandidateStageSourceV1(
                        role = ChapterCandidateArtifactRoleV1.BODY,
                        candidateChapterVersionId = draft.candidateChapterVersionId,
                        candidateContentHash = draft.candidateContentHash,
                        chapterId = draft.chapterId,
                        chapterIndex = draft.chapterIndex,
                        revisionIndex = draft.revisionIndex,
                        predecessorStageId = stage.stageId,
                        routeBindingHash = routeBindingHash,
                        requestSourceBindingHash = requestSourceBindingHash,
                    ),
                    maxAttempts = policyInput.stageMaximumAttempts,
                )
                ChapterConsistencyOutcomeResultV1.RevisionReady(
                    seal = artifacts.seal(permit, draft.toSealDraft(next, routeBindingHash)),
                    plan = decision,
                )
            }
            is ChapterRevisionPolicyDecisionV1.NeedsAction -> {
                require(draft.revisionRequestSourceBindingHash == null)
                require(
                    draft.consistencyMappingSnapshotJson == null &&
                        draft.consistencyMappingSnapshotContentHash == null,
                ) { "A needs-action route must not carry the final mapping snapshot." }
                ChapterConsistencyOutcomeResultV1.NeedsAction(
                    artifacts.settleConsistencyNeedsAction(
                        permit,
                        ChapterConsistencyNeedsActionDraftV1(
                            candidateChapterVersionId = draft.candidateChapterVersionId,
                            chapterId = draft.chapterId,
                            chapterIndex = draft.chapterIndex,
                            candidateContentHash = draft.candidateContentHash,
                            canonicalOutputHash = draft.canonicalOutputHash,
                            sourceBindingHash = draft.sourceBindingHash,
                            revisionIndex = draft.revisionIndex,
                            routeBindingHash = routeBindingHash,
                            sourceRouteBindingHash = draft.candidateRouteBindingHash,
                            reason = decision.reason,
                            usage = draft.usage,
                            settledAt = draft.routedAt,
                            artifactRole = draft.artifactRole,
                        ),
                    ),
                )
            }
        }
    }

    private fun validate(
        draft: ChapterConsistencyOutcomeDraftV1,
        policyInput: ChapterRevisionPolicyInputV1,
    ) {
        require(listOf(draft.candidateChapterVersionId, draft.chapterId, draft.nextStageId).all(IDENTIFIER::matches))
        require(draft.chapterIndex in 1..10_000 && draft.revisionIndex in 0..2)
        require(
            draft.artifactRole in setOf(
                ChapterCandidateArtifactRoleV1.CONSISTENCY,
                ChapterCandidateArtifactRoleV1.POST_ANALYSIS,
            ),
        )
        require(listOf(draft.candidateContentHash, draft.canonicalOutputHash, draft.sourceBindingHash).all(HASH::matches))
        require(draft.candidateRouteBindingHash == null || HASH.matches(draft.candidateRouteBindingHash))
        require(
            draft.revisionRequestSourceBindingHash == null ||
                HASH.matches(draft.revisionRequestSourceBindingHash),
        )
        require(draft.expectedCurrentVersionId == null || IDENTIFIER.matches(draft.expectedCurrentVersionId))
        require((draft.revisionIndex == 0) == (draft.candidateRouteBindingHash == null))
        require(draft.routedAt >= 0L)
        val mappingSnapshotJson = draft.consistencyMappingSnapshotJson
        val mappingSnapshotContentHash = draft.consistencyMappingSnapshotContentHash
        require((mappingSnapshotJson == null) == (mappingSnapshotContentHash == null)) {
            "Consistency outcome mapping snapshot fields must be present or absent together."
        }
        if (mappingSnapshotJson != null) {
            val snapshotHash = requireNotNull(mappingSnapshotContentHash) {
                "Consistency outcome mapping snapshot hash is missing."
            }
            require(HASH.matches(snapshotHash))
            require(mappingSnapshotJson.toByteArray(Charsets.UTF_8).size in 2..49_152)
            require(runCatching { STRICT_JSON.parseToJsonElement(mappingSnapshotJson) }.getOrNull() is JsonObject) {
                "Consistency outcome mapping snapshot is not a strict JSON object."
            }
        }
        require(policyInput.currentCandidateContentHash == draft.candidateContentHash)
        require(policyInput.completedAutomaticRevisions == draft.revisionIndex)
        require(policyInput.candidateContentHashHistory.size == draft.revisionIndex + 1)
    }

    private fun ChapterConsistencyOutcomeDraftV1.toSealDraft(
        next: GenerationStageSetup,
        routeBindingHash: String,
    ) =
        ChapterCandidateArtifactSealDraftV1(
            role = artifactRole,
            candidateChapterVersionId = candidateChapterVersionId,
            chapterId = chapterId,
            chapterIndex = chapterIndex,
            candidateContentHash = candidateContentHash,
            canonicalOutputHash = canonicalOutputHash,
            sourceBindingHash = sourceBindingHash,
            revisionIndex = revisionIndex,
            usage = usage,
            nextStage = next,
            sealedAt = routedAt,
            routeBindingHash = routeBindingHash,
            sourceRouteBindingHash = candidateRouteBindingHash,
        )

    private companion object {
        val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
        val HASH = Regex("[0-9a-f]{64}")
        val STRICT_JSON = Json { isLenient = false; ignoreUnknownKeys = false }
    }
}
