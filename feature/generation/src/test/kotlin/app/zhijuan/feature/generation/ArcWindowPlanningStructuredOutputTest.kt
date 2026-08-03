package app.zhijuan.feature.generation

import app.zhijuan.core.model.OutlineNodeType
import app.zhijuan.core.task.ArcPlanningWindowInput
import app.zhijuan.core.task.ArcPlanningWindowPolicyV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArcWindowPlanningStructuredOutputTest {
    private val parser = ArcWindowPlanningOutputParser()
    private val selection = ArcPlanningWindowPolicyV1.select(
        ArcPlanningWindowInput(10_000, 1, 1, 2_500),
    )
    private val expected = ArcWindowPlanningExpectation(
        masterOutlineContentHash = "a".repeat(64),
        parentOutlineContentHash = "b".repeat(64),
        targetChapterCount = 10_000,
        selection = selection,
    )

    @Test
    fun `schema is provider-ready and exposes a bounded nested chapter window`() {
        assertEquals("arc-plan.v1", ArcWindowPlanOutputContractV1.schemaId)
        ArcWindowPlanOutputContractV1.providerSchema.withValue { schema ->
            assertTrue("\"maxItems\":8" in schema)
            assertTrue("\"chapterWindow\"" in schema)
            assertTrue("\"continuityCarry\"" in schema)
            assertTrue("\"additionalProperties\":false" in schema)
        }
    }

    @Test
    fun `valid ten-thousand-chapter book still parses only the first eight chapter briefs`() {
        val plan = parse(validJson())
        val result = ArcWindowPlanningValidator.validate(plan, expected)

        assertInstanceOf(ArcWindowPlanningValidationResult.Valid::class.java, result)
        assertEquals(1..40, plan.arcStartChapter..plan.arcEndChapter)
        assertEquals((1..8).toList(), plan.chapters.map(WindowChapterBriefV1::chapterIndex))
        assertEquals(9, plan.nextWindowStartChapter)
        assertTrue(plan.toString().contains("content=redacted"))
    }

    @Test
    fun `nine chapter briefs and duplicate json keys fail strict validation`() {
        val nineChapters = validJson(windowEnd = 9, chapterCount = 9)
        assertInstanceOf(PlanningOutputValidationResult.Invalid::class.java, parser.parse(nineChapters))

        val duplicate = validJson().decodeToString().replace(
            "\"schemaVersion\":1,",
            "\"schemaVersion\":1,\"schemaVersion\":1,",
        ).encodeToByteArray()
        assertInstanceOf(PlanningOutputValidationResult.Invalid::class.java, parser.parse(duplicate))
    }

    @Test
    fun `cross validator rejects changed evidence and next-window pointer`() {
        val plan = parse(
            validJson(
                masterHash = "c".repeat(64),
                nextWindow = 10,
            ),
        )
        val result = ArcWindowPlanningValidator.validate(plan, expected)
            as ArcWindowPlanningValidationResult.Invalid

        assertTrue(result.issues.any { it.code == ArcWindowCrossIssueCode.MASTER_OUTLINE_HASH_MISMATCH })
        assertTrue(result.issues.any { it.code == ArcWindowCrossIssueCode.NEXT_WINDOW_POINTER_MISMATCH })
    }

    @Test
    fun `persistence mapping is deterministic and produces one root one arc and eight chapters`() {
        val plan = parse(validJson())
        val ids = ArcWindowPlanningPersistenceIds(
            bookId = "book.long",
            masterOutlineRevisionId = "outline.master.1",
            parentOutlineRevisionId = "outline.master.1",
            parentRevisionNo = 1,
            outlineRevisionId = "outline.window.2",
            generationStageId = "stage.arc-window.1",
        )
        val first = ArcWindowPlanningPersistenceMapper.map(plan, expected, ids, 10L)
        val second = ArcWindowPlanningPersistenceMapper.map(plan, expected, ids, 10L)

        assertEquals(first, second)
        assertEquals(2, first.revision.revisionNo)
        assertEquals("outline.master.1", first.revision.parentRevisionId)
        assertEquals(10, first.nodes.size)
        assertEquals(1, first.nodes.count { it.nodeType == OutlineNodeType.BOOK })
        assertEquals(1, first.nodes.count { it.nodeType == OutlineNodeType.ARC })
        assertEquals(8, first.nodes.count { it.nodeType == OutlineNodeType.CHAPTER })
        assertEquals((1..8).toList(), first.nodes.mapNotNull { it.plannedChapterIndex })
    }

    private fun parse(raw: ByteArray): ArcWindowPlanV1 = when (val result = parser.parse(raw)) {
        is PlanningOutputValidationResult.Valid -> result.value
        is PlanningOutputValidationResult.Invalid -> error(result.report.toString())
    }

    private fun validJson(
        masterHash: String = "a".repeat(64),
        parentHash: String = "b".repeat(64),
        windowEnd: Int = 8,
        chapterCount: Int = 8,
        nextWindow: Int? = 9,
    ): ByteArray {
        val chapters = (1..chapterCount).joinToString(",") { chapter ->
            """{"chapterIndex":$chapter,"title":"Chapter $chapter","goal":"Advance the investigation","conflict":"Evidence creates resistance","turn":"A clue changes meaning","outcome":"The next choice becomes unavoidable","hook":"A new contradiction appears","continuityCarry":["Preserve known locations and evidence"]}"""
        }
        val next = nextWindow?.toString() ?: "null"
        return """
            {"schemaVersion":1,"policyVersion":"zhijuan.arc-window-policy.v1","masterOutlineContentHash":"$masterHash","parentOutlineContentHash":"$parentHash","targetChapterCount":10000,"arc":{"arcId":"arc.1.40","startChapter":1,"endChapter":40,"title":"The first seam","dramaticQuestion":"Can the evidence survive institutional pressure?","openingState":"The anomaly is private and unverified.","closingState":"The protagonist holds a verifiable chain at personal cost.","milestones":[{"milestoneId":"milestone.first","chapterIndex":8,"purpose":"Confirm the anomaly","consequence":"Opposition identifies the investigation"}],"continuityConstraints":["Physical evidence cannot reset without an explained cause"]},"chapterWindow":{"windowId":"window.1.$windowEnd","startChapter":1,"endChapter":$windowEnd,"chapters":[$chapters]},"nextWindowStartChapter":$next}
        """.trimIndent().encodeToByteArray()
    }
}
