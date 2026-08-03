package app.zhijuan.feature.generation

import app.zhijuan.core.model.ForeshadowStatus
import app.zhijuan.core.model.StoryEntityType
import app.zhijuan.provider.common.ProviderJsonSchema
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

enum class ForeshadowOperationV1 {
    PLANT,
    DEVELOP,
    RESOLVE,
    ABANDON,
}

data class ChapterTimelineEventV1(
    val name: String,
    val participantEntityIds: List<String>,
    val locationEntityId: String?,
    val storyTimeExpression: String,
    val constraints: List<String>,
    val evidence: String,
)

data class ChapterForeshadowOperationV1(
    val operation: ForeshadowOperationV1,
    val foreshadowItemId: String?,
    val description: String,
    val targetStartChapterIndex: Int?,
    val targetEndChapterIndex: Int?,
    val visibleEntityIds: List<String>,
    val importance: Int,
    val fromStatus: ForeshadowStatus?,
    val confidenceMicros: Int,
    val evidence: String,
)

data class ChapterStoryTrackingV1(
    val sourceChapterVersionId: String,
    val sourceChapterContentHash: String,
    val chapterId: String,
    val chapterIndex: Int,
    val memorySnapshotHash: String,
    val priorForeshadowSnapshotHash: String,
    val knownEntitySnapshotHash: String,
    val timelineEvents: List<ChapterTimelineEventV1>,
    val foreshadowOperations: List<ChapterForeshadowOperationV1>,
    val canonicalJson: String,
    val contentHash: String,
) {
    override fun toString(): String =
        "ChapterStoryTrackingV1(chapterIndex=$chapterIndex, timelineCount=${timelineEvents.size}, " +
            "foreshadowCount=${foreshadowOperations.size}, content=redacted)"
}

data class TrackingKnownEntity(
    val entityId: String,
    val entityType: StoryEntityType,
)

data class TrackingKnownForeshadow(
    val foreshadowItemId: String,
    val description: String,
    val status: ForeshadowStatus,
    val visibleEntityIds: Set<String>,
    val importance: Int,
) {
    override fun toString(): String =
        "TrackingKnownForeshadow(status=$status, visibleEntityCount=${visibleEntityIds.size}, content=redacted)"
}

data class ChapterTrackingExpectation(
    val sourceChapterVersionId: String,
    val sourceChapterContentHash: String,
    val chapterId: String,
    val chapterIndex: Int,
    val memorySnapshotHash: String,
    val priorForeshadowSnapshotHash: String,
    val knownEntitySnapshotHash: String,
    val knownEntities: Map<String, StoryEntityType>,
    val priorForeshadows: Map<String, TrackingKnownForeshadow>,
) {
    init {
        require(IDENTIFIER.matches(sourceChapterVersionId) && IDENTIFIER.matches(chapterId))
        require(listOf(sourceChapterContentHash, memorySnapshotHash, priorForeshadowSnapshotHash, knownEntitySnapshotHash).all(HASH::matches))
        require(chapterIndex in 1..10_000)
        require(knownEntities.size in 1..256 && knownEntities.keys.all(IDENTIFIER::matches))
        require(priorForeshadows.size <= 256 && priorForeshadows.keys.all(IDENTIFIER::matches))
    }

    override fun toString(): String =
        "ChapterTrackingExpectation(chapterIndex=$chapterIndex, entityCount=${knownEntities.size}, " +
            "foreshadowCount=${priorForeshadows.size}, content=redacted)"
}

class ChapterTrackingOutputParser(
    private val validator: StructuredOutputValidator = StructuredOutputValidator(),
) {
    fun parse(source: ByteArray): PlanningOutputValidationResult<ChapterStoryTrackingV1> =
        when (val result = validator.validate(source, ChapterTrackingOutputContractV1)) {
            is StructuredOutputValidationResult.Invalid -> PlanningOutputValidationResult.Invalid(result.report)
            is StructuredOutputValidationResult.Valid -> PlanningOutputValidationResult.Valid(
                result.output.withDocument(::fromDocument),
            )
        }

    internal fun fromValidated(output: ValidatedStructuredOutput): ChapterStoryTrackingV1 {
        require(output.schemaId == ChapterTrackingOutputContractV1.schemaId)
        require(output.schemaVersion == ChapterTrackingOutputContractV1.currentSchemaVersion)
        return output.withDocument(::fromDocument)
    }

    internal fun fromDocument(document: JsonObject): ChapterStoryTrackingV1 {
        val canonical = document.toString()
        return ChapterStoryTrackingV1(
            sourceChapterVersionId = document.stringValue("sourceChapterVersionId"),
            sourceChapterContentHash = document.stringValue("sourceChapterContentHash"),
            chapterId = document.stringValue("chapterId"),
            chapterIndex = document.intValue("chapterIndex"),
            memorySnapshotHash = document.stringValue("memorySnapshotHash"),
            priorForeshadowSnapshotHash = document.stringValue("priorForeshadowSnapshotHash"),
            knownEntitySnapshotHash = document.stringValue("knownEntitySnapshotHash"),
            timelineEvents = document.objectValues("timelineEvents").map { event ->
                ChapterTimelineEventV1(
                    name = event.stringValue("name"),
                    participantEntityIds = event.stringValues("participantEntityIds"),
                    locationEntityId = event.nullableStringValue("locationEntityId"),
                    storyTimeExpression = event.stringValue("storyTimeExpression"),
                    constraints = event.stringValues("constraints"),
                    evidence = event.stringValue("evidence"),
                )
            },
            foreshadowOperations = document.objectValues("foreshadowOperations").map { operation ->
                ChapterForeshadowOperationV1(
                    operation = ForeshadowOperationV1.valueOf(operation.stringValue("operation")),
                    foreshadowItemId = operation.nullableStringValue("foreshadowItemId"),
                    description = operation.stringValue("description"),
                    targetStartChapterIndex = operation.nullableIntValue("targetStartChapterIndex"),
                    targetEndChapterIndex = operation.nullableIntValue("targetEndChapterIndex"),
                    visibleEntityIds = operation.stringValues("visibleEntityIds"),
                    importance = operation.intValue("importance"),
                    fromStatus = operation.nullableStringValue("fromStatus")?.let(ForeshadowStatus::valueOf),
                    confidenceMicros = operation.intValue("confidenceMicros"),
                    evidence = operation.stringValue("evidence"),
                )
            },
            canonicalJson = canonical,
            contentHash = sha256(canonical),
        )
    }
}

internal class BoundChapterTrackingOutputContract(
    private val expectation: ChapterTrackingExpectation,
    private val parser: ChapterTrackingOutputParser = ChapterTrackingOutputParser(),
) : StructuredOutputContract {
    override val schemaId = ChapterTrackingOutputContractV1.schemaId
    override val currentSchemaVersion = ChapterTrackingOutputContractV1.currentSchemaVersion
    override val providerSchema = ChapterTrackingOutputContractV1.providerSchema
    override val limits = ChapterTrackingOutputContractV1.limits

    override fun validate(document: JsonObject): List<StructuredOutputIssue> {
        val structural = ChapterTrackingOutputContractV1.validate(document)
        if (structural.isNotEmpty()) return structural
        return ChapterTrackingCrossValidator.validate(parser.fromDocument(document), expectation)
    }
}

object ChapterTrackingCrossValidator {
    fun validate(
        tracking: ChapterStoryTrackingV1,
        expected: ChapterTrackingExpectation,
    ): List<StructuredOutputIssue> = buildList {
        fun issue(path: String) {
            if (size < 64) add(StructuredOutputIssue(StructuredOutputIssueCode.VALUE_INVALID, path))
        }
        if (tracking.sourceChapterVersionId != expected.sourceChapterVersionId) issue("$.sourceChapterVersionId")
        if (tracking.sourceChapterContentHash != expected.sourceChapterContentHash) issue("$.sourceChapterContentHash")
        if (tracking.chapterId != expected.chapterId) issue("$.chapterId")
        if (tracking.chapterIndex != expected.chapterIndex) issue("$.chapterIndex")
        if (tracking.memorySnapshotHash != expected.memorySnapshotHash) issue("$.memorySnapshotHash")
        if (tracking.priorForeshadowSnapshotHash != expected.priorForeshadowSnapshotHash) issue("$.priorForeshadowSnapshotHash")
        if (tracking.knownEntitySnapshotHash != expected.knownEntitySnapshotHash) issue("$.knownEntitySnapshotHash")

        val timelineKeys = mutableSetOf<String>()
        tracking.timelineEvents.forEachIndexed { index, event ->
            val base = "$.timelineEvents[$index]"
            if (event.participantEntityIds.any { it !in expected.knownEntities }) issue("$base.participantEntityIds")
            if (event.participantEntityIds.distinct().size != event.participantEntityIds.size) issue("$base.participantEntityIds")
            if (event.locationEntityId != null && expected.knownEntities[event.locationEntityId] != StoryEntityType.LOCATION) {
                issue("$base.locationEntityId")
            }
            if (event.constraints.map { it.trim().lowercase() }.distinct().size != event.constraints.size) {
                issue("$base.constraints")
            }
            val key = listOf(
                event.name.trim().lowercase(), event.storyTimeExpression.trim().lowercase(),
                event.locationEntityId.orEmpty(), event.participantEntityIds.sorted().joinToString(","),
            ).joinToString("|")
            if (!timelineKeys.add(key)) issue(base)
        }

        val referencedExisting = mutableSetOf<String>()
        val newDescriptions = mutableSetOf<String>()
        val existingDescriptions = expected.priorForeshadows.values
            .mapTo(mutableSetOf()) { it.description.trim().lowercase() }
        tracking.foreshadowOperations.forEachIndexed { index, operation ->
            val base = "$.foreshadowOperations[$index]"
            if (operation.visibleEntityIds.any { it !in expected.knownEntities } ||
                operation.visibleEntityIds.distinct().size != operation.visibleEntityIds.size
            ) issue("$base.visibleEntityIds")
            val targetPairValid = (operation.targetStartChapterIndex == null) == (operation.targetEndChapterIndex == null) &&
                (operation.targetStartChapterIndex == null || operation.targetStartChapterIndex <= operation.targetEndChapterIndex!!)
            if (!targetPairValid) issue("$base.targetStartChapterIndex")
            when (operation.operation) {
                ForeshadowOperationV1.PLANT -> {
                    if (operation.foreshadowItemId != null || operation.fromStatus != null) issue(base)
                    if (operation.targetStartChapterIndex != null && operation.targetStartChapterIndex <= expected.chapterIndex) {
                        issue("$base.targetStartChapterIndex")
                    }
                    val normalized = operation.description.trim().lowercase()
                    if (normalized in existingDescriptions || !newDescriptions.add(normalized)) issue("$base.description")
                }
                ForeshadowOperationV1.DEVELOP,
                ForeshadowOperationV1.RESOLVE,
                ForeshadowOperationV1.ABANDON,
                -> {
                    val id = operation.foreshadowItemId
                    val prior = id?.let(expected.priorForeshadows::get)
                    if (id == null || prior == null || !referencedExisting.add(id)) issue("$base.foreshadowItemId")
                    if (prior != null) {
                        if (operation.fromStatus != prior.status) issue("$base.fromStatus")
                        if (operation.description != prior.description) issue("$base.description")
                        if (operation.importance != prior.importance) issue("$base.importance")
                        if (!operation.visibleEntityIds.toSet().containsAll(prior.visibleEntityIds)) issue("$base.visibleEntityIds")
                    }
                    if (operation.targetStartChapterIndex != null || operation.targetEndChapterIndex != null) issue(base)
                    val transitionAllowed = when (operation.operation) {
                        ForeshadowOperationV1.DEVELOP -> operation.fromStatus in setOf(ForeshadowStatus.PLANTED, ForeshadowStatus.DEVELOPING)
                        ForeshadowOperationV1.RESOLVE -> operation.fromStatus in setOf(ForeshadowStatus.PLANTED, ForeshadowStatus.DEVELOPING)
                        ForeshadowOperationV1.ABANDON -> operation.fromStatus in setOf(ForeshadowStatus.PLANNED, ForeshadowStatus.PLANTED, ForeshadowStatus.DEVELOPING) && operation.confidenceMicros == 1_000_000
                        ForeshadowOperationV1.PLANT -> false
                    }
                    if (!transitionAllowed) issue("$base.operation")
                }
            }
        }
    }.distinct().take(64)
}

object ChapterTrackingOutputContractV1 : StructuredOutputContract {
    override val schemaId = "chapter-story-tracking.v1"
    override val currentSchemaVersion = 1
    override val providerSchema = ProviderJsonSchema.from(CHAPTER_TRACKING_SCHEMA)
    override val limits = StructuredOutputLimits(
        maximumBytes = 512 * 1_024,
        maximumRepairSourceBytes = 256 * 1_024,
        maximumDepth = 10,
        maximumNodes = 8_192,
        maximumObjectMembers = 16,
        maximumArrayItems = 512,
        maximumStringCharacters = 256 * 1_024,
    )

    override fun validate(document: JsonObject): List<StructuredOutputIssue> = buildList {
        val root = MemoryContractReader(document, "$", this)
        root.exactKeys(ROOT_KEYS)
        root.exactInt("schemaVersion", 1)
        root.identifier("sourceChapterVersionId")
        root.hash("sourceChapterContentHash")
        root.identifier("chapterId")
        root.int("chapterIndex", 1..10_000)
        root.hash("memorySnapshotHash")
        root.hash("priorForeshadowSnapshotHash")
        root.hash("knownEntitySnapshotHash")
        root.objects("timelineEvents", 0..64).forEachIndexed { index, event ->
            val reader = MemoryContractReader(event, "$.timelineEvents[$index]", this)
            reader.exactKeys(TIMELINE_KEYS)
            reader.string("name", 1..500)
            reader.strings("participantEntityIds", 0..16, 1..128)
            reader.nullableIdentifier("locationEntityId")
            reader.string("storyTimeExpression", 1..500)
            reader.strings("constraints", 0..16, 1..1_000)
            reader.string("evidence", 1..2_000)
        }
        root.objects("foreshadowOperations", 0..64).forEachIndexed { index, operation ->
            val reader = MemoryContractReader(operation, "$.foreshadowOperations[$index]", this)
            reader.exactKeys(FORESHADOW_KEYS)
            reader.enumString("operation", ForeshadowOperationV1.entries.mapTo(mutableSetOf()) { it.name })
            reader.nullableIdentifier("foreshadowItemId")
            reader.string("description", 1..2_000)
            reader.nullableInt("targetStartChapterIndex", 1..10_000)
            reader.nullableInt("targetEndChapterIndex", 1..10_000)
            reader.strings("visibleEntityIds", 0..32, 1..128)
            reader.int("importance", 0..100)
            val fromStatus = operation["fromStatus"]
            if (fromStatus == null) {
                this += StructuredOutputIssue(StructuredOutputIssueCode.REQUIRED_FIELD_MISSING, "$.foreshadowOperations[$index].fromStatus")
            } else if (fromStatus !is JsonNull) {
                reader.enumString("fromStatus", ForeshadowStatus.entries.mapTo(mutableSetOf()) { it.name })
            }
            reader.int("confidenceMicros", 0..1_000_000)
            reader.string("evidence", 1..2_000)
        }
    }
}

private fun JsonObject.stringValue(key: String): String = (getValue(key) as JsonPrimitive).content
private fun JsonObject.nullableStringValue(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
private fun JsonObject.intValue(key: String): Int = (getValue(key) as JsonPrimitive).intOrNull!!
private fun JsonObject.nullableIntValue(key: String): Int? =
    (get(key) as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull
private fun JsonObject.objectValues(key: String): List<JsonObject> = (getValue(key) as JsonArray).map { it as JsonObject }
private fun JsonObject.stringValues(key: String): List<String> =
    (getValue(key) as JsonArray).map { (it as JsonPrimitive).content }

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

private val ROOT_KEYS = setOf(
    "schemaVersion", "sourceChapterVersionId", "sourceChapterContentHash", "chapterId", "chapterIndex",
    "memorySnapshotHash", "priorForeshadowSnapshotHash", "knownEntitySnapshotHash",
    "timelineEvents", "foreshadowOperations",
)
private val TIMELINE_KEYS = setOf(
    "name", "participantEntityIds", "locationEntityId", "storyTimeExpression", "constraints", "evidence",
)
private val FORESHADOW_KEYS = setOf(
    "operation", "foreshadowItemId", "description", "targetStartChapterIndex", "targetEndChapterIndex",
    "visibleEntityIds", "importance", "fromStatus", "confidenceMicros", "evidence",
)
private val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
private val HASH = Regex("[0-9a-f]{64}")

private val CHAPTER_TRACKING_SCHEMA = """
{"type":"object","additionalProperties":false,"required":["schemaVersion","sourceChapterVersionId","sourceChapterContentHash","chapterId","chapterIndex","memorySnapshotHash","priorForeshadowSnapshotHash","knownEntitySnapshotHash","timelineEvents","foreshadowOperations"],"properties":{"schemaVersion":{"const":1},"sourceChapterVersionId":{"type":"string","minLength":1,"maxLength":128,"pattern":"^[A-Za-z0-9._:-]+$"},"sourceChapterContentHash":{"type":"string","pattern":"^[0-9a-f]{64}$"},"chapterId":{"type":"string","minLength":1,"maxLength":128,"pattern":"^[A-Za-z0-9._:-]+$"},"chapterIndex":{"type":"integer","minimum":1,"maximum":10000},"memorySnapshotHash":{"type":"string","pattern":"^[0-9a-f]{64}$"},"priorForeshadowSnapshotHash":{"type":"string","pattern":"^[0-9a-f]{64}$"},"knownEntitySnapshotHash":{"type":"string","pattern":"^[0-9a-f]{64}$"},"timelineEvents":{"type":"array","maxItems":64,"items":{"type":"object","additionalProperties":false,"required":["name","participantEntityIds","locationEntityId","storyTimeExpression","constraints","evidence"],"properties":{"name":{"type":"string","minLength":1,"maxLength":500},"participantEntityIds":{"type":"array","maxItems":16,"uniqueItems":true,"items":{"type":"string","minLength":1,"maxLength":128,"pattern":"^[A-Za-z0-9._:-]+$"}},"locationEntityId":{"type":["string","null"],"minLength":1,"maxLength":128,"pattern":"^[A-Za-z0-9._:-]+$"},"storyTimeExpression":{"type":"string","minLength":1,"maxLength":500},"constraints":{"type":"array","maxItems":16,"uniqueItems":true,"items":{"type":"string","minLength":1,"maxLength":1000}},"evidence":{"type":"string","minLength":1,"maxLength":2000}}}},"foreshadowOperations":{"type":"array","maxItems":64,"items":{"type":"object","additionalProperties":false,"required":["operation","foreshadowItemId","description","targetStartChapterIndex","targetEndChapterIndex","visibleEntityIds","importance","fromStatus","confidenceMicros","evidence"],"properties":{"operation":{"enum":["PLANT","DEVELOP","RESOLVE","ABANDON"]},"foreshadowItemId":{"type":["string","null"],"minLength":1,"maxLength":128,"pattern":"^[A-Za-z0-9._:-]+$"},"description":{"type":"string","minLength":1,"maxLength":2000},"targetStartChapterIndex":{"type":["integer","null"],"minimum":1,"maximum":10000},"targetEndChapterIndex":{"type":["integer","null"],"minimum":1,"maximum":10000},"visibleEntityIds":{"type":"array","maxItems":32,"uniqueItems":true,"items":{"type":"string","minLength":1,"maxLength":128,"pattern":"^[A-Za-z0-9._:-]+$"}},"importance":{"type":"integer","minimum":0,"maximum":100},"fromStatus":{"type":["string","null"],"enum":[null,"PLANNED","PLANTED","DEVELOPING","RESOLVED","ABANDONED"]},"confidenceMicros":{"type":"integer","minimum":0,"maximum":1000000},"evidence":{"type":"string","minLength":1,"maxLength":2000}}}}}}
""".trimIndent()
