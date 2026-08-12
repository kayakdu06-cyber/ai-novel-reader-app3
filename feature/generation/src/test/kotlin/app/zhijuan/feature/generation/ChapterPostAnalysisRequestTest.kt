package app.zhijuan.feature.generation

import app.zhijuan.core.database.memory.NarrativeObligationV1
import app.zhijuan.core.database.memory.StoryStateKeyV1
import app.zhijuan.core.database.memory.StoryStateNamespaceV1
import app.zhijuan.core.model.AdultStatus
import app.zhijuan.core.model.ConsistencyCriterionV1
import app.zhijuan.core.model.StoryEntityType
import app.zhijuan.core.task.ChapterDeterministicConsistencyFactsV1
import app.zhijuan.core.task.SceneExecutionContract
import app.zhijuan.provider.common.PromptLayer
import app.zhijuan.provider.common.ProviderModelId
import app.zhijuan.provider.common.ProviderTimeoutPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChapterPostAnalysisRequestTest {
    @Test
    fun `factory produces one frozen request for every post analysis capability`() {
        val prepared = ChapterPostAnalysisRequestFactoryV1.prepare(spec())

        assertTrue(prepared is ChapterPostAnalysisRequestPreparationV1.Ready)
        val bound = (prepared as ChapterPostAnalysisRequestPreparationV1.Ready).boundRequest
        assertEquals(ChapterPostAnalysisOutputContractV1.providerSchema.withValue { it },
            bound.request.structuredOutputSchema?.withValue { it })
        assertEquals(1, bound.request.prompt.withParts { parts ->
            parts.count { it.layer == PromptLayer.USER_REQUEST }
        })
        val source = bound.request.prompt.withParts { parts ->
            parts.single { it.layer == PromptLayer.USER_REQUEST }.content.withValue {
                Json.parseToJsonElement(it).jsonObject
            }
        }
        assertTrue("memorySource" in source && "trackingExpectation" in source)
        assertTrue("consistencySource" in source && "narrativeExpectation" in source)
    }

    @Test
    fun `local severe repetition prevents any remote post analysis request`() {
        val repeated = "这是足够长的重复段落，用于证明本地检查能在打开远程请求之前阻止严重重复剧情。".repeat(3)
        val result = ChapterPostAnalysisRequestFactoryV1.prepare(spec("$repeated\n\n$repeated"))

        assertTrue(result is ChapterPostAnalysisRequestPreparationV1.LocalRevisionRequired)
    }

    private fun spec(content: String = DEFAULT_BODY): ChapterPostAnalysisRequestSpecV1 {
        val hash = content.sha256()
        val memory = ChapterMemoryExtractionRequestSpec(
            requestId = "request-1", generationId = "generation-1", stageId = "stage-1", attemptId = "attempt-1",
            modelId = ProviderModelId.from("fake-model"), sourceChapterVersionId = "candidate-1",
            sourceChapterContentHash = hash, chapterId = "chapter-1", chapterIndex = 1,
            chapterContent = content, knownEntities = listOf(
                ChapterMemoryKnownEntity("character-1", "主角", StoryEntityType.CHARACTER, AdultStatus.CONFIRMED_ADULT),
            ), maximumOutputTokens = 4_096, timeouts = TIMEOUTS,
        )
        val consistency = ChapterConsistencyCheckRequestSpec(
            requestId = memory.requestId, generationId = memory.generationId, stageId = memory.stageId,
            attemptId = memory.attemptId, modelId = memory.modelId,
            sourceChapterVersionId = memory.sourceChapterVersionId, sourceChapterContentHash = hash,
            chapterId = memory.chapterId, chapterIndex = memory.chapterIndex, chapterContent = content,
            minimumBodyCodePoints = 1, deterministicFacts = ChapterDeterministicConsistencyFactsV1(
                currentChapterIndex = 1, expectedChapterIndex = 1,
                entities = listOf(app.zhijuan.core.task.DeterministicEntityFactV1(
                    "character-1", StoryEntityType.CHARACTER, AdultStatus.CONFIRMED_ADULT, 24,
                )), references = emptyList(), characterReturns = emptyList(), locationConstraints = emptyList(),
                itemOwnershipConstraints = emptyList(), timelineConstraints = emptyList(), requiredEvents = emptyList(),
            ), sceneExecutionContract = SceneExecutionContract.NotApplicable,
            sceneParticipantEntityIds = emptySet(), requiredProcessNodeIds = emptySet(),
            knownEntities = listOf(ChapterConsistencyKnownEntityV1(
                "character-1", "主角", StoryEntityType.CHARACTER, AdultStatus.CONFIRMED_ADULT, 24, false,
            )), evidenceItems = emptyList(), maximumOutputTokens = 4_096, timeouts = TIMEOUTS,
        )
        val tracking = ChapterTrackingExpectation(
            sourceChapterVersionId = memory.sourceChapterVersionId, sourceChapterContentHash = hash,
            chapterId = memory.chapterId, chapterIndex = 1, memorySnapshotHash = HASH_B,
            priorForeshadowSnapshotHash = HASH_C, knownEntitySnapshotHash = HASH_D,
            knownEntities = mapOf("character-1" to StoryEntityType.CHARACTER), priorForeshadows = emptyMap(),
        )
        return ChapterPostAnalysisRequestSpecV1(
            memory = memory, trackingExpectation = tracking, consistency = consistency,
            narrative = ChapterPostAnalysisNarrativeExpectationV1(
                activeNamespaces = setOf(StoryStateNamespaceV1.SYSTEM),
                priorObligations = listOf(NarrativeObligationV1("obligation-1", "完成任务", 2)),
                currentStateValues = mapOf(
                    StoryStateKeyV1(StoryStateNamespaceV1.SYSTEM, "character-1", "level") to "1",
                ),
            ),
        )
    }

    private fun String.sha256() = java.security.MessageDigest.getInstance("SHA-256")
        .digest(toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val DEFAULT_BODY = "主角完成任务，系统提示等级提升，并记住下一章仍需处理的代价。"
        private const val HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        private const val HASH_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        private const val HASH_D = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        private val TIMEOUTS = ProviderTimeoutPolicy(1_000, 1_000, 1_000, 1_000)
    }
}
