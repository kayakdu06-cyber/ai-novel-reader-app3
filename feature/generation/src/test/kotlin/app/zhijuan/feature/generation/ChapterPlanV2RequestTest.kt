package app.zhijuan.feature.generation

import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.RelevantCharacterAdultGate
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
}
