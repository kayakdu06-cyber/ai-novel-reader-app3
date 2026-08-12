package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.ChapterPlanV2FrozenSources
import app.zhijuan.core.database.generation.ReadyChapterContext
import app.zhijuan.core.model.FadePolicy
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.RelevantCharacterAdultGate
import app.zhijuan.core.task.PromptInstruction
import app.zhijuan.core.task.SceneExecutionContract
import app.zhijuan.provider.common.ProviderModelId
import app.zhijuan.provider.common.ProviderTimeoutPolicy
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChapterPlanV2RequestTest {
    @Test
    fun `factory freezes matching expectation activation policy and context`() {
        val routing = routing()
        val policyHash = ChapterPlanV2RequestFactory.policyCompilationHash(routing)
        val context = """{"chapterId":"chapter-2","facts":["fact-1"]}"""
        val request = ChapterPlanV2RequestFactory.create(spec(routing, policyHash, context))

        assertTrue(request.request.stream)
        assertEquals(ChapterPlanOutputContractV2.providerSchema.withValue { it },
            request.request.structuredOutputSchema?.withValue { it })
        assertEquals(request.activationHash, request.expectation.activationHash)
        assertEquals(request.policyCompilationHash, request.expectation.policyCompilationHash)
        assertNotEquals(request.policyManifestHash, request.policyCompilationHash)
        assertTrue(request.policyManifestJson.contains("\"policyCompilationHash\":\"$policyHash\""))
        assertFalse(request.requestBindingHash == request.expectationHash)
        Json.parseToJsonElement(request.expectationJson) as JsonObject
        Json.parseToJsonElement(request.activationManifestJson) as JsonObject
        Json.parseToJsonElement(request.policyManifestJson) as JsonObject
    }

    @Test
    fun `factory rejects a policy hash not produced by selected policy`() {
        val routing = routing()
        assertThrows(IllegalArgumentException::class.java) {
            ChapterPlanV2RequestFactory.create(spec(routing, "f".repeat(64), "{}"))
        }
    }

    @Test
    fun `frozen request restores exact prompt and sensitive scene contract`() {
        val routing = routing()
        val policyHash = ChapterPlanV2RequestFactory.policyCompilationHash(routing)
        val contextJson = """{"chapterId":"chapter-2","facts":["fact-1"]}"""
        val sceneContract = SceneExecutionContract.Allowed(
            automatic = true,
            intimacyDetailLevel = 4,
            fadePolicy = FadePolicy.AVOID,
            strictBodyAndSensoryContinuity = true,
            requiredKeyProcessCoveragePercent = 100,
            fadeSubstitutionAllowed = false,
            requiresStateContinuity = true,
            requiresRelevantAftermath = true,
            instructions = listOf(PromptInstruction("scene.continuity", "保持身体、感官、状态与后果连续。")),
        )
        val baseSpec = spec(routing, policyHash, contextJson)
        val sourceSpec = baseSpec.copy(
            expectation = baseSpec.expectation.copy(
                base = baseSpec.expectation.base.copy(
                    confirmedAdultFictionalCharacterIds = setOf("character-1"),
                    sceneExecutionContract = sceneContract,
                ),
            ),
            idempotencyKey = "idempotency-1",
        )
        val created = ChapterPlanV2RequestFactory.create(sourceSpec)
        val frozen = ChapterPlanV2FrozenSources.freeze(
            expectationJson = created.expectationJson,
            activationManifestJson = created.activationManifestJson,
            activationHash = created.activationHash,
            policyManifestJson = created.policyManifestJson,
            policyCompilationHash = created.policyCompilationHash,
            contextEvidenceHash = created.contextEvidenceHash,
        )
        val restored = ChapterPlanV2RequestFactory.restore(FrozenChapterPlanV2RequestSpec(
            requestId = sourceSpec.requestId,
            generationId = sourceSpec.generationId,
            stageId = sourceSpec.stageId,
            attemptId = sourceSpec.attemptId,
            modelId = sourceSpec.modelId,
            context = ReadyChapterContext(
                contextSnapshotId = "context-snapshot-1",
                contextStageId = "context-stage-1",
                chapterPlanStageId = sourceSpec.stageId,
                providerPayloadJson = contextJson,
                contentHash = sourceSpec.contextContentHash,
                sourceManifestHash = sourceSpec.contextSourceManifestHash,
                selectedItemCount = 1,
                omittedItemCount = 0,
                estimatedInputTokens = 32,
                inputBudgetTokens = 1_024,
                replayed = true,
            ),
            frozen = frozen,
            maximumOutputTokens = sourceSpec.maximumOutputTokens,
            timeouts = sourceSpec.timeouts,
            idempotencyKey = sourceSpec.idempotencyKey,
        ))

        assertEquals(created.expectation, restored.expectation)
        assertEquals(sceneContract, restored.expectation.base.sceneExecutionContract)
        assertEquals(created.requestBindingHash, restored.requestBindingHash)
        assertEquals(created.expectationHash, restored.expectationHash)
        assertEquals(created.activationManifestHash, restored.activationManifestHash)
        assertEquals(created.policyManifestHash, restored.policyManifestHash)
        assertEquals(promptParts(created), promptParts(restored))
    }

    private fun routing(): ChapterPromptPolicySelectionV1 {
        val source = CreationSnapshotIntentSourceV1(
            sourceContentHash = "1".repeat(64),
            rawInputJson = """{"storyIdea":"一名成年人寻找失物","requestedGenreId":null,"advancedDetails":{}}""",
            normalizedInputJson = """{"storyIdea":"一名成年人寻找失物","requestedGenreId":null,"advancedDetails":{}}""",
        )
        val book = BookCapabilityRouterV1.derive(source)
        return (ChapterCapabilityRouterV1.activate(book, ChapterCapabilityRequestV1(
            phase = GenerationPhase.BUILD_CHAPTER_PLAN,
            chapterTaskText = "找到第一条线索",
            availablePolicyPromptChars = 20_000,
            adultGate = RelevantCharacterAdultGate.UNKNOWN,
        )) as ChapterCapabilityRoutingDecisionV1.Ready).selection
    }

    private fun spec(
        routing: ChapterPromptPolicySelectionV1,
        policyHash: String,
        context: String,
    ): ChapterPlanV2RequestSpec {
        val base = ChapterPlanExpectationV1(
            chapterId = "chapter-2", chapterIndex = 2,
            contextContentHash = sha256(context), contextSourceManifestHash = "2".repeat(64),
            knownCharacterIds = setOf("character-1"),
            confirmedAdultFictionalCharacterIds = emptySet(),
            sceneExecutionContract = SceneExecutionContract.NotApplicable,
        )
        return ChapterPlanV2RequestSpec(
            requestId = "request-1", generationId = "job-1", stageId = "plan-stage",
            attemptId = "attempt-1", modelId = ProviderModelId.from("fake-model"),
            contextPayloadJson = context, contextContentHash = base.contextContentHash,
            contextSourceManifestHash = base.contextSourceManifestHash,
            contextEvidenceHash = "3".repeat(64),
            expectation = ChapterPlanExpectationV2(
                base = base, activationHash = routing.activation.activationHash,
                policyCompilationHash = policyHash, contextEvidenceHash = "3".repeat(64),
                activeCapabilityIds = routing.activation.activeCapabilityIds,
                activeStateNamespaces = routing.activation.expectedStateNamespaceIds,
                priorObligationIds = emptySet(),
            ),
            policySelection = routing, maximumOutputTokens = 4_096,
            timeouts = ProviderTimeoutPolicy(1_000, 2_000, 2_000, 10_000),
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun promptParts(request: BoundChapterPlanV2Request): List<Pair<String, String>> =
        request.request.prompt.withParts { parts ->
            parts.map { part -> part.layer.name to part.content.withValue { it } }
        }
}
