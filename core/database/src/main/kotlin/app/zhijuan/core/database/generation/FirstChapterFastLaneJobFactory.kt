package app.zhijuan.core.database.generation

import app.zhijuan.core.model.GenerationJobType
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.task.FirstChapterProgressionPolicyV1
import app.zhijuan.core.task.PromptBundleCatalogV1
import app.zhijuan.core.task.StageIdempotencyKey
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class FirstChapterFastLaneJobSpec(
    val jobId: String,
    val stageId: String,
    val bookId: String,
    val chapterId: String,
    val chapterIndex: Int,
    val userIntentJson: String,
    val budgetSnapshotJson: String,
    val creationSnapshotHash: String,
    val promptBindingHash: String,
    val seedStageId: String,
    val seedRawOutputHash: String,
    val seedContentHash: String,
    val maxAttempts: Int = 2,
    val createdAt: Long,
)

object FirstChapterFastLaneJobFactory {
    fun create(spec: FirstChapterFastLaneJobSpec): GenerationJobSetup {
        require(
            listOf(spec.jobId, spec.stageId, spec.bookId, spec.chapterId, spec.seedStageId).all(IDENTIFIER::matches),
        ) { "First-chapter fast-lane identifiers are invalid." }
        require(spec.chapterIndex == 1) { "The fast lane can target only chapter one." }
        require(
            listOf(
                spec.creationSnapshotHash,
                spec.promptBindingHash,
                spec.seedRawOutputHash,
                spec.seedContentHash,
            ).all(HASH::matches),
        ) { "First-chapter fast-lane hashes are invalid." }
        require(spec.maxAttempts in 1..4)
        require(spec.createdAt >= 0L)
        val bootstrapEvidence = JsonObject(
            linkedMapOf(
                "schemaVersion" to JsonPrimitive(1),
                "policyVersion" to JsonPrimitive(FirstChapterProgressionPolicyV1.POLICY_VERSION),
                "contractVersion" to JsonPrimitive(
                    FirstChapterProgressionPolicyV1.FAST_LANE_CONTRACT_VERSION,
                ),
                "outputSchemaId" to JsonPrimitive(
                    FirstChapterProgressionPolicyV1.FAST_LANE_OUTPUT_SCHEMA_ID,
                ),
                "bookId" to JsonPrimitive(spec.bookId),
                "chapterId" to JsonPrimitive(spec.chapterId),
                "chapterIndex" to JsonPrimitive(spec.chapterIndex),
                "creationSnapshotHash" to JsonPrimitive(spec.creationSnapshotHash),
                "promptBindingHash" to JsonPrimitive(spec.promptBindingHash),
                "seedStageId" to JsonPrimitive(spec.seedStageId),
                "seedRawOutputHash" to JsonPrimitive(spec.seedRawOutputHash),
                "seedContentHash" to JsonPrimitive(spec.seedContentHash),
                "requiredRoughChapterCount" to JsonPrimitive(
                    FirstChapterProgressionPolicyV1.REQUIRED_ROUGH_CHAPTER_COUNT,
                ),
            ),
        )
        val inputSources = JsonObject(
            linkedMapOf(
                "schemaVersion" to JsonPrimitive(1),
                "promptBundleVersion" to JsonPrimitive(PromptBundleCatalogV1.BUNDLE_VERSION),
                "firstChapterBootstrap" to bootstrapEvidence,
            ),
        ).toString()
        val inputVersionHash = sha256(
            listOf(
                PromptBundleCatalogV1.BUNDLE_VERSION,
                FirstChapterProgressionPolicyV1.FAST_LANE_CONTRACT_VERSION,
                spec.creationSnapshotHash,
                spec.promptBindingHash,
                spec.seedStageId,
                spec.seedRawOutputHash,
                spec.seedContentHash,
                spec.bookId,
                spec.chapterId,
                spec.chapterIndex.toString(),
            ).joinToString("\u0000"),
        )
        return GenerationJobSetup(
            jobId = spec.jobId,
            bookId = spec.bookId,
            jobType = GenerationJobType.CONTINUE_BOOK,
            userIntentJson = spec.userIntentJson,
            budgetSnapshotJson = spec.budgetSnapshotJson,
            promptBundleVersion = PromptBundleCatalogV1.BUNDLE_VERSION,
            stages = listOf(
                GenerationStageSetup(
                    stageId = spec.stageId,
                    phase = GenerationPhase.BUILD_CHAPTER_PLAN,
                    targetType = GenerationTargetType.CHAPTER,
                    targetId = spec.chapterId,
                    inputVersionHash = inputVersionHash,
                    idempotencyKey = StageIdempotencyKey.create(
                        jobId = spec.jobId,
                        phase = GenerationPhase.BUILD_CHAPTER_PLAN,
                        targetId = spec.chapterId,
                        inputVersionHash = inputVersionHash,
                    ).value,
                    maxAttempts = spec.maxAttempts,
                    inputSourcesJson = inputSources,
                ),
            ),
            createdAt = spec.createdAt,
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
    private val HASH = Regex("[0-9a-f]{64}")
}
