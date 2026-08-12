package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.ArcWindowPlanningCommitRepository
import app.zhijuan.core.database.generation.ArcWindowPlanningCommitResult
import app.zhijuan.core.database.generation.ArcWindowPromptSources
import app.zhijuan.core.database.generation.ArcWindowPromptSourcesRepository
import app.zhijuan.core.database.generation.GenerationRunnerCurrentStageRouteSnapshot
import app.zhijuan.core.database.generation.GenerationRunnerStageRoute
import app.zhijuan.core.database.generation.GenerationStreamingDraftRepository
import app.zhijuan.core.database.generation.RequestIntentDraft
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.RelevantCharacterAdultGate
import app.zhijuan.provider.common.GenerationParameters
import app.zhijuan.provider.common.GenerationRequest
import app.zhijuan.provider.common.PromptLayer
import app.zhijuan.provider.common.PromptPart
import app.zhijuan.provider.common.ProviderFinishReason
import app.zhijuan.provider.common.ProviderPrompt
import app.zhijuan.provider.common.SensitiveProviderText
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

fun interface ArcWindowBoundExecutor {
    suspend fun executeBound(
        snapshot: GenerationRunnerCurrentStageRouteSnapshot,
        requestedAt: Long,
    ): ArcWindowExecutionResult
}

sealed interface ArcWindowExecutionResult {
    data class Accepted(
        val commit: ArcWindowPlanningCommitResult,
        val execution: AuditedStreamingExecutionResult.Completed,
    ) : ArcWindowExecutionResult

    data class RepairRequired(
        val report: StructuredOutputInvalidReport,
        val execution: AuditedStreamingExecutionResult.Completed,
    ) : ArcWindowExecutionResult

    data class NeedsAction(
        val report: StructuredOutputInvalidReport,
        val execution: AuditedStreamingExecutionResult.Completed,
    ) : ArcWindowExecutionResult

    data class Other(val execution: AuditedStreamingExecutionResult) : ArcWindowExecutionResult
}

internal fun interface ArcWindowBoundSourceLoader {
    suspend fun load(
        snapshot: GenerationRunnerCurrentStageRouteSnapshot,
        loadedAt: Long,
    ): ArcWindowPromptSources

    companion object {
        fun from(repository: ArcWindowPromptSourcesRepository) =
            ArcWindowBoundSourceLoader(repository::loadBound)
    }
}

internal class PersistentArcWindowBoundExecutorV1(
    private val sources: ArcWindowBoundSourceLoader,
    private val remote: GenerationBoundRemoteExecutionProvider,
    private val requests: GenerationStreamingDraftRepository,
    private val executor: AuditedStreamingProviderExecutor,
    private val validation: StructuredOutputValidationCoordinator,
    private val commits: ArcWindowPlanningCommitRepository,
    private val clock: GenerationExecutionClock = SystemGenerationExecutionClock,
) : ArcWindowBoundExecutor {
    override suspend fun executeBound(
        snapshot: GenerationRunnerCurrentStageRouteSnapshot,
        requestedAt: Long,
    ): ArcWindowExecutionResult {
        require(snapshot.route == GenerationRunnerStageRoute.ARC_WINDOW_V1)
        val source = sources.load(snapshot, requestedAt)
        val resolved = remote.resolve(snapshot, requestedAt)
        val attemptOrdinal = snapshot.attemptCount + 1
        val attemptId = stableGenerationExecutionId(
            "attempt",
            source.jobId,
            source.stageId,
            attemptOrdinal.toString(),
        )
        val request = request(source, resolved, attemptId, attemptOrdinal)
        val persisted = requests.prepareBoundArcWindowBeforeSend(
            snapshot = snapshot,
            draft = RequestIntentDraft(
                attemptId = attemptId,
                usageLedgerId = stableGenerationExecutionId(
                    "usage",
                    source.jobId,
                    source.stageId,
                    attemptOrdinal.toString(),
                ),
                stageId = source.stageId,
                retryParentAttemptId = null,
                connectionSnapshotJson = resolved.connectionSnapshotJson,
                modelSnapshotJson = resolved.modelSnapshotJson,
                protocolSnapshotJson = resolved.protocolSnapshotJson,
                inputHash = request.bindingHash,
                streamDraftRef = null,
                createdAt = requestedAt,
            ),
            budget = resolved.budget(
                stableGenerationExecutionId(
                    "budget",
                    source.jobId,
                    source.stageId,
                    attemptOrdinal.toString(),
                ),
            ),
        )
        val execution = executor.execute(persisted, resolved.adapter, resolved.profile, request.request)
        if (execution !is AuditedStreamingExecutionResult.Completed || execution.reason != ProviderFinishReason.STOP) {
            return ArcWindowExecutionResult.Other(execution)
        }
        val validatedAt = now()
        return when (val decision = validation.validate(execution, request.contract, validatedAt)) {
            is StructuredOutputValidationDecision.Accepted -> {
                val v2 = when (
                    val parsed = ArcWindowPlanV2Parser().parse(
                        decision.output.withDocument { it.toString().encodeToByteArray() },
                    )
                ) {
                    is PlanningOutputValidationResult.Valid -> parsed.value
                    is PlanningOutputValidationResult.Invalid -> error(
                        "Validated arc-window output could not be restored.",
                    )
                }
                val plan = v2.basePlan
                val expectation = ArcWindowPlanningExpectation(
                    masterOutlineContentHash = source.frozen.masterOutlineContentHash,
                    parentOutlineContentHash = source.frozen.parentOutlineContentHash,
                    targetChapterCount = source.frozen.targetChapterCount,
                    selection = source.frozen.selection,
                )
                require(ArcWindowPlanningValidator.validate(plan, expectation) is ArcWindowPlanningValidationResult.Valid)
                val committedAt = now().coerceAtLeast(validatedAt)
                val commit = commits.commit(
                    decision.commitPermit,
                    ArcWindowPlanningPersistenceMapper.map(
                        plan = plan,
                        chapterContracts = v2.chapterContracts,
                        expected = expectation,
                        ids = ArcWindowPlanningPersistenceIds(
                            bookId = source.bookId,
                            masterOutlineRevisionId = source.frozen.masterOutlineRevisionId,
                            parentOutlineRevisionId = source.frozen.parentOutlineRevisionId,
                            parentRevisionNo = 1,
                            outlineRevisionId = stableGenerationExecutionId(
                                "window",
                                source.bookId,
                                source.stageId,
                            ),
                            generationStageId = source.stageId,
                        ),
                        committedAt = committedAt,
                        schemaId = ArcWindowPlanOutputContractV2.schemaId,
                        policyVersion = "zhijuan.arc-window-policy.v2",
                        canonicalPlanJson = v2.canonicalJson,
                        canonicalPlanHash = v2.contentHash,
                    ).copy(usage = execution.latestUsage.toFinalUsageCommit()),
                )
                ArcWindowExecutionResult.Accepted(commit, execution)
            }
            is StructuredOutputValidationDecision.RepairRequired ->
                ArcWindowExecutionResult.RepairRequired(decision.report, execution)
            is StructuredOutputValidationDecision.NeedsAction ->
                ArcWindowExecutionResult.NeedsAction(decision.report, execution)
        }
    }

    private fun request(
        source: ArcWindowPromptSources,
        resolved: GenerationBoundRemoteExecution,
        attemptId: String,
        attemptOrdinal: Int,
    ): BoundArcWindowRequest {
        val preparation = PromptBundleProviderBridge.prepare(
            source.promptBundle,
            GenerationPhase.BUILD_ARC_PLAN,
            intimacyRelevant = false,
            adultGate = RelevantCharacterAdultGate.UNKNOWN,
        ) as? PromptStagePreparation.Remote ?: error("Arc-window phase is not remotely executable.")
        val selection = source.frozen.selection
        val policy = arcPolicy(source)
        val policyHash = ChapterPlanV2RequestFactory.policyCompilationHash(policy)
        val contextHash = sha256(
            source.frozen.masterOutlineContentHash + "\u0000" + source.frozen.parentOutlineContentHash +
                "\u0000" + source.userIntentJson,
        )
        val expectation = ArcWindowExpectationV2(
            base = ArcWindowPlanningExpectation(
                masterOutlineContentHash = source.frozen.masterOutlineContentHash,
                parentOutlineContentHash = source.frozen.parentOutlineContentHash,
                targetChapterCount = source.frozen.targetChapterCount,
                selection = selection,
            ),
            policyCompilationHash = policyHash,
            contextEvidenceHash = contextHash,
        )
        val payload = JsonObject(
            linkedMapOf(
                "schemaVersion" to JsonPrimitive(1),
                "creationInput" to kotlinx.serialization.json.Json.parseToJsonElement(source.userIntentJson),
                "masterOutlineRevisionId" to JsonPrimitive(source.frozen.masterOutlineRevisionId),
                "masterOutlineContentHash" to JsonPrimitive(source.frozen.masterOutlineContentHash),
                "parentOutlineContentHash" to JsonPrimitive(source.frozen.parentOutlineContentHash),
                "targetChapterCount" to JsonPrimitive(source.frozen.targetChapterCount),
                "arcId" to JsonPrimitive(selection.arcId),
                "arcStartChapter" to JsonPrimitive(selection.arcStartChapter),
                "arcEndChapter" to JsonPrimitive(selection.arcEndChapter),
                "windowId" to JsonPrimitive(selection.windowId),
                "windowStartChapter" to JsonPrimitive(selection.windowStartChapter),
                "windowEndChapter" to JsonPrimitive(selection.windowEndChapter),
                "policyCompilationHash" to JsonPrimitive(policyHash),
                "contextEvidenceHash" to JsonPrimitive(contextHash),
            ),
        ).toString()
        val parts = preparation.withInstructions { instructions ->
            listOf(
                PromptPart(
                    PromptLayer.APPLICATION_HARD_RULES,
                    SensitiveProviderText.from(
                        source.promptBundle.applicationHardRules.joinToString("\n") { it.text },
                    ),
                ),
                PromptPart(
                    PromptLayer.STAGE_CONTRACT,
                    SensitiveProviderText.from(
                        instructions.joinToString("\n") { it.text } +
                            "\n只输出符合 arc-plan.v2 的单个 JSON 对象，不输出 Markdown 或解释。" +
                            "\n每章 chapterContracts 必须给出目标、能力提示、叙事义务和禁止重复项。",
                    ),
                ),
                PromptPart(
                    PromptLayer.WRITING_STYLE,
                    SensitiveProviderText.from(
                        source.promptBundle.presentationInstructions.joinToString("\n") { it.text },
                    ),
                ),
                PromptPart(PromptLayer.USER_REQUEST, SensitiveProviderText.from(payload)),
            )
        }
        val binding = sha256(parts.joinToString("\u0000") { part ->
            part.layer.name + ":" + part.content.withValue { it }
        })
        return BoundArcWindowRequest(
            GenerationRequest(
                requestId = stableGenerationExecutionId(
                    "request",
                    source.jobId,
                    source.stageId,
                    attemptOrdinal.toString(),
                ),
                generationId = source.jobId,
                stageId = source.stageId,
                attemptId = attemptId,
                modelId = resolved.modelId,
                prompt = ProviderPrompt(parts),
                parameters = GenerationParameters(temperature = 0.3, maxOutputTokens = resolved.maximumOutputTokens),
                structuredOutputSchema = ArcWindowPlanOutputContractV2.providerSchema,
                stream = preparation.stream,
                timeouts = resolved.timeouts,
                idempotencyKey = source.stageIdempotencyKey,
            ),
            binding,
            BoundArcWindowPlanV2OutputContract(expectation),
        )
    }

    private fun arcPolicy(source: ArcWindowPromptSources): ChapterPromptPolicySelectionV1 {
        val book = BookCapabilityRouterV1.derive(
            CreationSnapshotIntentSourceV1(
                source.promptBundle.sourceContentHash,
                source.userIntentJson,
                source.userIntentJson,
            ),
        )
        return when (
            val routed = ChapterCapabilityRouterV1.activate(
                book,
                ChapterCapabilityRequestV1(
                    phase = GenerationPhase.BUILD_ARC_PLAN,
                    chapterTaskText = source.userIntentJson,
                    availablePolicyPromptChars = 4_096,
                ),
            )
        ) {
            is ChapterCapabilityRoutingDecisionV1.Ready -> routed.selection
            is ChapterCapabilityRoutingDecisionV1.Blocked -> error(
                "Arc-window capability routing is blocked: ${routed.reason}",
            )
        }
    }

    private fun now(): Long = clock.nowMillis().also { require(it >= 0L) }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }

    private data class BoundArcWindowRequest(
        val request: GenerationRequest,
        val bindingHash: String,
        val contract: StructuredOutputContract,
    )
}
