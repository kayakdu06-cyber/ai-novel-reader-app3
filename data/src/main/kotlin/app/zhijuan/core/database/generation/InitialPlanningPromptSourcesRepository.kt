package app.zhijuan.core.database.generation

import androidx.room.withTransaction
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationJobType
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.RequestAttemptStatus
import app.zhijuan.core.security.AndroidProtectedArtifactStore
import app.zhijuan.core.security.ProtectedArtifactType
import app.zhijuan.core.task.BoundPromptBundle
import app.zhijuan.core.task.PromptBundleCatalogV1
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class InitialPlanningPromptSources(
    val jobId: String,
    val stageId: String,
    val bookId: String,
    val phase: GenerationPhase,
    val targetChapterCount: Int,
    val stageInputVersionHash: String,
    val stageIdempotencyKey: String,
    val userIntentJson: String,
    val promptBundle: BoundPromptBundle,
    val predecessorJson: String?,
    val nextStageId: String?,
) {
    override fun toString(): String =
        "InitialPlanningPromptSources(phase=$phase, targetChapterCount=$targetChapterCount, content=redacted)"
}

/** Loads one initial-planning request from the exact leased Stage and immutable predecessor output. */
class InitialPlanningPromptSourcesRepository(
    private val database: ZhijuanDatabase,
    private val artifactStore: AndroidProtectedArtifactStore,
    private val leasePolicy: GenerationLeasePolicy = GenerationLeasePolicy(),
) {
    suspend fun loadBound(
        snapshot: GenerationRunnerCurrentStageRouteSnapshot,
        loadedAt: Long,
    ): InitialPlanningPromptSources {
        require(snapshot.route in INITIAL_PLANNING_ROUTES)
        val loaded = database.withTransaction {
            val lease = snapshot.executionLease
            val dao = database.generationDao()
            val stage = requireNotNull(dao.findStage(lease.stageId)) { "Initial planning Stage is missing." }
            val job = requireNotNull(dao.findJob(lease.jobId)) { "Initial planning Job is missing." }
            val jobHeartbeat = requireNotNull(job.leaseHeartbeatAt) { "Initial planning Job heartbeat is missing." }
            val stageHeartbeat = requireNotNull(stage.leaseHeartbeatAt) { "Initial planning Stage heartbeat is missing." }
            if (
                job.jobType != GenerationJobType.CREATE_BOOK || job.promptBundleVersion != PromptBundleCatalogV1.BUNDLE_VERSION ||
                job.status != GenerationJobStatus.RUNNING || stage.status != GenerationStageStatus.PREPARING ||
                job.currentStageId != stage.stageId || stage.jobId != job.jobId || stage.targetId != job.bookId ||
                job.pauseOrStopReason != null ||
                job.leaseTokenOrNull() != lease.jobLeaseToken || stage.leaseTokenOrNull() != lease.stageLeaseToken ||
                jobHeartbeat < lease.jobHeartbeatAt || stageHeartbeat < lease.stageHeartbeatAt ||
                stage.attemptCount != snapshot.attemptCount || stage.maxAttempts != snapshot.maxAttempts ||
                GenerationRunnerStageRouteResolver.resolve(stage) != snapshot.route
            ) throw StaleGenerationStateException("Initial planning bound source snapshot changed.")
            require(
                loadedAt >= job.updatedAt && loadedAt >= stage.updatedAt &&
                    loadedAt >= jobHeartbeat && loadedAt >= stageHeartbeat,
            ) { "Initial planning source load time cannot move backwards." }
            if (leasePolicy.isExpired(jobHeartbeat, loadedAt) || leasePolicy.isExpired(stageHeartbeat, loadedAt)) {
                throw StaleGenerationStateException("Initial planning execution lease expired before source load.")
            }
            val frozen = InitialPlanningJobFactory.parseAndVerify(stage)
            val bundle = PromptBundleBindingRepository(database).bindForBook(job.bookId)
            require(
                bundle.bindingHash == frozen.promptBundleBindingHash &&
                    bundle.sourceContentHash == frozen.creationSnapshotHash,
            ) {
                "Initial planning Prompt Bundle binding changed."
            }
            val stages = dao.stagesForJob(job.jobId)
            val expectedDependencyPhase = when (stage.phase) {
                GenerationPhase.BUILD_STORY_SEED -> null
                GenerationPhase.BUILD_BIBLE -> GenerationPhase.BUILD_STORY_SEED
                GenerationPhase.BUILD_MASTER_OUTLINE -> GenerationPhase.BUILD_BIBLE
                else -> error("Initial planning phase is unsupported.")
            }
            require(
                (expectedDependencyPhase == null && frozen.dependencyStageIds.isEmpty()) ||
                    (expectedDependencyPhase != null && frozen.dependencyStageIds.size == 1),
            ) { "Initial planning dependency shape changed." }
            val predecessor = frozen.dependencyStageIds.singleOrNull()?.let { dependencyId ->
                val dependency = requireNotNull(stages.singleOrNull { it.stageId == dependencyId }) {
                    "Initial planning predecessor Stage is missing."
                }
                require(
                    dependency.phase == expectedDependencyPhase &&
                        dependency.targetId == job.bookId &&
                        dependency.status == GenerationStageStatus.SUCCEEDED,
                ) {
                    "Initial planning predecessor has not succeeded."
                }
                val reference = parseOutputReference(requireNotNull(dependency.outputReferenceJson))
                val attempt = requireNotNull(dao.findAttempt(reference.attemptId)) {
                    "Initial planning predecessor Attempt is missing."
                }
                require(
                    attempt.stageId == dependency.stageId && attempt.status == RequestAttemptStatus.SUCCEEDED &&
                        attempt.outputHash == reference.rawOutputHash && attempt.streamDraftRef != null,
                ) { "Initial planning predecessor evidence changed." }
                PredecessorEvidence(requireNotNull(attempt.streamDraftRef), reference.rawOutputHash)
            }
            val nextPhase = when (stage.phase) {
                GenerationPhase.BUILD_STORY_SEED -> GenerationPhase.BUILD_BIBLE
                GenerationPhase.BUILD_BIBLE -> GenerationPhase.BUILD_MASTER_OUTLINE
                GenerationPhase.BUILD_MASTER_OUTLINE -> null
                else -> error("Initial planning phase is unsupported.")
            }
            LoadedRows(
                sources = InitialPlanningPromptSources(
                    jobId = job.jobId,
                    stageId = stage.stageId,
                    bookId = job.bookId,
                    phase = stage.phase,
                    targetChapterCount = frozen.targetChapterCount,
                    stageInputVersionHash = stage.inputVersionHash,
                    stageIdempotencyKey = stage.idempotencyKey,
                    userIntentJson = job.userIntentJson,
                    promptBundle = bundle,
                    predecessorJson = null,
                    nextStageId = nextPhase?.let { phase ->
                        requireNotNull(stages.singleOrNull { it.phase == phase }).stageId
                    },
                ),
                predecessor = predecessor,
            )
        }
        val predecessorJson = loaded.predecessor?.let { evidence ->
            artifactStore.readBytes(evidence.artifactRefId, ProtectedArtifactType.STREAM_DRAFT).use { lease ->
                lease.withBytes { bytes ->
                    require(sha256(bytes) == evidence.rawOutputHash) {
                        "Initial planning predecessor protected output changed."
                    }
                    bytes.decodeToString(throwOnInvalidSequence = true)
                }
            }
        }
        return loaded.sources.copy(predecessorJson = predecessorJson)
    }

    private fun parseOutputReference(value: String): OutputReference {
        val root = runCatching { Json.parseToJsonElement(value) as JsonObject }
            .getOrElse { throw IllegalArgumentException("Initial planning predecessor reference is invalid.") }
        val attemptId = (root["attemptId"] as? JsonPrimitive)?.content
        val rawOutputHash = (root["rawOutputHash"] as? JsonPrimitive)?.content
        require(attemptId?.matches(IDENTIFIER) == true && rawOutputHash?.matches(HASH) == true)
        return OutputReference(attemptId, rawOutputHash)
    }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value).joinToString("") { "%02x".format(it) }

    private data class LoadedRows(
        val sources: InitialPlanningPromptSources,
        val predecessor: PredecessorEvidence?,
    )

    private data class PredecessorEvidence(val artifactRefId: String, val rawOutputHash: String)
    private data class OutputReference(val attemptId: String, val rawOutputHash: String)

    private companion object {
        val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
        val HASH = Regex("[0-9a-f]{64}")
    }
}
