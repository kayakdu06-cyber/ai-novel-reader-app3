package app.zhijuan.core.database.generation

import app.zhijuan.core.model.GenerationJobType
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.task.ChapterContextBudgetPolicyV1
import app.zhijuan.core.task.ChapterContextBudgetSpec
import app.zhijuan.core.task.ChapterContextLimitSource
import app.zhijuan.core.task.PromptBundleCatalogV1
import app.zhijuan.core.task.StageIdempotencyKey
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

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
    const val SOURCE_POLICY_VERSION = "zhijuan.chapter-context-assembly-source.v1"
    const val CHAPTER_PLAN_SOURCE_POLICY_VERSION = "zhijuan.chapter-plan-source.v1"
    const val CHAPTER_PLAN_SCHEMA_ID = "chapter-plan.v1"

    fun create(spec: ChapterContextAssemblyJobSpec): GenerationJobSetup {
        validate(spec)
        val contextBase = JsonObject(
            linkedMapOf(
                "schemaVersion" to JsonPrimitive(1),
                "sourcePolicyVersion" to JsonPrimitive(SOURCE_POLICY_VERSION),
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
                "sourcePolicyVersion" to JsonPrimitive(CHAPTER_PLAN_SOURCE_POLICY_VERSION),
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

    /**
     * Strictly verifies one frozen ASSEMBLE_CONTEXT Stage and returns its finite frozen source
     * data. This is the single authoritative parser shared by the repository and the route
     * resolver; it performs no database access and no dynamic currentness judgement. Unknown or
     * malformed contracts fail closed.
     */
    internal fun parseAndVerify(stage: GenerationStageEntity): ChapterContextAssemblySourceV1 {
        require(stage.phase == GenerationPhase.ASSEMBLE_CONTEXT) {
            "Chapter-context assembly stage phase is invalid."
        }
        require(stage.targetType == GenerationTargetType.CHAPTER) {
            "Chapter-context assembly stage target is invalid."
        }
        require(stage.maxAttempts == 1) { "Chapter-context assembly retry limit is invalid." }
        require(stage.targetId.isNotBlank()) { "Chapter-context assembly target id is invalid." }
        val root = runCatching {
            CONTEXT_STRICT_JSON.parseToJsonElement(stage.inputSourcesJson) as JsonObject
        }.getOrElse { throw IllegalArgumentException("Chapter-context assembly source binding is invalid JSON.") }
        require(root.keys == ROOT_KEYS) {
            "Chapter-context assembly source binding has unexpected fields."
        }
        require(root.int("schemaVersion") == 1) {
            "Chapter-context assembly source schema is unsupported."
        }
        require(root.string("sourcePolicyVersion") == SOURCE_POLICY_VERSION) {
            "Chapter-context assembly source policy is not supported."
        }
        require(root.string("promptBundleVersion") == PromptBundleCatalogV1.BUNDLE_VERSION) {
            "Chapter-context assembly prompt bundle is stale."
        }
        require(root.string("outputSchemaId") == ChapterContextBudgetPolicyV1.MANIFEST_SCHEMA_ID) {
            "Chapter-context assembly output schema is not supported."
        }
        val dependencies = root["dependencyStageIds"] as? JsonArray
            ?: throw IllegalArgumentException("Chapter-context assembly dependency stage ids are invalid.")
        require(dependencies.isEmpty()) {
            "Chapter-context assembly source must freeze no stage dependencies."
        }
        val context = root["contextAssembly"] as? JsonObject
            ?: throw IllegalArgumentException("Chapter-context assembly source binding is missing.")
        require(context.keys == CONTEXT_KEYS) {
            "Chapter-context assembly source binding has unexpected fields."
        }
        require(context.string("policyVersion") == ChapterContextBudgetPolicyV1.POLICY_VERSION) {
            "Chapter-context assembly policy version is not supported."
        }
        val targetChapterIndex = context.int("targetChapterIndex")
        require(targetChapterIndex >= 1) { "Chapter-context assembly target chapter index is invalid." }
        val promptBindingHash = context.string("promptBindingHash")
        require(HASH.matches(promptBindingHash)) {
            "Chapter-context assembly prompt binding hash is invalid."
        }
        require(context.string("targetPhase") == GenerationPhase.BUILD_CHAPTER_PLAN.name) {
            "Chapter-context assembly target phase is invalid."
        }
        val limitSource = runCatching {
            ChapterContextLimitSource.valueOf(context.string("limitSource"))
        }.getOrElse { throw IllegalArgumentException("Chapter-context assembly limit source is invalid.") }
        val userAddition = context.optionalString("userAddition")
        require(
            userAddition == null ||
                userAddition.isNotBlank() &&
                userAddition.toByteArray(Charsets.UTF_8).size <= MAX_USER_ADDITION_BYTES,
        ) { "Chapter-context assembly user addition is invalid." }
        val budget = ChapterContextBudgetSpec(
            contextLimitTokens = context.nullableInt("contextLimitTokens"),
            maximumOutputTokens = context.nullableInt("maximumOutputTokens"),
            requestedOutputTokens = context.int("requestedOutputTokens"),
            limitSource = limitSource,
            unknownLimitConfirmed = context.boolean("unknownLimitConfirmed"),
            tokenizerFamily = context.string("tokenizerFamily"),
        )
        val progression = root["chapterProgressionGate"] as? JsonObject
            ?: throw IllegalArgumentException("Chapter-context assembly progression evidence is missing.")
        val evidenceHash = progression.optionalString("evidenceHash")
            ?: throw IllegalArgumentException("Chapter-context assembly progression evidence hash is missing.")
        require(HASH.matches(evidenceHash)) {
            "Chapter-context assembly progression evidence hash is invalid."
        }
        require(
            sha256(JsonObject(progression.filterKeys { it != "evidenceHash" }).toString()) == evidenceHash,
        ) { "Chapter-context assembly progression evidence hash is inconsistent." }
        require(progression.optionalString("chapterId") == stage.targetId) {
            "Chapter-context assembly target changed after freezing."
        }
        require(progression.int("chapterIndex") == targetChapterIndex) {
            "Chapter-context assembly target index changed after freezing."
        }
        require(stage.inputVersionHash == sha256(stage.inputSourcesJson)) {
            "Chapter-context assembly input hash does not match its frozen source binding."
        }
        return ChapterContextAssemblySourceV1(
            targetChapterIndex = targetChapterIndex,
            promptBindingHash = promptBindingHash,
            progressionEvidenceHash = evidenceHash,
            budget = budget,
            userAddition = userAddition,
        )
    }

    /**
     * Strictly verifies the request-preexisting source identity of one normal chapter-plan Stage.
     * It does not read the assembled context payload, database state, connection, or Provider.
     */
    internal fun parseAndVerifyChapterPlan(stage: GenerationStageEntity): ChapterPlanSourceV1 {
        require(stage.phase == GenerationPhase.BUILD_CHAPTER_PLAN) {
            "Chapter-plan stage phase is invalid."
        }
        require(stage.targetType == GenerationTargetType.CHAPTER) {
            "Chapter-plan stage target is invalid."
        }
        require(stage.maxAttempts in 1..4) { "Chapter-plan retry limit is invalid." }
        require(stage.targetId.isNotBlank()) { "Chapter-plan target id is invalid." }
        val root = runCatching {
            CONTEXT_STRICT_JSON.parseToJsonElement(stage.inputSourcesJson) as JsonObject
        }.getOrElse { throw IllegalArgumentException("Chapter-plan source binding is invalid JSON.") }
        require(root.keys == CHAPTER_PLAN_ROOT_KEYS) {
            "Chapter-plan source binding has unexpected fields."
        }
        require(root.int("schemaVersion") == 1) { "Chapter-plan source schema is unsupported." }
        require(root.string("sourcePolicyVersion") == CHAPTER_PLAN_SOURCE_POLICY_VERSION) {
            "Chapter-plan source policy is not supported."
        }
        require(root.string("promptBundleVersion") == PromptBundleCatalogV1.BUNDLE_VERSION) {
            "Chapter-plan prompt bundle is stale."
        }
        require(root.string("outputSchemaId") == CHAPTER_PLAN_SCHEMA_ID) {
            "Chapter-plan output schema is not supported."
        }
        val contextStageId = root.string("contextAssemblyStageId")
        require(IDENTIFIER.matches(contextStageId)) { "Chapter-plan context stage id is invalid." }
        val dependencies = root["dependencyStageIds"] as? JsonArray
            ?: throw IllegalArgumentException("Chapter-plan dependency stage ids are invalid.")
        require(
            dependencies.size == 1 &&
                dependencies.single().let { dependency ->
                    dependency is JsonPrimitive && dependency.isString &&
                        dependency.contentOrNull == contextStageId
                },
        ) { "Chapter-plan context dependency is invalid." }
        val contextInputVersionHash = root.string("contextInputVersionHash")
        require(HASH.matches(contextInputVersionHash)) {
            "Chapter-plan context input hash is invalid."
        }
        require(root.string("contextPolicyVersion") == ChapterContextBudgetPolicyV1.POLICY_VERSION) {
            "Chapter-plan context policy is not supported."
        }
        require(root.string("contextManifestSchemaId") == ChapterContextBudgetPolicyV1.MANIFEST_SCHEMA_ID) {
            "Chapter-plan context manifest schema is not supported."
        }
        val progression = root["chapterProgressionGate"] as? JsonObject
            ?: throw IllegalArgumentException("Chapter-plan progression evidence is missing.")
        val evidenceHash = progression.optionalString("evidenceHash")
            ?: throw IllegalArgumentException("Chapter-plan progression evidence hash is missing.")
        require(HASH.matches(evidenceHash)) { "Chapter-plan progression evidence hash is invalid." }
        require(
            sha256(JsonObject(progression.filterKeys { it != "evidenceHash" }).toString()) == evidenceHash,
        ) { "Chapter-plan progression evidence hash is inconsistent." }
        require(progression.optionalString("chapterId") == stage.targetId) {
            "Chapter-plan target changed after freezing."
        }
        val targetChapterIndex = progression.int("chapterIndex")
        require(targetChapterIndex >= 1) { "Chapter-plan target chapter index is invalid." }
        require(stage.inputVersionHash == sha256(stage.inputSourcesJson)) {
            "Chapter-plan input hash does not match its frozen source binding."
        }
        return ChapterPlanSourceV1(
            contextAssemblyStageId = contextStageId,
            contextInputVersionHash = contextInputVersionHash,
            targetChapterIndex = targetChapterIndex,
            progressionEvidenceHash = evidenceHash,
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
    private val ROOT_KEYS = setOf(
        "schemaVersion",
        "sourcePolicyVersion",
        "promptBundleVersion",
        "outputSchemaId",
        "dependencyStageIds",
        "contextAssembly",
        "chapterProgressionGate",
    )
    private val CONTEXT_KEYS = setOf(
        "policyVersion",
        "targetChapterIndex",
        "promptBindingHash",
        "targetPhase",
        "contextLimitTokens",
        "maximumOutputTokens",
        "requestedOutputTokens",
        "limitSource",
        "unknownLimitConfirmed",
        "tokenizerFamily",
        "userAddition",
    )
    private val CHAPTER_PLAN_ROOT_KEYS = setOf(
        "schemaVersion",
        "sourcePolicyVersion",
        "promptBundleVersion",
        "outputSchemaId",
        "dependencyStageIds",
        "contextAssemblyStageId",
        "contextInputVersionHash",
        "contextPolicyVersion",
        "contextManifestSchemaId",
        "chapterProgressionGate",
    )
}

/**
 * Finite frozen source data of one chapter-context assembly Stage, shared by the repository and
 * the route resolver. Never carries the assembled payload.
 */
internal class ChapterContextAssemblySourceV1(
    val targetChapterIndex: Int,
    val promptBindingHash: String,
    val progressionEvidenceHash: String,
    val budget: ChapterContextBudgetSpec,
    val userAddition: String?,
) {
    override fun toString(): String =
        "ChapterContextAssemblySourceV1(targetChapterIndex=$targetChapterIndex, " +
            "promptBindingHash=redacted, progressionEvidenceHash=redacted, budget=redacted, " +
            "userAddition=redacted)"
}

/** Finite request-preexisting identity of one normal chapter-plan Stage. */
internal data class ChapterPlanSourceV1(
    val contextAssemblyStageId: String,
    val contextInputVersionHash: String,
    val targetChapterIndex: Int,
    val progressionEvidenceHash: String,
) {
    override fun toString(): String =
        "ChapterPlanSourceV1(contextAssemblyStageId=redacted, contextInputVersionHash=redacted, " +
            "targetChapterIndex=$targetChapterIndex, progressionEvidenceHash=redacted)"
}

private val CONTEXT_STRICT_JSON = Json { isLenient = false }

private fun JsonObject.string(key: String): String =
    (get(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
        ?: throw IllegalArgumentException("Chapter-context assembly string field is invalid: $key")

private fun JsonObject.optionalString(key: String): String? {
    val value = get(key) ?: throw IllegalArgumentException("Chapter-context assembly field is missing: $key")
    if (value is JsonNull) return null
    return (value as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
        ?: throw IllegalArgumentException("Chapter-context assembly string field is invalid: $key")
}

private fun JsonObject.int(key: String): Int =
    (get(key) as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull
        ?: throw IllegalArgumentException("Chapter-context assembly integer field is invalid: $key")

private fun JsonObject.nullableInt(key: String): Int? {
    val value = get(key) ?: throw IllegalArgumentException("Chapter-context assembly field is missing: $key")
    if (value is JsonNull) return null
    return (value as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull
        ?: throw IllegalArgumentException("Chapter-context assembly integer field is invalid: $key")
}

private fun JsonObject.boolean(key: String): Boolean =
    (get(key) as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.booleanOrNull
        ?: throw IllegalArgumentException("Chapter-context assembly boolean field is invalid: $key")
