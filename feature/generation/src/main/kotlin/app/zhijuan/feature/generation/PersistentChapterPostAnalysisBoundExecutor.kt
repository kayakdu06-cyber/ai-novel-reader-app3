package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.ChapterPostAnalysisPromptSources
import app.zhijuan.core.database.generation.ChapterPostAnalysisPromptSourcesRepository
import app.zhijuan.core.database.generation.ChapterTrackingProjectionSourceRepository
import app.zhijuan.core.database.generation.GenerationRunnerCurrentStageRouteSnapshot
import app.zhijuan.core.database.generation.GenerationRunnerStageRoute
import app.zhijuan.core.database.generation.GenerationStreamingDraftRepository
import app.zhijuan.core.database.generation.RequestIntentDraft
import app.zhijuan.core.database.memory.NarrativeObligationV1
import app.zhijuan.core.database.memory.StoryStateKeyV1
import app.zhijuan.core.database.memory.StoryStateNamespaceV1
import app.zhijuan.core.model.StoryEntityType
import app.zhijuan.core.task.ChapterDeterministicConsistencyFactsV1
import app.zhijuan.core.task.ConsistencyEvidenceRange
import app.zhijuan.core.task.DeterministicEntityFactV1
import app.zhijuan.core.task.DeterministicEntityReferenceV1
import app.zhijuan.core.task.SceneExecutionContract
import app.zhijuan.provider.common.ProviderModelId
import app.zhijuan.provider.common.ProviderTimeoutPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

fun interface ChapterPostAnalysisBoundSourceLoader {
    suspend fun load(
        snapshot: GenerationRunnerCurrentStageRouteSnapshot,
        loadedAt: Long,
    ): ChapterPostAnalysisPromptSources

    companion object {
        fun from(repository: ChapterPostAnalysisPromptSourcesRepository): ChapterPostAnalysisBoundSourceLoader =
            ChapterPostAnalysisBoundSourceLoader(repository::loadBound)
    }
}

/** One exact-token production path from a sealed candidate BODY to its finite persisted route. */
class PersistentChapterPostAnalysisBoundExecutorV1(
    private val sources: ChapterPostAnalysisBoundSourceLoader,
    private val remote: GenerationBoundRemoteExecutionProvider,
    private val requests: GenerationStreamingDraftRepository,
    private val coordinator: ChapterPostAnalysisCoordinatorV1,
    private val routing: ChapterPostAnalysisRoutingCoordinatorV1,
    private val clock: GenerationExecutionClock = SystemGenerationExecutionClock,
    private val minimumBodyCodePoints: Int = 2_500,
    private val revisionStageMaximumAttempts: Int = 2,
) : ChapterPostAnalysisBoundExecutor {
    init {
        require(minimumBodyCodePoints in 1..1_000_000)
        require(revisionStageMaximumAttempts in 1..16)
    }

    override suspend fun executeBound(
        snapshot: GenerationRunnerCurrentStageRouteSnapshot,
        requestedAt: Long,
    ): ChapterPostAnalysisRoutingResultV1 {
        require(snapshot.route == GenerationRunnerStageRoute.CANDIDATE_CHAPTER_POST_ANALYSIS_V1)
        require(requestedAt >= 0L)
        val lease = snapshot.executionLease
        val source = sources.load(snapshot, requestedAt)
        require(source.stageId == lease.stageId)
        val execution = remote.resolve(snapshot, requestedAt)
        val ordinal = (snapshot.attemptCount + 1).toString()
        val attemptId = stableGenerationExecutionId("attempt", lease.jobId, lease.stageId, ordinal)
        val assembly = ChapterPostAnalysisBoundRequestAssemblerV1.assemble(
            source = source,
            requestId = stableGenerationExecutionId("request", lease.jobId, lease.stageId, ordinal),
            generationId = lease.jobId,
            attemptId = attemptId,
            modelId = execution.modelId,
            maximumOutputTokens = execution.maximumOutputTokens,
            timeouts = execution.timeouts,
            minimumBodyCodePoints = minimumBodyCodePoints,
        )
        val bound = when (val prepared = ChapterPostAnalysisRequestFactoryV1.prepare(assembly.requestSpec)) {
            is ChapterPostAnalysisRequestPreparationV1.Ready -> prepared.boundRequest
            is ChapterPostAnalysisRequestPreparationV1.LocalRevisionRequired ->
                throw IllegalStateException("Bound post-analysis candidate requires local revision.")
            is ChapterPostAnalysisRequestPreparationV1.SceneBlocked ->
                throw IllegalStateException("Bound post-analysis scene is blocked: ${prepared.reason}")
        }
        val persisted = requests.prepareBoundChapterPostAnalysisBeforeSend(
            snapshot = snapshot,
            draft = RequestIntentDraft(
                attemptId = attemptId,
                usageLedgerId = stableGenerationExecutionId("usage", lease.jobId, lease.stageId, ordinal),
                stageId = lease.stageId,
                retryParentAttemptId = null,
                connectionSnapshotJson = execution.connectionSnapshotJson,
                modelSnapshotJson = execution.modelSnapshotJson,
                protocolSnapshotJson = execution.protocolSnapshotJson,
                inputHash = bound.sourceBindingHash,
                streamDraftRef = null,
                createdAt = requestedAt,
            ),
            budget = execution.budget(
                stableGenerationExecutionId("budget", lease.jobId, lease.stageId, ordinal),
            ),
        )
        val result = coordinator.execute(persisted, execution.adapter, execution.profile, bound)
        val routedAt = clock.nowMillis().also { require(it >= requestedAt) }
        val nextStageId = stableGenerationExecutionId(
            "stage-route",
            lease.jobId,
            source.chapterId,
            source.revisionIndex.toString(),
        )
        val routeSpec = ChapterCandidateConsistencyRoutingSpecV1(
            candidate = ChapterCandidatePipelineIdentityV1(
                chapterVersionId = source.candidateChapterVersionId,
                chapterId = source.chapterId,
                chapterIndex = source.chapterIndex,
                contentHash = source.candidateContentHash,
                revisionIndex = source.revisionIndex,
                routeBindingHash = source.routeBindingHash,
            ),
            candidateContent = source.bodyText,
            candidateContentHashHistory = source.candidateContentHashHistory,
            minimumBodyCodePoints = minimumBodyCodePoints,
            totalRevisionAttemptsUsed = source.revisionIndex,
            revisionStageMaximumAttempts = revisionStageMaximumAttempts,
            nextStageId = nextStageId,
            expectedCurrentVersionId = source.expectedCurrentVersionId,
            revisionRequest = ChapterCandidateRevisionRequestSeedV1(
                requestId = stableGenerationExecutionId("request-revise", lease.jobId, nextStageId),
                generationId = lease.jobId,
                attemptId = stableGenerationExecutionId("attempt-revise", lease.jobId, nextStageId),
                modelId = execution.modelId,
                sceneExecutionContract = assembly.planExpectation.base.sceneExecutionContract,
                sceneParticipantEntityIds = assembly.sceneParticipantEntityIds,
                requiredProcessNodeIds = assembly.plan.basePlan.requiredProcessNodeIds.toSet(),
                knownEntities = assembly.knownEntities,
                maximumOutputTokens = execution.maximumOutputTokens,
                timeouts = execution.timeouts,
                idempotencyKey = nextStageId,
            ),
            routedAt = routedAt,
        )
        return when (result) {
            is ChapterPostAnalysisResultV1.Accepted -> routing.route(result, bound, routeSpec)
            is ChapterPostAnalysisResultV1.RevisionRequired -> routing.route(result, bound, routeSpec)
            is ChapterPostAnalysisResultV1.RepairRequired ->
                throw IllegalStateException("Bound post-analysis output requires structured repair.")
            is ChapterPostAnalysisResultV1.NeedsAction ->
                throw IllegalStateException("Bound post-analysis output needs user action.")
            is ChapterPostAnalysisResultV1.Other ->
                throw IllegalStateException("Bound post-analysis remote execution did not complete.")
        }
    }
}

internal data class ChapterPostAnalysisBoundAssemblyV1(
    val requestSpec: ChapterPostAnalysisRequestSpecV1,
    val plan: ChapterPlanV2,
    val planExpectation: ChapterPlanExpectationV2,
    val sceneParticipantEntityIds: Set<String>,
    val knownEntities: List<ChapterConsistencyKnownEntityV1>,
)

internal object ChapterPostAnalysisBoundRequestAssemblerV1 {
    fun assemble(
        source: ChapterPostAnalysisPromptSources,
        requestId: String,
        generationId: String,
        attemptId: String,
        modelId: ProviderModelId,
        maximumOutputTokens: Int,
        timeouts: ProviderTimeoutPolicy,
        minimumBodyCodePoints: Int,
    ): ChapterPostAnalysisBoundAssemblyV1 {
        val expectation = ChapterPlanV2RequestFactory.restoreExpectation(source.frozenPlan)
        val plan = when (val parsed = ChapterPlanV2Parser().parse(source.canonicalPlanJson.encodeToByteArray())) {
            is PlanningOutputValidationResult.Valid -> parsed.value
            is PlanningOutputValidationResult.Invalid ->
                throw IllegalArgumentException("Frozen post-analysis chapter plan is invalid.")
        }
        when (val validation = ChapterPlanV2BusinessValidator.validate(plan, expectation)) {
            is ChapterPlanV2BusinessResult.Valid -> Unit
            is ChapterPlanV2BusinessResult.Invalid -> throw IllegalArgumentException(
                "Frozen post-analysis chapter plan violates its expectation: ${validation.issues}",
            )
        }
        require(
            plan.chapterId == source.chapterId && plan.chapterIndex == source.chapterIndex &&
                plan.contextContentHash == source.context.contentHash &&
                plan.contextSourceManifestHash == source.context.sourceManifestHash,
        ) { "Frozen post-analysis plan no longer matches its source chain." }
        val confirmedAdultFictional = expectation.base.confirmedAdultFictionalCharacterIds
        val known = source.knownEntities.sortedBy { it.entityId }.map { entity ->
            ChapterConsistencyKnownEntityV1(
                entityId = entity.entityId,
                canonicalName = entity.canonicalName,
                entityType = entity.entityType,
                adultStatus = entity.adultStatus,
                ageYears = entity.ageYears,
                realIdentifiablePerson = entity.entityType == StoryEntityType.CHARACTER &&
                    entity.entityId !in confirmedAdultFictional,
            )
        }
        val participants = plan.basePlan.scenes
            .filter { it.intimacyRelevant }
            .flatMapTo(linkedSetOf()) { it.participantCharacterIds }
        if (expectation.base.sceneExecutionContract is SceneExecutionContract.Allowed) {
            require(participants.isNotEmpty() && participants.all(confirmedAdultFictional::contains)) {
                "Relevant scene participants no longer match the adult-fictional gate."
            }
        } else {
            require(participants.isEmpty())
        }
        val bodyCodePoints = source.bodyText.codePointCount(0, source.bodyText.length)
        val knownById = known.associateBy { it.entityId }
        val references = participants.map { entityId ->
            val name = requireNotNull(knownById[entityId]).canonicalName
            val utf16Start = source.bodyText.indexOf(name)
            require(utf16Start >= 0) { "Relevant scene participant is absent from the candidate BODY." }
            val start = source.bodyText.codePointCount(0, utf16Start)
            val end = start + name.codePointCount(0, name.length)
            DeterministicEntityReferenceV1(
                entityId = entityId,
                adultRelevant = true,
                evidenceRange = ConsistencyEvidenceRange(start, end),
            )
        }
        val deterministic = ChapterDeterministicConsistencyFactsV1(
            currentChapterIndex = source.chapterIndex,
            expectedChapterIndex = expectation.base.chapterIndex,
            entities = known.map { entity ->
                DeterministicEntityFactV1(
                    entity.entityId,
                    entity.entityType,
                    entity.adultStatus,
                    entity.ageYears,
                )
            },
            references = references,
            characterReturns = emptyList(),
            locationConstraints = emptyList(),
            itemOwnershipConstraints = emptyList(),
            timelineConstraints = emptyList(),
            requiredEvents = emptyList(),
        )
        require(bodyCodePoints > 0)
        val common = RemoteIdentityV1(
            requestId = requestId,
            generationId = generationId,
            stageId = source.stageId,
            attemptId = attemptId,
        )
        val memory = ChapterMemoryExtractionRequestSpec(
            requestId = common.requestId,
            generationId = common.generationId,
            stageId = common.stageId,
            attemptId = common.attemptId,
            modelId = modelId,
            sourceChapterVersionId = source.candidateChapterVersionId,
            sourceChapterContentHash = source.candidateContentHash,
            chapterId = source.chapterId,
            chapterIndex = source.chapterIndex,
            chapterContent = source.bodyText,
            knownEntities = source.knownEntities.sortedBy { it.entityId }.map { entity ->
                ChapterMemoryKnownEntity(
                    entity.entityId,
                    entity.canonicalName,
                    entity.entityType,
                    entity.adultStatus,
                )
            },
            maximumOutputTokens = maximumOutputTokens,
            timeouts = timeouts,
            idempotencyKey = source.stageIdempotencyKey,
        )
        val tracking = ChapterTrackingExpectation(
            sourceChapterVersionId = source.candidateChapterVersionId,
            sourceChapterContentHash = source.candidateContentHash,
            chapterId = source.chapterId,
            chapterIndex = source.chapterIndex,
            memorySnapshotHash = source.context.contentHash,
            priorForeshadowSnapshotHash = ChapterTrackingProjectionSourceRepository.foreshadowSnapshotHash(
                source.priorForeshadows,
            ),
            knownEntitySnapshotHash = ChapterTrackingProjectionSourceRepository.entitySnapshotHash(
                source.knownEntities,
            ),
            knownEntities = source.knownEntities.associate { it.entityId to it.entityType },
            priorForeshadows = source.priorForeshadows.associate { item ->
                item.foreshadowItemId to TrackingKnownForeshadow(
                    foreshadowItemId = item.foreshadowItemId,
                    description = item.description,
                    status = item.foreshadowStatus,
                    visibleEntityIds = strictStringSet(item.visibleEntityIdsJson),
                    importance = item.importance,
                )
            },
        )
        val consistency = ChapterConsistencyCheckRequestSpec(
            requestId = common.requestId,
            generationId = common.generationId,
            stageId = common.stageId,
            attemptId = common.attemptId,
            modelId = modelId,
            sourceChapterVersionId = source.candidateChapterVersionId,
            sourceChapterContentHash = source.candidateContentHash,
            chapterId = source.chapterId,
            chapterIndex = source.chapterIndex,
            chapterContent = source.bodyText,
            minimumBodyCodePoints = minimumBodyCodePoints,
            deterministicFacts = deterministic,
            sceneExecutionContract = expectation.base.sceneExecutionContract,
            sceneParticipantEntityIds = participants,
            requiredProcessNodeIds = plan.basePlan.requiredProcessNodeIds.toSet(),
            knownEntities = known,
            evidenceItems = evidenceItems(source),
            maximumOutputTokens = maximumOutputTokens,
            timeouts = timeouts,
            idempotencyKey = source.stageIdempotencyKey,
        )
        return ChapterPostAnalysisBoundAssemblyV1(
            requestSpec = ChapterPostAnalysisRequestSpecV1(
                memory = memory,
                trackingExpectation = tracking,
                consistency = consistency,
                narrative = narrativeExpectation(plan, expectation),
            ),
            plan = plan,
            planExpectation = expectation,
            sceneParticipantEntityIds = participants,
            knownEntities = known,
        )
    }

    private fun narrativeExpectation(
        plan: ChapterPlanV2,
        expectation: ChapterPlanExpectationV2,
    ): ChapterPostAnalysisNarrativeExpectationV1 {
        val active = expectation.activeStateNamespaces.map(::stateNamespace).toSet()
        require(active.isNotEmpty()) { "Frozen chapter has no supported active state namespace." }
        val actions = plan.obligationActions.associateBy { it.obligationId }
        val obligations = expectation.priorObligationIds.sorted().map { id ->
            val action = requireNotNull(actions[id]) { "Frozen prior obligation disappeared from the plan." }
            NarrativeObligationV1(id, action.plannedEvidence, action.nextDueChapterIndex)
        }
        val current = linkedMapOf<StoryStateKeyV1, String>()
        plan.expectedStateDeltas.forEach { delta ->
            val namespace = stateNamespace(delta.namespace)
            val key = StoryStateKeyV1(namespace, delta.entityId, delta.attribute, delta.relatedEntityId)
            delta.oldValueJson?.let { current[key] = it }
        }
        return ChapterPostAnalysisNarrativeExpectationV1(active, obligations, current)
    }

    private fun evidenceItems(source: ChapterPostAnalysisPromptSources): List<ChapterConsistencyEvidenceItemV1> {
        val context = runCatching { STRICT_JSON.parseToJsonElement(source.context.providerPayloadJson) as JsonObject }
            .getOrElse { throw IllegalArgumentException("Frozen chapter context payload is invalid.") }
        val layers = context["layers"] as? JsonArray
            ?: throw IllegalArgumentException("Frozen chapter context layers are missing.")
        val contextItems = layers.mapIndexedNotNull { index, element ->
            val layer = element as? JsonObject ?: return@mapIndexedNotNull null
            val kind = (layer["kind"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
                ?: return@mapIndexedNotNull null
            val evidenceKind = when (kind) {
                "ADULT_AND_IDENTITY_FACT", "BIBLE_WORLD_RULE", "BIBLE_HARD_FACT", "FORBIDDEN_CHANGE",
                "PREVIOUS_CHAPTER_SUMMARY", "RECENT_CHAPTER_SUMMARY", "RUNTIME_HISTORY" ->
                    ChapterConsistencyEvidenceKindV1.HARD_FACT
                "CURRENT_STATE" -> ChapterConsistencyEvidenceKindV1.ENTITY_STATE
                "TIMELINE_HISTORY" -> ChapterConsistencyEvidenceKindV1.TIMELINE_EVENT
                "TARGET_ARC", "TARGET_CHAPTER_PLAN", "DISTANT_PLAN" ->
                    ChapterConsistencyEvidenceKindV1.REQUIRED_EVENT
                else -> null
            } ?: return@mapIndexedNotNull null
            val content = (layer["content"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
                ?: return@mapIndexedNotNull null
            val payload = runCatching { STRICT_JSON.parseToJsonElement(content) as JsonObject }
                .getOrElse { JsonObject(mapOf("content" to JsonPrimitive(content))) }
            ChapterConsistencyEvidenceItemV1("context-$index", evidenceKind, payload.toString())
        }
        val foreshadows = source.priorForeshadows.map { item ->
            ChapterConsistencyEvidenceItemV1(
                evidenceId = item.foreshadowItemId,
                kind = ChapterConsistencyEvidenceKindV1.FORESHADOW_STATE,
                payloadJson = JsonObject(
                    linkedMapOf(
                        "foreshadowItemId" to JsonPrimitive(item.foreshadowItemId),
                        "description" to JsonPrimitive(item.description),
                        "status" to JsonPrimitive(item.foreshadowStatus.name),
                        "visibleEntityIds" to JsonArray(strictStringSet(item.visibleEntityIdsJson).sorted().map(::JsonPrimitive)),
                        "importance" to JsonPrimitive(item.importance),
                    ),
                ).toString(),
            )
        }
        return contextItems + foreshadows
    }

    private fun strictStringSet(value: String): Set<String> {
        val array = runCatching { STRICT_JSON.parseToJsonElement(value) as JsonArray }
            .getOrElse { throw IllegalArgumentException("Frozen visible-entity ids are invalid.") }
        return array.map { element ->
            (element as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
                ?: throw IllegalArgumentException("Frozen visible-entity id is invalid.")
        }.toSet()
    }

    private fun stateNamespace(value: String): StoryStateNamespaceV1 = when (value.lowercase()) {
        "narrative", "mystery", "faction" -> StoryStateNamespaceV1.WORLD
        "character" -> StoryStateNamespaceV1.CHARACTER
        "relationship", "intimacy", "romance" -> StoryStateNamespaceV1.RELATIONSHIP
        "item" -> StoryStateNamespaceV1.ITEM
        "system" -> StoryStateNamespaceV1.SYSTEM
        "cultivation" -> StoryStateNamespaceV1.CULTIVATION
        "world" -> StoryStateNamespaceV1.WORLD
        else -> throw IllegalArgumentException("Unsupported frozen state namespace: $value")
    }

    private data class RemoteIdentityV1(
        val requestId: String,
        val generationId: String,
        val stageId: String,
        val attemptId: String,
    )

    private val STRICT_JSON = Json { isLenient = false; ignoreUnknownKeys = false }
}
