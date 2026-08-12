package app.zhijuan.core.database.generation

import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.task.PromptBundleCatalogV1
import app.zhijuan.core.task.StageIdempotencyKey
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal data class ChapterPlanSourceV2(
    val contextAssemblyStageId: String,
    val contextInputVersionHash: String,
    val targetChapterIndex: Int,
    val progressionEvidenceHash: String,
    val frozen: ChapterPlanV2FrozenSources,
) {
    override fun toString(): String =
        "ChapterPlanSourceV2(targetChapterIndex=$targetChapterIndex, sources=redacted, hashes=redacted)"
}

/** Adds v2 authority manifests without changing the established context/progression job factory. */
object ChapterPlanV2StageBinding {
    const val SOURCE_POLICY_VERSION = "zhijuan.chapter-plan-source.v2"
    const val OUTPUT_SCHEMA_ID = "chapter-plan.v2"

    fun bind(setup: GenerationJobSetup, frozen: ChapterPlanV2FrozenSources): GenerationJobSetup {
        val planIndexes = setup.stages.withIndex().filter {
            it.value.phase == GenerationPhase.BUILD_CHAPTER_PLAN
        }
        require(planIndexes.size == 1) { "A chapter-plan v2 job must contain exactly one plan Stage." }
        val index = planIndexes.single().index
        val stage = setup.stages[index]
        require(stage.targetType == GenerationTargetType.CHAPTER) {
            "Chapter-plan v2 Stage target is invalid."
        }
        ChapterContextAssemblyJobFactory.parseAndVerifyChapterPlan(
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
            ),
        )
        val root = parseObject(stage.inputSourcesJson)
        require(root.string("sourcePolicyVersion") == ChapterContextAssemblyJobFactory.CHAPTER_PLAN_SOURCE_POLICY_VERSION) {
            "Only a verified chapter-plan v1 source may be upgraded to v2."
        }
        val upgraded = JsonObject(
            linkedMapOf<String, kotlinx.serialization.json.JsonElement>().apply {
                putAll(root)
                this["schemaVersion"] = JsonPrimitive(2)
                this["sourcePolicyVersion"] = JsonPrimitive(SOURCE_POLICY_VERSION)
                this["outputSchemaId"] = JsonPrimitive(OUTPUT_SCHEMA_ID)
                putAll(frozen.stageFields())
            },
        ).toString()
        val inputHash = sha256(upgraded)
        val replacement = stage.copy(
            inputVersionHash = inputHash,
            idempotencyKey = StageIdempotencyKey.create(
                jobId = setup.jobId,
                phase = stage.phase,
                targetId = stage.targetId,
                inputVersionHash = inputHash,
            ).value,
            inputSourcesJson = upgraded,
        )
        return setup.copy(stages = setup.stages.toMutableList().apply { this[index] = replacement })
    }

    internal fun parseAndVerify(stage: GenerationStageEntity): ChapterPlanSourceV2 {
        require(stage.phase == GenerationPhase.BUILD_CHAPTER_PLAN) { "Chapter-plan v2 phase is invalid." }
        require(stage.targetType == GenerationTargetType.CHAPTER && stage.targetId.isNotBlank()) {
            "Chapter-plan v2 target is invalid."
        }
        require(stage.maxAttempts in 1..4) { "Chapter-plan v2 retry limit is invalid." }
        val root = parseObject(stage.inputSourcesJson)
        require(root.keys == ROOT_KEYS) { "Chapter-plan v2 source binding has unexpected fields." }
        require(root.int("schemaVersion") == 2) { "Chapter-plan v2 source schema is unsupported." }
        require(root.string("sourcePolicyVersion") == SOURCE_POLICY_VERSION)
        require(root.string("promptBundleVersion") == PromptBundleCatalogV1.BUNDLE_VERSION)
        require(root.string("outputSchemaId") == OUTPUT_SCHEMA_ID)
        val contextStageId = root.string("contextAssemblyStageId")
        require(IDENTIFIER.matches(contextStageId)) { "Chapter-plan v2 context Stage id is invalid." }
        val dependencies = root["dependencyStageIds"] as? JsonArray
            ?: throw IllegalArgumentException("Chapter-plan v2 dependency list is invalid.")
        require(dependencies.size == 1 && dependencies.single().let {
            it is JsonPrimitive && it.isString && it.contentOrNull == contextStageId
        }) { "Chapter-plan v2 context dependency is invalid." }
        val contextInputHash = root.string("contextInputVersionHash")
        require(HASH.matches(contextInputHash)) { "Chapter-plan v2 context input hash is invalid." }
        val progression = root["chapterProgressionGate"] as? JsonObject
            ?: throw IllegalArgumentException("Chapter-plan v2 progression evidence is missing.")
        val evidenceHash = progression.string("evidenceHash")
        require(HASH.matches(evidenceHash))
        require(sha256(JsonObject(progression.filterKeys { it != "evidenceHash" }).toString()) == evidenceHash) {
            "Chapter-plan v2 progression evidence hash is inconsistent."
        }
        require(progression.string("chapterId") == stage.targetId) {
            "Chapter-plan v2 target changed after freezing."
        }
        val chapterIndex = progression.int("chapterIndex")
        require(chapterIndex in 1..10_000)
        require(stage.inputVersionHash == sha256(stage.inputSourcesJson)) {
            "Chapter-plan v2 input hash does not match its frozen source binding."
        }
        return ChapterPlanSourceV2(
            contextAssemblyStageId = contextStageId,
            contextInputVersionHash = contextInputHash,
            targetChapterIndex = chapterIndex,
            progressionEvidenceHash = evidenceHash,
            frozen = ChapterPlanV2FrozenSources.fromStageRoot(root),
        )
    }

    private fun parseObject(value: String): JsonObject = runCatching {
        STRICT_JSON.parseToJsonElement(value) as JsonObject
    }.getOrElse { throw IllegalArgumentException("Chapter-plan v2 source binding is invalid JSON.") }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
    private val HASH = Regex("[0-9a-f]{64}")
    private val STRICT_JSON = Json { isLenient = false }
    private val ROOT_KEYS = setOf(
        "schemaVersion", "sourcePolicyVersion", "promptBundleVersion", "outputSchemaId",
        "dependencyStageIds", "contextAssemblyStageId", "contextInputVersionHash",
        "contextPolicyVersion", "contextManifestSchemaId", "chapterProgressionGate",
        "expectation", "expectationHash", "activationManifest", "activationManifestHash",
        "activationHash", "policyManifest", "policyManifestHash", "policyCompilationHash",
        "contextEvidenceHash",
    )
}

private fun JsonObject.string(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
        ?: throw IllegalArgumentException("Chapter-plan v2 string field is invalid: $key")

private fun JsonObject.int(key: String): Int =
    (this[key] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull
        ?: throw IllegalArgumentException("Chapter-plan v2 integer field is invalid: $key")
