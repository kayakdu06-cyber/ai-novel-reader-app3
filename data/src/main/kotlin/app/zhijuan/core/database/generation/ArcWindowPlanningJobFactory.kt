package app.zhijuan.core.database.generation

import app.zhijuan.core.model.GenerationJobType
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.task.ArcPlanningWindowInput
import app.zhijuan.core.task.ArcPlanningWindowPolicyV1
import app.zhijuan.core.task.ArcPlanningWindowSelection
import app.zhijuan.core.task.PromptBundleCatalogV1
import app.zhijuan.core.task.StageIdempotencyKey
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

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
    val selection: ArcPlanningWindowSelection,
)

data class FrozenArcWindowPlanningSource(
    val masterOutlineRevisionId: String,
    val masterOutlineContentHash: String,
    val parentOutlineRevisionId: String,
    val parentOutlineContentHash: String,
    val targetChapterCount: Int,
    val selection: ArcPlanningWindowSelection,
)

/** Freezes one deterministic 1-8 chapter rolling window without generating a whole-book outline. */
object ArcWindowPlanningJobFactory {
    const val SOURCE_POLICY_VERSION = "zhijuan.arc-window-stage.v1"
    const val OUTPUT_SCHEMA_ID = "arc-plan.v2"
    const val OUTPUT_POLICY_VERSION = "zhijuan.arc-window-policy.v2"

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
                "sourcePolicyVersion" to JsonPrimitive(SOURCE_POLICY_VERSION),
                "policyVersion" to JsonPrimitive(OUTPUT_POLICY_VERSION),
                "promptBundleVersion" to JsonPrimitive(PromptBundleCatalogV1.BUNDLE_VERSION),
                "outputSchemaId" to JsonPrimitive(OUTPUT_SCHEMA_ID),
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
        val inputVersionHash = inputHash(
            masterRevisionId = spec.masterOutlineRevisionId,
            masterHash = spec.masterOutlineContentHash,
            parentRevisionId = spec.parentOutlineRevisionId,
            parentHash = spec.parentOutlineContentHash,
            sources = inputSources,
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

    internal fun parseAndVerify(stage: GenerationStageEntity): FrozenArcWindowPlanningSource {
        require(stage.phase == GenerationPhase.BUILD_ARC_PLAN)
        require(stage.targetType == GenerationTargetType.OUTLINE && IDENTIFIER.matches(stage.targetId))
        require(stage.maxAttempts in 1..4)
        val root = runCatching { JSON.parseToJsonElement(stage.inputSourcesJson) as JsonObject }
            .getOrElse { throw IllegalArgumentException("Arc-window sources are invalid JSON.") }
        require(root.keys == SOURCE_KEYS) { "Arc-window source keys are invalid." }
        require(root.int("schemaVersion") == 1)
        require(root.string("sourcePolicyVersion") == SOURCE_POLICY_VERSION)
        require(root.string("policyVersion") == OUTPUT_POLICY_VERSION)
        require(root.string("promptBundleVersion") == PromptBundleCatalogV1.BUNDLE_VERSION)
        require(root.string("outputSchemaId") == OUTPUT_SCHEMA_ID)
        val masterRevisionId = root.identifier("masterOutlineRevisionId")
        val masterHash = root.hash("masterOutlineContentHash")
        val parentRevisionId = root.identifier("parentOutlineRevisionId")
        val parentHash = root.hash("parentOutlineContentHash")
        require(root.nullableString("activeArcId") == null && root.nullableString("activeArcContentHash") == null) {
            "Initial arc-window source cannot claim an existing active arc."
        }
        val windowInput = ArcPlanningWindowInput(
            targetChapterCount = root.int("targetChapterCount"),
            nextChapterIndex = root.int("nextChapterIndex"),
            enclosingBeatStartChapter = root.int("enclosingBeatStartChapter"),
            enclosingBeatEndChapter = root.int("enclosingBeatEndChapter"),
        )
        val selection = ArcPlanningWindowPolicyV1.select(windowInput)
        require(
            root.string("arcId") == selection.arcId &&
                root.int("arcStartChapter") == selection.arcStartChapter &&
                root.int("arcEndChapter") == selection.arcEndChapter &&
                root.string("windowId") == selection.windowId &&
                root.int("windowStartChapter") == selection.windowStartChapter &&
                root.int("windowEndChapter") == selection.windowEndChapter &&
                root.nullableInt("nextWindowStartChapter") == selection.nextWindowStartChapter,
        ) { "Arc-window selection changed after freezing." }
        require(
            stage.inputVersionHash == inputHash(
                masterRevisionId,
                masterHash,
                parentRevisionId,
                parentHash,
                stage.inputSourcesJson,
            ),
        ) { "Arc-window input hash changed." }
        require(
            stage.idempotencyKey == StageIdempotencyKey.create(
                stage.jobId,
                stage.phase,
                stage.targetId,
                stage.inputVersionHash,
            ).value,
        ) { "Arc-window idempotency key changed." }
        return FrozenArcWindowPlanningSource(
            masterRevisionId,
            masterHash,
            parentRevisionId,
            parentHash,
            windowInput.targetChapterCount,
            selection,
        )
    }

    private fun inputHash(
        masterRevisionId: String,
        masterHash: String,
        parentRevisionId: String,
        parentHash: String,
        sources: String,
    ): String = sha256(
        listOf(
            PromptBundleCatalogV1.BUNDLE_VERSION,
            OUTPUT_POLICY_VERSION,
            masterRevisionId,
            masterHash,
            parentRevisionId,
            parentHash,
            sources,
        ).joinToString("\u0000"),
    )

    private fun JsonObject.string(key: String): String =
        (get(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
            ?: throw IllegalArgumentException("Arc-window string source is invalid: $key")

    private fun JsonObject.identifier(key: String): String = string(key).also {
        require(IDENTIFIER.matches(it))
    }

    private fun JsonObject.hash(key: String): String = string(key).also { require(HASH.matches(it)) }

    private fun JsonObject.int(key: String): Int =
        (get(key) as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull
            ?: throw IllegalArgumentException("Arc-window integer source is invalid: $key")

    private fun JsonObject.nullableString(key: String): String? {
        val value = get(key) ?: throw IllegalArgumentException("Arc-window source is missing: $key")
        if (value is JsonNull) return null
        return (value as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
            ?: throw IllegalArgumentException("Arc-window nullable string source is invalid: $key")
    }

    private fun JsonObject.nullableInt(key: String): Int? {
        val value = get(key) ?: throw IllegalArgumentException("Arc-window source is missing: $key")
        if (value is JsonNull) return null
        return (value as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull
            ?: throw IllegalArgumentException("Arc-window nullable integer source is invalid: $key")
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private val JSON = Json { isLenient = false }
    private val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
    private val HASH = Regex("[0-9a-f]{64}")
    private val SOURCE_KEYS = setOf(
        "schemaVersion", "sourcePolicyVersion", "policyVersion", "promptBundleVersion",
        "outputSchemaId", "masterOutlineRevisionId", "masterOutlineContentHash",
        "parentOutlineRevisionId", "parentOutlineContentHash", "targetChapterCount",
        "nextChapterIndex", "enclosingBeatStartChapter", "enclosingBeatEndChapter",
        "activeArcId", "activeArcContentHash", "arcId", "arcStartChapter", "arcEndChapter",
        "windowId", "windowStartChapter", "windowEndChapter", "nextWindowStartChapter",
    )
}
