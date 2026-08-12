package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.ChapterCandidateArtifactRoleV1
import app.zhijuan.core.database.generation.ChapterCandidateArtifactSealDraftV1
import app.zhijuan.core.database.generation.ChapterCandidateArtifactSealRepositoryV1
import app.zhijuan.core.database.generation.ChapterCandidateArtifactSealResultV1
import app.zhijuan.core.database.generation.ChapterCandidateStageBindingV1
import app.zhijuan.core.database.generation.ChapterCandidateStageSourceV1
import app.zhijuan.core.database.generation.GenerationOutputValidationRepository
import app.zhijuan.core.database.generation.GenerationRunnerCurrentStageRouteSnapshot
import app.zhijuan.core.database.generation.InitialChapterDraftPromptSources
import app.zhijuan.core.database.generation.PersistedStreamingRequest
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.provider.common.GenerationParameters
import app.zhijuan.provider.common.GenerationRequest
import app.zhijuan.provider.common.PromptLayer
import app.zhijuan.provider.common.PromptPart
import app.zhijuan.provider.common.ProviderAdapter
import app.zhijuan.provider.common.ProviderConnectionProfile
import app.zhijuan.provider.common.ProviderModelId
import app.zhijuan.provider.common.ProviderPrompt
import app.zhijuan.provider.common.ProviderTimeoutPolicy
import app.zhijuan.provider.common.SensitiveProviderText
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class InitialChapterDraftRequestSpec(
    val requestId: String,
    val generationId: String,
    val attemptId: String,
    val modelId: ProviderModelId,
    val sources: InitialChapterDraftPromptSources,
    val maximumOutputTokens: Int,
    val timeouts: ProviderTimeoutPolicy,
    val idempotencyKey: String? = null,
) {
    init {
        require(listOf(requestId, generationId, attemptId, sources.stageId).all(IDENTIFIER::matches))
        require(maximumOutputTokens in 512..16_384)
    }
}

class BoundInitialChapterDraftRequest internal constructor(
    val request: GenerationRequest,
    val sourceBindingHash: String,
    val chapterId: String,
    val chapterIndex: Int,
) {
    override fun toString(): String =
        "BoundInitialChapterDraftRequest(chapterIndex=$chapterIndex, content=redacted)"
}

object InitialChapterDraftRequestFactory {
    fun create(spec: InitialChapterDraftRequestSpec): BoundInitialChapterDraftRequest {
        val source = JsonObject(linkedMapOf(
            "schemaVersion" to JsonPrimitive(1),
            "schemaId" to JsonPrimitive("zhijuan.initial-chapter-draft-request.v1"),
            "chapterPlan" to Json.parseToJsonElement(spec.sources.canonicalPlanJson),
            "chapterContext" to Json.parseToJsonElement(spec.sources.contextPayloadJson),
            "expectation" to Json.parseToJsonElement(spec.sources.expectationJson),
            "activationManifest" to Json.parseToJsonElement(spec.sources.activationManifestJson),
            "policyManifest" to Json.parseToJsonElement(spec.sources.policyManifestJson),
        )).toString()
        val request = GenerationRequest(
            requestId = spec.requestId,
            generationId = spec.generationId,
            stageId = spec.sources.stageId,
            attemptId = spec.attemptId,
            modelId = spec.modelId,
            prompt = ProviderPrompt(listOf(
                PromptPart(PromptLayer.STAGE_CONTRACT, SensitiveProviderText.from(
                    "只写当前章节正文。严格执行冻结计划、上下文、人物和状态约束；不得重复已完成情节、丢弃因果或跳过要求的场景过程。" ,
                )),
                ChapterDraftOutputContractV1.initialStageContractPart(),
                PromptPart(PromptLayer.USER_REQUEST, SensitiveProviderText.from(source)),
            )),
            parameters = GenerationParameters(temperature = 0.7, maxOutputTokens = spec.maximumOutputTokens),
            structuredOutputSchema = ChapterDraftOutputContractV1.providerSchema,
            stream = true,
            timeouts = spec.timeouts,
            idempotencyKey = spec.idempotencyKey,
        )
        return BoundInitialChapterDraftRequest(
            request = request,
            sourceBindingHash = sha256(source),
            chapterId = spec.sources.chapterId,
            chapterIndex = spec.sources.chapterIndex,
        )
    }
}

data class InitialChapterDraftAdvanceSpec(
    val jobId: String,
    val candidateChapterVersionId: String,
    val memoryStageId: String,
    val memoryStageMaximumAttempts: Int,
    val sealedAt: Long,
)

sealed interface InitialChapterDraftExecutionResult {
    data class EnteredPostAnalysis(
        val seal: ChapterCandidateArtifactSealResultV1,
        val candidateChapterVersionId: String,
        val candidateContentHash: String,
        val formal: Boolean = false,
    ) : InitialChapterDraftExecutionResult
    data class Continuation(val result: ChapterDraftStreamingResult.ContinuationSettled) : InitialChapterDraftExecutionResult
    data class Other(val result: ChapterDraftStreamingResult) : InitialChapterDraftExecutionResult
}

class InitialChapterDraftCoordinator(
    private val drafts: ChapterDraftStreamingCoordinator,
    private val outputs: GenerationOutputValidationRepository,
    private val seals: ChapterCandidateArtifactSealRepositoryV1,
    private val clock: GenerationExecutionClock = SystemGenerationExecutionClock,
) {
    suspend fun execute(
        persisted: PersistedStreamingRequest,
        adapter: ProviderAdapter,
        profile: ProviderConnectionProfile,
        bound: BoundInitialChapterDraftRequest,
        advance: InitialChapterDraftAdvanceSpec,
    ): InitialChapterDraftExecutionResult {
        require(persisted.attempt.stageId == bound.request.stageId)
        require(persisted.attempt.attemptId == bound.request.attemptId)
        require(persisted.inputHash == bound.sourceBindingHash)
        return when (val result = drafts.executeInitial(persisted, adapter, profile, bound.request)) {
            is ChapterDraftStreamingResult.ReadyForValidation -> {
                val validatedAt = now()
                val permit = outputs.recordStructuredOutputValid(result.response, validatedAt)
                val contentHash = result.response.persistedOutputHash
                val next = ChapterCandidateStageBindingV1.stageSetup(
                    jobId = advance.jobId,
                    stageId = advance.memoryStageId,
                    phase = GenerationPhase.EXTRACT_MEMORY,
                    source = ChapterCandidateStageSourceV1(
                    role = ChapterCandidateArtifactRoleV1.POST_ANALYSIS,
                        candidateChapterVersionId = advance.candidateChapterVersionId,
                        candidateContentHash = contentHash,
                        chapterId = bound.chapterId,
                        chapterIndex = bound.chapterIndex,
                        revisionIndex = 0,
                        predecessorStageId = bound.request.stageId,
                    ),
                    maxAttempts = advance.memoryStageMaximumAttempts,
                )
                val seal = seals.seal(
                    permit,
                    ChapterCandidateArtifactSealDraftV1(
                        role = ChapterCandidateArtifactRoleV1.BODY,
                        candidateChapterVersionId = advance.candidateChapterVersionId,
                        chapterId = bound.chapterId,
                        chapterIndex = bound.chapterIndex,
                        candidateContentHash = contentHash,
                        canonicalOutputHash = contentHash,
                        sourceBindingHash = bound.sourceBindingHash,
                        revisionIndex = 0,
                        usage = result.execution.latestUsage.toFinalUsageCommit(),
                        nextStage = next,
                        sealedAt = now().coerceAtLeast(validatedAt),
                    ),
                )
                InitialChapterDraftExecutionResult.EnteredPostAnalysis(
                    seal, advance.candidateChapterVersionId, contentHash,
                )
            }
            is ChapterDraftStreamingResult.ContinuationSettled -> InitialChapterDraftExecutionResult.Continuation(result)
            else -> InitialChapterDraftExecutionResult.Other(result)
        }
    }

    private fun now(): Long = clock.nowMillis().also { require(it >= 0) }
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
private val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
