package app.zhijuan.core.database.generation

import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationJobType
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.task.PromptBundleCatalogV1
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

data class GenerationJobSetup(
    val jobId: String,
    val bookId: String,
    val jobType: GenerationJobType,
    val userIntentJson: String,
    val budgetSnapshotJson: String,
    val promptBundleVersion: String,
    val stages: List<GenerationStageSetup>,
    val createdAt: Long,
) {
    override fun toString(): String =
        "GenerationJobSetup(jobType=$jobType, stageCount=${stages.size}, payloads=redacted)"
}

data class GenerationStageSetup(
    val stageId: String,
    val phase: GenerationPhase,
    val targetType: GenerationTargetType,
    val targetId: String,
    val inputVersionHash: String,
    val idempotencyKey: String,
    val maxAttempts: Int,
    val inputSourcesJson: String,
) {
    override fun toString(): String =
        "GenerationStageSetup(phase=$phase, targetType=$targetType, payloads=redacted)"
}

data class StoredGenerationJobSetup(
    val job: StoredGenerationJobState,
    val stages: List<StoredGenerationStageState>,
)

class GenerationJobSetupRepository(
    private val database: ZhijuanDatabase,
) {
    suspend fun create(setup: GenerationJobSetup): StoredGenerationJobSetup {
        validate(setup)
        val job = GenerationJobEntity(
            jobId = setup.jobId,
            bookId = setup.bookId,
            jobType = setup.jobType,
            status = GenerationJobStatus.CREATED,
            userIntentJson = setup.userIntentJson,
            budgetSnapshotJson = setup.budgetSnapshotJson,
            promptBundleVersion = setup.promptBundleVersion,
            createdAt = setup.createdAt,
            updatedAt = setup.createdAt,
        )
        val stages = setup.stages.map { stage ->
            GenerationStageEntity(
                stageId = stage.stageId,
                jobId = setup.jobId,
                phase = stage.phase,
                targetType = stage.targetType,
                targetId = stage.targetId,
                status = GenerationStageStatus.PENDING,
                inputVersionHash = stage.inputVersionHash,
                idempotencyKey = stage.idempotencyKey,
                maxAttempts = stage.maxAttempts,
                inputSourcesJson = stage.inputSourcesJson,
                createdAt = setup.createdAt,
                updatedAt = setup.createdAt,
            )
        }
        database.generationDao().createJob(job, stages)
        val dao = database.generationDao()
        return StoredGenerationJobSetup(
            job = requireNotNull(dao.findJob(setup.jobId)).toStoredState(),
            stages = dao.stagesForJob(setup.jobId).map { it.toStoredState() },
        )
    }

    private fun validate(setup: GenerationJobSetup) {
        require(IDENTIFIER.matches(setup.jobId)) { "Generation job id is invalid." }
        require(IDENTIFIER.matches(setup.bookId)) { "Generation book id is invalid." }
        require(setup.createdAt >= 0L) { "Generation creation time is invalid." }
        require(setup.promptBundleVersion.length in 1..128) { "Prompt bundle version is invalid." }
        require(setup.stages.size in 1..MAX_STAGES) { "Generation stage count is invalid." }
        requireJson(setup.userIntentJson, "User intent")
        requireJson(setup.budgetSnapshotJson, "Budget snapshot")
        require(setup.stages.map { it.stageId }.distinct().size == setup.stages.size) {
            "Generation stage ids must be unique."
        }
        require(setup.stages.map { it.idempotencyKey }.distinct().size == setup.stages.size) {
            "Generation idempotency keys must be unique."
        }
        setup.stages.forEach { stage ->
            require(IDENTIFIER.matches(stage.stageId)) { "Generation stage id is invalid." }
            require(IDENTIFIER.matches(stage.targetId)) { "Generation target id is invalid." }
            require(stage.inputVersionHash.length in 1..256) { "Input version hash is invalid." }
            require(stage.idempotencyKey.length in 1..256) { "Idempotency key is invalid." }
            require(stage.maxAttempts in 1..MAX_ATTEMPTS) { "Stage attempt limit is invalid." }
            requireJson(stage.inputSourcesJson, "Input sources")
            requireSupportedChapterGate(setup.promptBundleVersion, stage)
        }
    }

    private fun requireSupportedChapterGate(
        promptBundleVersion: String,
        stage: GenerationStageSetup,
    ) {
        if (
            promptBundleVersion != PromptBundleCatalogV1.BUNDLE_VERSION ||
            stage.targetType != GenerationTargetType.CHAPTER ||
            stage.phase !in REMOTE_CHAPTER_PHASES
        ) {
            return
        }
        val root = Json.parseToJsonElement(stage.inputSourcesJson) as? JsonObject
            ?: throw IllegalArgumentException("Supported chapter input sources must be a JSON object.")
        require("firstChapterBootstrap" in root || "chapterProgressionGate" in root) {
            "Supported chapter stages must freeze first-chapter bootstrap or full progression evidence."
        }
    }

    private fun requireJson(value: String, label: String) {
        require(value.toByteArray(Charsets.UTF_8).size in 2..MAX_JSON_BYTES) {
            "$label JSON size is invalid."
        }
        runCatching { Json.parseToJsonElement(value) }
            .getOrElse { throw IllegalArgumentException("$label JSON is invalid.") }
    }

    private companion object {
        val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
        const val MAX_STAGES = 10_000
        const val MAX_ATTEMPTS = 16
        const val MAX_JSON_BYTES = 65_536
        val REMOTE_CHAPTER_PHASES = setOf(
            GenerationPhase.BUILD_CHAPTER_PLAN,
            GenerationPhase.DRAFT_CHAPTER,
            GenerationPhase.CHECK_CONSISTENCY,
            GenerationPhase.REVISE_CHAPTER,
        )
    }
}
