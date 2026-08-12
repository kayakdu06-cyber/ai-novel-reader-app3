package app.zhijuan.core.database.generation

import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.model.RequestAttemptStatus
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal data class InitialChapterDraftSourceV1(
    val planStageId: String,
    val planAttemptId: String,
    val planArtifactRefId: String,
    val planArtifactRevision: Int,
    val planRawOutputHash: String,
    val canonicalPlanHash: String,
    val canonicalPlanJson: String,
    val requestBindingHash: String,
    val expectationHash: String,
    val activationManifestHash: String,
    val activationHash: String,
    val policyManifestHash: String,
    val policyCompilationHash: String,
    val contextEvidenceHash: String,
) {
    override fun toString(): String = "InitialChapterDraftSourceV1(plan=redacted, evidence=redacted)"
}

/** Frozen request-preexisting identity of the first BODY request for one chapter. */
internal object InitialChapterDraftStageBinding {
    const val SOURCE_POLICY_VERSION = "zhijuan.initial-chapter-draft-source.v1"
    const val OUTPUT_SCHEMA_ID = "chapter-draft.v1"

    fun parseAndVerify(stage: GenerationStageEntity): InitialChapterDraftSourceV1 {
        require(
            stage.phase == GenerationPhase.DRAFT_CHAPTER &&
                stage.targetType == GenerationTargetType.CHAPTER &&
                IDENTIFIER.matches(stage.targetId),
        ) { "Initial chapter draft target is invalid." }
        require(stage.maxAttempts in 1..4) { "Initial chapter draft retry limit is invalid." }
        val root = objectValue(stage.inputSourcesJson, "Initial chapter draft source")
        require(root.keys == ROOT_KEYS) { "Initial chapter draft source has unexpected fields." }
        require(root.int("schemaVersion") == 1)
        require(root.string("sourcePolicyVersion") == SOURCE_POLICY_VERSION)
        require(root.string("outputSchemaId") == OUTPUT_SCHEMA_ID)
        val planStageId = root.identifier("planStageId")
        val dependencies = root["dependencyStageIds"] as? JsonArray
            ?: throw IllegalArgumentException("Initial chapter draft dependency list is invalid.")
        require(dependencies.size == 1 && dependencies.single().stringOrNull() == planStageId) {
            "Initial chapter draft must depend on exactly its frozen plan Stage."
        }
        val plan = root["canonicalPlan"] as? JsonObject
            ?: throw IllegalArgumentException("Initial chapter draft canonical plan is missing.")
        val canonicalPlan = canonicalize(plan).toString()
        require(plan.toString() == canonicalPlan) { "Initial chapter draft plan is not canonical JSON." }
        require(plan.string("chapterId") == stage.targetId) {
            "Initial chapter draft target changed after planning."
        }
        require(plan.int("chapterIndex") in 1..10_000)
        val source = InitialChapterDraftSourceV1(
            planStageId = planStageId,
            planAttemptId = root.identifier("planAttemptId"),
            planArtifactRefId = root.artifactRef("planArtifactRefId"),
            planArtifactRevision = root.int("planArtifactRevision").also { require(it >= 0) },
            planRawOutputHash = root.hash("planRawOutputHash"),
            canonicalPlanHash = root.hash("canonicalPlanHash"),
            canonicalPlanJson = canonicalPlan,
            requestBindingHash = root.hash("requestBindingHash"),
            expectationHash = root.hash("expectationHash"),
            activationManifestHash = root.hash("activationManifestHash"),
            activationHash = root.hash("activationHash"),
            policyManifestHash = root.hash("policyManifestHash"),
            policyCompilationHash = root.hash("policyCompilationHash"),
            contextEvidenceHash = root.hash("contextEvidenceHash"),
        )
        require(sha256(canonicalPlan) == source.canonicalPlanHash)
        require(plan.string("activationHash") == source.activationHash)
        require(plan.string("policyCompilationHash") == source.policyCompilationHash)
        require(plan.string("contextEvidenceHash") == source.contextEvidenceHash)
        require(HASH.matches(plan.string("contextContentHash")))
        require(HASH.matches(plan.string("contextSourceManifestHash")))
        require(stage.inputVersionHash == sha256(stage.inputSourcesJson)) {
            "Initial chapter draft input hash does not match its frozen source."
        }
        return source
    }

    private fun objectValue(value: String, label: String): JsonObject = runCatching {
        STRICT_JSON.parseToJsonElement(value) as JsonObject
    }.getOrElse { throw IllegalArgumentException("$label is not a strict JSON object.") }

    private fun canonicalize(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.entries.sortedBy { it.key }.associate { it.key to canonicalize(it.value) })
        is JsonArray -> JsonArray(value.map(::canonicalize))
        else -> value
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
            ?: throw IllegalArgumentException("Initial chapter draft string field is invalid: $key")

    private fun JsonObject.int(key: String): Int =
        (this[key] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull
            ?: throw IllegalArgumentException("Initial chapter draft integer field is invalid: $key")

    private fun JsonObject.identifier(key: String): String = string(key).also {
        require(IDENTIFIER.matches(it)) { "Initial chapter draft identifier is invalid: $key" }
    }

    private fun JsonObject.artifactRef(key: String): String = string(key).also {
        require(ARTIFACT_REF.matches(it)) { "Initial chapter draft artifact reference is invalid." }
    }

    private fun JsonObject.hash(key: String): String = string(key).also {
        require(HASH.matches(it)) { "Initial chapter draft hash is invalid: $key" }
    }

    private fun JsonElement.stringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull

    private val ROOT_KEYS = setOf(
        "schemaVersion", "sourcePolicyVersion", "outputSchemaId", "dependencyStageIds",
        "planStageId", "planAttemptId", "planArtifactRefId", "planArtifactRevision",
        "planRawOutputHash", "canonicalPlanHash", "canonicalPlan", "requestBindingHash",
        "expectationHash", "activationManifestHash", "activationHash", "policyManifestHash",
        "policyCompilationHash", "contextEvidenceHash",
    )
    private val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
    private val ARTIFACT_REF = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val HASH = Regex("[0-9a-f]{64}")
    private val STRICT_JSON = Json { isLenient = false; ignoreUnknownKeys = false }
}

/** Rechecks the plan and context chain immediately before Provider open. */
internal class InitialChapterDraftSourceGuard(private val database: app.zhijuan.core.database.ZhijuanDatabase) {
    suspend fun requireProviderOpenAllowedIfBound(
        stage: GenerationStageEntity,
        job: GenerationJobEntity,
        attemptInputHash: String,
    ): Boolean {
        if (stage.phase != GenerationPhase.DRAFT_CHAPTER) return false
        val source = runCatching { InitialChapterDraftStageBinding.parseAndVerify(stage) }.getOrElse {
            val policy = runCatching {
                (Json.parseToJsonElement(stage.inputSourcesJson) as? JsonObject)
                    ?.get("sourcePolicyVersion")?.let { it as? JsonPrimitive }?.contentOrNull
            }.getOrNull()
            if (policy == InitialChapterDraftStageBinding.SOURCE_POLICY_VERSION) throw it
            return false
        }
        require(HASH.matches(attemptInputHash)) { "Initial chapter draft request binding is invalid." }
        val dao = database.generationDao()
        val planStage = requireNotNull(dao.findStage(source.planStageId)) { "Frozen chapter plan Stage is missing." }
        val planAttempt = requireNotNull(dao.findAttempt(source.planAttemptId)) { "Frozen chapter plan Attempt is missing." }
        val planSource = ChapterPlanV2StageBinding.parseAndVerify(planStage)
        require(
            planStage.jobId == job.jobId && planStage.targetId == stage.targetId &&
                planStage.status == GenerationStageStatus.SUCCEEDED &&
                planAttempt.stageId == planStage.stageId && planAttempt.status == RequestAttemptStatus.SUCCEEDED &&
                planAttempt.inputHash == source.requestBindingHash &&
                planAttempt.outputHash == source.planRawOutputHash &&
                planAttempt.streamDraftRef == source.planArtifactRefId,
        ) { "Initial chapter draft plan evidence changed before Provider open." }
        val output = objectValue(requireNotNull(planStage.outputReferenceJson), "Chapter plan output")
        require(
            output.keys == PLAN_OUTPUT_KEYS && output.int("schemaVersion") == 2 &&
                output.string("outputSchemaId") == ChapterPlanV2StageBinding.OUTPUT_SCHEMA_ID &&
                output.string("attemptId") == source.planAttemptId &&
                output.string("artifactRefId") == source.planArtifactRefId &&
                output.int("artifactRevision") == source.planArtifactRevision &&
                output.string("rawOutputHash") == source.planRawOutputHash &&
                output.string("canonicalPlanHash") == source.canonicalPlanHash &&
                output.string("requestBindingHash") == source.requestBindingHash &&
                output.string("nextStageId") == stage.stageId,
        ) { "Initial chapter draft plan output changed before Provider open." }
        require(
            planSource.frozen.expectationHash == source.expectationHash &&
                planSource.frozen.activationManifestHash == source.activationManifestHash &&
                planSource.frozen.activationHash == source.activationHash &&
                planSource.frozen.policyManifestHash == source.policyManifestHash &&
                planSource.frozen.policyCompilationHash == source.policyCompilationHash &&
                planSource.frozen.contextEvidenceHash == source.contextEvidenceHash,
        ) { "Initial chapter draft authority evidence changed before Provider open." }
        ChapterProgressionGateRepository(database).requireProviderOpenAllowed(planStage, job)
        ChapterContextAssemblyRepository(database).requireProviderOpenAllowed(planStage, job)
        return true
    }

    private fun objectValue(value: String, label: String): JsonObject = runCatching {
        Json.parseToJsonElement(value) as JsonObject
    }.getOrElse { throw IllegalArgumentException("$label is not a strict JSON object.") }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
            ?: throw IllegalArgumentException("Initial chapter draft output field is invalid: $key")

    private fun JsonObject.int(key: String): Int =
        (this[key] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull
            ?: throw IllegalArgumentException("Initial chapter draft output field is invalid: $key")

    private companion object {
        val HASH = Regex("[0-9a-f]{64}")
        val PLAN_OUTPUT_KEYS = setOf(
            "schemaVersion", "outputSchemaId", "attemptId", "artifactRefId", "artifactRevision",
            "rawOutputHash", "canonicalPlanHash", "requestBindingHash", "nextStageId",
        )
    }
}
