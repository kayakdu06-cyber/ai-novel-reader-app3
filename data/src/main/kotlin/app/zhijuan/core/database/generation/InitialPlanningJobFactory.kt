package app.zhijuan.core.database.generation

import app.zhijuan.core.model.GenerationJobType
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.task.PromptBundleCatalogV1
import app.zhijuan.core.task.StageIdempotencyKey
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

data class InitialPlanningStageIds(
    val seedStageId: String,
    val bibleStageId: String,
    val outlineStageId: String,
)

data class InitialPlanningJobSpec(
    val jobId: String,
    val bookId: String,
    val creationSnapshotId: String,
    val creationSnapshotHash: String,
    val promptBundleBindingHash: String,
    val targetChapterCount: Int,
    val userIntentJson: String,
    val budgetSnapshotJson: String,
    val stageIds: InitialPlanningStageIds,
    val maxAttemptsPerStage: Int = 2,
    val createdAt: Long,
)

data class FrozenInitialPlanningStageSource(
    val creationSnapshotId: String,
    val creationSnapshotHash: String,
    val promptBundleBindingHash: String,
    val targetChapterCount: Int,
    val outputSchemaId: String,
    val dependencyStageIds: List<String>,
)

/** Freezes the only supported seed -> Bible -> master-outline planning chain. */
object InitialPlanningJobFactory {
    const val SOURCE_POLICY_VERSION = "zhijuan.initial-planning-stage.v1"

    fun create(spec: InitialPlanningJobSpec): GenerationJobSetup {
        require(
            listOf(
                spec.jobId,
                spec.bookId,
                spec.creationSnapshotId,
                spec.stageIds.seedStageId,
                spec.stageIds.bibleStageId,
                spec.stageIds.outlineStageId,
            ).all(IDENTIFIER::matches),
        ) { "Initial planning identifier is invalid." }
        require(HASH.matches(spec.creationSnapshotHash) && HASH.matches(spec.promptBundleBindingHash)) {
            "Initial planning hash is invalid."
        }
        require(spec.targetChapterCount in 80..10_000) { "Initial planning chapter target is invalid." }
        require(spec.maxAttemptsPerStage in 1..4) { "Initial planning retry limit is invalid." }
        require(spec.createdAt >= 0L) { "Initial planning creation time is invalid." }
        val definitions = listOf(
            StageDefinition(
                stageId = spec.stageIds.seedStageId,
                phase = GenerationPhase.BUILD_STORY_SEED,
                targetType = GenerationTargetType.BOOK,
                outputSchemaId = "story-seed.v1",
                dependencies = emptyList(),
            ),
            StageDefinition(
                stageId = spec.stageIds.bibleStageId,
                phase = GenerationPhase.BUILD_BIBLE,
                targetType = GenerationTargetType.STORY_BIBLE,
                outputSchemaId = "story-bible.v1",
                dependencies = listOf(spec.stageIds.seedStageId),
            ),
            StageDefinition(
                stageId = spec.stageIds.outlineStageId,
                phase = GenerationPhase.BUILD_MASTER_OUTLINE,
                targetType = GenerationTargetType.OUTLINE,
                outputSchemaId = "master-outline.v1",
                dependencies = listOf(spec.stageIds.bibleStageId),
            ),
        )
        require(definitions.map(StageDefinition::stageId).distinct().size == definitions.size) {
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
                val inputSources = inputSources(spec, definition)
                val inputVersionHash = sha256(inputSources)
                GenerationStageSetup(
                    stageId = definition.stageId,
                    phase = definition.phase,
                    targetType = definition.targetType,
                    targetId = spec.bookId,
                    inputVersionHash = inputVersionHash,
                    idempotencyKey = StageIdempotencyKey.create(
                        jobId = spec.jobId,
                        phase = definition.phase,
                        targetId = spec.bookId,
                        inputVersionHash = inputVersionHash,
                    ).value,
                    maxAttempts = spec.maxAttemptsPerStage,
                    inputSourcesJson = inputSources,
                )
            },
            createdAt = spec.createdAt,
        )
    }

    internal fun parseAndVerify(stage: GenerationStageEntity): FrozenInitialPlanningStageSource {
        val root = runCatching { STRICT_JSON.parseToJsonElement(stage.inputSourcesJson) as JsonObject }
            .getOrElse { throw IllegalArgumentException("Initial planning sources are invalid.") }
        require(root.keys == SOURCE_KEYS) { "Initial planning source keys are invalid." }
        require(root.requiredInt("schemaVersion") == 1) { "Initial planning schema is unsupported." }
        require(root.requiredString("sourcePolicyVersion") == SOURCE_POLICY_VERSION) {
            "Initial planning source policy is unsupported."
        }
        require(root.requiredString("promptBundleVersion") == PromptBundleCatalogV1.BUNDLE_VERSION) {
            "Initial planning Prompt Bundle is unsupported."
        }
        val source = FrozenInitialPlanningStageSource(
            creationSnapshotId = root.requiredIdentifier("creationSnapshotId"),
            creationSnapshotHash = root.requiredHash("creationSnapshotHash"),
            promptBundleBindingHash = root.requiredHash("promptBundleBindingHash"),
            targetChapterCount = root.requiredInt("targetChapterCount"),
            outputSchemaId = root.requiredString("outputSchemaId"),
            dependencyStageIds = root.requiredIdentifiers("dependencyStageIds"),
        )
        require(source.targetChapterCount in 80..10_000) { "Initial planning chapter target is invalid." }
        val expected = expectedDefinition(stage.phase)
        require(stage.targetType == expected.targetType && source.outputSchemaId == expected.outputSchemaId) {
            "Initial planning phase binding is invalid."
        }
        require(stage.targetId.matches(IDENTIFIER)) { "Initial planning target is invalid." }
        require(stage.inputVersionHash == sha256(stage.inputSourcesJson)) {
            "Initial planning input hash changed."
        }
        require(
            stage.idempotencyKey == StageIdempotencyKey.create(
                jobId = stage.jobId,
                phase = stage.phase,
                targetId = stage.targetId,
                inputVersionHash = stage.inputVersionHash,
            ).value,
        ) { "Initial planning idempotency key changed." }
        return source
    }

    private fun inputSources(spec: InitialPlanningJobSpec, definition: StageDefinition): String =
        JsonObject(
            linkedMapOf(
                "schemaVersion" to JsonPrimitive(1),
                "sourcePolicyVersion" to JsonPrimitive(SOURCE_POLICY_VERSION),
                "creationSnapshotId" to JsonPrimitive(spec.creationSnapshotId),
                "creationSnapshotHash" to JsonPrimitive(spec.creationSnapshotHash),
                "promptBundleVersion" to JsonPrimitive(PromptBundleCatalogV1.BUNDLE_VERSION),
                "promptBundleBindingHash" to JsonPrimitive(spec.promptBundleBindingHash),
                "targetChapterCount" to JsonPrimitive(spec.targetChapterCount),
                "outputSchemaId" to JsonPrimitive(definition.outputSchemaId),
                "dependencyStageIds" to JsonArray(definition.dependencies.map(::JsonPrimitive)),
            ),
        ).toString()

    private fun expectedDefinition(phase: GenerationPhase): StageDefinition = when (phase) {
        GenerationPhase.BUILD_STORY_SEED ->
            StageDefinition("unused", phase, GenerationTargetType.BOOK, "story-seed.v1", emptyList())
        GenerationPhase.BUILD_BIBLE ->
            StageDefinition("unused", phase, GenerationTargetType.STORY_BIBLE, "story-bible.v1", emptyList())
        GenerationPhase.BUILD_MASTER_OUTLINE ->
            StageDefinition("unused", phase, GenerationTargetType.OUTLINE, "master-outline.v1", emptyList())
        else -> throw IllegalArgumentException("Generation Stage is not an initial planning phase.")
    }

    private fun JsonObject.requiredString(key: String): String =
        (getValue(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
            ?: throw IllegalArgumentException("Initial planning string source is invalid.")

    private fun JsonObject.requiredIdentifier(key: String): String = requiredString(key).also {
        require(IDENTIFIER.matches(it)) { "Initial planning identifier source is invalid." }
    }

    private fun JsonObject.requiredHash(key: String): String = requiredString(key).also {
        require(HASH.matches(it)) { "Initial planning hash source is invalid." }
    }

    private fun JsonObject.requiredInt(key: String): Int =
        (getValue(key) as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull
            ?: throw IllegalArgumentException("Initial planning integer source is invalid.")

    private fun JsonObject.requiredIdentifiers(key: String): List<String> =
        (getValue(key) as? JsonArray)?.map { element ->
            (element as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                ?.also { require(IDENTIFIER.matches(it)) }
                ?: throw IllegalArgumentException("Initial planning dependency source is invalid.")
        } ?: throw IllegalArgumentException("Initial planning dependency source is invalid.")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private data class StageDefinition(
        val stageId: String,
        val phase: GenerationPhase,
        val targetType: GenerationTargetType,
        val outputSchemaId: String,
        val dependencies: List<String>,
    )

    private val STRICT_JSON = Json { isLenient = false }
    private val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
    private val HASH = Regex("[0-9a-f]{64}")
    private val SOURCE_KEYS = setOf(
        "schemaVersion",
        "sourcePolicyVersion",
        "creationSnapshotId",
        "creationSnapshotHash",
        "promptBundleVersion",
        "promptBundleBindingHash",
        "targetChapterCount",
        "outputSchemaId",
        "dependencyStageIds",
    )
}
