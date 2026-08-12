package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.ChapterPlanV2FrozenSources
import app.zhijuan.core.database.generation.ChapterPostAnalysisPromptSources
import app.zhijuan.core.database.generation.ReadyChapterContext
import app.zhijuan.core.database.memory.ForeshadowItemEntity
import app.zhijuan.core.database.memory.StoryEntity
import app.zhijuan.core.database.memory.StoryStateNamespaceV1
import app.zhijuan.core.model.AdultStatus
import app.zhijuan.core.model.DerivedDataStatus
import app.zhijuan.core.model.FadePolicy
import app.zhijuan.core.model.ForeshadowStatus
import app.zhijuan.core.model.MemorySource
import app.zhijuan.core.model.StoryEntityType
import app.zhijuan.core.task.PromptInstruction
import app.zhijuan.core.task.SceneExecutionContract
import app.zhijuan.provider.common.ProviderModelId
import app.zhijuan.provider.common.ProviderTimeoutPolicy
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PersistentChapterPostAnalysisBoundExecutorTest {
    @Test
    fun `assembler preserves mixed capabilities and strict adult fictional scene contract`() {
        val body = "林川看见苏遥走近。" + "两名成年人在场景中完成计划要求的动作、反应、状态变化和余波。".repeat(8)
        val expectation = expectation(body)
        val frozen = ChapterPlanV2FrozenSources.freeze(
            expectationJson = expectationJson(expectation),
            activationManifestJson = activationJson(expectation),
            activationHash = HASH_C,
            policyManifestJson = policyJson(),
            policyCompilationHash = HASH_D,
            contextEvidenceHash = HASH_E,
        )
        val sources = ChapterPostAnalysisPromptSources(
            stageId = "stage-analysis",
            stageInputVersionHash = HASH_A,
            stageIdempotencyKey = "idempotency-analysis",
            bookId = "book-1",
            candidateChapterVersionId = "candidate-1",
            candidateContentHash = sha256(body),
            candidateContentHashHistory = listOf(sha256(body)),
            chapterId = "chapter-1",
            chapterIndex = 1,
            revisionIndex = 0,
            routeBindingHash = null,
            bodyText = body,
            canonicalPlanJson = planJson(body),
            context = ReadyChapterContext(
                contextSnapshotId = "context-1",
                contextStageId = "context-stage",
                chapterPlanStageId = "plan-stage",
                providerPayloadJson = contextJson(),
                contentHash = HASH_A,
                sourceManifestHash = HASH_B,
                selectedItemCount = 1,
                omittedItemCount = 0,
                estimatedInputTokens = 100,
                inputBudgetTokens = 1_000,
                replayed = true,
            ),
            frozenPlan = frozen,
            knownEntities = listOf(
                character("character-1", "林川"),
                character("character-2", "苏遥"),
            ),
            priorForeshadows = listOf(foreshadow()),
            expectedCurrentVersionId = null,
        )

        val assembled = ChapterPostAnalysisBoundRequestAssemblerV1.assemble(
            source = sources,
            requestId = "request-1",
            generationId = "job-1",
            attemptId = "attempt-1",
            modelId = ProviderModelId.from("fake-model"),
            maximumOutputTokens = 4_096,
            timeouts = ProviderTimeoutPolicy(1_000, 1_000, 1_000, 1_000),
            minimumBodyCodePoints = 1,
        )

        assertEquals(
            setOf(
                StoryStateNamespaceV1.WORLD,
                StoryStateNamespaceV1.CHARACTER,
                StoryStateNamespaceV1.RELATIONSHIP,
                StoryStateNamespaceV1.SYSTEM,
                StoryStateNamespaceV1.ITEM,
            ),
            assembled.requestSpec.narrative.activeNamespaces,
        )
        assertEquals(setOf("character-1", "character-2"), assembled.sceneParticipantEntityIds)
        assertTrue(assembled.knownEntities.none { it.realIdentifiablePerson })
        assertEquals(setOf("promise-1"), assembled.requestSpec.narrative.priorObligations.map { it.obligationId }.toSet())
        val prepared = ChapterPostAnalysisRequestFactoryV1.prepare(assembled.requestSpec)
        assertTrue(prepared is ChapterPostAnalysisRequestPreparationV1.Ready)
        val ready = prepared as ChapterPostAnalysisRequestPreparationV1.Ready
        assertFalse(ready.boundRequest.request.prompt.toString().contains(body))
    }

    private fun expectation(body: String) = ChapterPlanExpectationV2(
        base = ChapterPlanExpectationV1(
            chapterId = "chapter-1",
            chapterIndex = 1,
            contextContentHash = HASH_A,
            contextSourceManifestHash = HASH_B,
            knownCharacterIds = setOf("character-1", "character-2"),
            confirmedAdultFictionalCharacterIds = setOf("character-1", "character-2"),
            sceneExecutionContract = SceneExecutionContract.Allowed(
                automatic = true,
                intimacyDetailLevel = 4,
                fadePolicy = FadePolicy.AVOID,
                strictBodyAndSensoryContinuity = true,
                requiredKeyProcessCoveragePercent = 100,
                fadeSubstitutionAllowed = false,
                requiresStateContinuity = true,
                requiresRelevantAftermath = true,
                instructions = listOf(PromptInstruction("scene-continuity", "保持过程、身体、感官、状态和余波连续。")),
            ),
        ),
        activationHash = HASH_C,
        policyCompilationHash = HASH_D,
        contextEvidenceHash = HASH_E,
        activeCapabilityIds = setOf(
            "core-narrative", "character-continuity", "relationship-progression",
            "progression-system", "item-progression", "mystery", "faction-politics",
            "intimacy-continuity", "romance",
        ),
        activeStateNamespaces = setOf(
            "narrative", "character", "relationship", "system", "item",
            "mystery", "faction", "intimacy", "romance",
        ),
        priorObligationIds = setOf("promise-1"),
    )

    private fun expectationJson(value: ChapterPlanExpectationV2) = """
        {"activationHash":"$HASH_C","activeCapabilityIds":["character-continuity","core-narrative","faction-politics","intimacy-continuity","item-progression","mystery","progression-system","relationship-progression","romance"],"activeStateNamespaces":["character","faction","intimacy","item","mystery","narrative","relationship","romance","system"],"chapterId":"chapter-1","chapterIndex":1,"confirmedAdultFictionalCharacterIds":["character-1","character-2"],"contextContentHash":"$HASH_A","contextEvidenceHash":"$HASH_E","contextSourceManifestHash":"$HASH_B","creativeIntent":"开放组合测试","knownCharacterIds":["character-1","character-2"],"policyCompilationHash":"$HASH_D","priorObligationIds":["promise-1"],"sceneExecutionContract":{"automatic":true,"fadePolicy":"AVOID","fadeSubstitutionAllowed":false,"instructions":[{"id":"scene-continuity","text":"保持过程、身体、感官、状态和余波连续。"}],"intimacyDetailLevel":4,"kind":"ALLOWED","requiredKeyProcessCoveragePercent":100,"requiresRelevantAftermath":true,"requiresStateContinuity":true,"strictBodyAndSensoryContinuity":true},"schemaVersion":2}
    """.trimIndent()

    private fun activationJson(value: ChapterPlanExpectationV2) = """
        {"activationHash":"$HASH_C","activeCapabilityIds":["character-continuity","core-narrative","faction-politics","intimacy-continuity","item-progression","mystery","progression-system","relationship-progression","romance"],"expectedStateNamespaceIds":["character","faction","intimacy","item","mystery","narrative","relationship","romance","system"],"phase":"BUILD_CHAPTER_PLAN","requestBindingHash":"$HASH_F","requiredPolicyFragmentIds":["continuity.core"],"schemaVersion":1,"sourceManifestHash":"$HASH_G"}
    """.trimIndent()

    private fun policyJson() = """
        {"instructions":[{"id":"continuity.core","text":"保持连续。"}],"policyCompilationHash":"$HASH_D","policyPackChecksum":"$HASH_H","policyPackId":"builtin","policyPackVersion":"1","promptBundleVersion":"bundle-1","schemaVersion":1,"selectedFragmentIds":["continuity.core"]}
    """.trimIndent()

    private fun planJson(body: String) = """
        {"schemaVersion":2,"policyVersion":"zhijuan.chapter-plan-output-policy.v2","chapterId":"chapter-1","chapterIndex":1,"contextContentHash":"$HASH_A","contextSourceManifestHash":"$HASH_B","openingState":"两人相遇","chapterGoal":"推进关系并触发系统和道具变化","closingState":"关系和状态已改变","finalHook":"新线索出现","continuityConstraints":["人物和道具状态连续"],"scenes":[{"sceneId":"scene-1","sequence":1,"purpose":"推进关系并产生后果","location":"室内","pointOfViewCharacterId":"character-1","participantCharacterIds":["character-1","character-2"],"openingState":"两人靠近","turn":"系统任务触发","closingState":"关系改变","continuityCarry":["记录关系与系统变化"],"intimacyRelevant":true,"requiredProcessNodes":[{"nodeId":"process-1","sequence":1,"action":"动作发生","reaction":"角色回应","spatialStateAfter":"位置连续","bodyStateAfter":"身体状态连续","clothingAndObjectStateAfter":"物品状态连续","sensoryChange":"感官连续"},{"nodeId":"process-2","sequence":2,"action":"状态推进","reaction":"角色确认","spatialStateAfter":"位置继续连续","bodyStateAfter":"身体状态继续连续","clothingAndObjectStateAfter":"物品状态继续连续","sensoryChange":"感官变化连续"},{"nodeId":"process-3","sequence":3,"action":"过程完成","reaction":"角色承受后果","spatialStateAfter":"结束位置明确","bodyStateAfter":"结束身体状态明确","clothingAndObjectStateAfter":"结束物品状态明确","sensoryChange":"结束感官状态明确"}],"aftermath":"关系和身体状态影响后续"}],"activationHash":"$HASH_C","policyCompilationHash":"$HASH_D","chapterObjective":"推进混合剧情并留下因果","activeCapabilityIds":["character-continuity","core-narrative","faction-politics","intimacy-continuity","item-progression","mystery","progression-system","relationship-progression","romance"],"obligationActions":[{"obligationId":"promise-1","action":"PROGRESS","plannedEvidence":"兑现此前承诺","nextDueChapterIndex":2}],"expectedStateDeltas":[{"namespace":"system","entityId":"character-1","relatedEntityId":null,"attribute":"level","oldValueJson":"1","newValueJson":"2","plannedEvidence":"系统任务完成"},{"namespace":"item","entityId":"item-1","relatedEntityId":null,"attribute":"owner","oldValueJson":"character-1","newValueJson":"character-2","plannedEvidence":"道具发生转移"}],"prohibitedRepetitions":[],"requiredCallbacks":[],"sceneCauseEffect":[{"sceneId":"scene-1","cause":"承诺和系统任务同时生效","effect":"关系、系统和道具状态变化"}],"endHook":"新线索出现","contextEvidenceHash":"$HASH_E"}
    """.trimIndent()

    private fun contextJson() = """
        {"schemaVersion":1,"policyVersion":"zhijuan.chapter-context-budget.v1","targetChapterIndex":1,"layers":[{"itemId":"fact-1","kind":"BIBLE_HARD_FACT","content":"{\"fact\":\"两名角色均为虚构成年人\"}"}]}
    """.trimIndent()

    private fun character(id: String, name: String) = StoryEntity(
        entityId = id,
        bookId = "book-1",
        entityType = StoryEntityType.CHARACTER,
        canonicalName = name,
        aliasesJson = "[]",
        stableDefinitionJson = "{}",
        adultStatus = AdultStatus.CONFIRMED_ADULT,
        ageYears = 24,
        sourceBibleRevisionId = "bible-1",
        createdAt = 1,
        updatedAt = 1,
    )

    private fun foreshadow() = ForeshadowItemEntity(
        foreshadowItemId = "clue-1",
        bookId = "book-1",
        description = "尚未解释的线索",
        foreshadowStatus = ForeshadowStatus.PLANTED,
        memoryStatus = DerivedDataStatus.VALID,
        targetStartChapterIndex = 2,
        targetEndChapterIndex = 4,
        sourceChapterVersionId = null,
        plantedChapterVersionId = null,
        resolvedChapterVersionId = null,
        visibleEntityIdsJson = "[\"character-1\"]",
        importance = 80,
        source = MemorySource.STORY_BIBLE,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        private const val HASH_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        private const val HASH_D = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        private const val HASH_E = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        private const val HASH_F = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
        private const val HASH_G = "1111111111111111111111111111111111111111111111111111111111111111"
        private const val HASH_H = "2222222222222222222222222222222222222222222222222222222222222222"
    }
}
