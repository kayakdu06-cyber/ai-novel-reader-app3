package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.ChapterContextAssemblyBoundExecutorV1
import app.zhijuan.core.database.generation.ChapterPlanAuthoritySourceRepository
import app.zhijuan.core.database.generation.ChapterPlanV2StageUpgradeRepository
import app.zhijuan.core.database.generation.GenerationRunnerCurrentStageRouteSnapshot
import app.zhijuan.core.database.generation.PersistedChapterContextAssemblyResult
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.task.PromptBundleCatalogV1
import java.security.MessageDigest

/** Converts the v1 placeholder into a frozen v2 plan immediately after local context assembly. */
internal class PersistentChapterContextAndPlanBindingExecutorV1(
    private val contextExecutor: ChapterContextAssemblyBoundExecutorV1,
    private val authoritySources: ChapterPlanAuthoritySourceRepository,
    private val upgrades: ChapterPlanV2StageUpgradeRepository,
) : ChapterContextAssemblyBoundExecutorV1 {
    override suspend fun assembleBound(
        snapshot: GenerationRunnerCurrentStageRouteSnapshot,
        assembledAt: Long,
    ): PersistedChapterContextAssemblyResult {
        val result = contextExecutor.assembleBound(snapshot, assembledAt)
        if (result !is PersistedChapterContextAssemblyResult.Ready) return result
        val source = authoritySources.loadAfterContextAssembly(snapshot, result.context, assembledAt)
        val book = BookCapabilityRouterV1.derive(
            CreationSnapshotIntentSourceV1(
                sourceContentHash = source.creationSnapshotContentHash,
                rawInputJson = source.rawInputJson,
                normalizedInputJson = source.normalizedInputJson,
            ),
        )
        val selection = when (
            val routed = ChapterCapabilityRouterV1.activate(
                book,
                ChapterCapabilityRequestV1(
                    phase = GenerationPhase.BUILD_CHAPTER_PLAN,
                    chapterTaskText = source.chapterTaskText,
                    obligationTexts = source.obligationIds.sorted(),
                    explicitlyRequiredCapabilityIds = source.capabilityHintIds.filterTo(linkedSetOf()) {
                        BuiltInStateCapabilityCatalogV1.find(it) != null && it in book.manifest.capabilityIds
                    },
                    intimacyRelevant = source.intimacyRelevant,
                    adultGate = source.adultGate,
                    availablePolicyPromptChars = 4_096,
                ),
            )
        ) {
            is ChapterCapabilityRoutingDecisionV1.Ready -> routed.selection
            is ChapterCapabilityRoutingDecisionV1.Blocked -> error(
                "Chapter capability routing is blocked: ${routed.reason}",
            )
        }
        val scene = PromptBundleCatalogV1.resolveScene(
            bundle = source.promptBundle,
            phase = GenerationPhase.BUILD_CHAPTER_PLAN,
            intimacyRelevant = source.intimacyRelevant,
            adultGate = source.adultGate,
        )
        val policyHash = ChapterPlanV2RequestFactory.policyCompilationHash(selection)
        val expectation = ChapterPlanExpectationV2(
            base = ChapterPlanExpectationV1(
                chapterId = source.chapterId,
                chapterIndex = source.chapterIndex,
                contextContentHash = result.context.contentHash,
                contextSourceManifestHash = result.context.sourceManifestHash,
                knownCharacterIds = source.knownCharacterIds,
                confirmedAdultFictionalCharacterIds = source.confirmedAdultFictionalCharacterIds,
                sceneExecutionContract = scene,
            ),
            activationHash = selection.activation.activationHash,
            policyCompilationHash = policyHash,
            contextEvidenceHash = sha256(result.context.providerPayloadJson),
            activeCapabilityIds = selection.activation.activeCapabilityIds,
            activeStateNamespaces = selection.activation.expectedStateNamespaceIds,
            priorObligationIds = source.obligationIds,
        )
        upgrades.upgradePending(
            snapshot = snapshot,
            frozen = ChapterPlanV2RequestFactory.freezeAuthority(expectation, selection),
            upgradedAt = assembledAt,
        )
        return result
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }
}
