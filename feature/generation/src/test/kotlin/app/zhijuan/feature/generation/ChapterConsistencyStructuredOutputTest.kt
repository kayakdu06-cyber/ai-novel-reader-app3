package app.zhijuan.feature.generation

import app.zhijuan.core.model.ConsistencyCriterionV1
import app.zhijuan.core.model.ConsistencyIssueCode
import app.zhijuan.core.model.ConsistencyIssueSeverity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChapterConsistencyStructuredOutputTest {
    @Test
    fun validCompleteCriterionMatrixIsAccepted() {
        val expected = expectation(strict = true)
        val document = document(expected, emptyList())
        val result = StructuredOutputValidator().validate(
            document.toString().encodeToByteArray(),
            BoundChapterConsistencyOutputContract(expected),
        )

        assertTrue(result is StructuredOutputValidationResult.Valid)
        val report = ChapterConsistencyOutputParser().fromDocument(document)
        assertEquals(expected.expectedCriteria, report.criterionResults.map { it.criterion })
        assertEquals(0, report.blockerCount)
        assertTrue(report.contentHash.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun missingOrReorderedCriterionCannotMasqueradeAsCompleteCheck() {
        val expected = expectation(strict = true)
        val original = document(expected, emptyList())
        val criteria = original.getValue("criterionResults") as JsonArray
        val missing = JsonObject(original + ("criterionResults" to JsonArray(criteria.dropLast(1))))
        val reversed = JsonObject(original + ("criterionResults" to JsonArray(criteria.reversed())))

        assertInvalid(missing, expected, "$.criterionResults")
        assertInvalid(reversed, expected, "$.criterionResults")
    }

    @Test
    fun evidenceRangeAndKnownEntityAreBoundToFrozenCandidate() {
        val expected = expectation(strict = false)
        val invalid = issue(
            id = "issue.1",
            code = ConsistencyIssueCode.HARD_FACT_CONFLICT,
            severity = ConsistencyIssueSeverity.BLOCKER,
            start = 95,
            end = 101,
            entities = listOf("char.unknown"),
        )

        assertInvalid(document(expected, listOf(invalid)), expected, "$.issues[0].endCodePointExclusive")
        val inRange = JsonObject(invalid + ("startCodePointInclusive" to JsonPrimitive(10)) +
            ("endCodePointExclusive" to JsonPrimitive(20)))
        assertInvalid(document(expected, listOf(inRange)), expected, "$.issues[0].relatedEntityIds")
    }

    @Test
    fun blockerClassIssueCannotBeDowngraded() {
        val expected = expectation(strict = true)
        val finding = issue(
            id = "issue.fade",
            code = ConsistencyIssueCode.FADE_SUBSTITUTION,
            severity = ConsistencyIssueSeverity.MAJOR,
            start = 10,
            end = 20,
        )

        assertInvalid(document(expected, listOf(finding)), expected, "$.issues[0].severity")
    }

    @Test
    fun repairActionCannotBeFreelySubstituted() {
        val expected = expectation(strict = true)
        val finding = JsonObject(
            issue(
                id = "issue.fade",
                code = ConsistencyIssueCode.FADE_SUBSTITUTION,
                severity = ConsistencyIssueSeverity.BLOCKER,
                start = 10,
                end = 20,
            ) + ("repairAction" to JsonPrimitive("RESTORE_FACT")),
        )

        assertInvalid(document(expected, listOf(finding)), expected, "$.issues[0].repairAction")
    }

    @Test
    fun minorVoiceIssueCannotBeEscalatedIntoAFalseBlocker() {
        val expected = expectation(strict = false)
        val finding = issue(
            id = "issue.voice",
            code = ConsistencyIssueCode.VOICE_CONTINUITY_BREAK,
            severity = ConsistencyIssueSeverity.BLOCKER,
            start = 10,
            end = 20,
        )

        assertInvalid(document(expected, listOf(finding)), expected, "$.issues[0].severity")
    }

    @Test
    fun criterionMustReferenceExactlyItsOwnIssues() {
        val expected = expectation(strict = false)
        val finding = issue(
            id = "issue.fact",
            code = ConsistencyIssueCode.HARD_FACT_CONFLICT,
            severity = ConsistencyIssueSeverity.BLOCKER,
            start = 10,
            end = 20,
            entities = listOf("char.hero"),
        )
        val valid = document(expected, listOf(finding))
        val criteria = (valid.getValue("criterionResults") as JsonArray).map { it as JsonObject }.toMutableList()
        val hardIndex = criteria.indexOfFirst {
            (it.getValue("criterion") as JsonPrimitive).content == ConsistencyCriterionV1.HARD_FACTS.name
        }
        criteria[hardIndex] = JsonObject(
            criteria[hardIndex] +
                ("status" to JsonPrimitive("PASS")) +
                ("issueIds" to JsonArray(emptyList())),
        )

        assertInvalid(
            JsonObject(valid + ("criterionResults" to JsonArray(criteria))),
            expected,
            "$.criterionResults[$hardIndex].issueIds",
        )
    }

    @Test
    fun requiredProcessFindingMustReferenceFrozenNode() {
        val expected = expectation(strict = true)
        val missingReference = issue(
            id = "issue.process",
            code = ConsistencyIssueCode.REQUIRED_PROCESS_MISSING,
            severity = ConsistencyIssueSeverity.BLOCKER,
            start = 10,
            end = 20,
        )
        val unknownReference = JsonObject(
            missingReference +
                ("relatedRequiredProcessNodeIds" to JsonArray(listOf(JsonPrimitive("process.unknown")))),
        )

        assertInvalid(document(expected, listOf(missingReference)), expected, "$.issues[0].relatedRequiredProcessNodeIds")
        assertInvalid(document(expected, listOf(unknownReference)), expected, "$.issues[0].relatedRequiredProcessNodeIds")
    }

    @Test
    fun everyFrozenRequiredProcessNodeMustHaveAnOrderedResult() {
        val expected = expectation(strict = true)
        val valid = document(expected, emptyList())
        val results = valid.getValue("requiredProcessResults") as JsonArray
        val missingOne = JsonObject(valid + ("requiredProcessResults" to JsonArray(results.dropLast(1))))
        val reordered = JsonObject(valid + ("requiredProcessResults" to JsonArray(results.reversed())))

        assertInvalid(missingOne, expected, "$.requiredProcessResults")
        assertInvalid(reordered, expected, "$.requiredProcessResults")
    }

    @Test
    fun modelCannotEmitLocalOnlyCodesAndDiagnosticsStayRedacted() {
        val expected = expectation(strict = false)
        val localOnly = issue(
            id = "issue.local",
            code = ConsistencyIssueCode.BODY_EMPTY,
            severity = ConsistencyIssueSeverity.BLOCKER,
            start = 0,
            end = 1,
        )
        val result = StructuredOutputValidator().validate(
            document(expected, listOf(localOnly)).toString().encodeToByteArray(),
            BoundChapterConsistencyOutputContract(expected),
        )

        assertTrue(result is StructuredOutputValidationResult.Invalid)
        assertTrue(expected.toString().contains("content=redacted"))
        assertFalse(expected.toString().contains("char.hero"))
    }

    private fun assertInvalid(
        document: JsonObject,
        expectation: ChapterConsistencyExpectation,
        path: String,
    ) {
        val result = StructuredOutputValidator().validate(
            document.toString().encodeToByteArray(),
            BoundChapterConsistencyOutputContract(expectation),
        ) as StructuredOutputValidationResult.Invalid
        assertTrue(result.report.issues.any { it.path == path }, result.report.issues.toString())
    }

    private fun expectation(strict: Boolean): ChapterConsistencyExpectation {
        val criteria = ConsistencyCriterionV1.entries.filter {
            strict || it !in STRICT_CRITERIA
        }
        return ChapterConsistencyExpectation(
            sourceChapterVersionId = "version.1",
            sourceChapterContentHash = "a".repeat(64),
            chapterId = "chapter.1",
            chapterIndex = 1,
            checkSourceSnapshotHash = "b".repeat(64),
            sceneContractHash = "c".repeat(64),
            bodyCodePointCount = 100,
            expectedCriteria = criteria,
            knownEntityIds = setOf("char.hero", "place.room"),
            knownForeshadowItemIds = setOf("foreshadow.key"),
            requiredProcessNodeIds = if (strict) setOf("process.1", "process.2") else emptySet(),
        )
    }

    private fun document(
        expected: ChapterConsistencyExpectation,
        findings: List<JsonObject>,
    ): JsonObject {
        val findingCriteria = findings.associate { finding ->
            val id = (finding.getValue("issueId") as JsonPrimitive).content
            val code = ConsistencyIssueCode.valueOf((finding.getValue("code") as JsonPrimitive).content)
            id to ConsistencyIssuePolicyV1.criterionFor(code)
        }
        val criteria = expected.expectedCriteria.map { criterion ->
            val ids = findingCriteria.filterValues { it == criterion }.keys.toList()
            JsonObject(
                linkedMapOf(
                    "criterion" to JsonPrimitive(criterion.name),
                    "status" to JsonPrimitive(if (ids.isEmpty()) "PASS" else "ISSUE"),
                    "issueIds" to JsonArray(ids.map(::JsonPrimitive)),
                ),
            )
        }
        val missingNodeIssues = findings.flatMap { finding ->
            val code = ConsistencyIssueCode.valueOf((finding.getValue("code") as JsonPrimitive).content)
            if (code != ConsistencyIssueCode.REQUIRED_PROCESS_MISSING) return@flatMap emptyList()
            val issueId = (finding.getValue("issueId") as JsonPrimitive).content
            (finding.getValue("relatedRequiredProcessNodeIds") as JsonArray).map { node ->
                (node as JsonPrimitive).content to issueId
            }
        }.toMap()
        return JsonObject(
            linkedMapOf(
                "schemaVersion" to JsonPrimitive(1),
                "sourceChapterVersionId" to JsonPrimitive(expected.sourceChapterVersionId),
                "sourceChapterContentHash" to JsonPrimitive(expected.sourceChapterContentHash),
                "chapterId" to JsonPrimitive(expected.chapterId),
                "chapterIndex" to JsonPrimitive(expected.chapterIndex),
                "checkSourceSnapshotHash" to JsonPrimitive(expected.checkSourceSnapshotHash),
                "sceneContractHash" to JsonPrimitive(expected.sceneContractHash),
                "criterionResults" to JsonArray(criteria),
                "requiredProcessResults" to JsonArray(expected.requiredProcessNodeIds.sorted().map { nodeId ->
                    val issueId = missingNodeIssues[nodeId]
                    JsonObject(
                        linkedMapOf(
                            "requiredProcessNodeId" to JsonPrimitive(nodeId),
                            "status" to JsonPrimitive(if (issueId == null) "COVERED" else "MISSING"),
                            "issueId" to (issueId?.let(::JsonPrimitive) ?: JsonNull),
                        ),
                    )
                }),
                "issues" to JsonArray(findings),
            ),
        )
    }

    private fun issue(
        id: String,
        code: ConsistencyIssueCode,
        severity: ConsistencyIssueSeverity,
        start: Int,
        end: Int,
        entities: List<String> = emptyList(),
    ): JsonObject = JsonObject(
        linkedMapOf(
            "issueId" to JsonPrimitive(id),
            "code" to JsonPrimitive(code.name),
            "severity" to JsonPrimitive(severity.name),
            "startCodePointInclusive" to JsonPrimitive(start),
            "endCodePointExclusive" to JsonPrimitive(end),
            "relatedEntityIds" to JsonArray(entities.map(::JsonPrimitive)),
            "relatedForeshadowItemIds" to JsonArray(emptyList()),
            "relatedRequiredProcessNodeIds" to JsonArray(emptyList()),
            "repairAction" to JsonPrimitive(ConsistencyIssuePolicyV1.requiredRepairAction(code).name),
        ),
    )

    private companion object {
        val STRICT_CRITERIA = setOf(
            ConsistencyCriterionV1.REQUIRED_PROCESS_COVERAGE,
            ConsistencyCriterionV1.NO_FADE_SUBSTITUTION,
            ConsistencyCriterionV1.SENSORY_CONTINUITY,
            ConsistencyCriterionV1.RELEVANT_AFTERMATH,
            ConsistencyCriterionV1.NON_MECHANICAL_DETAIL,
        )
    }
}
