package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.GenerationRunnerCurrentStageRouteSnapshot
import app.zhijuan.core.database.generation.GenerationRunnerStageRoute
import app.zhijuan.core.database.generation.GenerationStreamingDraftRepository
import app.zhijuan.core.database.generation.InitialPlanningCommitRepository
import app.zhijuan.core.database.generation.InitialPlanningCommitResult
import app.zhijuan.core.database.generation.InitialPlanningPromptSources
import app.zhijuan.core.database.generation.InitialPlanningPromptSourcesRepository
import app.zhijuan.core.database.generation.RequestIntentDraft
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.RelevantCharacterAdultGate
import app.zhijuan.provider.common.GenerationParameters
import app.zhijuan.provider.common.GenerationRequest
import app.zhijuan.provider.common.PromptLayer
import app.zhijuan.provider.common.PromptPart
import app.zhijuan.provider.common.ProviderFinishReason
import app.zhijuan.provider.common.ProviderModelId
import app.zhijuan.provider.common.ProviderPrompt
import app.zhijuan.provider.common.ProviderTimeoutPolicy
import app.zhijuan.provider.common.SensitiveProviderText
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

fun interface InitialPlanningBoundExecutor {
    suspend fun executeBound(
        snapshot: GenerationRunnerCurrentStageRouteSnapshot,
        requestedAt: Long,
    ): InitialPlanningExecutionResult
}

sealed interface InitialPlanningExecutionResult {
    data class Accepted(
        val commit: InitialPlanningCommitResult,
        val execution: AuditedStreamingExecutionResult.Completed,
    ) : InitialPlanningExecutionResult

    data class RepairRequired(
        val report: StructuredOutputInvalidReport,
        val execution: AuditedStreamingExecutionResult.Completed,
    ) : InitialPlanningExecutionResult

    data class NeedsAction(
        val report: StructuredOutputInvalidReport,
        val execution: AuditedStreamingExecutionResult.Completed,
    ) : InitialPlanningExecutionResult

    data class Other(val execution: AuditedStreamingExecutionResult) : InitialPlanningExecutionResult
}

internal fun interface InitialPlanningBoundSourceLoader {
    suspend fun load(
        snapshot: GenerationRunnerCurrentStageRouteSnapshot,
        loadedAt: Long,
    ): InitialPlanningPromptSources

    companion object {
        fun from(repository: InitialPlanningPromptSourcesRepository) =
            InitialPlanningBoundSourceLoader(repository::loadBound)
    }
}

internal class PersistentInitialPlanningBoundExecutorV1(
    private val sources: InitialPlanningBoundSourceLoader,
    private val remote: GenerationBoundRemoteExecutionProvider,
    private val requests: GenerationStreamingDraftRepository,
    private val executor: AuditedStreamingProviderExecutor,
    private val validation: StructuredOutputValidationCoordinator,
    private val commits: InitialPlanningCommitRepository,
    private val parser: InitialPlanningOutputParser = InitialPlanningOutputParser(),
    private val clock: GenerationExecutionClock = SystemGenerationExecutionClock,
) : InitialPlanningBoundExecutor {
    override suspend fun executeBound(
        snapshot: GenerationRunnerCurrentStageRouteSnapshot,
        requestedAt: Long,
    ): InitialPlanningExecutionResult {
        require(snapshot.route in INITIAL_ROUTES)
        val source = sources.load(snapshot, requestedAt)
        val resolved = remote.resolve(snapshot, requestedAt)
        val attemptOrdinal = snapshot.attemptCount + 1
        val attemptId = stableGenerationExecutionId("attempt", source.jobId, source.stageId, attemptOrdinal.toString())
        val request = InitialPlanningRequestFactory.create(
            source = source,
            requestId = stableGenerationExecutionId("request", source.jobId, source.stageId, attemptOrdinal.toString()),
            attemptId = attemptId,
            modelId = resolved.modelId,
            maximumOutputTokens = resolved.maximumOutputTokens,
            timeouts = resolved.timeouts,
        )
        val persisted = requests.prepareBoundInitialPlanningBeforeSend(
            snapshot = snapshot,
            draft = RequestIntentDraft(
                attemptId = attemptId,
                usageLedgerId = stableGenerationExecutionId("usage", source.jobId, source.stageId, attemptOrdinal.toString()),
                stageId = source.stageId,
                retryParentAttemptId = null,
                connectionSnapshotJson = resolved.connectionSnapshotJson,
                modelSnapshotJson = resolved.modelSnapshotJson,
                protocolSnapshotJson = resolved.protocolSnapshotJson,
                inputHash = request.requestBindingHash,
                streamDraftRef = null,
                createdAt = requestedAt,
            ),
            budget = resolved.budget(
                stableGenerationExecutionId("budget", source.jobId, source.stageId, attemptOrdinal.toString()),
            ),
        )
        val execution = executor.execute(persisted, resolved.adapter, resolved.profile, request.request)
        if (execution !is AuditedStreamingExecutionResult.Completed || execution.reason != ProviderFinishReason.STOP) {
            return InitialPlanningExecutionResult.Other(execution)
        }
        val validatedAt = now()
        return when (val decision = validation.validate(execution, request.contract, validatedAt)) {
            is StructuredOutputValidationDecision.Accepted -> {
                val committedAt = now().coerceAtLeast(validatedAt)
                val usage = execution.latestUsage.toFinalUsageCommit()
                val commit = when (source.phase) {
                    GenerationPhase.BUILD_STORY_SEED -> {
                        val seed = requireValid(parser.storySeed(canonicalBytes(decision.output)))
                        commits.commitStorySeed(
                            decision.commitPermit,
                            InitialPlanningPersistenceMapper.storySeed(
                                seed, source.targetChapterCount, requireNotNull(source.nextStageId), committedAt,
                            ).copy(usage = usage),
                        )
                    }
                    GenerationPhase.BUILD_BIBLE -> {
                        val seed = requireValid(parser.storySeed(requireNotNull(source.predecessorJson).encodeToByteArray()))
                        val bible = requireValid(parser.storyBible(canonicalBytes(decision.output)))
                        commits.commitStoryBible(
                            decision.commitPermit,
                            InitialPlanningPersistenceMapper.storyBible(
                                seed = seed,
                                bible = bible,
                                bookId = source.bookId,
                                bibleRevisionId = stableGenerationExecutionId("bible", source.bookId, source.stageId),
                                bibleStageId = source.stageId,
                                nextStageId = requireNotNull(source.nextStageId),
                                committedAt = committedAt,
                            ).copy(usage = usage),
                        )
                    }
                    GenerationPhase.BUILD_MASTER_OUTLINE -> {
                        val bible = requireValid(parser.storyBible(requireNotNull(source.predecessorJson).encodeToByteArray()))
                        val outline = requireValid(parser.masterOutline(canonicalBytes(decision.output)))
                        commits.commitMasterOutline(
                            decision.commitPermit,
                            InitialPlanningPersistenceMapper.masterOutline(
                                bible = bible,
                                outline = outline,
                                expectedTargetChapterCount = source.targetChapterCount,
                                bookId = source.bookId,
                                outlineRevisionId = stableGenerationExecutionId("outline", source.bookId, source.stageId),
                                outlineStageId = source.stageId,
                                committedAt = committedAt,
                            ).copy(usage = usage),
                        )
                    }
                    else -> error("Initial planning phase is unsupported.")
                }
                InitialPlanningExecutionResult.Accepted(commit, execution)
            }
            is StructuredOutputValidationDecision.RepairRequired ->
                InitialPlanningExecutionResult.RepairRequired(decision.report, execution)
            is StructuredOutputValidationDecision.NeedsAction ->
                InitialPlanningExecutionResult.NeedsAction(decision.report, execution)
        }
    }

    private fun canonicalBytes(output: ValidatedStructuredOutput): ByteArray =
        output.withDocument { it.toString().encodeToByteArray() }

    private fun <T> requireValid(result: PlanningOutputValidationResult<T>): T = when (result) {
        is PlanningOutputValidationResult.Valid -> result.value
        is PlanningOutputValidationResult.Invalid -> error("Validated planning output could not be restored.")
    }

    private fun now(): Long = clock.nowMillis().also { require(it >= 0L) }

    private companion object {
        val INITIAL_ROUTES = setOf(
            GenerationRunnerStageRoute.INITIAL_STORY_SEED_V1,
            GenerationRunnerStageRoute.INITIAL_STORY_BIBLE_V1,
            GenerationRunnerStageRoute.INITIAL_MASTER_OUTLINE_V1,
        )
    }
}

internal class BoundInitialPlanningRequest(
    val request: GenerationRequest,
    val contract: StructuredOutputContract,
    val requestBindingHash: String,
)

internal object InitialPlanningRequestFactory {
    fun create(
        source: InitialPlanningPromptSources,
        requestId: String,
        attemptId: String,
        modelId: ProviderModelId,
        maximumOutputTokens: Int,
        timeouts: ProviderTimeoutPolicy,
    ): BoundInitialPlanningRequest {
        val preparation = PromptBundleProviderBridge.prepare(
            bundle = source.promptBundle,
            phase = source.phase,
            intimacyRelevant = false,
            adultGate = RelevantCharacterAdultGate.UNKNOWN,
        ) as? PromptStagePreparation.Remote
            ?: error("Initial planning Prompt Bundle does not allow remote execution.")
        val contract = contract(source.phase)
        require(preparation.outputSchemaId == contract.schemaId && preparation.bindingHash == source.promptBundle.bindingHash)
        val stage = source.promptBundle.contractFor(source.phase)
        val payload = JsonObject(linkedMapOf(
            "schemaVersion" to JsonPrimitive(1),
            "targetChapterCount" to JsonPrimitive(source.targetChapterCount),
            "creationInput" to strictObject(source.userIntentJson, "creation input"),
            "predecessor" to (source.predecessorJson?.let { strictObject(it, "predecessor") } ?: JsonNull),
        )).toString()
        val parts = buildList {
            add(PromptPart(
                PromptLayer.APPLICATION_HARD_RULES,
                SensitiveProviderText.from(source.promptBundle.applicationHardRules.joinToString("\n") { it.text }),
            ))
            add(PromptPart(
                PromptLayer.STAGE_CONTRACT,
                SensitiveProviderText.from(
                    stage.instructions.joinToString("\n") { it.text } +
                        "\n只输出符合 ${contract.schemaId} 的单个 JSON 对象，不输出 Markdown、解释或第二个候选。",
                ),
            ))
            if (source.phase == GenerationPhase.BUILD_MASTER_OUTLINE) {
                add(PromptPart(PromptLayer.STORY_BIBLE, SensitiveProviderText.from(requireNotNull(source.predecessorJson))))
            }
            add(PromptPart(
                PromptLayer.WRITING_STYLE,
                SensitiveProviderText.from(source.promptBundle.presentationInstructions.joinToString("\n") { it.text }),
            ))
            add(PromptPart(PromptLayer.USER_REQUEST, SensitiveProviderText.from(payload)))
        }
        val requestBindingHash = sha256(
            parts.joinToString("\u0000") { part ->
                part.layer.name + ":" + part.content.withValue { it }
            },
        )
        return BoundInitialPlanningRequest(
            request = GenerationRequest(
                requestId = requestId,
                generationId = source.jobId,
                stageId = source.stageId,
                attemptId = attemptId,
                modelId = modelId,
                prompt = ProviderPrompt(parts),
                parameters = GenerationParameters(temperature = 0.3, maxOutputTokens = maximumOutputTokens),
                structuredOutputSchema = contract.providerSchema,
                stream = preparation.stream,
                timeouts = timeouts,
                idempotencyKey = source.stageIdempotencyKey,
            ),
            contract = contract,
            requestBindingHash = requestBindingHash,
        )
    }

    private fun contract(phase: GenerationPhase): StructuredOutputContract = when (phase) {
        GenerationPhase.BUILD_STORY_SEED -> StorySeedOutputContractV1
        GenerationPhase.BUILD_BIBLE -> StoryBibleOutputContractV1
        GenerationPhase.BUILD_MASTER_OUTLINE -> MasterOutlineOutputContractV1
        else -> error("Initial planning phase is unsupported.")
    }

    private fun strictObject(value: String, label: String): JsonObject =
        runCatching { Json.parseToJsonElement(value) as JsonObject }
            .getOrElse { throw IllegalArgumentException("Initial planning $label is invalid.") }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
