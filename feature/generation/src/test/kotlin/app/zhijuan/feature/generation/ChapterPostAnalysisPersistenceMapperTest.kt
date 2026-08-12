package app.zhijuan.feature.generation

import app.zhijuan.core.database.memory.NarrativeObligationV1
import app.zhijuan.core.database.memory.StoryStateKeyV1
import app.zhijuan.core.database.memory.StoryStateNamespaceV1
import app.zhijuan.core.model.ConsistencyCriterionV1
import app.zhijuan.core.model.StoryEntityType
import app.zhijuan.core.task.ChapterConsistencyPolicyDecisionV1
import app.zhijuan.core.task.ChapterConsistencyPolicyV1
import app.zhijuan.core.task.ChapterLocalConsistencyCheckerV1
import app.zhijuan.core.task.ChapterLocalConsistencyInput
import app.zhijuan.core.task.SceneExecutionContract
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChapterPostAnalysisPersistenceMapperTest {
    @Test
    fun `one validated analysis maps every state family into existing atomic commit rows`() {
        val parsed = ChapterPostAnalysisOutputParser().parse(
            ChapterPostAnalysisStructuredOutputTest().validOutput().toByteArray(),
        ) as PlanningOutputValidationResult.Valid
        val body = "主角完成任务，系统升到二级，收起钥匙，并与同伴明确建立信任。"
        val bodyHash = body.sha256()
        val scene = (ChapterConsistencyPolicyV1.resolve(SceneExecutionContract.NotApplicable) as
            ChapterConsistencyPolicyDecisionV1.Ready).contract
        val expectation = ChapterPostAnalysisExpectationV1(
            memory = ChapterMemoryExtractionExpectation(
                "candidate-1", bodyHash, "chapter-1", 1,
                setOf("character-1", "character-2", "item-key"),
            ),
            tracking = ChapterTrackingExpectation(
                "candidate-1", bodyHash, "chapter-1", 1, HASH_B, HASH_C, HASH_D,
                mapOf(
                    "character-1" to StoryEntityType.CHARACTER,
                    "character-2" to StoryEntityType.CHARACTER,
                    "item-key" to StoryEntityType.ITEM,
                ),
                emptyMap(),
            ),
            consistency = ChapterConsistencyExpectation(
                "candidate-1", bodyHash, "chapter-1", 1, HASH_E, scene.contractHash,
                body.codePointCount(0, body.length), scene.expectedCriteria,
                setOf("character-1", "character-2", "item-key"), emptySet(), emptySet(),
            ),
            narrative = ChapterPostAnalysisNarrativeExpectationV1(
                activeNamespaces = setOf(
                    StoryStateNamespaceV1.SYSTEM,
                    StoryStateNamespaceV1.ITEM,
                    StoryStateNamespaceV1.RELATIONSHIP,
                ),
                priorObligations = listOf(NarrativeObligationV1("obligation-1", "取得线索", 2)),
                currentStateValues = mapOf(
                    StoryStateKeyV1(StoryStateNamespaceV1.SYSTEM, "character-1", "level") to "1",
                    StoryStateKeyV1(StoryStateNamespaceV1.ITEM, "item-key", "owner") to "null",
                    StoryStateKeyV1(
                        StoryStateNamespaceV1.RELATIONSHIP, "character-1", "trust", "character-2",
                    ) to "0",
                ),
            ),
        )
        val analysis = parsed.value.copy(
            sourceChapterContentHash = bodyHash,
            checkSourceSnapshotHash = HASH_E,
            sceneContractHash = scene.contractHash,
            criterionResults = scene.expectedCriteria.map {
                ConsistencyCriterionResultV1(it, ConsistencyCriterionStatusV1.PASS, emptyList())
            },
        )
        val local = ChapterLocalConsistencyCheckerV1.check(
            ChapterLocalConsistencyInput(body, bodyHash, minimumBodyCodePoints = 1),
        )

        val mapped = ChapterPostAnalysisPersistenceMapperV1.map(
            analysis,
            ChapterPostAnalysisMappingSpecV1(
                bookId = "book-1", generationStageId = "analysis-stage", modelSnapshotJson = "{}",
                createdAt = 100, expectation = expectation, localReport = local, sceneContract = scene,
            ),
        )

        assertEquals(5, mapped.memory.entityEvents.size)
        assertEquals(2, mapped.memory.canonFacts.size)
        assertEquals(setOf("system.level", "item.owner", "relationship.character-2.trust"),
            mapped.memory.entityEvents.takeLast(3).map { it.attributeKey }.toSet())
        assertTrue(mapped.memory.canonFacts.last().factPayloadJson.contains(bodyHash))
        assertEquals(ChapterConsistencyGateDecisionV1.ACCEPT_CANDIDATE, mapped.consistency.gate.decision)
    }

    private fun String.sha256() = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        private const val HASH_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        private const val HASH_D = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        private const val HASH_E = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
    }
}
