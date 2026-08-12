package app.zhijuan.feature.generation

import app.zhijuan.core.task.ArcPlanningWindowPolicyV1
import app.zhijuan.core.task.ArcPlanningWindowSelection
import app.zhijuan.provider.common.ProviderJsonSchema
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

data class ArcMilestoneV1(
    val milestoneId: String,
    val chapterIndex: Int,
    val purpose: String,
    val consequence: String,
)

data class WindowChapterBriefV1(
    val chapterIndex: Int,
    val title: String,
    val goal: String,
    val conflict: String,
    val turn: String,
    val outcome: String,
    val hook: String,
    val continuityCarry: List<String>,
)

data class ArcWindowPlanV1(
    val policyVersion: String,
    val masterOutlineContentHash: String,
    val parentOutlineContentHash: String,
    val targetChapterCount: Int,
    val arcId: String,
    val arcStartChapter: Int,
    val arcEndChapter: Int,
    val title: String,
    val dramaticQuestion: String,
    val openingState: String,
    val closingState: String,
    val milestones: List<ArcMilestoneV1>,
    val continuityConstraints: List<String>,
    val windowId: String,
    val windowStartChapter: Int,
    val windowEndChapter: Int,
    val chapters: List<WindowChapterBriefV1>,
    val nextWindowStartChapter: Int?,
    val canonicalJson: String,
    val contentHash: String,
) {
    override fun toString(): String =
        "ArcWindowPlanV1(arcRange=$arcStartChapter..$arcEndChapter, " +
            "windowRange=$windowStartChapter..$windowEndChapter, chapterCount=${chapters.size}, content=redacted)"
}

enum class ArcWindowCrossIssueCode {
    POLICY_VERSION_MISMATCH,
    MASTER_OUTLINE_HASH_MISMATCH,
    PARENT_OUTLINE_HASH_MISMATCH,
    TARGET_CHAPTER_COUNT_MISMATCH,
    ARC_SELECTION_MISMATCH,
    WINDOW_SELECTION_MISMATCH,
    CHAPTER_SEQUENCE_MISMATCH,
    NEXT_WINDOW_POINTER_MISMATCH,
}

data class ArcWindowCrossIssue(
    val code: ArcWindowCrossIssueCode,
    val reference: String,
) {
    init {
        require(REFERENCE.matches(reference))
    }

    private companion object {
        val REFERENCE = Regex("[A-Za-z0-9._:-]{1,128}")
    }
}

data class ArcWindowPlanningExpectation(
    val masterOutlineContentHash: String,
    val parentOutlineContentHash: String,
    val targetChapterCount: Int,
    val selection: ArcPlanningWindowSelection,
) {
    init {
        require(HASH.matches(masterOutlineContentHash) && HASH.matches(parentOutlineContentHash))
        require(targetChapterCount in 80..10_000)
        require(selection.windowEndChapter <= targetChapterCount)
    }

    private companion object {
        val HASH = Regex("[0-9a-f]{64}")
    }
}

sealed interface ArcWindowPlanningValidationResult {
    data class Valid(val plan: ArcWindowPlanV1) : ArcWindowPlanningValidationResult
    data class Invalid(val issues: List<ArcWindowCrossIssue>) : ArcWindowPlanningValidationResult {
        init {
            require(issues.isNotEmpty() && issues.size <= 32)
        }

        override fun toString(): String =
            "ArcWindowPlanningValidationResult.Invalid(issueCodes=${issues.map { it.code }.distinct()}, content=redacted)"
    }
}

class ArcWindowPlanningOutputParser(
    private val validator: StructuredOutputValidator = StructuredOutputValidator(),
) {
    fun parse(source: ByteArray): PlanningOutputValidationResult<ArcWindowPlanV1> =
        when (val result = validator.validate(source, ArcWindowPlanOutputContractV1)) {
            is StructuredOutputValidationResult.Invalid -> PlanningOutputValidationResult.Invalid(result.report)
            is StructuredOutputValidationResult.Valid -> PlanningOutputValidationResult.Valid(
                result.output.withDocument(::toPlan),
            )
        }

    private fun toPlan(document: JsonObject): ArcWindowPlanV1 {
        val arc = document.objectValue("arc")
        val window = document.objectValue("chapterWindow")
        val canonical = document.toString()
        return ArcWindowPlanV1(
            policyVersion = document.stringValue("policyVersion"),
            masterOutlineContentHash = document.stringValue("masterOutlineContentHash"),
            parentOutlineContentHash = document.stringValue("parentOutlineContentHash"),
            targetChapterCount = document.intValue("targetChapterCount"),
            arcId = arc.stringValue("arcId"),
            arcStartChapter = arc.intValue("startChapter"),
            arcEndChapter = arc.intValue("endChapter"),
            title = arc.stringValue("title"),
            dramaticQuestion = arc.stringValue("dramaticQuestion"),
            openingState = arc.stringValue("openingState"),
            closingState = arc.stringValue("closingState"),
            milestones = arc.objectValues("milestones").map { milestone ->
                ArcMilestoneV1(
                    milestoneId = milestone.stringValue("milestoneId"),
                    chapterIndex = milestone.intValue("chapterIndex"),
                    purpose = milestone.stringValue("purpose"),
                    consequence = milestone.stringValue("consequence"),
                )
            },
            continuityConstraints = arc.stringValues("continuityConstraints"),
            windowId = window.stringValue("windowId"),
            windowStartChapter = window.intValue("startChapter"),
            windowEndChapter = window.intValue("endChapter"),
            chapters = window.objectValues("chapters").map { chapter ->
                WindowChapterBriefV1(
                    chapterIndex = chapter.intValue("chapterIndex"),
                    title = chapter.stringValue("title"),
                    goal = chapter.stringValue("goal"),
                    conflict = chapter.stringValue("conflict"),
                    turn = chapter.stringValue("turn"),
                    outcome = chapter.stringValue("outcome"),
                    hook = chapter.stringValue("hook"),
                    continuityCarry = chapter.stringValues("continuityCarry"),
                )
            },
            nextWindowStartChapter = document.nullableIntValue("nextWindowStartChapter"),
            canonicalJson = canonical,
            contentHash = sha256(canonical),
        )
    }
}

object ArcWindowPlanningValidator {
    fun validate(
        plan: ArcWindowPlanV1,
        expected: ArcWindowPlanningExpectation,
    ): ArcWindowPlanningValidationResult {
        val selection = expected.selection
        val issues = buildList {
            if (plan.policyVersion != ArcPlanningWindowPolicyV1.POLICY_VERSION) {
                add(ArcWindowCrossIssue(ArcWindowCrossIssueCode.POLICY_VERSION_MISMATCH, "policyVersion"))
            }
            if (plan.masterOutlineContentHash != expected.masterOutlineContentHash) {
                add(
                    ArcWindowCrossIssue(
                        ArcWindowCrossIssueCode.MASTER_OUTLINE_HASH_MISMATCH,
                        "masterOutlineContentHash",
                    ),
                )
            }
            if (plan.parentOutlineContentHash != expected.parentOutlineContentHash) {
                add(
                    ArcWindowCrossIssue(
                        ArcWindowCrossIssueCode.PARENT_OUTLINE_HASH_MISMATCH,
                        "parentOutlineContentHash",
                    ),
                )
            }
            if (plan.targetChapterCount != expected.targetChapterCount) {
                add(
                    ArcWindowCrossIssue(
                        ArcWindowCrossIssueCode.TARGET_CHAPTER_COUNT_MISMATCH,
                        "targetChapterCount",
                    ),
                )
            }
            if (
                plan.arcId != selection.arcId ||
                plan.arcStartChapter != selection.arcStartChapter ||
                plan.arcEndChapter != selection.arcEndChapter
            ) {
                add(ArcWindowCrossIssue(ArcWindowCrossIssueCode.ARC_SELECTION_MISMATCH, "arc"))
            }
            if (
                plan.windowId != selection.windowId ||
                plan.windowStartChapter != selection.windowStartChapter ||
                plan.windowEndChapter != selection.windowEndChapter
            ) {
                add(ArcWindowCrossIssue(ArcWindowCrossIssueCode.WINDOW_SELECTION_MISMATCH, "chapterWindow"))
            }
            if (
                plan.chapters.map(WindowChapterBriefV1::chapterIndex) !=
                (selection.windowStartChapter..selection.windowEndChapter).toList()
            ) {
                add(ArcWindowCrossIssue(ArcWindowCrossIssueCode.CHAPTER_SEQUENCE_MISMATCH, "chapters"))
            }
            if (plan.nextWindowStartChapter != selection.nextWindowStartChapter) {
                add(
                    ArcWindowCrossIssue(
                        ArcWindowCrossIssueCode.NEXT_WINDOW_POINTER_MISMATCH,
                        "nextWindowStartChapter",
                    ),
                )
            }
        }.distinct().take(32)
        return if (issues.isEmpty()) {
            ArcWindowPlanningValidationResult.Valid(plan)
        } else {
            ArcWindowPlanningValidationResult.Invalid(issues)
        }
    }
}

object ArcWindowPlanOutputContractV1 : StructuredOutputContract {
    override val schemaId = "arc-plan.v1"
    override val currentSchemaVersion = 1
    override val providerSchema = ProviderJsonSchema.from(ARC_WINDOW_PLAN_SCHEMA)

    override fun validate(document: JsonObject): List<StructuredOutputIssue> = buildList {
        val root = ArcContractReader(document, "$", this)
        root.exactKeys(ROOT_KEYS)
        root.exactInt("schemaVersion", 1)
        root.exactString("policyVersion", ArcPlanningWindowPolicyV1.POLICY_VERSION)
        root.hash("masterOutlineContentHash")
        root.hash("parentOutlineContentHash")
        root.int("targetChapterCount", 80..10_000)
        root.nullableInt("nextWindowStartChapter", 1..10_000)

        val arc = root.objectValue("arc")
        val arcReader = ArcContractReader(arc, "$.arc", this)
        arcReader.exactKeys(ARC_KEYS)
        arcReader.identifier("arcId")
        val arcStart = arcReader.int("startChapter", 1..10_000)
        val arcEnd = arcReader.int("endChapter", 1..10_000)
        if (arcStart != null && arcEnd != null && (arcEnd < arcStart || arcEnd - arcStart + 1 > 40)) {
            add(StructuredOutputIssue(StructuredOutputIssueCode.VALUE_INVALID, "$.arc.endChapter"))
        }
        listOf("title", "dramaticQuestion", "openingState", "closingState").forEach {
            arcReader.string(it, 1..2_000)
        }
        val milestones = arcReader.objects("milestones", 1..8)
        milestones.forEachIndexed { index, milestone ->
            val reader = ArcContractReader(milestone, "$.arc.milestones[$index]", this)
            reader.exactKeys(MILESTONE_KEYS)
            reader.identifier("milestoneId")
            val chapter = reader.int("chapterIndex", 1..10_000)
            if (chapter != null && arcStart != null && arcEnd != null && chapter !in arcStart..arcEnd) {
                add(StructuredOutputIssue(StructuredOutputIssueCode.VALUE_INVALID, "$.arc.milestones[$index].chapterIndex"))
            }
            reader.string("purpose", 1..1_000)
            reader.string("consequence", 1..1_000)
        }
        duplicateStrings(milestones.mapNotNull { it.stringOrNull("milestoneId") }, "$.arc.milestones", this)
        arcReader.strings("continuityConstraints", 1..24, 1..1_000)

        val window = root.objectValue("chapterWindow")
        val windowReader = ArcContractReader(window, "$.chapterWindow", this)
        windowReader.exactKeys(WINDOW_KEYS)
        windowReader.identifier("windowId")
        val windowStart = windowReader.int("startChapter", 1..10_000)
        val windowEnd = windowReader.int("endChapter", 1..10_000)
        if (
            windowStart != null && windowEnd != null &&
            (windowEnd < windowStart || windowEnd - windowStart + 1 > 8 ||
                arcStart == null || arcEnd == null || windowStart !in arcStart..arcEnd || windowEnd !in arcStart..arcEnd)
        ) {
            add(StructuredOutputIssue(StructuredOutputIssueCode.VALUE_INVALID, "$.chapterWindow.endChapter"))
        }
        val chapters = windowReader.objects("chapters", 1..8)
        chapters.forEachIndexed { index, chapter ->
            val reader = ArcContractReader(chapter, "$.chapterWindow.chapters[$index]", this)
            reader.exactKeys(CHAPTER_KEYS)
            reader.int("chapterIndex", 1..10_000)
            listOf("title", "goal", "conflict", "turn", "outcome", "hook").forEach {
                reader.string(it, 1..1_500)
            }
            reader.strings("continuityCarry", 1..16, 1..1_000)
        }
        val indices = chapters.mapNotNull { it.intOrNull("chapterIndex") }
        if (
            indices.distinct().size != indices.size ||
            (windowStart != null && windowEnd != null && indices != (windowStart..windowEnd).toList())
        ) {
            add(StructuredOutputIssue(StructuredOutputIssueCode.VALUE_INVALID, "$.chapterWindow.chapters"))
        }
    }
}

private class ArcContractReader(
    private val value: JsonObject,
    private val path: String,
    private val issues: MutableList<StructuredOutputIssue>,
) {
    fun exactKeys(expected: Set<String>) {
        expected.filterNot(value::containsKey).forEach {
            issues += StructuredOutputIssue(StructuredOutputIssueCode.REQUIRED_FIELD_MISSING, "$path.$it")
        }
        value.keys.filterNot(expected::contains).forEach {
            issues += StructuredOutputIssue(StructuredOutputIssueCode.UNKNOWN_FIELD, "$path.$it")
        }
    }

    fun exactInt(key: String, expected: Int) {
        val actual = int(key, expected..expected)
        if (actual != null && actual != expected) invalid(key)
    }

    fun exactString(key: String, expected: String) {
        val actual = string(key, expected.length..expected.length)
        if (actual != null && actual != expected) invalid(key)
    }

    fun string(key: String, range: IntRange): String? {
        val primitive = value[key] as? JsonPrimitive
        val text = primitive?.takeIf(JsonPrimitive::isString)?.contentOrNull
        if (text == null) type(key) else if (text.length !in range || text.isBlank()) invalid(key)
        return text
    }

    fun identifier(key: String) {
        val text = string(key, 1..128)
        if (text != null && !IDENTIFIER.matches(text)) invalid(key)
    }

    fun hash(key: String) {
        val text = string(key, 64..64)
        if (text != null && !HASH.matches(text)) invalid(key)
    }

    fun int(key: String, range: IntRange): Int? {
        val primitive = value[key] as? JsonPrimitive
        val number = primitive?.takeUnless(JsonPrimitive::isString)?.intOrNull
        if (number == null) type(key) else if (number !in range) invalid(key)
        return number
    }

    fun nullableInt(key: String, range: IntRange) {
        val element = value[key]
        if (element == null) {
            issues += StructuredOutputIssue(StructuredOutputIssueCode.REQUIRED_FIELD_MISSING, "$path.$key")
        } else if (element !is kotlinx.serialization.json.JsonNull) {
            val primitive = element as? JsonPrimitive
            val number = primitive?.takeUnless(JsonPrimitive::isString)?.intOrNull
            if (number == null) type(key) else if (number !in range) invalid(key)
        }
    }

    fun objectValue(key: String): JsonObject {
        val result = value[key] as? JsonObject
        if (result == null) type(key)
        return result ?: JsonObject(emptyMap())
    }

    fun objects(key: String, range: IntRange): List<JsonObject> {
        val array = value[key] as? JsonArray
        if (array == null) {
            type(key)
            return emptyList()
        }
        if (array.size !in range) invalid(key)
        return array.mapIndexedNotNull { index, element ->
            (element as? JsonObject).also {
                if (it == null) issues += StructuredOutputIssue(StructuredOutputIssueCode.TYPE_MISMATCH, "$path.$key[$index]")
            }
        }
    }

    fun strings(key: String, range: IntRange, itemRange: IntRange) {
        val array = value[key] as? JsonArray
        if (array == null) {
            type(key)
            return
        }
        if (array.size !in range) invalid(key)
        array.forEachIndexed { index, element ->
            val text = (element as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
            if (text == null) {
                issues += StructuredOutputIssue(StructuredOutputIssueCode.TYPE_MISMATCH, "$path.$key[$index]")
            } else if (text.length !in itemRange || text.isBlank()) {
                issues += StructuredOutputIssue(StructuredOutputIssueCode.VALUE_INVALID, "$path.$key[$index]")
            }
        }
    }

    private fun type(key: String) {
        issues += StructuredOutputIssue(StructuredOutputIssueCode.TYPE_MISMATCH, "$path.$key")
    }

    private fun invalid(key: String) {
        issues += StructuredOutputIssue(StructuredOutputIssueCode.VALUE_INVALID, "$path.$key")
    }

    private companion object {
        val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
        val HASH = Regex("[0-9a-f]{64}")
    }
}

private fun JsonObject.objectValue(key: String): JsonObject = getValue(key) as JsonObject
private fun JsonObject.objectValues(key: String): List<JsonObject> = (getValue(key) as JsonArray).map { it as JsonObject }
private fun JsonObject.stringValue(key: String): String = (getValue(key) as JsonPrimitive).content
private fun JsonObject.stringValues(key: String): List<String> = (getValue(key) as JsonArray).map { (it as JsonPrimitive).content }
private fun JsonObject.intValue(key: String): Int = (getValue(key) as JsonPrimitive).intOrNull!!
private fun JsonObject.nullableIntValue(key: String): Int? = (getValue(key) as? JsonPrimitive)?.intOrNull
private fun JsonObject.stringOrNull(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
private fun JsonObject.intOrNull(key: String): Int? = (get(key) as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull

private fun duplicateStrings(values: List<String>, path: String, issues: MutableList<StructuredOutputIssue>) {
    if (values.distinct().size != values.size) {
        issues += StructuredOutputIssue(StructuredOutputIssueCode.VALUE_INVALID, path)
    }
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

private val ROOT_KEYS = setOf(
    "schemaVersion", "policyVersion", "masterOutlineContentHash", "parentOutlineContentHash",
    "targetChapterCount", "arc", "chapterWindow", "nextWindowStartChapter",
)
private val ARC_KEYS = setOf(
    "arcId", "startChapter", "endChapter", "title", "dramaticQuestion", "openingState",
    "closingState", "milestones", "continuityConstraints",
)
private val MILESTONE_KEYS = setOf("milestoneId", "chapterIndex", "purpose", "consequence")
private val WINDOW_KEYS = setOf("windowId", "startChapter", "endChapter", "chapters")
private val CHAPTER_KEYS = setOf(
    "chapterIndex", "title", "goal", "conflict", "turn", "outcome", "hook", "continuityCarry",
)

private val ARC_WINDOW_PLAN_SCHEMA = """
{"type":"object","additionalProperties":false,"required":["schemaVersion","policyVersion","masterOutlineContentHash","parentOutlineContentHash","targetChapterCount","arc","chapterWindow","nextWindowStartChapter"],"properties":{"schemaVersion":{"const":1},"policyVersion":{"const":"zhijuan.arc-window-policy.v1"},"masterOutlineContentHash":{"type":"string","pattern":"^[0-9a-f]{64}$"},"parentOutlineContentHash":{"type":"string","pattern":"^[0-9a-f]{64}$"},"targetChapterCount":{"type":"integer","minimum":80,"maximum":10000},"arc":{"type":"object","additionalProperties":false,"required":["arcId","startChapter","endChapter","title","dramaticQuestion","openingState","closingState","milestones","continuityConstraints"],"properties":{"arcId":{"type":"string","minLength":1,"maxLength":128,"pattern":"^[A-Za-z0-9._:-]+$"},"startChapter":{"type":"integer","minimum":1,"maximum":10000},"endChapter":{"type":"integer","minimum":1,"maximum":10000},"title":{"type":"string","minLength":1,"maxLength":2000},"dramaticQuestion":{"type":"string","minLength":1,"maxLength":2000},"openingState":{"type":"string","minLength":1,"maxLength":2000},"closingState":{"type":"string","minLength":1,"maxLength":2000},"milestones":{"type":"array","minItems":1,"maxItems":8,"items":{"type":"object","additionalProperties":false,"required":["milestoneId","chapterIndex","purpose","consequence"],"properties":{"milestoneId":{"type":"string","minLength":1,"maxLength":128,"pattern":"^[A-Za-z0-9._:-]+$"},"chapterIndex":{"type":"integer","minimum":1,"maximum":10000},"purpose":{"type":"string","minLength":1,"maxLength":1000},"consequence":{"type":"string","minLength":1,"maxLength":1000}}}},"continuityConstraints":{"type":"array","minItems":1,"maxItems":24,"items":{"type":"string","minLength":1,"maxLength":1000}}}},"chapterWindow":{"type":"object","additionalProperties":false,"required":["windowId","startChapter","endChapter","chapters"],"properties":{"windowId":{"type":"string","minLength":1,"maxLength":128,"pattern":"^[A-Za-z0-9._:-]+$"},"startChapter":{"type":"integer","minimum":1,"maximum":10000},"endChapter":{"type":"integer","minimum":1,"maximum":10000},"chapters":{"type":"array","minItems":1,"maxItems":8,"items":{"type":"object","additionalProperties":false,"required":["chapterIndex","title","goal","conflict","turn","outcome","hook","continuityCarry"],"properties":{"chapterIndex":{"type":"integer","minimum":1,"maximum":10000},"title":{"type":"string","minLength":1,"maxLength":1500},"goal":{"type":"string","minLength":1,"maxLength":1500},"conflict":{"type":"string","minLength":1,"maxLength":1500},"turn":{"type":"string","minLength":1,"maxLength":1500},"outcome":{"type":"string","minLength":1,"maxLength":1500},"hook":{"type":"string","minLength":1,"maxLength":1500},"continuityCarry":{"type":"array","minItems":1,"maxItems":16,"items":{"type":"string","minLength":1,"maxLength":1000}}}}}},"nextWindowStartChapter":{"type":["integer","null"],"minimum":1,"maximum":10000}}}
}
""".trimIndent()
