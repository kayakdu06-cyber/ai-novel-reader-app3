package app.zhijuan.feature.generation

import app.zhijuan.core.task.ArcPlanningWindowSelection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

class ArcWindowPlanV2ContractTest {
    @Test
    fun `arc v2 keeps bounded window and binds every chapter contract`() {
        val plan = (ArcWindowPlanV2Parser().parse(validV2().toByteArray()) as PlanningOutputValidationResult.Valid).value
        val result = ArcWindowPlanV2BusinessValidator.validate(plan, expectation())
        assertTrue(result is ArcWindowV2BusinessResult.Valid)
        assertEquals(listOf(1, 2), plan.chapterContracts.map(ArcWindowChapterContractV2::chapterIndex))
        assertEquals(2, plan.basePlan.chapters.size)
    }

    @Test
    fun `arc v2 rejects a missing window chapter while v1 stays readable`() {
        val invalid = validV2().replace(
            "{\"chapterIndex\":2,\"objective\":\"承担第一章后果\",\"capabilityHints\":[\"core-narrative\"],\"obligationIds\":[\"promise-1\"],\"prohibitedRepetitions\":[\"不复述第一章选择\"]}",
            "{\"chapterIndex\":3,\"objective\":\"承担第一章后果\",\"capabilityHints\":[\"core-narrative\"],\"obligationIds\":[\"promise-1\"],\"prohibitedRepetitions\":[\"不复述第一章选择\"]}",
        )
        val plan = (ArcWindowPlanV2Parser().parse(invalid.toByteArray()) as PlanningOutputValidationResult.Valid).value
        val result = ArcWindowPlanV2BusinessValidator.validate(plan, expectation()) as ArcWindowV2BusinessResult.Invalid
        assertTrue(result.issues.any { it.code == ArcWindowV2IssueCode.CHAPTER_CONTRACT_SEQUENCE_MISMATCH })
        assertTrue(ArcWindowPlanningOutputParser().parse(validV1().toByteArray()) is PlanningOutputValidationResult.Valid)
    }

    @Test
    fun `v2 chapter contracts remain in persisted chapter authority`() {
        val plan = (ArcWindowPlanV2Parser().parse(validV2().toByteArray())
            as PlanningOutputValidationResult.Valid).value
        val draft = ArcWindowPlanningPersistenceMapper.map(
            plan = plan.basePlan,
            chapterContracts = plan.chapterContracts,
            expected = expectation().base,
            ids = ArcWindowPlanningPersistenceIds(
                bookId = "book-1",
                masterOutlineRevisionId = "master-1",
                parentOutlineRevisionId = "parent-1",
                parentRevisionNo = 1,
                outlineRevisionId = "window-revision-1",
                generationStageId = "stage-1",
            ),
            committedAt = 1L,
            schemaId = ArcWindowPlanOutputContractV2.schemaId,
            policyVersion = "zhijuan.arc-window-policy.v2",
            canonicalPlanJson = plan.canonicalJson,
            canonicalPlanHash = plan.contentHash,
        )
        assertEquals(plan.canonicalJson, draft.revision.summaryJson)
        assertEquals(plan.contentHash, draft.revision.contentHash)
        val chapter = draft.nodes.single { it.plannedChapterIndex == 1 }
        val root = Json.parseToJsonElement(chapter.planJson).jsonObject
        assertEquals("完成第一次选择", root.getValue("objective").jsonPrimitive.content)
        assertEquals(
            listOf("core-narrative"),
            root.getValue("capabilityHints").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("promise-1"),
            root.getValue("obligationIds").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    private fun expectation() = ArcWindowExpectationV2(
        base = ArcWindowPlanningExpectation(
            masterOutlineContentHash = HASH_A,
            parentOutlineContentHash = HASH_B,
            targetChapterCount = 80,
            selection = ArcPlanningWindowSelection(
                policyVersion = "zhijuan.arc-window-policy.v1",
                arcId = "arc-1",
                arcStartChapter = 1,
                arcEndChapter = 20,
                windowId = "window-1",
                windowStartChapter = 1,
                windowEndChapter = 2,
                nextWindowStartChapter = 3,
            ),
        ),
        policyCompilationHash = HASH_C,
        contextEvidenceHash = HASH_D,
    )

    private fun validV2() = validV1()
        .replace("\"schemaVersion\":1", "\"schemaVersion\":2")
        .replace("zhijuan.arc-window-policy.v1", "zhijuan.arc-window-policy.v2")
        .dropLast(1) + """,
        "policyCompilationHash":"$HASH_C","contextEvidenceHash":"$HASH_D",
        "chapterContracts":[
          {"chapterIndex":1,"objective":"完成第一次选择","capabilityHints":["core-narrative"],"obligationIds":["promise-1"],"prohibitedRepetitions":[]},
          {"chapterIndex":2,"objective":"承担第一章后果","capabilityHints":["core-narrative"],"obligationIds":["promise-1"],"prohibitedRepetitions":["不复述第一章选择"]}
        ]}
        """.trimIndent().replace("\n", "")

    private fun validV1() = """
      {"schemaVersion":1,"policyVersion":"zhijuan.arc-window-policy.v1","masterOutlineContentHash":"$HASH_A",
      "parentOutlineContentHash":"$HASH_B","targetChapterCount":80,
      "arc":{"arcId":"arc-1","startChapter":1,"endChapter":20,"title":"第一卷","dramaticQuestion":"主角是否接受代价",
      "openingState":"系统尚未激活","closingState":"主角承担升级代价","milestones":[{"milestoneId":"m1","chapterIndex":10,"purpose":"中点转折","consequence":"代价公开"}],"continuityConstraints":["系统升级需完成任务"]},
      "chapterWindow":{"windowId":"window-1","startChapter":1,"endChapter":2,"chapters":[
      {"chapterIndex":1,"title":"选择","goal":"激活系统","conflict":"奖励伴随代价","turn":"主角接受代价","outcome":"系统激活","hook":"新任务出现","continuityCarry":["系统一级"]},
      {"chapterIndex":2,"title":"后果","goal":"完成新任务","conflict":"代价阻碍行动","turn":"主角改变计划","outcome":"任务推进","hook":"敌人发现秘密","continuityCarry":["承诺仍未完成"]}]},"nextWindowStartChapter":3}
    """.trimIndent().replace("\n", "")

    companion object {
        private const val HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        private const val HASH_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        private const val HASH_D = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
    }
}
