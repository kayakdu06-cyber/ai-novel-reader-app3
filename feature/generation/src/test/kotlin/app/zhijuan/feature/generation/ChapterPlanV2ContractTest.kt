package app.zhijuan.feature.generation

import app.zhijuan.core.task.SceneExecutionContract
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Named
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class ChapterPlanV2ContractTest {
    @Test
    fun `v2 binds activation policy obligations state and scene causality`() {
        val parsed = ChapterPlanV2Parser().parse(validV2().toByteArray()) as PlanningOutputValidationResult.Valid
        val result = ChapterPlanV2BusinessValidator.validate(parsed.value, expectation())
        assertTrue(result is ChapterPlanV2BusinessResult.Valid)
        assertEquals(setOf("promise-1"), parsed.value.obligationActions.map { it.obligationId }.toSet())
        assertEquals("system", parsed.value.expectedStateDeltas.single().namespace)
    }

    @Test
    fun `v2 rejects missing obligations and inactive state while v1 stays readable`() {
        val invalidJson = validV2()
            .replace("\"obligationActions\":[{\"obligationId\":\"promise-1\",\"action\":\"PROGRESS\",\"plannedEvidence\":\"完成系统任务\",\"nextDueChapterIndex\":null}]", "\"obligationActions\":[]")
            .replace("\"namespace\":\"system\"", "\"namespace\":\"item\"")
        val plan = (ChapterPlanV2Parser().parse(invalidJson.toByteArray()) as PlanningOutputValidationResult.Valid).value
        val result = ChapterPlanV2BusinessValidator.validate(plan, expectation()) as ChapterPlanV2BusinessResult.Invalid
        assertTrue(result.issues.any { it.code == ChapterPlanV2IssueCode.OBLIGATION_DISAPPEARED })
        assertTrue(result.issues.any { it.code == ChapterPlanV2IssueCode.INACTIVE_STATE_NAMESPACE })

        assertTrue(ChapterPlanOutputParser().parse(validV1().toByteArray()) is PlanningOutputValidationResult.Valid)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPlans")
    fun `bound contract rejects every unsafe mismatch before commit`(
        @Suppress("UNUSED_PARAMETER") label: String,
        source: String,
    ) {
        val result = StructuredOutputValidator().validate(
            source.toByteArray(),
            BoundChapterPlanV2OutputContract(expectation()),
        )
        assertTrue(result is StructuredOutputValidationResult.Invalid)
    }

    private fun expectation() = ChapterPlanExpectationV2(
        base = ChapterPlanExpectationV1(
            chapterId = "chapter-1",
            chapterIndex = 1,
            contextContentHash = HASH_A,
            contextSourceManifestHash = HASH_B,
            knownCharacterIds = setOf("character-1"),
            confirmedAdultFictionalCharacterIds = emptySet(),
            sceneExecutionContract = SceneExecutionContract.NotApplicable,
        ),
        activationHash = HASH_C,
        policyCompilationHash = HASH_D,
        contextEvidenceHash = HASH_E,
        activeCapabilityIds = setOf("character-continuity", "core-narrative", "progression-system"),
        activeStateNamespaces = setOf("character", "system"),
        priorObligationIds = setOf("promise-1"),
    )

    private fun validV2() = validV1()
        .replace("\"schemaVersion\":1", "\"schemaVersion\":2")
        .replace("zhijuan.chapter-plan-output-policy.v1", "zhijuan.chapter-plan-output-policy.v2")
        .dropLast(1) + """,
        "activationHash":"$HASH_C",
        "policyCompilationHash":"$HASH_D",
        "chapterObjective":"完成系统任务并承担新的后果",
        "activeCapabilityIds":["character-continuity","core-narrative","progression-system"],
        "obligationActions":[{"obligationId":"promise-1","action":"PROGRESS","plannedEvidence":"完成系统任务","nextDueChapterIndex":null}],
        "expectedStateDeltas":[{"namespace":"system","entityId":"character-1","relatedEntityId":null,"attribute":"level","oldValueJson":"1","newValueJson":"2","plannedEvidence":"完成系统任务"}],
        "prohibitedRepetitions":["不得复述上一章完整战斗"],
        "requiredCallbacks":["回应系统任务倒计时"],
        "sceneCauseEffect":[{"sceneId":"scene-1","cause":"任务倒计时结束","effect":"主角必须作出选择"}],
        "endHook":"奖励触发新的代价",
        "contextEvidenceHash":"$HASH_E"}
        """.trimIndent()

    private fun validV1() = """
        {"schemaVersion":1,"policyVersion":"zhijuan.chapter-plan-output-policy.v1","chapterId":"chapter-1","chapterIndex":1,
        "contextContentHash":"$HASH_A","contextSourceManifestHash":"$HASH_B","openingState":"任务即将到期",
        "chapterGoal":"主角完成任务","closingState":"任务完成但出现代价","finalHook":"奖励触发新的代价",
        "continuityConstraints":["主角仍在城内"],"scenes":[{"sceneId":"scene-1","sequence":1,
        "purpose":"完成任务并制造后果","location":"城内","pointOfViewCharacterId":"character-1",
        "participantCharacterIds":["character-1"],"openingState":"倒计时只剩一分钟","turn":"主角发现奖励附带代价",
        "closingState":"主角接受代价","continuityCarry":["系统升到二级"],"intimacyRelevant":false,
        "requiredProcessNodes":[],"aftermath":null}]}
    """.trimIndent().replace("\n", "")

    companion object {
        private const val HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        private const val HASH_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        private const val HASH_D = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        private const val HASH_E = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"

        @JvmStatic
        fun invalidPlans(): List<Arguments> {
            val valid = ChapterPlanV2ContractTest().validV2()
            return listOf(
                Arguments.of(Named.of("schema", "schema"), valid.replace("\"schemaVersion\":2", "\"schemaVersion\":3")),
                Arguments.of(Named.of("person", "person"), valid.replace("\"character-1\"", "\"unknown-person\"")),
                Arguments.of(Named.of("activation", "activation"), valid.replace(HASH_C, "f".repeat(64))),
                Arguments.of(Named.of("obligation", "obligation"), valid.replace(
                    "\"obligationActions\":[{\"obligationId\":\"promise-1\",\"action\":\"PROGRESS\",\"plannedEvidence\":\"完成系统任务\",\"nextDueChapterIndex\":null}]",
                    "\"obligationActions\":[]",
                )),
                Arguments.of(Named.of("plan hash evidence", "plan-hash"), valid.replace(HASH_E, "0".repeat(64))),
            )
        }
    }
}
