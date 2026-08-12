package app.zhijuan.feature.generation

import app.zhijuan.core.database.memory.NarrativeObligationActionV1
import app.zhijuan.core.database.memory.NarrativeObligationUpdateV1
import app.zhijuan.core.database.memory.StoryStateDeltaV1
import app.zhijuan.core.database.memory.StoryStateKeyV1
import app.zhijuan.core.database.memory.StoryStateNamespaceV1
import app.zhijuan.core.model.ConsistencyIssueCode
import app.zhijuan.core.model.ConsistencyIssueSeverity
import app.zhijuan.core.model.ConsistencyRepairActionV1
import app.zhijuan.provider.common.ProviderJsonSchema
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

enum class PostAnalysisEvidenceSubjectV1 {
    ENTITY_EVENT,
    TIMELINE_EVENT,
    FORESHADOW_TRANSITION,
    OBLIGATION,
    STORY_STATE_DELTA,
    REPETITION_FINDING,
    CONSISTENCY_FINDING,
}

data class ChapterPostAnalysisEvidenceBindingV1(
    val bindingId: String,
    val subject: PostAnalysisEvidenceSubjectV1,
    val subjectIndex: Int,
    val startCodePointInclusive: Int,
    val endCodePointExclusive: Int,
)

data class ChapterRepetitionFindingV1(
    val findingId: String,
    val firstStartCodePointInclusive: Int,
    val firstEndCodePointExclusive: Int,
    val repeatedStartCodePointInclusive: Int,
    val repeatedEndCodePointExclusive: Int,
    val severity: ConsistencyIssueSeverity,
    val repairAction: ConsistencyRepairActionV1,
)

data class ChapterPostAnalysisV1(
    val sourceChapterVersionId: String,
    val sourceChapterContentHash: String,
    val chapterId: String,
    val chapterIndex: Int,
    val checkSourceSnapshotHash: String,
    val sceneContractHash: String,
    val summary: ChapterMemorySummaryV1,
    val entityEvents: List<ChapterMemoryEntityEventV1>,
    val canonFacts: List<ChapterMemoryFactV1>,
    val timelineEvents: List<ChapterTimelineEventV1>,
    val foreshadowTransitions: List<ChapterForeshadowOperationV1>,
    val completedAndOpenObligations: List<NarrativeObligationUpdateV1>,
    val storyStateDeltas: List<StoryStateDeltaV1>,
    val repetitionFindings: List<ChapterRepetitionFindingV1>,
    val consistencyFindings: List<ModelConsistencyIssueV1>,
    val presentationFindings: List<String>,
    val criterionResults: List<ConsistencyCriterionResultV1>,
    val requiredProcessResults: List<RequiredProcessResultV1>,
    val severeRevisionRequired: Boolean,
    val evidenceBindings: List<ChapterPostAnalysisEvidenceBindingV1>,
    val canonicalJson: String,
    val contentHash: String,
) {
    override fun toString(): String =
        "ChapterPostAnalysisV1(chapterIndex=$chapterIndex, stateDeltaCount=${storyStateDeltas.size}, " +
            "issueCount=${consistencyFindings.size + repetitionFindings.size}, content=redacted)"
}

class ChapterPostAnalysisOutputParser(
    private val validator: StructuredOutputValidator = StructuredOutputValidator(),
) {
    fun parse(source: ByteArray): PlanningOutputValidationResult<ChapterPostAnalysisV1> =
        when (val result = validator.validate(source, ChapterPostAnalysisOutputContractV1)) {
            is StructuredOutputValidationResult.Invalid -> PlanningOutputValidationResult.Invalid(result.report)
            is StructuredOutputValidationResult.Valid -> PlanningOutputValidationResult.Valid(
                result.output.withDocument(::fromDocument),
            )
        }

    internal fun fromDocument(document: JsonObject): ChapterPostAnalysisV1 {
        val memory = ChapterMemoryOutputParser().fromDocument(document.memoryDocument())
        val consistency = ChapterConsistencyOutputParser().fromDocument(document.consistencyDocument())
        val canonical = document.toString()
        return ChapterPostAnalysisV1(
            sourceChapterVersionId = memory.sourceChapterVersionId,
            sourceChapterContentHash = memory.sourceChapterContentHash,
            chapterId = memory.chapterId,
            chapterIndex = memory.chapterIndex,
            checkSourceSnapshotHash = consistency.checkSourceSnapshotHash,
            sceneContractHash = consistency.sceneContractHash,
            summary = memory.summary,
            entityEvents = memory.entityEvents,
            canonFacts = memory.facts,
            timelineEvents = document.objects("timelineEvents").map(::timelineEvent),
            foreshadowTransitions = document.objects("foreshadowTransitions").map(::foreshadowTransition),
            completedAndOpenObligations = document.objects("completedAndOpenObligations").map(::obligation),
            storyStateDeltas = document.objects("storyStateDeltas").map(::stateDelta),
            repetitionFindings = document.objects("repetitionFindings").map(::repetitionFinding),
            consistencyFindings = consistency.issues,
            presentationFindings = document.strings("presentationFindings"),
            criterionResults = consistency.criterionResults,
            requiredProcessResults = consistency.requiredProcessResults,
            severeRevisionRequired = document.boolean("severeRevisionRequired"),
            evidenceBindings = document.objects("evidenceBindings").map(::evidenceBinding),
            canonicalJson = canonical,
            contentHash = sha256(canonical),
        )
    }

    internal fun fromValidated(output: ValidatedStructuredOutput): ChapterPostAnalysisV1 {
        require(output.schemaId == ChapterPostAnalysisOutputContractV1.schemaId)
        require(output.schemaVersion == ChapterPostAnalysisOutputContractV1.currentSchemaVersion)
        return output.withDocument(::fromDocument)
    }

    private fun timelineEvent(value: JsonObject) = ChapterTimelineEventV1(
        name = value.string("name"),
        participantEntityIds = value.strings("participantEntityIds"),
        locationEntityId = value.nullableString("locationEntityId"),
        storyTimeExpression = value.string("storyTimeExpression"),
        constraints = value.strings("constraints"),
        evidence = value.string("evidence"),
    )

    private fun foreshadowTransition(value: JsonObject) = ChapterForeshadowOperationV1(
        operation = ForeshadowOperationV1.valueOf(value.string("operation")),
        foreshadowItemId = value.nullableString("foreshadowItemId"),
        description = value.string("description"),
        targetStartChapterIndex = value.nullableInt("targetStartChapterIndex"),
        targetEndChapterIndex = value.nullableInt("targetEndChapterIndex"),
        visibleEntityIds = value.strings("visibleEntityIds"),
        importance = value.int("importance"),
        fromStatus = value.nullableString("fromStatus")?.let(app.zhijuan.core.model.ForeshadowStatus::valueOf),
        confidenceMicros = value.int("confidenceMicros"),
        evidence = value.string("evidence"),
    )

    private fun obligation(value: JsonObject) = NarrativeObligationUpdateV1(
        obligationId = value.string("obligationId"),
        action = NarrativeObligationActionV1.valueOf(value.string("action")),
        evidence = value.string("evidence"),
        nextDueChapterIndex = value.nullableInt("nextDueChapterIndex"),
    )

    private fun stateDelta(value: JsonObject) = StoryStateDeltaV1(
        key = StoryStateKeyV1(
            namespace = StoryStateNamespaceV1.valueOf(value.string("namespace")),
            entityId = value.string("entityId"),
            attribute = value.string("attribute"),
            relatedEntityId = value.nullableString("relatedEntityId"),
        ),
        oldValueJson = value.nullableString("oldValueJson"),
        newValueJson = value.string("newValueJson"),
        evidence = value.string("evidence"),
    )

    private fun repetitionFinding(value: JsonObject) = ChapterRepetitionFindingV1(
        findingId = value.string("findingId"),
        firstStartCodePointInclusive = value.int("firstStartCodePointInclusive"),
        firstEndCodePointExclusive = value.int("firstEndCodePointExclusive"),
        repeatedStartCodePointInclusive = value.int("repeatedStartCodePointInclusive"),
        repeatedEndCodePointExclusive = value.int("repeatedEndCodePointExclusive"),
        severity = ConsistencyIssueSeverity.valueOf(value.string("severity")),
        repairAction = ConsistencyRepairActionV1.valueOf(value.string("repairAction")),
    )

    private fun evidenceBinding(value: JsonObject) = ChapterPostAnalysisEvidenceBindingV1(
        bindingId = value.string("bindingId"),
        subject = PostAnalysisEvidenceSubjectV1.valueOf(value.string("subject")),
        subjectIndex = value.int("subjectIndex"),
        startCodePointInclusive = value.int("startCodePointInclusive"),
        endCodePointExclusive = value.int("endCodePointExclusive"),
    )
}

object ChapterPostAnalysisOutputContractV1 : StructuredOutputContract {
    override val schemaId = "chapter-post-analysis.v1"
    override val currentSchemaVersion = 1
    override val providerSchema = ProviderJsonSchema.from(buildProviderSchema().toString())
    override val limits = StructuredOutputLimits(
        maximumBytes = 768 * 1_024,
        maximumRepairSourceBytes = 384 * 1_024,
        maximumDepth = 12,
        maximumNodes = 24_576,
        maximumObjectMembers = 32,
        maximumArrayItems = 1_024,
        maximumStringCharacters = 384 * 1_024,
    )

    override fun validate(document: JsonObject): List<StructuredOutputIssue> = buildList {
        val root = MemoryContractReader(document, "$", this)
        root.exactKeys(ROOT_KEYS)
        root.exactInt("schemaVersion", 1)
        root.identifier("sourceChapterVersionId")
        root.hash("sourceChapterContentHash")
        root.identifier("chapterId")
        root.int("chapterIndex", 1..10_000)
        root.hash("checkSourceSnapshotHash")
        root.hash("sceneContractHash")

        addAll(ChapterMemoryOutputContractV1.validate(document.memoryDocument()))
        addAll(ChapterTrackingOutputContractV1.validate(document.trackingDocument()))
        addAll(ChapterConsistencyOutputContractV1.validate(document.consistencyDocument()))

        val obligations = root.objects("completedAndOpenObligations", 0..256)
        obligations.forEachIndexed { index, value -> validateObligation(value, index, this) }
        val states = root.objects("storyStateDeltas", 0..256)
        states.forEachIndexed { index, value -> validateState(value, index, this) }
        val repetitions = root.objects("repetitionFindings", 0..64)
        repetitions.forEachIndexed { index, value -> validateRepetition(value, index, this) }
        root.strings("presentationFindings", 0..128, 1..128)
        val severe = (document["severeRevisionRequired"] as? JsonPrimitive)
            ?.takeUnless(JsonPrimitive::isString)
            ?.booleanOrNull
        if (severe == null) {
            this += StructuredOutputIssue(
                StructuredOutputIssueCode.TYPE_MISMATCH,
                "$.severeRevisionRequired",
            )
        }
        val bindings = root.objects("evidenceBindings", 0..1_024)
        bindings.forEachIndexed { index, value -> validateBinding(value, index, this) }

        validateRelationships(document, obligations, states, repetitions, bindings, severe, this)
    }.distinct().take(128)
}

private fun validateObligation(value: JsonObject, index: Int, issues: MutableList<StructuredOutputIssue>) {
    val reader = MemoryContractReader(value, "$.completedAndOpenObligations[$index]", issues)
    reader.exactKeys(OBLIGATION_KEYS)
    reader.identifier("obligationId")
    reader.enumString("action", NarrativeObligationActionV1.entries.mapTo(mutableSetOf()) { it.name })
    reader.string("evidence", 1..2_000)
    reader.nullableInt("nextDueChapterIndex", 1..10_000)
    val action = value.stringOrNull("action")
    if ((action == NarrativeObligationActionV1.POSTPONE.name) != (value["nextDueChapterIndex"] !is JsonNull)) {
        issues += invalid("$.completedAndOpenObligations[$index].nextDueChapterIndex")
    }
}

private fun validateState(value: JsonObject, index: Int, issues: MutableList<StructuredOutputIssue>) {
    val path = "$.storyStateDeltas[$index]"
    val reader = MemoryContractReader(value, path, issues)
    reader.exactKeys(STATE_KEYS)
    reader.enumString("namespace", StoryStateNamespaceV1.entries.mapTo(mutableSetOf()) { it.name })
    reader.identifier("entityId")
    reader.string("attribute", 1..96)
    reader.nullableIdentifier("relatedEntityId")
    reader.nullableString("oldValueJson", 1..4_000)
    reader.string("newValueJson", 1..4_000)
    reader.string("evidence", 1..2_000)
    val namespace = value.stringOrNull("namespace")
    val related = value["relatedEntityId"] !is JsonNull
    if ((namespace == StoryStateNamespaceV1.RELATIONSHIP.name) != related) issues += invalid("$path.relatedEntityId")
    if (value.stringOrNull("attribute")?.matches(STATE_ATTRIBUTE) != true) issues += invalid("$path.attribute")
    listOf("oldValueJson", "newValueJson").forEach { key ->
        val raw = value.stringOrNull(key)
        if (raw != null && runCatching { STRICT_JSON.parseToJsonElement(raw) }.isFailure) issues += invalid("$path.$key")
    }
}

private fun validateRepetition(value: JsonObject, index: Int, issues: MutableList<StructuredOutputIssue>) {
    val path = "$.repetitionFindings[$index]"
    val reader = MemoryContractReader(value, path, issues)
    reader.exactKeys(REPETITION_KEYS)
    reader.identifier("findingId")
    val firstStart = reader.int("firstStartCodePointInclusive", 0..4_194_303)
    val firstEnd = reader.int("firstEndCodePointExclusive", 1..4_194_304)
    val repeatedStart = reader.int("repeatedStartCodePointInclusive", 0..4_194_303)
    val repeatedEnd = reader.int("repeatedEndCodePointExclusive", 1..4_194_304)
    reader.enumString("severity", setOf(ConsistencyIssueSeverity.MAJOR.name))
    reader.enumString("repairAction", setOf(ConsistencyRepairActionV1.REMOVE_DUPLICATION.name))
    if (firstStart != null && firstEnd != null && firstEnd <= firstStart) issues += invalid("$path.firstEndCodePointExclusive")
    if (repeatedStart != null && repeatedEnd != null && repeatedEnd <= repeatedStart) issues += invalid("$path.repeatedEndCodePointExclusive")
    if (firstEnd != null && repeatedStart != null && repeatedStart < firstEnd) issues += invalid("$path.repeatedStartCodePointInclusive")
}

private fun validateBinding(value: JsonObject, index: Int, issues: MutableList<StructuredOutputIssue>) {
    val path = "$.evidenceBindings[$index]"
    val reader = MemoryContractReader(value, path, issues)
    reader.exactKeys(BINDING_KEYS)
    reader.identifier("bindingId")
    reader.enumString("subject", PostAnalysisEvidenceSubjectV1.entries.mapTo(mutableSetOf()) { it.name })
    reader.int("subjectIndex", 0..1_023)
    val start = reader.int("startCodePointInclusive", 0..4_194_303)
    val end = reader.int("endCodePointExclusive", 1..4_194_304)
    if (start != null && end != null && end <= start) issues += invalid("$path.endCodePointExclusive")
}

private fun validateRelationships(
    document: JsonObject,
    obligations: List<JsonObject>,
    states: List<JsonObject>,
    repetitions: List<JsonObject>,
    bindings: List<JsonObject>,
    severe: Boolean?,
    issues: MutableList<StructuredOutputIssue>,
) {
    val findingIds = document.objects("consistencyFindings").mapNotNull { it.stringOrNull("issueId") }
    val presentationIds = document.stringsOrEmpty("presentationFindings")
    if (presentationIds.distinct().size != presentationIds.size || presentationIds.any { it !in findingIds }) {
        issues += invalid("$.presentationFindings")
    }
    val presentationCodes = document.objects("consistencyFindings")
        .filter { it.stringOrNull("issueId") in presentationIds }
        .mapNotNull { it.stringOrNull("code") }
    if (presentationCodes.any { it !in PRESENTATION_CODES }) issues += invalid("$.presentationFindings")

    val bindingIds = bindings.mapNotNull { it.stringOrNull("bindingId") }
    if (bindingIds.distinct().size != bindingIds.size) issues += invalid("$.evidenceBindings")
    val counts = mapOf(
        PostAnalysisEvidenceSubjectV1.ENTITY_EVENT.name to document.arraySize("entityEvents"),
        PostAnalysisEvidenceSubjectV1.TIMELINE_EVENT.name to document.arraySize("timelineEvents"),
        PostAnalysisEvidenceSubjectV1.FORESHADOW_TRANSITION.name to document.arraySize("foreshadowTransitions"),
        PostAnalysisEvidenceSubjectV1.OBLIGATION.name to obligations.size,
        PostAnalysisEvidenceSubjectV1.STORY_STATE_DELTA.name to states.size,
        PostAnalysisEvidenceSubjectV1.REPETITION_FINDING.name to repetitions.size,
        PostAnalysisEvidenceSubjectV1.CONSISTENCY_FINDING.name to document.arraySize("consistencyFindings"),
    )
    bindings.forEachIndexed { index, binding ->
        val subject = binding.stringOrNull("subject")
        val subjectIndex = binding.intOrNull("subjectIndex")
        if (subject == null || subjectIndex == null || subjectIndex !in 0 until (counts[subject] ?: 0)) {
            issues += invalid("$.evidenceBindings[$index].subjectIndex")
        }
    }
    listOf(
        PostAnalysisEvidenceSubjectV1.OBLIGATION.name to obligations.size,
        PostAnalysisEvidenceSubjectV1.STORY_STATE_DELTA.name to states.size,
        PostAnalysisEvidenceSubjectV1.REPETITION_FINDING.name to repetitions.size,
    ).forEach { (subject, count) ->
        if ((0 until count).any { subjectIndex -> bindings.none {
                it.stringOrNull("subject") == subject && it.intOrNull("subjectIndex") == subjectIndex
            } }) issues += invalid("$.evidenceBindings")
    }

    val severeFindings = repetitions.isNotEmpty() || document.objects("consistencyFindings").any {
        it.stringOrNull("severity") in setOf(ConsistencyIssueSeverity.BLOCKER.name, ConsistencyIssueSeverity.MAJOR.name)
    }
    if (severe != null && severe != severeFindings) issues += invalid("$.severeRevisionRequired")
}

private fun JsonObject.memoryDocument() = JsonObject(linkedMapOf(
    "schemaVersion" to getValue("schemaVersion"),
    "sourceChapterVersionId" to getValue("sourceChapterVersionId"),
    "sourceChapterContentHash" to getValue("sourceChapterContentHash"),
    "chapterId" to getValue("chapterId"),
    "chapterIndex" to getValue("chapterIndex"),
    "summary" to getValue("summary"),
    "entityEvents" to getValue("entityEvents"),
    "facts" to getValue("canonFacts"),
))

private fun JsonObject.trackingDocument() = JsonObject(linkedMapOf(
    "schemaVersion" to getValue("schemaVersion"),
    "sourceChapterVersionId" to getValue("sourceChapterVersionId"),
    "sourceChapterContentHash" to getValue("sourceChapterContentHash"),
    "chapterId" to getValue("chapterId"),
    "chapterIndex" to getValue("chapterIndex"),
    "memorySnapshotHash" to JsonPrimitive(ZERO_HASH),
    "priorForeshadowSnapshotHash" to JsonPrimitive(ZERO_HASH),
    "knownEntitySnapshotHash" to JsonPrimitive(ZERO_HASH),
    "timelineEvents" to getValue("timelineEvents"),
    "foreshadowOperations" to getValue("foreshadowTransitions"),
))

private fun JsonObject.consistencyDocument() = JsonObject(linkedMapOf(
    "schemaVersion" to getValue("schemaVersion"),
    "sourceChapterVersionId" to getValue("sourceChapterVersionId"),
    "sourceChapterContentHash" to getValue("sourceChapterContentHash"),
    "chapterId" to getValue("chapterId"),
    "chapterIndex" to getValue("chapterIndex"),
    "checkSourceSnapshotHash" to getValue("checkSourceSnapshotHash"),
    "sceneContractHash" to getValue("sceneContractHash"),
    "criterionResults" to getValue("criterionResults"),
    "requiredProcessResults" to getValue("requiredProcessResults"),
    "issues" to getValue("consistencyFindings"),
))

private fun buildProviderSchema(): JsonObject {
    val memory = schema(ChapterMemoryOutputContractV1.providerSchema)
    val tracking = schema(ChapterTrackingOutputContractV1.providerSchema)
    val consistency = schema(ChapterConsistencyOutputContractV1.providerSchema)
    val memoryProperties = memory.getValue("properties").jsonObject
    val trackingProperties = tracking.getValue("properties").jsonObject
    val consistencyProperties = consistency.getValue("properties").jsonObject
    return buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put("required", buildJsonArray { ROOT_KEYS.forEach { add(JsonPrimitive(it)) } })
        put("properties", buildJsonObject {
            listOf("schemaVersion", "sourceChapterVersionId", "sourceChapterContentHash", "chapterId", "chapterIndex")
                .forEach { put(it, memoryProperties.getValue(it)) }
            put("checkSourceSnapshotHash", consistencyProperties.getValue("checkSourceSnapshotHash"))
            put("sceneContractHash", consistencyProperties.getValue("sceneContractHash"))
            put("summary", memoryProperties.getValue("summary"))
            put("entityEvents", memoryProperties.getValue("entityEvents"))
            put("canonFacts", memoryProperties.getValue("facts"))
            put("timelineEvents", trackingProperties.getValue("timelineEvents"))
            put("foreshadowTransitions", trackingProperties.getValue("foreshadowOperations"))
            put("completedAndOpenObligations", arraySchema(256, obligationSchema()))
            put("storyStateDeltas", arraySchema(256, stateSchema()))
            put("repetitionFindings", arraySchema(64, repetitionSchema()))
            put("consistencyFindings", consistencyProperties.getValue("issues"))
            put("presentationFindings", stringArraySchema(128))
            put("criterionResults", consistencyProperties.getValue("criterionResults"))
            put("requiredProcessResults", consistencyProperties.getValue("requiredProcessResults"))
            put("severeRevisionRequired", buildJsonObject { put("type", "boolean") })
            put("evidenceBindings", arraySchema(1_024, bindingSchema()))
        })
    }
}

private fun schema(value: ProviderJsonSchema): JsonObject = value.withValue { raw ->
    STRICT_JSON.parseToJsonElement(raw).jsonObject
}

private fun arraySchema(max: Int, items: JsonObject) = buildJsonObject {
    put("type", "array"); put("maxItems", max); put("items", items)
}

private fun stringArraySchema(max: Int) = arraySchema(max, buildJsonObject {
    put("type", "string"); put("minLength", 1); put("maxLength", 128); put("pattern", "^[A-Za-z0-9._:-]+$")
})

private fun objectSchema(required: Set<String>, properties: JsonObject) = buildJsonObject {
    put("type", "object"); put("additionalProperties", false)
    put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } }); put("properties", properties)
}

private fun obligationSchema() = objectSchema(OBLIGATION_KEYS, buildJsonObject {
    put("obligationId", idSchema()); put("action", enumSchema(NarrativeObligationActionV1.entries.map { it.name }))
    put("evidence", textSchema(2_000)); put("nextDueChapterIndex", nullableIntSchema(1, 10_000))
})

private fun stateSchema() = objectSchema(STATE_KEYS, buildJsonObject {
    put("namespace", enumSchema(StoryStateNamespaceV1.entries.map { it.name })); put("entityId", idSchema())
    put("attribute", buildJsonObject { put("type", "string"); put("minLength", 1); put("maxLength", 96); put("pattern", "^[a-z][a-z0-9._-]*$") })
    put("relatedEntityId", nullableIdSchema()); put("oldValueJson", nullableTextSchema(4_000))
    put("newValueJson", textSchema(4_000)); put("evidence", textSchema(2_000))
})

private fun repetitionSchema() = objectSchema(REPETITION_KEYS, buildJsonObject {
    put("findingId", idSchema()); put("firstStartCodePointInclusive", intSchema(0, 4_194_303))
    put("firstEndCodePointExclusive", intSchema(1, 4_194_304)); put("repeatedStartCodePointInclusive", intSchema(0, 4_194_303))
    put("repeatedEndCodePointExclusive", intSchema(1, 4_194_304)); put("severity", enumSchema(listOf("MAJOR")))
    put("repairAction", enumSchema(listOf("REMOVE_DUPLICATION")))
})

private fun bindingSchema() = objectSchema(BINDING_KEYS, buildJsonObject {
    put("bindingId", idSchema()); put("subject", enumSchema(PostAnalysisEvidenceSubjectV1.entries.map { it.name }))
    put("subjectIndex", intSchema(0, 1_023)); put("startCodePointInclusive", intSchema(0, 4_194_303))
    put("endCodePointExclusive", intSchema(1, 4_194_304))
})

private fun idSchema() = buildJsonObject {
    put("type", "string"); put("minLength", 1); put("maxLength", 128); put("pattern", "^[A-Za-z0-9._:-]+$")
}
private fun nullableIdSchema() = buildJsonObject { put("anyOf", buildJsonArray { add(buildJsonObject { put("type", "null") }); add(idSchema()) }) }
private fun textSchema(max: Int) = buildJsonObject { put("type", "string"); put("minLength", 1); put("maxLength", max) }
private fun nullableTextSchema(max: Int) = buildJsonObject { put("anyOf", buildJsonArray { add(buildJsonObject { put("type", "null") }); add(textSchema(max)) }) }
private fun intSchema(min: Int, max: Int) = buildJsonObject { put("type", "integer"); put("minimum", min); put("maximum", max) }
private fun nullableIntSchema(min: Int, max: Int) = buildJsonObject { put("anyOf", buildJsonArray { add(buildJsonObject { put("type", "null") }); add(intSchema(min, max)) }) }
private fun enumSchema(values: List<String>) = buildJsonObject {
    put("type", "string")
    put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
}

private fun JsonObject.objects(key: String) = (getValue(key) as JsonArray).map { it.jsonObject }
private fun JsonObject.strings(key: String) = (getValue(key) as JsonArray).map { (it as JsonPrimitive).content }
private fun JsonObject.stringsOrEmpty(key: String) = (get(key) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
private fun JsonObject.string(key: String) = (getValue(key) as JsonPrimitive).content
private fun JsonObject.stringOrNull(key: String) = (get(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
private fun JsonObject.nullableString(key: String) = (get(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
private fun JsonObject.int(key: String) = (getValue(key) as JsonPrimitive).intOrNull!!
private fun JsonObject.intOrNull(key: String) = (get(key) as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull
private fun JsonObject.nullableInt(key: String) = intOrNull(key)
private fun JsonObject.boolean(key: String) = (getValue(key) as JsonPrimitive).booleanOrNull!!
private fun JsonObject.arraySize(key: String) = (get(key) as? JsonArray)?.size ?: 0
private fun invalid(path: String) = StructuredOutputIssue(StructuredOutputIssueCode.VALUE_INVALID, path)
private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

private val ROOT_KEYS = linkedSetOf(
    "schemaVersion", "sourceChapterVersionId", "sourceChapterContentHash", "chapterId", "chapterIndex",
    "checkSourceSnapshotHash", "sceneContractHash", "summary", "entityEvents", "canonFacts",
    "timelineEvents", "foreshadowTransitions", "completedAndOpenObligations", "storyStateDeltas",
    "repetitionFindings", "consistencyFindings", "presentationFindings", "criterionResults",
    "requiredProcessResults", "severeRevisionRequired", "evidenceBindings",
)
private val OBLIGATION_KEYS = linkedSetOf("obligationId", "action", "evidence", "nextDueChapterIndex")
private val STATE_KEYS = linkedSetOf("namespace", "entityId", "attribute", "relatedEntityId", "oldValueJson", "newValueJson", "evidence")
private val REPETITION_KEYS = linkedSetOf("findingId", "firstStartCodePointInclusive", "firstEndCodePointExclusive", "repeatedStartCodePointInclusive", "repeatedEndCodePointExclusive", "severity", "repairAction")
private val BINDING_KEYS = linkedSetOf("bindingId", "subject", "subjectIndex", "startCodePointInclusive", "endCodePointExclusive")
private val STATE_ATTRIBUTE = Regex("[a-z][a-z0-9._-]{0,95}")
private val ZERO_HASH = "0".repeat(64)
private val PRESENTATION_CODES = setOf(
    ConsistencyIssueCode.FADE_SUBSTITUTION.name,
    ConsistencyIssueCode.SENSORY_CONTINUITY_BREAK.name,
    ConsistencyIssueCode.RELEVANT_AFTERMATH_MISSING.name,
    ConsistencyIssueCode.MECHANICAL_DETAIL_LIST.name,
    ConsistencyIssueCode.PRESENTATION_PROFILE_DRIFT.name,
)
private val STRICT_JSON = Json { isLenient = false; ignoreUnknownKeys = false }
