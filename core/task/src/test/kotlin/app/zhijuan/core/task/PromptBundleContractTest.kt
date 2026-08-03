package app.zhijuan.core.task

import app.zhijuan.core.model.BookLengthMode
import app.zhijuan.core.model.BookLengthPolicy
import app.zhijuan.core.model.BookPresentationPreset
import app.zhijuan.core.model.ContentPresentationMappingV1
import app.zhijuan.core.model.FadePolicy
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenreContentDimensionBaseline
import app.zhijuan.core.model.RelevantCharacterAdultGate
import app.zhijuan.core.model.RelevantSceneBlockReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PromptBundleContractTest {
    @Test
    fun bundleCoversEveryPhaseAndKeepsLocalWorkAwayFromProvider() {
        val bundle = bind(BookPresentationPreset.BALANCED)

        assertEquals(GenerationPhase.entries, bundle.stageContracts.map(PromptStageContract::phase))
        assertEquals(
            setOf(
                GenerationPhase.NORMALIZE_INPUT,
                GenerationPhase.ASSEMBLE_CONTEXT,
                GenerationPhase.COMMIT_CHAPTER,
            ),
            bundle.stageContracts.filterNot(PromptStageContract::remoteInvocationAllowed)
                .map(PromptStageContract::phase)
                .toSet(),
        )
        bundle.stageContracts.filter(PromptStageContract::remoteInvocationAllowed).forEach { contract ->
            assertTrue(contract.outputSchemaId?.endsWith(".v1") == true)
        }
        assertEquals(
            PromptScenePolicy.APPLY_CONTENT_PROFILE,
            bundle.contractFor(GenerationPhase.DRAFT_CHAPTER).scenePolicy,
        )
        assertEquals(
            PromptScenePolicy.APPLY_CONTENT_PROFILE,
            bundle.contractFor(GenerationPhase.REVISE_CHAPTER).scenePolicy,
        )
    }

    @Test
    fun bindingIsDeterministicVersionedAndIncludesFrozenSourceHash() {
        val first = bind(BookPresentationPreset.BALANCED, sourceHash = "a".repeat(64))
        val same = bind(BookPresentationPreset.BALANCED, sourceHash = "a".repeat(64))
        val changed = bind(BookPresentationPreset.BALANCED, sourceHash = "b".repeat(64))

        assertEquals(PromptBundleCatalogV1.BUNDLE_VERSION, first.bundleVersion)
        assertEquals(PromptBundleCatalogV1.CONTRACT_SCHEMA_VERSION, first.contractSchemaVersion)
        assertEquals(first.bindingHash, same.bindingHash)
        assertNotEquals(first.bindingHash, changed.bindingHash)
        assertTrue(first.bindingHash.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun detailedAvoidFadeAutomaticallyAddsStrictBodyAndSensoryContinuity() {
        val bundle = bind(BookPresentationPreset.DETAILED)
        val scene = PromptBundleCatalogV1.resolveScene(
            bundle = bundle,
            phase = GenerationPhase.DRAFT_CHAPTER,
            intimacyRelevant = true,
            adultGate = RelevantCharacterAdultGate.CONFIRMED_ADULTS,
        ) as SceneExecutionContract.Allowed

        assertTrue(scene.automatic)
        assertEquals(4, scene.intimacyDetailLevel)
        assertEquals(FadePolicy.AVOID, scene.fadePolicy)
        assertTrue(scene.strictBodyAndSensoryContinuity)
        assertEquals(100, scene.requiredKeyProcessCoveragePercent)
        assertFalse(scene.fadeSubstitutionAllowed)
        assertTrue(scene.requiresStateContinuity)
        assertTrue(scene.requiresRelevantAftermath)
        assertEquals(
            setOf(
                "scene.no-fade-substitution",
                "scene.body-space-causality",
                "scene.sensory-continuity",
                "scene.body-state-continuity",
                "scene.relevant-aftermath",
            ),
            scene.instructions.map(PromptInstruction::id).toSet(),
        )
        val joined = scene.instructions.joinToString("\n") { it.text }
        assertTrue(joined.contains("不用黑屏、跳时、转场、含糊带过或事后总结替代"))
        assertTrue(joined.contains("位置、姿势、衣着与身体接触"))
        assertTrue(joined.contains("触觉、视觉、声音、呼吸与节奏、语言和心理"))
        assertTrue(joined.contains("任何变化都要有可感知原因"))
        assertTrue(joined.contains("真正相关的余波"))
    }

    @Test
    fun detailedPresentationDoesNotRaiseUnrelatedGenreDimensions() {
        val baseline = GenreContentDimensionBaseline(
            conflictDetailLevel = 1,
            graphicInjuryLevel = 0,
            languageIntensityLevel = 2,
            emotionalPressureLevel = 3,
        )
        val bundle = bind(BookPresentationPreset.DETAILED, baseline = baseline)

        assertEquals(1, bundle.contentProfile.conflictDetailLevel)
        assertEquals(0, bundle.contentProfile.graphicInjuryLevel)
        assertEquals(2, bundle.contentProfile.languageIntensityLevel)
        assertEquals(3, bundle.contentProfile.emotionalPressureLevel)
        assertTrue(bundle.presentationInstructions.single().text.contains("关键过程完整展开"))
    }

    @Test
    fun adultFactsGateBlocksRelevantSceneWithoutInventingAUserChoice() {
        val bundle = bind(BookPresentationPreset.DETAILED)

        assertEquals(
            SceneExecutionContract.Blocked(RelevantSceneBlockReason.ADULT_STATUS_UNKNOWN),
            PromptBundleCatalogV1.resolveScene(
                bundle,
                GenerationPhase.DRAFT_CHAPTER,
                intimacyRelevant = true,
                adultGate = RelevantCharacterAdultGate.UNKNOWN,
            ),
        )
        assertEquals(
            SceneExecutionContract.Blocked(RelevantSceneBlockReason.ADULT_STATUS_NOT_CONFIRMED),
            PromptBundleCatalogV1.resolveScene(
                bundle,
                GenerationPhase.REVISE_CHAPTER,
                intimacyRelevant = true,
                adultGate = RelevantCharacterAdultGate.NOT_CONFIRMED,
            ),
        )
        assertEquals(
            SceneExecutionContract.NotApplicable,
            PromptBundleCatalogV1.resolveScene(
                bundle,
                GenerationPhase.DRAFT_CHAPTER,
                intimacyRelevant = false,
                adultGate = RelevantCharacterAdultGate.UNKNOWN,
            ),
        )
    }

    @Test
    fun sceneRulesAttachToPlanningWritingCheckingAndRevisionButNotUnrelatedStages() {
        val bundle = bind(BookPresentationPreset.DETAILED)

        listOf(
            GenerationPhase.BUILD_CHAPTER_PLAN,
            GenerationPhase.DRAFT_CHAPTER,
            GenerationPhase.CHECK_CONSISTENCY,
            GenerationPhase.REVISE_CHAPTER,
        ).forEach { phase ->
            assertTrue(
                PromptBundleCatalogV1.resolveScene(
                    bundle,
                    phase,
                    intimacyRelevant = true,
                    adultGate = RelevantCharacterAdultGate.CONFIRMED_ADULTS,
                ) is SceneExecutionContract.Allowed,
            )
        }
        assertEquals(
            SceneExecutionContract.NotApplicable,
            PromptBundleCatalogV1.resolveScene(
                bundle,
                GenerationPhase.BUILD_BIBLE,
                intimacyRelevant = true,
                adultGate = RelevantCharacterAdultGate.CONFIRMED_ADULTS,
            ),
        )
    }

    @Test
    fun storyContractsCanAssignAdultFactsForNewFictionalCharactersWithoutAnotherDefaultQuestion() {
        val bundle = bind(BookPresentationPreset.DETAILED)
        val hardRules = bundle.applicationHardRules.associateBy(PromptInstruction::id)
        val seedText = bundle.contractFor(GenerationPhase.BUILD_STORY_SEED).instructions.single().text
        val bibleText = bundle.contractFor(GenerationPhase.BUILD_BIBLE).instructions.single().text

        assertTrue(hardRules.getValue("hard.adult-facts").text.contains("确认年满十八岁"))
        assertTrue(hardRules.getValue("hard.fictional-characters").text.contains("真实可识别人物"))
        assertTrue(seedText.contains("用户未指定年龄的新建虚构人物"))
        assertTrue(bibleText.contains("自动分配明确成年年龄"))
        assertTrue(bibleText.contains("显式未成年、真实人物或矛盾年龄不得改写为成年"))
    }

    @Test
    fun balancedSceneKeepsContinuityWithoutForcingDetailedRules() {
        val bundle = bind(BookPresentationPreset.BALANCED)
        val scene = PromptBundleCatalogV1.resolveScene(
            bundle,
            GenerationPhase.DRAFT_CHAPTER,
            intimacyRelevant = true,
            adultGate = RelevantCharacterAdultGate.CONFIRMED_ADULTS,
        ) as SceneExecutionContract.Allowed

        assertTrue(scene.automatic)
        assertFalse(scene.strictBodyAndSensoryContinuity)
        assertEquals(null, scene.requiredKeyProcessCoveragePercent)
        assertTrue(scene.fadeSubstitutionAllowed)
        assertEquals(
            setOf("scene.proportional-detail", "scene.state-and-aftermath"),
            scene.instructions.map(PromptInstruction::id).toSet(),
        )
    }

    @Test
    fun frozenLengthRulesRemainShort80Medium300AndCustomLong() {
        val short = bind(
            preset = BookPresentationPreset.BALANCED,
            lengthMode = BookLengthMode.SHORT,
            minimum = BookLengthPolicy.SHORT_MINIMUM_CHAPTERS,
            target = BookLengthPolicy.SHORT_MINIMUM_CHAPTERS,
        )
        val medium = bind(BookPresentationPreset.BALANCED)
        val long = bind(
            preset = BookPresentationPreset.BALANCED,
            lengthMode = BookLengthMode.LONG,
            minimum = BookLengthPolicy.LONG_MINIMUM_CHAPTERS,
            target = 888,
        )

        assertEquals(80, short.targetChapterCount)
        assertEquals(300, medium.targetChapterCount)
        assertEquals(301, long.minimumChapterCount)
        assertEquals(888, long.targetChapterCount)
    }

    @Test
    fun unsupportedOrMalformedSnapshotBindingFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            PromptBundleCatalogV1.bind(source(snapshotSchemaVersion = 2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            source(sourceHash = "not-a-hash")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PromptBundleCatalogV1.bind(
                source(
                    lengthMode = BookLengthMode.LONG,
                    minimum = BookLengthPolicy.LONG_MINIMUM_CHAPTERS,
                    target = 300,
                ),
            )
        }
    }

    @Test
    fun diagnosticStringsRedactHashesAndInstructionText() {
        val bundle = bind(BookPresentationPreset.DETAILED)
        val source = source()
        val scene = PromptBundleCatalogV1.resolveScene(
            bundle,
            GenerationPhase.DRAFT_CHAPTER,
            intimacyRelevant = true,
            adultGate = RelevantCharacterAdultGate.CONFIRMED_ADULTS,
        )

        assertFalse(bundle.toString().contains(bundle.bindingHash))
        assertFalse(bundle.toString().contains(bundle.sourceContentHash))
        assertFalse(source.toString().contains(source.sourceContentHash))
        assertFalse(bundle.applicationHardRules.first().toString().contains("年满十八岁"))
        assertFalse(scene.toString().contains("身体接触"))
        assertFalse(bundle.contractFor(GenerationPhase.DRAFT_CHAPTER).toString().contains("候选正文"))
    }

    private fun bind(
        preset: BookPresentationPreset,
        sourceHash: String = "a".repeat(64),
        baseline: GenreContentDimensionBaseline = GenreContentDimensionBaseline(2, 1, 1, 2),
        lengthMode: BookLengthMode = BookLengthMode.MEDIUM,
        minimum: Int = BookLengthPolicy.MEDIUM_MINIMUM_CHAPTERS,
        target: Int = BookLengthPolicy.MEDIUM_MINIMUM_CHAPTERS,
    ): BoundPromptBundle = PromptBundleCatalogV1.bind(
        source(
            preset = preset,
            sourceHash = sourceHash,
            baseline = baseline,
            lengthMode = lengthMode,
            minimum = minimum,
            target = target,
        ),
    )

    private fun source(
        preset: BookPresentationPreset = BookPresentationPreset.BALANCED,
        sourceHash: String = "a".repeat(64),
        snapshotSchemaVersion: Int = 1,
        baseline: GenreContentDimensionBaseline = GenreContentDimensionBaseline(2, 1, 1, 2),
        lengthMode: BookLengthMode = BookLengthMode.MEDIUM,
        minimum: Int = BookLengthPolicy.MEDIUM_MINIMUM_CHAPTERS,
        target: Int = BookLengthPolicy.MEDIUM_MINIMUM_CHAPTERS,
    ) = PromptBundleSourceBinding(
        snapshotSchemaVersion = snapshotSchemaVersion,
        sourceContentHash = sourceHash,
        lengthMode = lengthMode,
        minimumChapterCount = minimum,
        targetChapterCount = target,
        lengthPolicySchemaVersion = BookLengthPolicy.SCHEMA_VERSION,
        presentationDirective = ContentPresentationMappingV1.directiveFor(preset),
        genreBaseline = baseline,
    )
}
