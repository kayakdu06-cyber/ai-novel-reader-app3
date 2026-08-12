package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.InitialChapterDraftPromptSources
import app.zhijuan.provider.common.ProviderModelId
import app.zhijuan.provider.common.ProviderTimeoutPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InitialChapterDraftRequestTest {
    @Test
    fun `request uses only frozen plan context and policy sources`() {
        val bound = InitialChapterDraftRequestFactory.create(
            InitialChapterDraftRequestSpec(
                requestId = "request-1", generationId = "job-1", attemptId = "attempt-1",
                modelId = ProviderModelId.from("fake-model"), sources = sources(),
                maximumOutputTokens = 4_096,
                timeouts = ProviderTimeoutPolicy(1_000, 2_000, 2_000, 10_000),
            ),
        )
        assertTrue(bound.request.stream)
        assertEquals(ChapterDraftOutputContractV1.providerSchema.withValue { it },
            bound.request.structuredOutputSchema?.withValue { it })
        assertEquals("chapter-2", bound.chapterId)
        assertEquals(2, bound.chapterIndex)
        bound.request.prompt.withParts { parts ->
            val payload = parts.last().content.withValue { Json.parseToJsonElement(it) as JsonObject }
            assertEquals(setOf("schemaVersion", "schemaId", "chapterPlan", "chapterContext", "expectation", "activationManifest", "policyManifest"), payload.keys)
        }
    }

    private fun sources() = InitialChapterDraftPromptSources(
        stageId = "draft-stage", stageInputVersionHash = "a".repeat(64),
        chapterId = "chapter-2", chapterIndex = 2,
        canonicalPlanJson = """{"chapterId":"chapter-2","chapterIndex":2}""",
        contextPayloadJson = """{"facts":[]}""",
        expectationJson = """{"chapterId":"chapter-2"}""",
        activationManifestJson = """{"activeCapabilityIds":[]}""",
        policyManifestJson = """{"selectedFragmentIds":[]}""",
    )
}
