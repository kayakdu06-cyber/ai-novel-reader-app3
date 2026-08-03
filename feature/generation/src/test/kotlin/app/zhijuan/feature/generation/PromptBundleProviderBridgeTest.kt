package app.zhijuan.feature.generation

import app.zhijuan.core.model.BookLengthMode
import app.zhijuan.core.model.BookLengthPolicy
import app.zhijuan.core.model.BookPresentationPreset
import app.zhijuan.core.model.ContentPresentationMappingV1
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenreContentDimensionBaseline
import app.zhijuan.core.model.RelevantCharacterAdultGate
import app.zhijuan.core.model.RelevantSceneBlockReason
import app.zhijuan.core.task.PromptBundleCatalogV1
import app.zhijuan.core.task.PromptBundleSourceBinding
import app.zhijuan.core.task.PromptContractLayer
import app.zhijuan.provider.common.PromptLayer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PromptBundleProviderBridgeTest {
    @Test
    fun firstChapterFastLaneUsesItsOwnVersionedBootstrapSchema() {
        val prepared = PromptBundleProviderBridge.prepareFirstChapterFastLane(
            bundle = bundle(),
            intimacyRelevant = false,
            adultGate = RelevantCharacterAdultGate.UNKNOWN,
        )

        val remote = prepared as PromptStagePreparation.Remote
        assertEquals("first-chapter-bootstrap.v1", remote.outputSchemaId)
        assertEquals("zhijuan.first-chapter-fast-lane.v1", remote.templateId)
        assertTrue(!remote.stream)
    }

    @Test
    fun contractLayersMapExactlyToProviderPrecedence() {
        assertEquals(
            PromptContractLayer.entries.map(Enum<*>::name),
            PromptLayer.entries.map(Enum<*>::name),
        )
        val prepared = PromptBundleProviderBridge.prepare(
            bundle(),
            GenerationPhase.DRAFT_CHAPTER,
            intimacyRelevant = false,
            adultGate = RelevantCharacterAdultGate.UNKNOWN,
        ) as PromptStagePreparation.Remote
        assertEquals(PromptLayer.entries, prepared.requiredLayers)
    }

    @Test
    fun detailedRelevantDraftAutomaticallyCarriesContinuityInstructions() {
        val prepared = PromptBundleProviderBridge.prepare(
            bundle(BookPresentationPreset.DETAILED),
            GenerationPhase.DRAFT_CHAPTER,
            intimacyRelevant = true,
            adultGate = RelevantCharacterAdultGate.CONFIRMED_ADULTS,
        ) as PromptStagePreparation.Remote

        assertTrue(prepared.stream)
        assertEquals("chapter-draft.v1", prepared.outputSchemaId)
        val instructionIds = prepared.withInstructions { instructions ->
            instructions.map { it.id }.toSet()
        }
        assertTrue("scene.no-fade-substitution" in instructionIds)
        assertTrue("scene.body-space-causality" in instructionIds)
        assertTrue("scene.sensory-continuity" in instructionIds)
        assertTrue("scene.body-state-continuity" in instructionIds)
        assertTrue("scene.relevant-aftermath" in instructionIds)
        assertFalse(prepared.toString().contains("身体接触"))
        assertFalse(prepared.toString().contains(prepared.bindingHash))
    }

    @Test
    fun unresolvedAdultFactsBlockBeforeRemotePreparation() {
        val prepared = PromptBundleProviderBridge.prepare(
            bundle(BookPresentationPreset.DETAILED),
            GenerationPhase.BUILD_CHAPTER_PLAN,
            intimacyRelevant = true,
            adultGate = RelevantCharacterAdultGate.UNKNOWN,
        )

        assertEquals(
            PromptStagePreparation.Blocked(
                GenerationPhase.BUILD_CHAPTER_PLAN,
                RelevantSceneBlockReason.ADULT_STATUS_UNKNOWN,
            ),
            prepared,
        )
    }

    @Test
    fun localStageNeverBecomesAProviderRequest() {
        val prepared = PromptBundleProviderBridge.prepare(
            bundle(BookPresentationPreset.DETAILED),
            GenerationPhase.COMMIT_CHAPTER,
            intimacyRelevant = true,
            adultGate = RelevantCharacterAdultGate.CONFIRMED_ADULTS,
        )

        assertEquals(
            PromptStagePreparation.LocalOnly(
                GenerationPhase.COMMIT_CHAPTER,
                "commit-chapter.v1",
            ),
            prepared,
        )
    }

    private fun bundle(
        preset: BookPresentationPreset = BookPresentationPreset.BALANCED,
    ) = PromptBundleCatalogV1.bind(
        PromptBundleSourceBinding(
            snapshotSchemaVersion = 1,
            sourceContentHash = "d".repeat(64),
            lengthMode = BookLengthMode.MEDIUM,
            minimumChapterCount = BookLengthPolicy.MEDIUM_MINIMUM_CHAPTERS,
            targetChapterCount = BookLengthPolicy.MEDIUM_MINIMUM_CHAPTERS,
            lengthPolicySchemaVersion = BookLengthPolicy.SCHEMA_VERSION,
            presentationDirective = ContentPresentationMappingV1.directiveFor(preset),
            genreBaseline = GenreContentDimensionBaseline(1, 0, 2, 2),
        ),
    )
}
