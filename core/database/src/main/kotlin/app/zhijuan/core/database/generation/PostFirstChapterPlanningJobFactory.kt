package app.zhijuan.core.database.generation

import app.zhijuan.core.model.GenerationJobType
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.task.FirstChapterProgressionPolicyV1
import app.zhijuan.core.task.PromptBundleCatalogV1
import app.zhijuan.core.task.StageIdempotencyKey
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class PostFirstChapterPlanningJobSpec(
    val jobId: String,
    val bookId: String,
    val userIntentJson: String,
    val budgetSnapshotJson: String,
    val seedStageId: String,
    val seedRawOutputHash: String,
    val seedContentHash: String,
    val chapterId: String,
    val chapterVersionId: String,
    val chapterContentHash: String,
    val bibleStageId: String,
    val outlineStageId: String,
    val maxAttemptsPerStage: Int = 2,
    val createdAt: Long,
)

/** Builds the full planning chain after a fast-lane first chapter has become immutable/readable. */
object PostFirstChapterPlanningJobFactory {
    fun create(spec: PostFirstChapterPlanningJobSpec): GenerationJobSetup {
        require(
            listOf(
                spec.jobId,
                spec.bookId,
                spec.seedStageId,
                spec.chapterId,
                spec.chapterVersionId,
                spec.bibleStageId,
                spec.outlineStageId,
            ).all(IDENTIFIER::matches),
        ) { "Post-first-chapter planning identifiers are invalid." }
        require(spec.bibleStageId != spec.outlineStageId)
        require(
            listOf(spec.seedRawOutputHash, spec.seedContentHash, spec.chapterContentHash).all(HASH::matches),
        ) { "Post-first-chapter planning hashes are invalid." }
        require(spec.maxAttemptsPerStage in 1..4)
        require(spec.createdAt >= 0L)
        val marker = JsonObject(
            linkedMapOf(
                "schemaVersion" to JsonPrimitive(1),
                "policyVersion" to JsonPrimitive(FirstChapterProgressionPolicyV1.POLICY_VERSION),
                "bookId" to JsonPrimitive(spec.bookId),
                "chapterId" to JsonPrimitive(spec.chapterId),
                "chapterIndex" to JsonPrimitive(1),
                "chapterVersionId" to JsonPrimitive(spec.chapterVersionId),
                "chapterContentHash" to JsonPrimitive(spec.chapterContentHash),
                "seedStageId" to JsonPrimitive(spec.seedStageId),
                "seedRawOutputHash" to JsonPrimitive(spec.seedRawOutputHash),
                "seedContentHash" to JsonPrimitive(spec.seedContentHash),
                "bibleStageId" to JsonPrimitive(spec.bibleStageId),
                "outlineStageId" to JsonPrimitive(spec.outlineStageId),
            ),
        )
        val definitions = listOf(
            Definition(
                spec.bibleStageId,
                GenerationPhase.BUILD_BIBLE,
                GenerationTargetType.STORY_BIBLE,
                "story-bible.v1",
                emptyList(),
            ),
            Definition(
                spec.outlineStageId,
                GenerationPhase.BUILD_MASTER_OUTLINE,
                GenerationTargetType.OUTLINE,
                "master-outline.v1",
                listOf(spec.bibleStageId),
            ),
        )
        return GenerationJobSetup(
            jobId = spec.jobId,
            bookId = spec.bookId,
            jobType = GenerationJobType.CONTINUE_BOOK,
            userIntentJson = spec.userIntentJson,
            budgetSnapshotJson = spec.budgetSnapshotJson,
            promptBundleVersion = PromptBundleCatalogV1.BUNDLE_VERSION,
            stages = definitions.map { definition ->
                val inputSources = JsonObject(
                    linkedMapOf(
                        "schemaVersion" to JsonPrimitive(1),
                        "promptBundleVersion" to JsonPrimitive(PromptBundleCatalogV1.BUNDLE_VERSION),
                        "outputSchemaId" to JsonPrimitive(definition.outputSchemaId),
                        "dependencyStageIds" to JsonArray(definition.dependencies.map(::JsonPrimitive)),
                        "postFirstChapterPlanning" to marker,
                    ),
                ).toString()
                val inputHash = sha256(
                    listOf(
                        PromptBundleCatalogV1.BUNDLE_VERSION,
                        FirstChapterProgressionPolicyV1.POLICY_VERSION,
                        definition.phase.name,
                        definition.outputSchemaId,
                        marker.toString(),
                        definition.dependencies.joinToString(","),
                    ).joinToString("\u0000"),
                )
                GenerationStageSetup(
                    stageId = definition.stageId,
                    phase = definition.phase,
                    targetType = definition.targetType,
                    targetId = spec.bookId,
                    inputVersionHash = inputHash,
                    idempotencyKey = StageIdempotencyKey.create(
                        spec.jobId,
                        definition.phase,
                        spec.bookId,
                        inputHash,
                    ).value,
                    maxAttempts = spec.maxAttemptsPerStage,
                    inputSourcesJson = inputSources,
                )
            },
            createdAt = spec.createdAt,
        )
    }

    private data class Definition(
        val stageId: String,
        val phase: GenerationPhase,
        val targetType: GenerationTargetType,
        val outputSchemaId: String,
        val dependencies: List<String>,
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
    private val HASH = Regex("[0-9a-f]{64}")
}
