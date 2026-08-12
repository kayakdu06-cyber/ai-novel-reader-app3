package app.zhijuan.feature.generation

import app.zhijuan.provider.common.GenerationParameters
import app.zhijuan.provider.common.GenerationRequest
import app.zhijuan.provider.common.ProviderConnectionProfile
import app.zhijuan.provider.common.ProviderModelId
import app.zhijuan.provider.common.ProviderProtocol
import app.zhijuan.provider.common.ProviderPrompt
import app.zhijuan.provider.common.ProviderStreamEvent
import app.zhijuan.provider.common.ProviderTimeoutPolicy
import app.zhijuan.provider.common.PromptLayer
import app.zhijuan.provider.common.PromptPart
import app.zhijuan.provider.common.SensitiveProviderText
import app.zhijuan.provider.fake.FakeProviderAdapter
import app.zhijuan.provider.fake.fakeStreamScript
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChapterPlanV2FakeProviderTest {
    @Test
    fun `fake provider streams a strictly parseable chapter plan`() = runBlocking {
        val source = validPlan()
        val adapter = FakeProviderAdapter(fakeStreamScript {
            started("fake-plan")
            structured(source.take(source.length / 2))
            structured(source.drop(source.length / 2))
            usage(120, 80)
            completed()
        })
        val request = GenerationRequest(
            requestId = "fake-plan-request",
            generationId = "fake-plan-job",
            stageId = "fake-plan-stage",
            attemptId = "fake-plan-attempt",
            modelId = ProviderModelId.from("fake-model"),
            prompt = ProviderPrompt(listOf(
                PromptPart(PromptLayer.STAGE_CONTRACT, SensitiveProviderText.from("return structured plan")),
            )),
            parameters = GenerationParameters(maxOutputTokens = 4_096),
            structuredOutputSchema = ChapterPlanOutputContractV2.providerSchema,
            stream = true,
            timeouts = ProviderTimeoutPolicy(1_000, 1_000, 1_000, 10_000),
            idempotencyKey = null,
        )
        val output = StringBuilder()
        val events = mutableListOf<ProviderStreamEvent>()
        adapter.generate(
            ProviderConnectionProfile.create("fake-connection", ProviderProtocol.OPENAI_CHAT_COMPAT, "https://fake.invalid"),
            request,
        ).collect { event ->
            events += event
            if (event is ProviderStreamEvent.StructuredDelta) event.fragment.withValue(output::append)
        }

        assertEquals(source, output.toString())
        assertTrue(ChapterPlanV2Parser().parse(output.toString().toByteArray()) is PlanningOutputValidationResult.Valid)
        assertTrue(events.last() is ProviderStreamEvent.Completed)
        assertEquals(1L, adapter.stats.snapshot().generateCalls)
    }

    private fun validPlan(): String = """
        {"schemaVersion":2,"policyVersion":"zhijuan.chapter-plan-output-policy.v2","chapterId":"chapter-1","chapterIndex":1,
        "contextContentHash":"${"a".repeat(64)}","contextSourceManifestHash":"${"b".repeat(64)}","openingState":"任务即将到期",
        "chapterGoal":"主角完成任务","closingState":"任务完成但出现代价","finalHook":"奖励触发新的代价",
        "continuityConstraints":["主角仍在城内"],"scenes":[{"sceneId":"scene-1","sequence":1,
        "purpose":"完成任务并制造后果","location":"城内","pointOfViewCharacterId":"character-1",
        "participantCharacterIds":["character-1"],"openingState":"倒计时只剩一分钟","turn":"主角发现奖励附带代价",
        "closingState":"主角接受代价","continuityCarry":["系统升到二级"],"intimacyRelevant":false,
        "requiredProcessNodes":[],"aftermath":null}],"activationHash":"${"c".repeat(64)}",
        "policyCompilationHash":"${"d".repeat(64)}","chapterObjective":"完成系统任务并承担新的后果",
        "activeCapabilityIds":["character-continuity","core-narrative","progression-system"],
        "obligationActions":[{"obligationId":"promise-1","action":"PROGRESS","plannedEvidence":"完成系统任务","nextDueChapterIndex":null}],
        "expectedStateDeltas":[{"namespace":"system","entityId":"character-1","relatedEntityId":null,"attribute":"level","oldValueJson":"1","newValueJson":"2","plannedEvidence":"完成系统任务"}],
        "prohibitedRepetitions":["不得复述上一章完整战斗"],"requiredCallbacks":["回应系统任务倒计时"],
        "sceneCauseEffect":[{"sceneId":"scene-1","cause":"任务倒计时结束","effect":"主角必须作出选择"}],
        "endHook":"奖励触发新的代价","contextEvidenceHash":"${"e".repeat(64)}"}
    """.trimIndent().replace("\n", "")
}
