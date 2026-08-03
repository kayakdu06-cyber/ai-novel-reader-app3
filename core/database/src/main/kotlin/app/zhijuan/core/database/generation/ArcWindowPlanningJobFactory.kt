package app.zhijuan.core.database.generation

import app.zhijuan.core.model.GenerationJobType
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.task.ArcPlanningWindowInput
import app.zhijuan.core.task.ArcPlanningWindowPolicyV1
import app.zhijuan.core.task.PromptBundleCatalogV1
import app.zhijuan.core.task.StageIdempotencyKey
import java.security.MessageDigest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class ArcWindowPlanningJobSpec(
    val jobId: String,
    val stageId: String,
    val bookId: String,
    val userIntentJson: String,
    val budgetSnapshotJson: String,
    val masterOutlineRevisionId: String,
    val masterOutlineContentHash: String,
    val parentOutlineRevisionId: String,
    val parentOutlineContentHash: String,
    val windowInput: ArcPlanningWindowInput,
    val maxAttempts: Int = 2,
    val createdAt: Long,
)

data class ArcWindowPlanningJobSetup(
    val generationSetup: GenerationJobSetup,
    val selection: app.zhijuan.core.task.ArcPlanningWindowSelection,
)

object ArcWindowPlanningJobFactory {
    fun create(spec: ArcWindowPlanningJobSpec): ArcWindowPlanningJobSetup {
        require(
            listOf(
                spec.jobId,
                spec.stageId,
                spec.bookId,
                spec.masterOutlineRevisionId,
                spec.parentOutlineRevisionId,
            ).all(IDENTIFIER::matches),
        ) { "Arc-window planning identifiers are invalid." }
        require(HASH.matches(spec.masterOutlineContentHash) && HASH.matches(spec.parentOutlineContentHash)) {
            "Arc-window planning outline hashes are invalid."
        }
        require(spec.maxAttempts in 1..4)
        require(spec.createdAt >= 0L)
        val selection = ArcPlanningWindowPolicyV1.select(spec.windowInput)
        val inputSources = JsonObject(
            linkedMapOf(
                "schemaVersion" to JsonPrimitive(1),
                "policyVersion" to JsonPrimitive(selection.policyVersion),
                "promptBundleVersion" to JsonPrimitive(PromptBundleCatalogV1.BUNDLE_VERSION),
                "outputSchemaId" to JsonPrimitive("arc-plan.v1"),
                "masterOutlineRevisionId" to JsonPrimitive(spec.masterOutlineRevisionId),
                "masterOutlineContentHash" to JsonPrimitive(spec.masterOutlineContentHash),
                "parentOutlineRevisionId" to JsonPrimitive(spec.parentOutlineRevisionId),
                "parentOutlineContentHash" to JsonPrimitive(spec.parentOutlineContentHash),
                "targetChapterCount" to JsonPrimitive(spec.windowInput.targetChapterCount),
                "nextChapterIndex" to JsonPrimitive(spec.windowInput.nextChapterIndex),
                "enclosingBeatStartChapter" to JsonPrimitive(spec.windowInput.enclosingBeatStartChapter),
                "enclosingBeatEndChapter" to JsonPrimitive(spec.windowInput.enclosingBeatEndChapter),
                "activeArcId" to (spec.windowInput.activeArc?.arcId?.let(::JsonPrimitive) ?: JsonNull),
                "activeArcContentHash" to (
                    spec.windowInput.activeArc?.contentHash?.let(::JsonPrimitive) ?: JsonNull
                ),
                "arcId" to JsonPrimitive(selection.arcId),
                "arcStartChapter" to JsonPrimitive(selection.arcStartChapter),
                "arcEndChapter" to JsonPrimitive(selection.arcEndChapter),
                "windowId" to JsonPrimitive(selection.windowId),
                "windowStartChapter" to JsonPrimitive(selection.windowStartChapter),
                "windowEndChapter" to JsonPrimitive(selection.windowEndChapter),
                "nextWindowStartChapter" to (
                    selection.nextWindowStartChapter?.let(::JsonPrimitive) ?: JsonNull
                ),
            ),
        ).toString()
        val inputVersionHash = sha256(
            listOf(
                PromptBundleCatalogV1.BUNDLE_VERSION,
                ArcPlanningWindowPolicyV1.POLICY_VERSION,
                spec.masterOutlineRevisionId,
                spec.masterOutlineContentHash,
                spec.parentOutlineRevisionId,
                spec.parentOutlineContentHash,
                inputSources,
            ).joinToString("\u0000"),
        )
        return ArcWindowPlanningJobSetup(
            generationSetup = GenerationJobSetup(
                jobId = spec.jobId,
                bookId = spec.bookId,
                jobType = GenerationJobType.CONTINUE_BOOK,
                userIntentJson = spec.userIntentJson,
                budgetSnapshotJson = spec.budgetSnapshotJson,
                promptBundleVersion = PromptBundleCatalogV1.BUNDLE_VERSION,
                stages = listOf(
                    GenerationStageSetup(
                        stageId = spec.stageId,
                        phase = GenerationPhase.BUILD_ARC_PLAN,
                        targetType = GenerationTargetType.OUTLINE,
                        targetId = spec.bookId,
                        inputVersionHash = inputVersionHash,
                        idempotencyKey = StageIdempotencyKey.create(
                            jobId = spec.jobId,
                            phase = GenerationPhase.BUILD_ARC_PLAN,
                            targetId = spec.bookId,
                            inputVersionHash = inputVersionHash,
                        ).value,
                        maxAttempts = spec.maxAttempts,
                        inputSourcesJson = inputSources,
                    ),
                ),
                createdAt = spec.createdAt,
            ),
            selection = selection,
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
    private val HASH = Regex("[0-9a-f]{64}")
}
