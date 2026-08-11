package app.zhijuan.feature.generation

import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.RelevantCharacterAdultGate
import app.zhijuan.core.model.RelevantSceneBlockReason
import app.zhijuan.core.task.BoundPromptBundle
import app.zhijuan.core.task.FirstChapterProgressionPolicyV1
import app.zhijuan.core.task.PromptBundleCatalogV1
import app.zhijuan.core.task.PromptContractLayer
import app.zhijuan.core.task.PromptInstruction
import app.zhijuan.core.task.PromptStageExecutor
import app.zhijuan.core.task.SceneExecutionContract
import app.zhijuan.provider.common.PromptLayer

sealed interface PromptStagePreparation {
    data class LocalOnly(
        val phase: GenerationPhase,
        val templateId: String,
    ) : PromptStagePreparation

    data class Blocked(
        val phase: GenerationPhase,
        val reason: RelevantSceneBlockReason,
    ) : PromptStagePreparation

    class Remote internal constructor(
        val phase: GenerationPhase,
        val templateId: String,
        val outputSchemaId: String,
        val stream: Boolean,
        val requiredLayers: List<PromptLayer>,
        instructions: List<PromptInstruction>,
        val bindingHash: String,
    ) : PromptStagePreparation {
        private val instructions = instructions.toList()

        val instructionCount: Int
            get() = instructions.size

        fun <T> withInstructions(block: (List<PromptInstruction>) -> T): T = block(instructions)

        override fun toString(): String =
            "PromptStagePreparation.Remote(phase=$phase, templateId=$templateId, " +
                "outputSchemaId=$outputSchemaId, stream=$stream, layerCount=${requiredLayers.size}, " +
                "instructionCount=$instructionCount, bindingHash=redacted, content=redacted)"
    }
}

object PromptBundleProviderBridge {
    fun prepare(
        bundle: BoundPromptBundle,
        phase: GenerationPhase,
        intimacyRelevant: Boolean,
        adultGate: RelevantCharacterAdultGate,
    ): PromptStagePreparation {
        require(bundle.bundleVersion == PromptBundleCatalogV1.BUNDLE_VERSION) {
            "Unsupported prompt bundle version for provider bridge."
        }
        val stage = bundle.contractFor(phase)
        if (!stage.remoteInvocationAllowed) {
            return PromptStagePreparation.LocalOnly(phase, stage.templateId)
        }
        val scene = PromptBundleCatalogV1.resolveScene(
            bundle = bundle,
            phase = phase,
            intimacyRelevant = intimacyRelevant,
            adultGate = adultGate,
        )
        if (scene is SceneExecutionContract.Blocked) {
            return PromptStagePreparation.Blocked(phase, scene.reason)
        }
        val sceneInstructions = if (scene is SceneExecutionContract.Allowed) {
            scene.instructions
        } else {
            emptyList()
        }
        return PromptStagePreparation.Remote(
            phase = phase,
            templateId = stage.templateId,
            outputSchemaId = requireNotNull(stage.outputSchemaId),
            stream = stage.executor == PromptStageExecutor.PROVIDER_STREAMING_STRUCTURED,
            requiredLayers = stage.requiredLayers.map { layer -> layer.toProviderLayer() },
            instructions = bundle.applicationHardRules +
                bundle.presentationInstructions +
                stage.instructions +
                sceneInstructions,
            bindingHash = bundle.bindingHash,
        )
    }

    /**
     * Versioned exception for the first chapter only. It deliberately returns a bootstrap schema
     * instead of pretending that the reduced inputs satisfy the normal chapter-plan contract.
     */
    fun prepareFirstChapterFastLane(
        bundle: BoundPromptBundle,
        intimacyRelevant: Boolean,
        adultGate: RelevantCharacterAdultGate,
    ): PromptStagePreparation {
        require(bundle.bundleVersion == PromptBundleCatalogV1.BUNDLE_VERSION) {
            "Unsupported prompt bundle version for the first-chapter fast lane."
        }
        val phase = GenerationPhase.BUILD_CHAPTER_PLAN
        val scene = PromptBundleCatalogV1.resolveScene(bundle, phase, intimacyRelevant, adultGate)
        if (scene is SceneExecutionContract.Blocked) {
            return PromptStagePreparation.Blocked(phase, scene.reason)
        }
        val sceneInstructions = (scene as? SceneExecutionContract.Allowed)?.instructions.orEmpty()
        val fastLaneInstructions = listOf(
            PromptInstruction(
                id = "stage.first-chapter-bootstrap",
                text = "只建立第一章所需的最小人物事实、核心世界规则、结局方向、前三章粗计划和第一章场景序列；不得把最小规划冒充完整故事圣经或全书总纲。",
            ),
            PromptInstruction(
                id = "stage.first-chapter-no-future-facts",
                text = "第二章和第三章只返回粗目标，不生成正文，也不把候选情节声明为已经发生的事实。",
            ),
        )
        return PromptStagePreparation.Remote(
            phase = phase,
            templateId = FirstChapterProgressionPolicyV1.FAST_LANE_CONTRACT_VERSION,
            outputSchemaId = FirstChapterProgressionPolicyV1.FAST_LANE_OUTPUT_SCHEMA_ID,
            stream = false,
            requiredLayers = listOf(
                PromptContractLayer.APPLICATION_HARD_RULES,
                PromptContractLayer.STAGE_CONTRACT,
                PromptContractLayer.WRITING_STYLE,
                PromptContractLayer.USER_REQUEST,
            ).map { PromptLayer.valueOf(it.name) },
            instructions = bundle.applicationHardRules +
                bundle.presentationInstructions +
                fastLaneInstructions +
                sceneInstructions,
            bindingHash = bundle.bindingHash,
        )
    }

    private fun PromptContractLayer.toProviderLayer(): PromptLayer = PromptLayer.valueOf(name)
}
