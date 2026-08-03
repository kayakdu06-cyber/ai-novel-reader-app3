package app.zhijuan.core.database.generation

import app.zhijuan.core.model.GenerationJobType
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.task.PromptBundleCatalogV1
import app.zhijuan.core.task.StageIdempotencyKey
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class InitialPlanningStageIds(
    val seedStageId: String,
    val bibleStageId: String,
    val outlineStageId: String,
)

data class InitialPlanningJobSpec(
    val jobId: String,
    val bookId: String,
    val userIntentJson: String,
    val budgetSnapshotJson: String,
    val creationSnapshotHash: String,
    val stageIds: InitialPlanningStageIds,
    val maxAttemptsPerStage: Int = 2,
    val createdAt: Long,
)

object InitialPlanningJobFactory {
    fun create(spec: InitialPlanningJobSpec): GenerationJobSetup {
        require(HASH.matches(spec.creationSnapshotHash)) { "Creation snapshot hash is invalid." }
        require(spec.maxAttemptsPerStage in 1..4) { "Initial planning retry limit is invalid." }
        val definitions = listOf(
            StageDefinition(
                stageId = spec.stageIds.seedStageId,
                phase = GenerationPhase.BUILD_STORY_SEED,
                targetType = GenerationTargetType.BOOK,
                outputSchemaId = "story-seed.v1",
                dependencyStageIds = emptyList(),
            ),
            StageDefinition(
                stageId = spec.stageIds.bibleStageId,
                phase = GenerationPhase.BUILD_BIBLE,
                targetType = GenerationTargetType.STORY_BIBLE,
                outputSchemaId = "story-bible.v1",
                dependencyStageIds = listOf(spec.stageIds.seedStageId),
            ),
            StageDefinition(
                stageId = spec.stageIds.outlineStageId,
                phase = GenerationPhase.BUILD_MASTER_OUTLINE,
                targetType = GenerationTargetType.OUTLINE,
                outputSchemaId = "master-outline.v1",
                dependencyStageIds = listOf(spec.stageIds.bibleStageId),
            ),
        )
        require(definitions.map { it.stageId }.all(IDENTIFIER::matches)) {
            "Initial planning stage id is invalid."
        }
        require(definitions.map { it.stageId }.distinct().size == definitions.size) {
            "Initial planning stage ids must be unique."
        }
        return GenerationJobSetup(
            jobId = spec.jobId,
            bookId = spec.bookId,
            jobType = GenerationJobType.CREATE_BOOK,
            userIntentJson = spec.userIntentJson,
            budgetSnapshotJson = spec.budgetSnapshotJson,
            promptBundleVersion = PromptBundleCatalogV1.BUNDLE_VERSION,
            stages = definitions.map { definition ->
                val versionHash = stageInputHash(spec.creationSnapshotHash, definition)
                GenerationStageSetup(
                    stageId = definition.stageId,
                    phase = definition.phase,
                    targetType = definition.targetType,
                    targetId = spec.bookId,
                    inputVersionHash = versionHash,
                    idempotencyKey = StageIdempotencyKey.create(
                        jobId = spec.jobId,
                        phase = definition.phase,
                        targetId = spec.bookId,
                        inputVersionHash = versionHash,
                    ).value,
                    maxAttempts = spec.maxAttemptsPerStage,
                    inputSourcesJson = JsonObject(
                        linkedMapOf(
                            "schemaVersion" to JsonPrimitive(1),
                            "creationSnapshotHash" to JsonPrimitive(spec.creationSnapshotHash),
                            "promptBundleVersion" to JsonPrimitive(PromptBundleCatalogV1.BUNDLE_VERSION),
                            "outputSchemaId" to JsonPrimitive(definition.outputSchemaId),
                            "dependencyStageIds" to JsonArray(
                                definition.dependencyStageIds.map(::JsonPrimitive),
                            ),
                        ),
                    ).toString(),
                )
            },
            createdAt = spec.createdAt,
        )
    }

    private fun stageInputHash(snapshotHash: String, definition: StageDefinition): String = sha256(
        listOf(
            snapshotHash,
            PromptBundleCatalogV1.BUNDLE_VERSION,
            definition.phase.name,
            definition.outputSchemaId,
            definition.dependencyStageIds.joinToString(","),
        ).joinToString("\u0000"),
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private data class StageDefinition(
        val stageId: String,
        val phase: GenerationPhase,
        val targetType: GenerationTargetType,
        val outputSchemaId: String,
        val dependencyStageIds: List<String>,
    )

    private val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
    private val HASH = Regex("[0-9a-f]{64}")
}
