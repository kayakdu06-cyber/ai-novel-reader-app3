package app.zhijuan.core.database.generation

import app.zhijuan.core.model.GenerationJobType
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.task.ChapterContextBudgetPolicyV1
import app.zhijuan.core.task.ChapterContextBudgetSpec
import app.zhijuan.core.task.PromptBundleCatalogV1
import app.zhijuan.core.task.StageIdempotencyKey
import java.security.MessageDigest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class ChapterContextAssemblyStageIds(
    val contextStageId: String,
    val chapterPlanStageId: String,
)

data class ChapterContextAssemblyJobSpec(
    val jobId: String,
    val bookId: String,
    val chapterId: String,
    val chapterIndex: Int,
    val userIntentJson: String,
    val budgetSnapshotJson: String,
    val promptBindingHash: String,
    val contextBudget: ChapterContextBudgetSpec,
    val progressionPermit: ChapterProgressionPermit,
    val stageIds: ChapterContextAssemblyStageIds,
    val userAddition: String? = null,
    val chapterPlanMaxAttempts: Int = 2,
    val createdAt: Long,
)

object ChapterContextAssemblyJobFactory {
    const val CHAPTER_PLAN_SCHEMA_ID = "chapter-plan.v1"

    fun create(spec: ChapterContextAssemblyJobSpec): GenerationJobSetup {
        validate(spec)
        val contextBase = JsonObject(
            linkedMapOf(
                "schemaVersion" to JsonPrimitive(1),
                "promptBundleVersion" to JsonPrimitive(PromptBundleCatalogV1.BUNDLE_VERSION),
                "outputSchemaId" to JsonPrimitive(ChapterContextBudgetPolicyV1.MANIFEST_SCHEMA_ID),
                "dependencyStageIds" to kotlinx.serialization.json.JsonArray(emptyList()),
                "contextAssembly" to JsonObject(
                    linkedMapOf(
                        "policyVersion" to JsonPrimitive(ChapterContextBudgetPolicyV1.POLICY_VERSION),
                        "targetChapterIndex" to JsonPrimitive(spec.chapterIndex),
                        "promptBindingHash" to JsonPrimitive(spec.promptBindingHash),
                        "targetPhase" to JsonPrimitive(GenerationPhase.BUILD_CHAPTER_PLAN.name),
                        "contextLimitTokens" to (
                            spec.contextBudget.contextLimitTokens?.let(::JsonPrimitive) ?: JsonNull
                        ),
                        "maximumOutputTokens" to (
                            spec.contextBudget.maximumOutputTokens?.let(::JsonPrimitive) ?: JsonNull
                        ),
                        "requestedOutputTokens" to JsonPrimitive(spec.contextBudget.requestedOutputTokens),
                        "limitSource" to JsonPrimitive(spec.contextBudget.limitSource.name),
                        "unknownLimitConfirmed" to JsonPrimitive(spec.contextBudget.unknownLimitConfirmed),
                        "tokenizerFamily" to JsonPrimitive(spec.contextBudget.tokenizerFamily),
                        "userAddition" to (spec.userAddition?.let(::JsonPrimitive) ?: JsonNull),
                    ),
                ),
            ),
        )
        val contextInput = spec.progressionPermit.bindInto(contextBase.toString())
        val contextInputHash = sha256(contextInput)
        val planBase = JsonObject(
            linkedMapOf(
                "schemaVersion" to JsonPrimitive(1),
                "promptBundleVersion" to JsonPrimitive(PromptBundleCatalogV1.BUNDLE_VERSION),
                "outputSchemaId" to JsonPrimitive(CHAPTER_PLAN_SCHEMA_ID),
                "dependencyStageIds" to kotlinx.serialization.json.JsonArray(
                    listOf(JsonPrimitive(spec.stageIds.contextStageId)),
                ),
                "contextAssemblyStageId" to JsonPrimitive(spec.stageIds.contextStageId),
                "contextInputVersionHash" to JsonPrimitive(contextInputHash),
                "contextPolicyVersion" to JsonPrimitive(ChapterContextBudgetPolicyV1.POLICY_VERSION),
                "contextManifestSchemaId" to JsonPrimitive(ChapterContextBudgetPolicyV1.MANIFEST_SCHEMA_ID),
            ),
        )
        val planInput = spec.progressionPermit.bindInto(planBase.toString())
        val planInputHash = sha256(planInput)
        return GenerationJobSetup(
            jobId = spec.jobId,
            bookId = spec.bookId,
            jobType = GenerationJobType.CONTINUE_BOOK,
            userIntentJson = spec.userIntentJson,
            budgetSnapshotJson = spec.budgetSnapshotJson,
            promptBundleVersion = PromptBundleCatalogV1.BUNDLE_VERSION,
            stages = listOf(
                GenerationStageSetup(
                    stageId = spec.stageIds.contextStageId,
                    phase = GenerationPhase.ASSEMBLE_CONTEXT,
                    targetType = GenerationTargetType.CHAPTER,
                    targetId = spec.chapterId,
                    inputVersionHash = contextInputHash,
                    idempotencyKey = StageIdempotencyKey.create(
                        jobId = spec.jobId,
                        phase = GenerationPhase.ASSEMBLE_CONTEXT,
                        targetId = spec.chapterId,
                        inputVersionHash = contextInputHash,
                    ).value,
                    maxAttempts = 1,
                    inputSourcesJson = contextInput,
                ),
                GenerationStageSetup(
                    stageId = spec.stageIds.chapterPlanStageId,
                    phase = GenerationPhase.BUILD_CHAPTER_PLAN,
                    targetType = GenerationTargetType.CHAPTER,
                    targetId = spec.chapterId,
                    inputVersionHash = planInputHash,
                    idempotencyKey = StageIdempotencyKey.create(
                        jobId = spec.jobId,
                        phase = GenerationPhase.BUILD_CHAPTER_PLAN,
                        targetId = spec.chapterId,
                        inputVersionHash = planInputHash,
                    ).value,
                    maxAttempts = spec.chapterPlanMaxAttempts,
                    inputSourcesJson = planInput,
                ),
            ),
            createdAt = spec.createdAt,
        )
    }

    private fun validate(spec: ChapterContextAssemblyJobSpec) {
        require(
            listOf(
                spec.jobId,
                spec.bookId,
                spec.chapterId,
                spec.stageIds.contextStageId,
                spec.stageIds.chapterPlanStageId,
            ).all(IDENTIFIER::matches),
        ) { "Chapter-context job identifiers are invalid." }
        require(spec.stageIds.contextStageId != spec.stageIds.chapterPlanStageId) {
            "Chapter-context stage ids must be distinct."
        }
        require(spec.chapterIndex >= 1) { "Chapter-context target index is invalid." }
        require(HASH.matches(spec.promptBindingHash)) { "Prompt binding hash is invalid." }
        require(spec.chapterPlanMaxAttempts in 1..4) { "Chapter-plan retry limit is invalid." }
        require(spec.createdAt >= 0L) { "Chapter-context job time is invalid." }
        require(
            spec.userAddition == null ||
                spec.userAddition.isNotBlank() &&
                spec.userAddition.toByteArray(Charsets.UTF_8).size <= MAX_USER_ADDITION_BYTES,
        ) { "Chapter-context user addition is invalid." }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
    private val HASH = Regex("[0-9a-f]{64}")
    private const val MAX_USER_ADDITION_BYTES = 16 * 1_024
}
