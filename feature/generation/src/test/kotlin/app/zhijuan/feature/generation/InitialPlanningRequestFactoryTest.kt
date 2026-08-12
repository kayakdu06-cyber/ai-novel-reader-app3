package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.InitialPlanningPromptSources
import app.zhijuan.core.model.BookLengthMode
import app.zhijuan.core.model.BookPresentationPreset
import app.zhijuan.core.model.ContentPresentationMappingV1
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenreContentDimensionBaseline
import app.zhijuan.core.task.PromptBundleCatalogV1
import app.zhijuan.core.task.PromptBundleSourceBinding
import app.zhijuan.provider.common.PromptLayer
import app.zhijuan.provider.common.ProviderModelId
import app.zhijuan.provider.common.ProviderTimeoutPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InitialPlanningRequestFactoryTest {
    @Test
    fun `factory binds each planning phase to exact schema and predecessor layer`() {
        val phases = listOf(
            GenerationPhase.BUILD_STORY_SEED to StorySeedOutputContractV1.schemaId,
            GenerationPhase.BUILD_BIBLE to StoryBibleOutputContractV1.schemaId,
            GenerationPhase.BUILD_MASTER_OUTLINE to MasterOutlineOutputContractV1.schemaId,
        )
        phases.forEach { (phase, schemaId) ->
            val bound = InitialPlanningRequestFactory.create(
                source = source(phase),
                requestId = "request-$phase",
                attemptId = "attempt-$phase",
                modelId = ProviderModelId.from("fixture-model"),
                maximumOutputTokens = 4_096,
                timeouts = ProviderTimeoutPolicy(1_000, 2_000, 2_000, 4_000),
            )
            assertEquals(schemaId, bound.contract.schemaId)
            assertTrue(bound.request.stream.not())
            assertTrue(
                bound.request.structuredOutputSchema?.withValue { actual ->
                    bound.contract.providerSchema.withValue { expected -> actual == expected }
                } == true,
            )
            val layers = bound.request.prompt.withParts { it.map { part -> part.layer } }
            assertEquals(phase == GenerationPhase.BUILD_MASTER_OUTLINE, PromptLayer.STORY_BIBLE in layers)
        }
    }

    private fun source(phase: GenerationPhase): InitialPlanningPromptSources {
        val directive = ContentPresentationMappingV1.directiveFor(BookPresentationPreset.DETAILED)
        val bundle = PromptBundleCatalogV1.bind(PromptBundleSourceBinding(
            snapshotSchemaVersion = 1,
            sourceContentHash = "a".repeat(64),
            lengthMode = BookLengthMode.SHORT,
            minimumChapterCount = 80,
            targetChapterCount = 80,
            lengthPolicySchemaVersion = 1,
            presentationDirective = directive,
            genreBaseline = GenreContentDimensionBaseline(3, 3, 3, 3),
        ))
        return InitialPlanningPromptSources(
            jobId = "job-1",
            stageId = "stage-${phase.name}",
            bookId = "book-1",
            phase = phase,
            targetChapterCount = 80,
            stageInputVersionHash = "b".repeat(64),
            stageIdempotencyKey = "idempotency-${phase.name}",
            userIntentJson = "{\"theme\":\"fixture\"}",
            promptBundle = bundle,
            predecessorJson = if (phase == GenerationPhase.BUILD_STORY_SEED) null else "{\"schemaVersion\":1}",
            nextStageId = if (phase == GenerationPhase.BUILD_MASTER_OUTLINE) null else "stage-next",
        )
    }
}
