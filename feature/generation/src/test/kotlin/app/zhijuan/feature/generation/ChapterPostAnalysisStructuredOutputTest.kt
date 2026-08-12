package app.zhijuan.feature.generation

import app.zhijuan.core.database.memory.StoryStateNamespaceV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChapterPostAnalysisStructuredOutputTest {
    @Test
    fun `mixed output parses obligations system item relationship and body evidence as one result`() {
        val result = ChapterPostAnalysisOutputParser().parse(validOutput().toByteArray())

        assertTrue(result is PlanningOutputValidationResult.Valid)
        val analysis = (result as PlanningOutputValidationResult.Valid).value
        assertEquals("obligation-1", analysis.completedAndOpenObligations.single().obligationId)
        assertEquals(
            setOf(StoryStateNamespaceV1.SYSTEM, StoryStateNamespaceV1.ITEM, StoryStateNamespaceV1.RELATIONSHIP),
            analysis.storyStateDeltas.map { it.key.namespace }.toSet(),
        )
        assertEquals(4, analysis.evidenceBindings.size)
        assertFalse(analysis.severeRevisionRequired)
    }

    @Test
    fun `one invalid sub block rejects the entire output without exposing partial result`() {
        val invalid = validOutput().replace(
            "\"newValueJson\":\"2\",\"evidence\":\"正文中系统提示升级\"",
            "\"evidence\":\"正文中系统提示升级\"",
        )

        val result = ChapterPostAnalysisOutputParser().parse(invalid.toByteArray())

        assertTrue(result is PlanningOutputValidationResult.Invalid)
        val report = (result as PlanningOutputValidationResult.Invalid).report
        assertTrue(report.issues.any { it.path == "$.storyStateDeltas[0].newValueJson" })
    }

    @Test
    fun `severe repetition requires revision and bound evidence`() {
        val severe = validOutput()
            .replace(
                "\"repetitionFindings\":[]",
                "\"repetitionFindings\":[{\"findingId\":\"repeat-1\",\"firstStartCodePointInclusive\":10,\"firstEndCodePointExclusive\":20,\"repeatedStartCodePointInclusive\":30,\"repeatedEndCodePointExclusive\":40,\"severity\":\"MAJOR\",\"repairAction\":\"REMOVE_DUPLICATION\"}]",
            )
            .replace("\"severeRevisionRequired\":false", "\"severeRevisionRequired\":true")
            .replace(
                "\"endCodePointExclusive\":36}]}",
                "\"endCodePointExclusive\":36},{\"bindingId\":\"bind-repeat\",\"subject\":\"REPETITION_FINDING\",\"subjectIndex\":0,\"startCodePointInclusive\":30,\"endCodePointExclusive\":40}]}",
            )

        val result = ChapterPostAnalysisOutputParser().parse(severe.toByteArray())

        assertTrue(result is PlanningOutputValidationResult.Valid)
        assertTrue((result as PlanningOutputValidationResult.Valid).value.severeRevisionRequired)
        assertEquals(1, result.value.repetitionFindings.size)
    }

    private fun validOutput() = """
        {"schemaVersion":1,"sourceChapterVersionId":"candidate-1","sourceChapterContentHash":"$HASH_A","chapterId":"chapter-1","chapterIndex":1,
        "checkSourceSnapshotHash":"$HASH_B","sceneContractHash":"$HASH_C",
        "summary":{"objectiveOutcome":"主角完成任务并承担后果","keyEvents":["系统升级"],"decisions":["接受代价"],"relationshipChanges":["双方建立信任"],"endingState":"新任务开启","unresolvedQuestions":["代价来源"],"importance":90},
        "entityEvents":[{"entityId":"character-1","attribute":"RELATIONSHIP","relatedEntityId":"character-2","oldValue":"陌生","newValue":"信任","storyTimeExpression":"当夜","confidenceMicros":1000000,"canonLevel":"STORY_CANON","evidence":"两人在事后明确结盟"},{"entityId":"character-1","attribute":"POSSESSION","relatedEntityId":null,"oldValue":null,"newValue":"持有钥匙","storyTimeExpression":"当夜","confidenceMicros":1000000,"canonLevel":"STORY_CANON","evidence":"正文写明主角收起钥匙"}],
        "canonFacts":[{"factKind":"DISCOVERY","entityId":"character-1","text":"系统升级需要完成任务","canonLevel":"STORY_CANON","confidenceMicros":1000000,"conflictGroupId":"system-rule"}],
        "timelineEvents":[{"name":"完成系统任务","participantEntityIds":["character-1","character-2"],"locationEntityId":null,"storyTimeExpression":"当夜","constraints":["发生在升级之前"],"evidence":"正文先写完成任务再显示升级"}],
        "foreshadowTransitions":[],
        "completedAndOpenObligations":[{"obligationId":"obligation-1","action":"PROGRESS","evidence":"主角取得关键线索","nextDueChapterIndex":null}],
        "storyStateDeltas":[{"namespace":"SYSTEM","entityId":"character-1","attribute":"level","relatedEntityId":null,"oldValueJson":"1","newValueJson":"2","evidence":"正文中系统提示升级"},{"namespace":"ITEM","entityId":"item-key","attribute":"owner","relatedEntityId":null,"oldValueJson":"null","newValueJson":"\"character-1\"","evidence":"正文写明主角收起钥匙"},{"namespace":"RELATIONSHIP","entityId":"character-1","attribute":"trust","relatedEntityId":"character-2","oldValueJson":"0","newValueJson":"1","evidence":"双方明确结盟"}],
        "repetitionFindings":[],"consistencyFindings":[],"presentationFindings":[],
        "criterionResults":[{"criterion":"BASIC_READABILITY","status":"PASS","issueIds":[]}],"requiredProcessResults":[],"severeRevisionRequired":false,
        "evidenceBindings":[{"bindingId":"bind-obligation","subject":"OBLIGATION","subjectIndex":0,"startCodePointInclusive":1,"endCodePointExclusive":9},{"bindingId":"bind-system","subject":"STORY_STATE_DELTA","subjectIndex":0,"startCodePointInclusive":10,"endCodePointExclusive":18},{"bindingId":"bind-item","subject":"STORY_STATE_DELTA","subjectIndex":1,"startCodePointInclusive":19,"endCodePointExclusive":27},{"bindingId":"bind-relationship","subject":"STORY_STATE_DELTA","subjectIndex":2,"startCodePointInclusive":28,"endCodePointExclusive":36}]}
    """.trimIndent().replace("\n", "")

    companion object {
        private const val HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        private const val HASH_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    }
}
