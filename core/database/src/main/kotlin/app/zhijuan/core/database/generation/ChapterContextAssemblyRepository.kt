package app.zhijuan.core.database.generation

import androidx.room.withTransaction
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.database.memory.ContextSnapshotEntity
import app.zhijuan.core.database.memory.OutlineNodeEntity
import app.zhijuan.core.database.memory.OutlineRevisionEntity
import app.zhijuan.core.database.search.CanonFactSourceV1
import app.zhijuan.core.database.search.ChapterSummarySourceV1
import app.zhijuan.core.database.search.EntityEventSourceV1
import app.zhijuan.core.database.search.ForeshadowSourceV1
import app.zhijuan.core.database.search.MemoryContextRouteSelectionItemV1
import app.zhijuan.core.database.search.MemoryContextRouteSelectionRepositoryV1
import app.zhijuan.core.database.search.MemoryContextRouteSelectionResultV1
import app.zhijuan.core.database.search.MemoryContextRouteV1
import app.zhijuan.core.database.search.MemoryContextSelectionStatusV1
import app.zhijuan.core.database.search.MemorySearchBackfillRepositoryV1
import app.zhijuan.core.database.search.MemorySearchSourceTypeV1
import app.zhijuan.core.database.search.StoryEntitySourceV1
import app.zhijuan.core.database.search.TimelineEventSourceV1
import app.zhijuan.core.model.CanonLevel
import app.zhijuan.core.model.DerivedDataStatus
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationJobType
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.model.OutlineNodeType
import app.zhijuan.core.model.StandardErrorCode
import app.zhijuan.core.task.ChapterContextAssemblyResult
import app.zhijuan.core.task.ChapterContextBlockReason
import app.zhijuan.core.task.ChapterContextBudgetPolicyV1
import app.zhijuan.core.task.ChapterContextBudgetSpec
import app.zhijuan.core.task.ChapterContextCandidate
import app.zhijuan.core.task.ChapterContextKind
import app.zhijuan.core.task.ChapterContextLimitSource
import app.zhijuan.core.task.ChapterContextSourceRef
import app.zhijuan.core.task.GenerationJobStateMachine
import app.zhijuan.core.task.GenerationStageStateMachine
import app.zhijuan.core.task.JobEvent
import app.zhijuan.core.task.PromptBundleCatalogV1
import app.zhijuan.core.task.StageEvent
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Exact-token chapter-context execution entry for a Phase 2B bound route snapshot. The
 * implementation must revalidate the persisted Job/Stage tokens, cursor, status, attempt bounds,
 * operation time and lease expiry inside the same Room transaction that commits business state,
 * and must never create attempts or open providers.
 */
fun interface ChapterContextAssemblyBoundExecutorV1 {
    suspend fun assembleBound(
        snapshot: GenerationRunnerCurrentStageRouteSnapshot,
        assembledAt: Long,
    ): PersistedChapterContextAssemblyResult
}

data class ReadyChapterContext(
    val contextSnapshotId: String,
    val contextStageId: String,
    val chapterPlanStageId: String,
    val providerPayloadJson: String,
    val contentHash: String,
    val sourceManifestHash: String,
    val selectedItemCount: Int,
    val omittedItemCount: Int,
    val estimatedInputTokens: Int,
    val inputBudgetTokens: Int,
    val replayed: Boolean,
) {
    override fun toString(): String =
        "ReadyChapterContext(selected=$selectedItemCount, omitted=$omittedItemCount, " +
            "tokens=$estimatedInputTokens/$inputBudgetTokens, replayed=$replayed, content=redacted)"
}

sealed interface PersistedChapterContextAssemblyResult {
    data class Ready(val context: ReadyChapterContext) : PersistedChapterContextAssemblyResult

    data class Blocked(
        val reason: ChapterContextBlockReason,
        val standardErrorCode: StandardErrorCode,
        val effectiveContextLimitTokens: Int?,
        val inputBudgetTokens: Int?,
        val requiredEstimatedTokens: Int?,
        val missingKinds: Set<ChapterContextKind>,
    ) : PersistedChapterContextAssemblyResult
}

/**
 * Builds a chapter context only from current persisted sources. This local stage creates no
 * request-attempt or usage rows. The immutable manifest contains enough selected content to
 * reproduce the exact Provider payload without retaining a second mutable copy. Besides the
 * legacy [assemble] entry, [assembleBound] executes the exact Phase 2B bound route snapshot and
 * revalidates both persisted lease tokens, cursor, status, attempt bounds, time and expiry inside
 * the same transaction before reusing the shared assembly and commit path.
 */
class ChapterContextAssemblyRepository(
    private val database: ZhijuanDatabase,
    private val leasePolicy: GenerationLeasePolicy = GenerationLeasePolicy(),
) : ChapterContextAssemblyBoundExecutorV1 {
    suspend fun assemble(
        stageId: String,
        leaseToken: GenerationLeaseToken,
        assembledAt: Long,
    ): PersistedChapterContextAssemblyResult {
        require(IDENTIFIER.matches(stageId)) { "Chapter-context stage id is invalid." }
        require(assembledAt >= 0L) { "Chapter-context assembly time is invalid." }
        return database.withTransaction {
            val generation = database.generationDao()
            val stage = requireNotNull(generation.findStage(stageId)) {
                "Chapter-context stage does not exist."
            }
            val job = requireNotNull(generation.findJob(stage.jobId)) {
                "Chapter-context job does not exist."
            }
            assembleInternal(stage, job, leaseToken, assembledAt)
        }
    }

    /**
     * Bound execution entry for the exact [GenerationRunnerCurrentStageRouteSnapshot] of a
     * [GenerationRunnerStageRoute.CHAPTER_CONTEXT_ASSEMBLY_V1] stage. The persisted Job/Stage rows
     * are re-read and every exact-token, cursor, status, attempt-bound, time and lease-expiry fact
     * is revalidated inside the same Room transaction that commits the shared assembly path, so
     * there is no read-then-write window between revalidation and business commit. A stage already
     * SUCCEEDED replays its durable snapshot without writing anything again.
     */
    override suspend fun assembleBound(
        snapshot: GenerationRunnerCurrentStageRouteSnapshot,
        assembledAt: Long,
    ): PersistedChapterContextAssemblyResult {
        require(snapshot.route == GenerationRunnerStageRoute.CHAPTER_CONTEXT_ASSEMBLY_V1) {
            "bound execution route is not the chapter-context assembly route."
        }
        val lease = snapshot.executionLease
        require(
            lease.jobStatus == GenerationJobStatus.RUNNING &&
                lease.stageStatus == GenerationStageStatus.PREPARING &&
                lease.jobLeaseToken.ownerId == lease.stageLeaseToken.ownerId,
        ) { "bound execution snapshot is not executable." }
        require(assembledAt >= 0L) { "bound execution time is invalid." }
        return database.withTransaction {
            val generation = database.generationDao()
            val stage = requireNotNull(generation.findStage(snapshot.executionLease.stageId)) {
                "bound execution stage does not exist."
            }
            val job = requireNotNull(generation.findJob(stage.jobId)) {
                "bound execution job does not exist."
            }
            require(snapshot.executionLease.jobId == stage.jobId) {
                "bound execution snapshot job identity is inconsistent."
            }
            if (stage.status == GenerationStageStatus.SUCCEEDED) {
                return@withTransaction PersistedChapterContextAssemblyResult.Ready(
                    replaySucceeded(stage, job),
                )
            }
            requireBoundExecutionEvidence(stage, job, snapshot, assembledAt)
            assembleInternal(stage, job, snapshot.executionLease.stageLeaseToken, assembledAt)
        }
    }

    /**
     * Shared single-transaction assembly and commit path used by both [assemble] and
     * [assembleBound]. It never opens its own transaction and never creates attempts or providers.
     */
    private suspend fun assembleInternal(
        stage: GenerationStageEntity,
        job: GenerationJobEntity,
        leaseToken: GenerationLeaseToken,
        assembledAt: Long,
    ): PersistedChapterContextAssemblyResult {
        val generation = database.generationDao()
        val memory = database.memoryDao()
        if (stage.status == GenerationStageStatus.SUCCEEDED) {
            return PersistedChapterContextAssemblyResult.Ready(replaySucceeded(stage, job))
        }
        require(
            stage.phase == GenerationPhase.ASSEMBLE_CONTEXT &&
                stage.targetType == GenerationTargetType.CHAPTER &&
                job.jobType == GenerationJobType.CONTINUE_BOOK &&
                job.promptBundleVersion == PromptBundleCatalogV1.BUNDLE_VERSION,
        ) { "Chapter-context stage contract is invalid." }
        require(stage.status == GenerationStageStatus.PREPARING) {
            "Chapter-context assembly can only run from PREPARING."
        }
        require(
            job.status in setOf(GenerationJobStatus.RUNNING, GenerationJobStatus.PAUSING) &&
                job.currentStageId == stage.stageId,
        ) { "Chapter-context job is not running this stage." }
        requireActiveLeases(stage, job, leaseToken, assembledAt)
        require(sha256(stage.inputSourcesJson) == stage.inputVersionHash) {
            "Chapter-context frozen input hash is inconsistent."
        }
        ChapterProgressionGateRepository(database).requireContextAssemblyAllowed(stage, job)

        val frozen = parseFrozenInput(stage)
        val chapter = requireNotNull(database.libraryDao().findChapter(stage.targetId)) {
            "Chapter-context target chapter is missing."
        }
        require(chapter.bookId == job.bookId && chapter.chapterIndex == frozen.targetChapterIndex) {
            "Chapter-context target changed after job creation."
        }
        val bundle = PromptBundleBindingRepository(database).bindForBook(job.bookId)
        require(bundle.bindingHash == frozen.promptBindingHash) {
            "Chapter-context Prompt Bundle binding is stale."
        }
        val searchBackfill = MemorySearchBackfillRepositoryV1(database)
        searchBackfill.ensureReady(job.bookId, assembledAt)
        var sources = loadAuthoritativeSources(
            bookId = job.bookId,
            targetChapterIndex = chapter.chapterIndex,
            userAddition = frozen.userAddition,
        )
        if (sources.memorySelection.indexRebuildRequired) {
            searchBackfill.rebuild(job.bookId, assembledAt)
            sources = loadAuthoritativeSources(
                bookId = job.bookId,
                targetChapterIndex = chapter.chapterIndex,
                userAddition = frozen.userAddition,
            )
        }
        val memoryBlockReason = when {
            sources.memorySelection.status == MemoryContextSelectionStatusV1.MANDATORY_OVERFLOW ->
                ChapterContextBlockReason.MANDATORY_MEMORY_SELECTION_EXCEEDS_LIMIT

            sources.memorySelection.indexRebuildRequired ->
                ChapterContextBlockReason.MEMORY_SEARCH_INDEX_INVALID

            else -> null
        }
        if (memoryBlockReason != null) {
            val error = memoryBlockReason.toStandardErrorCode()
            persistBlocked(stage, job, leaseToken, assembledAt, memoryBlockReason, error)
            return PersistedChapterContextAssemblyResult.Blocked(
                reason = memoryBlockReason,
                standardErrorCode = error,
                effectiveContextLimitTokens = frozen.budget.contextLimitTokens,
                inputBudgetTokens = null,
                requiredEstimatedTokens = null,
                missingKinds = emptySet(),
            )
        }
        val candidates = buildCandidates(bundle, frozen, sources)
        return when (
            val assembled = ChapterContextBudgetPolicyV1.assemble(
                targetChapterIndex = chapter.chapterIndex,
                budget = frozen.budget,
                candidates = candidates,
            )
        ) {
            is ChapterContextAssemblyResult.Blocked -> {
                val error = assembled.reason.toStandardErrorCode()
                persistBlocked(stage, job, leaseToken, assembledAt, assembled.reason, error)
                PersistedChapterContextAssemblyResult.Blocked(
                    reason = assembled.reason,
                    standardErrorCode = error,
                    effectiveContextLimitTokens = assembled.effectiveContextLimitTokens,
                    inputBudgetTokens = assembled.inputBudgetTokens,
                    requiredEstimatedTokens = assembled.requiredEstimatedTokens,
                    missingKinds = assembled.missingKinds,
                )
            }
            is ChapterContextAssemblyResult.Ready -> {
                val next = requireNextPlanStage(stage, job)
                val manifest = enrichManifest(
                    policyManifest = assembled.sourceManifestJson,
                    stage = stage,
                    frozen = frozen,
                    sources = sources,
                )
                val snapshotId = deterministicSnapshotId(job.bookId, stage.stageId)
                val snapshot = ContextSnapshotEntity(
                    contextSnapshotId = snapshotId,
                    bookId = job.bookId,
                    targetChapterId = chapter.chapterId,
                    targetChapterIndex = chapter.chapterIndex,
                    generationStageId = stage.stageId,
                    sourceManifestJson = manifest,
                    contentHash = assembled.contentHash,
                    status = DerivedDataStatus.VALID,
                    createdAt = assembledAt,
                    updatedAt = assembledAt,
                )
                val manifestHash = sha256(manifest)
                val output = outputReference(snapshot, next.stageId, assembled, manifestHash)
                check(
                    GenerationStageStateMachine.transition(
                        stage.status,
                        StageEvent.LOCAL_OUTPUT_READY,
                    ) == GenerationStageStatus.COMMITTING,
                )
                generation.transitionStage(
                    stageId = stage.stageId,
                    expectedStatus = GenerationStageStatus.PREPARING,
                    event = StageEvent.LOCAL_OUTPUT_READY,
                    updatedAt = assembledAt,
                    leaseToken = leaseToken,
                    leasePolicy = leasePolicy,
                )
                memory.insertContextSnapshot(snapshot)
                if (
                    generation.compareAndCommitStageOutput(
                        stageId = stage.stageId,
                        leaseOwnerId = leaseToken.ownerId,
                        leaseAcquiredAt = leaseToken.acquiredAt,
                        outputReferenceJson = output,
                        updatedAt = assembledAt,
                    ) != 1
                ) {
                    throw StaleGenerationStateException(
                        "Chapter-context commit lost the current stage lease.",
                    )
                }
                check(
                    GenerationStageStateMachine.transition(
                        next.status,
                        StageEvent.DEPENDENCIES_SATISFIED,
                    ) == GenerationStageStatus.READY,
                )
                val stageAdvanced = generation.compareAndSetStageStatus(
                    stageId = next.stageId,
                    expectedStatus = GenerationStageStatus.PENDING,
                    nextStatus = GenerationStageStatus.READY,
                    errorCode = null,
                    nextRetryAt = null,
                    updatedAt = assembledAt,
                )
                val jobAdvanced = if (job.status == GenerationJobStatus.PAUSING) {
                    generation.compareAndPauseJobAfterStage(
                        jobId = job.jobId,
                        expectedCurrentStageId = stage.stageId,
                        nextStageId = next.stageId,
                        updatedAt = assembledAt,
                    )
                } else {
                    generation.compareAndAdvanceJobStage(
                        jobId = job.jobId,
                        expectedCurrentStageId = stage.stageId,
                        nextStageId = next.stageId,
                        updatedAt = assembledAt,
                    )
                }
                if (stageAdvanced != 1 || jobAdvanced != 1) {
                    throw StaleGenerationStateException(
                        "Chapter-plan activation lost a concurrent update.",
                    )
                }
                PersistedChapterContextAssemblyResult.Ready(
                    readyResult(snapshot, next.stageId, assembled.providerPayloadJson, output, replayed = false),
                )
            }
        }
    }

    /** Revalidates and returns the exact context immediately before a chapter-plan request opens. */
    suspend fun loadForChapterPlanStage(
        stageId: String,
        validatedAt: Long,
    ): ReadyChapterContext = database.withTransaction {
        val generation = database.generationDao()
        val stage = requireNotNull(generation.findStage(stageId)) { "Chapter-plan stage is missing." }
        val job = requireNotNull(generation.findJob(stage.jobId)) { "Chapter-plan job is missing." }
        require(validatedAt >= stage.updatedAt && validatedAt >= job.updatedAt) {
            "Chapter-context validation time cannot move backwards."
        }
        require(
            job.status == GenerationJobStatus.RUNNING && job.currentStageId == stage.stageId &&
                stage.status in setOf(
                    GenerationStageStatus.PREPARING,
                    GenerationStageStatus.REQUEST_INTENT_RECORDED,
                ),
        ) { "Chapter-plan stage is not ready to consume assembled context." }
        requireProviderOpenAllowed(stage, job)
    }

    internal suspend fun requireProviderOpenAllowed(
        stage: GenerationStageEntity,
        job: GenerationJobEntity,
    ): ReadyChapterContext {
        if (stage.phase != GenerationPhase.BUILD_CHAPTER_PLAN) {
            throw IllegalArgumentException("Only a chapter-plan stage can consume chapter context.")
        }
        val planInput = parseObject(stage.inputSourcesJson, "Chapter-plan input sources")
        val contextStageId = planInput.optionalString("contextAssemblyStageId")
            ?: throw StaleGenerationStateException("Chapter-plan stage is missing assembled context evidence.")
        require(planInput.string("contextPolicyVersion") == ChapterContextBudgetPolicyV1.POLICY_VERSION)
        require(planInput.string("contextManifestSchemaId") == ChapterContextBudgetPolicyV1.MANIFEST_SCHEMA_ID)
        val contextStage = requireNotNull(database.generationDao().findStage(contextStageId)) {
            "Frozen chapter-context stage is missing."
        }
        require(
            contextStage.jobId == job.jobId && contextStage.targetId == stage.targetId &&
                contextStage.phase == GenerationPhase.ASSEMBLE_CONTEXT &&
                contextStage.status == GenerationStageStatus.SUCCEEDED &&
                contextStage.inputVersionHash == planInput.string("contextInputVersionHash"),
        ) { "Chapter-plan context dependency is stale or invalid." }
        val snapshot = requireNotNull(database.memoryDao().findContextSnapshotForStage(contextStageId)) {
            "Successful chapter-context stage is missing its snapshot."
        }
        require(snapshot.status == DerivedDataStatus.VALID && snapshot.targetChapterId == stage.targetId) {
            "Chapter-plan context snapshot is stale or belongs to another target."
        }
        val output = parseObject(
            requireNotNull(contextStage.outputReferenceJson),
            "Chapter-context output reference",
        )
        require(
            output.string("outputSchemaId") == ChapterContextBudgetPolicyV1.MANIFEST_SCHEMA_ID &&
                output.string("policyVersion") == ChapterContextBudgetPolicyV1.POLICY_VERSION &&
                output.string("contextSnapshotId") == snapshot.contextSnapshotId &&
                output.string("contentHash") == snapshot.contentHash &&
                output.string("sourceManifestHash") == sha256(snapshot.sourceManifestJson) &&
                output.string("nextStageId") == stage.stageId,
        ) { "Chapter-context output reference no longer matches its immutable snapshot." }
        val manifest = parseObject(snapshot.sourceManifestJson, "Chapter-context source manifest")
        requireCurrentAssemblyEvidence(manifest, contextStage, stage, job, snapshot)
        requireCurrentContextProjection(manifest, contextStage, job, snapshot)
        val payload = rebuildProviderPayload(manifest)
        require(sha256(payload) == snapshot.contentHash) {
            "Rebuilt chapter-context payload hash does not match the snapshot."
        }
        return readyResult(snapshot, stage.stageId, payload, contextStage.outputReferenceJson, replayed = true)
    }

    internal suspend fun requireProviderOpenAllowedIfBound(
        stage: GenerationStageEntity,
        job: GenerationJobEntity,
    ): ReadyChapterContext? {
        if (stage.phase != GenerationPhase.BUILD_CHAPTER_PLAN) return null
        val input = parseObject(stage.inputSourcesJson, "Chapter-plan input sources")
        if (input.optionalStringOrMissing("contextAssemblyStageId") == null) return null
        return requireProviderOpenAllowed(stage, job)
    }

    private suspend fun replaySucceeded(
        stage: GenerationStageEntity,
        job: GenerationJobEntity,
    ): ReadyChapterContext {
        require(
            stage.phase == GenerationPhase.ASSEMBLE_CONTEXT &&
                stage.targetType == GenerationTargetType.CHAPTER &&
                job.promptBundleVersion == PromptBundleCatalogV1.BUNDLE_VERSION,
        ) { "Completed stage is not a supported chapter-context stage." }
        val snapshot = requireNotNull(database.memoryDao().findContextSnapshotForStage(stage.stageId)) {
            "Completed chapter-context stage is missing its snapshot."
        }
        require(snapshot.status == DerivedDataStatus.VALID)
        val output = requireNotNull(stage.outputReferenceJson)
        val parsed = parseObject(output, "Chapter-context replay output")
        require(
            parsed.string("contextSnapshotId") == snapshot.contextSnapshotId &&
                parsed.string("contentHash") == snapshot.contentHash &&
                parsed.string("sourceManifestHash") == sha256(snapshot.sourceManifestJson),
        ) { "Completed chapter-context replay evidence changed." }
        val payload = rebuildProviderPayload(
            parseObject(snapshot.sourceManifestJson, "Chapter-context replay manifest"),
        )
        require(sha256(payload) == snapshot.contentHash)
        return readyResult(snapshot, parsed.string("nextStageId"), payload, output, replayed = true)
    }

    private suspend fun loadAuthoritativeSources(
        bookId: String,
        targetChapterIndex: Int,
        userAddition: String?,
    ): AuthoritativeContextSources {
        val memory = database.memoryDao()
        val head = requireNotNull(memory.findMemoryHead(bookId)) { "Book memory head is missing." }
        val bible = requireNotNull(head.currentBibleRevisionId?.let { memory.findBibleRevision(it) }) {
            "Current Story Bible revision is missing."
        }
        require(bible.bookId == bookId && sha256(bible.payloadJson) == bible.contentHash) {
            "Current Story Bible revision is invalid."
        }
        val currentOutline = requireNotNull(
            head.currentOutlineRevisionId?.let { memory.findOutlineRevision(it) },
        ) { "Current outline revision is missing." }
        require(currentOutline.bookId == bookId && sha256(currentOutline.summaryJson) == currentOutline.contentHash)
        val targetWindow = findTargetWindow(currentOutline, targetChapterIndex)
        val nodes = memory.findOutlineNodes(targetWindow.outlineRevisionId)
        val targetChapterNode = nodes.singleOrNull {
            it.nodeType == OutlineNodeType.CHAPTER && it.plannedChapterIndex == targetChapterIndex
        } ?: throw IllegalStateException("Target chapter plan is missing from its outline window.")
        val targetArcNode = nodes.singleOrNull {
            it.nodeType == OutlineNodeType.ARC && it.outlineNodeId == targetChapterNode.parentNodeId
        } ?: throw IllegalStateException("Target chapter arc is missing from its outline window.")
        require(
            sha256(targetChapterNode.planJson) == targetChapterNode.contentHash &&
                sha256(targetArcNode.planJson) == targetArcNode.contentHash,
        ) { "Target outline node content hash is invalid." }
        val root = nodes.singleOrNull { it.nodeType == OutlineNodeType.BOOK }
        root?.let { require(sha256(it.planJson) == it.contentHash) }

        val previousChapter = if (targetChapterIndex > 1) {
            database.libraryDao().chaptersForBook(bookId).singleOrNull {
                it.chapterIndex == targetChapterIndex - 1
            }
        } else {
            null
        }
        val previousVersion = previousChapter?.currentVersionId?.let {
            database.libraryDao().findChapterVersion(it)
        }
        val memorySelection = MemoryContextRouteSelectionRepositoryV1(database).select(
            bookId = bookId,
            targetChapterIndex = targetChapterIndex,
            targetChapterTitle = targetChapterNode.title,
            targetChapterPlanJson = targetChapterNode.planJson,
            targetArcTitle = targetArcNode.title,
            targetArcPlanJson = targetArcNode.planJson,
            userAddition = userAddition,
        )
        return AuthoritativeContextSources(
            bible = bible,
            currentOutline = currentOutline,
            targetWindow = targetWindow,
            targetArcNode = targetArcNode,
            targetChapterNode = targetChapterNode,
            rootNode = root,
            previousChapterVersionId = previousVersion?.chapterVersionId,
            previousChapterContentHash = previousVersion?.contentHash,
            entities = memory.activeEntitiesForBible(bookId, bible.bibleRevisionId, MAX_ENTITIES),
            entityEvents = memory.validEntityEventsBefore(bookId, targetChapterIndex, MAX_ENTITY_EVENTS),
            aggregateState = memory.latestValidAggregateStateBefore(bookId, targetChapterIndex),
            memorySelection = memorySelection,
        )
    }

    private suspend fun findTargetWindow(
        current: OutlineRevisionEntity,
        targetChapterIndex: Int,
    ): OutlineRevisionEntity {
        var cursor: OutlineRevisionEntity? = current
        var visited = 0
        while (cursor != null && visited++ < MAX_OUTLINE_CHAIN_DEPTH) {
            if (
                database.memoryDao().findOutlineNodes(cursor.outlineRevisionId).any {
                    it.nodeType == OutlineNodeType.CHAPTER &&
                        it.plannedChapterIndex == targetChapterIndex
                }
            ) {
                return cursor
            }
            cursor = cursor.parentRevisionId?.let { database.memoryDao().findOutlineRevision(it) }
        }
        throw IllegalStateException("Target chapter outline window is missing.")
    }

    private fun buildCandidates(
        bundle: app.zhijuan.core.task.BoundPromptBundle,
        frozen: ChapterContextAssemblySourceV1,
        sources: AuthoritativeContextSources,
    ): List<ChapterContextCandidate> {
        val collector = CandidateCollector()
        val addedMemorySources = mutableSetOf<Pair<MemorySearchSourceTypeV1, String>>()
        bundle.applicationHardRules.forEach { instruction ->
            collector.add(
                ChapterContextKind.APPLICATION_HARD_RULE,
                promptInstructionJson(instruction.id, instruction.text),
                "APPLICATION_PROMPT_RULE",
                instruction.id,
                bundle.bundleVersion,
                bundle.bindingHash,
                importance = 100,
            )
        }
        val contract = bundle.contractFor(GenerationPhase.BUILD_CHAPTER_PLAN)
        collector.add(
            ChapterContextKind.STAGE_CONTRACT,
            JsonObject(
                linkedMapOf(
                    "templateId" to JsonPrimitive(contract.templateId),
                    "outputSchemaId" to JsonPrimitive(requireNotNull(contract.outputSchemaId)),
                    "instructions" to JsonArray(
                        contract.instructions.map { promptInstructionElement(it.id, it.text) },
                    ),
                ),
            ).toString(),
            "PROMPT_STAGE_CONTRACT",
            contract.templateId,
            bundle.bundleVersion,
            bundle.bindingHash,
            importance = 100,
        )
        bundle.presentationInstructions.forEach { instruction ->
            collector.add(
                ChapterContextKind.WRITING_STYLE,
                promptInstructionJson(instruction.id, instruction.text),
                "PRESENTATION_INSTRUCTION",
                instruction.id,
                bundle.bundleVersion,
                bundle.bindingHash,
                importance = 100,
            )
        }

        val bibleJson = parseObject(sources.bible.payloadJson, "Story Bible payload")
        sources.entities.forEach { entity ->
            val content = JsonObject(
                linkedMapOf(
                    "entityId" to JsonPrimitive(entity.entityId),
                    "entityType" to JsonPrimitive(entity.entityType.name),
                    "canonicalName" to JsonPrimitive(entity.canonicalName),
                    "aliases" to parseJson(entity.aliasesJson, "Story entity aliases"),
                    "stableDefinition" to parseJson(entity.stableDefinitionJson, "Story entity definition"),
                    "adultStatus" to JsonPrimitive(entity.adultStatus.name),
                    "ageYears" to (entity.ageYears?.let(::JsonPrimitive) ?: JsonNull),
                ),
            ).toString()
            collector.add(
                ChapterContextKind.ADULT_AND_IDENTITY_FACT,
                content,
                "STORY_ENTITY",
                entity.entityId,
                sources.bible.bibleRevisionId,
                sha256(content),
                importance = 100,
            )
            addedMemorySources += MemorySearchSourceTypeV1.STORY_ENTITY to entity.entityId
        }
        bibleJson.array("worldRules").forEach { rule ->
            collector.add(
                ChapterContextKind.BIBLE_WORLD_RULE,
                rule.toString(),
                "STORY_BIBLE_WORLD_RULE",
                sources.bible.bibleRevisionId,
                sources.bible.bibleRevisionId,
                sources.bible.contentHash,
                importance = 100,
            )
        }
        bibleJson.array("forbiddenChanges").forEach { value ->
            collector.add(
                ChapterContextKind.FORBIDDEN_CHANGE,
                JsonObject(mapOf("text" to value)).toString(),
                "STORY_BIBLE_FORBIDDEN_CHANGE",
                sources.bible.bibleRevisionId,
                sources.bible.bibleRevisionId,
                sources.bible.contentHash,
                importance = 100,
            )
        }
        bibleJson.array("writingStyle").forEach { value ->
            collector.add(
                ChapterContextKind.WRITING_STYLE,
                JsonObject(mapOf("text" to value)).toString(),
                "STORY_BIBLE_WRITING_STYLE",
                sources.bible.bibleRevisionId,
                sources.bible.bibleRevisionId,
                sources.bible.contentHash,
                importance = 100,
            )
        }
        bibleJson.array("themes").forEach { value ->
            collector.add(
                ChapterContextKind.BIBLE_THEME,
                JsonObject(mapOf("text" to value)).toString(),
                "STORY_BIBLE_THEME",
                sources.bible.bibleRevisionId,
                sources.bible.bibleRevisionId,
                sources.bible.contentHash,
                importance = 60,
            )
        }
        collector.addOutline(ChapterContextKind.TARGET_ARC, sources.targetArcNode, 100)
        collector.addOutline(ChapterContextKind.TARGET_CHAPTER_PLAN, sources.targetChapterNode, 100)
        sources.rootNode?.let { collector.addOutline(ChapterContextKind.DISTANT_PLAN, it, 40) }
        sources.aggregateState?.let { aggregate ->
            collector.add(
                ChapterContextKind.CURRENT_STATE,
                aggregate.stateJson,
                "AGGREGATE_STATE",
                aggregate.aggregateStateId,
                aggregate.sourceThroughChapterVersionId,
                aggregate.contentHash,
                importance = 100,
                chapterIndex = aggregate.throughChapterIndex,
            )
        }
        val latestEventKeys = mutableSetOf<Pair<String, String>>()
        sources.entityEvents.forEach { event ->
            val isLatest = latestEventKeys.add(event.entityId to event.attributeKey)
            if (!isLatest) return@forEach
            val content = JsonObject(
                linkedMapOf(
                    "entityEventId" to JsonPrimitive(event.entityEventId),
                    "entityId" to JsonPrimitive(event.entityId),
                    "attributeKey" to JsonPrimitive(event.attributeKey),
                    "oldValue" to (event.oldValueJson?.let {
                        parseJson(it, "Entity event old value")
                    } ?: JsonNull),
                    "newValue" to parseJson(event.newValueJson, "Entity event new value"),
                    "storyTimeExpression" to (
                        event.storyTimeExpression?.let(::JsonPrimitive) ?: JsonNull
                    ),
                    "canonLevel" to JsonPrimitive(event.canonLevel.name),
                ),
            ).toString()
            collector.add(
                ChapterContextKind.CURRENT_STATE,
                content,
                "ENTITY_EVENT",
                event.entityEventId,
                event.sourceChapterVersionId,
                sha256(content),
                relevanceMicros = event.confidenceMicros,
                importance = 100,
                storyOrder = event.storyOrder,
            )
            addedMemorySources += MemorySearchSourceTypeV1.ENTITY_EVENT to event.entityEventId
        }
        sources.memorySelection.items.forEach { selection ->
            addSelectedMemoryCandidate(
                collector = collector,
                selection = selection,
                sources = sources,
                targetChapterIndex = frozen.targetChapterIndex,
                addedMemorySources = addedMemorySources,
            )
        }
        frozen.userAddition?.let { addition ->
            val content = JsonObject(mapOf("text" to JsonPrimitive(addition))).toString()
            collector.add(
                ChapterContextKind.USER_ADDITION,
                content,
                "FROZEN_USER_ADDITION",
                "${frozen.targetChapterIndex}",
                null,
                sha256(content),
                importance = 100,
                chapterIndex = frozen.targetChapterIndex,
            )
        }
        return collector.items
    }

    private fun addSelectedMemoryCandidate(
        collector: CandidateCollector,
        selection: MemoryContextRouteSelectionItemV1,
        sources: AuthoritativeContextSources,
        targetChapterIndex: Int,
        addedMemorySources: MutableSet<Pair<MemorySearchSourceTypeV1, String>>,
    ) {
        val source = selection.source
        val identity = source.sourceType to source.sourceId
        if (!addedMemorySources.add(identity)) return
        val ftsRelevance = selection.ftsEvidence?.let {
            (1_000_000 - (selection.rank - 1).coerceAtMost(999) * 1_000).coerceAtLeast(1)
        } ?: 0
        when (source) {
            is StoryEntitySourceV1 -> {
                val story = source.story
                val content = JsonObject(
                    linkedMapOf(
                        "entityId" to JsonPrimitive(story.entityId),
                        "entityType" to JsonPrimitive(story.entityType.name),
                        "canonicalName" to JsonPrimitive(story.canonicalName),
                        "aliases" to parseJson(story.aliasesJson, "Story entity aliases"),
                        "stableDefinition" to parseJson(
                            story.stableDefinitionJson,
                            "Story entity definition",
                        ),
                        "adultStatus" to JsonPrimitive(story.adultStatus.name),
                        "ageYears" to (story.ageYears?.let(::JsonPrimitive) ?: JsonNull),
                    ),
                ).toString()
                collector.add(
                    ChapterContextKind.RUNTIME_HISTORY,
                    content,
                    source.sourceType.name,
                    story.entityId,
                    story.sourceBibleRevisionId,
                    sha256(content),
                    relevanceMicros = ftsRelevance,
                    importance = 80,
                )
            }

            is ChapterSummarySourceV1 -> {
                val summary = source.summary
                val isRecent = MemoryContextRouteV1.RECENT_SUMMARY in selection.routes
                val isPrevious = isRecent && summary.chapterIndex == targetChapterIndex - 1 &&
                    summary.chapterVersionId == sources.previousChapterVersionId
                val kind = when {
                    isPrevious -> ChapterContextKind.PREVIOUS_CHAPTER_SUMMARY
                    isRecent -> ChapterContextKind.RECENT_CHAPTER_SUMMARY
                    else -> ChapterContextKind.RUNTIME_HISTORY
                }
                collector.add(
                    kind,
                    summary.summaryJson,
                    source.sourceType.name,
                    summary.chapterSummaryId,
                    summary.chapterVersionId,
                    sha256(summary.summaryJson),
                    relevanceMicros = maxOf(
                        ftsRelevance,
                        chapterRelevance(targetChapterIndex, summary.chapterIndex),
                    ),
                    importance = summary.importance,
                    chapterIndex = summary.chapterIndex,
                )
            }

            is EntityEventSourceV1 -> {
                val event = source.event
                val content = JsonObject(
                    linkedMapOf(
                        "entityEventId" to JsonPrimitive(event.entityEventId),
                        "entityId" to JsonPrimitive(event.entityId),
                        "attributeKey" to JsonPrimitive(event.attributeKey),
                        "oldValue" to (event.oldValueJson?.let {
                            parseJson(it, "Entity event old value")
                        } ?: JsonNull),
                        "newValue" to parseJson(event.newValueJson, "Entity event new value"),
                        "storyTimeExpression" to (
                            event.storyTimeExpression?.let(::JsonPrimitive) ?: JsonNull
                        ),
                        "canonLevel" to JsonPrimitive(event.canonLevel.name),
                    ),
                ).toString()
                collector.add(
                    ChapterContextKind.RUNTIME_HISTORY,
                    content,
                    source.sourceType.name,
                    event.entityEventId,
                    event.sourceChapterVersionId,
                    sha256(content),
                    relevanceMicros = ftsRelevance,
                    importance = 60,
                    chapterIndex = source.chapterIndex,
                    storyOrder = event.storyOrder,
                )
            }

            is CanonFactSourceV1 -> {
                val fact = source.fact
                val mandatory = MemoryContextRouteV1.MANDATORY_HARD_FACT in selection.routes
                check(!mandatory || fact.canonLevel == CanonLevel.HARD_CANON) {
                    "Mandatory memory route returned a non-hard fact."
                }
                val content = JsonObject(
                    linkedMapOf(
                        "canonFactId" to JsonPrimitive(fact.canonFactId),
                        "entityId" to (fact.entityId?.let(::JsonPrimitive) ?: JsonNull),
                        "factText" to JsonPrimitive(fact.factText),
                        "factPayload" to parseJson(fact.factPayloadJson, "Canon fact payload"),
                        "canonLevel" to JsonPrimitive(fact.canonLevel.name),
                        "scope" to parseJson(fact.scopeJson, "Canon fact scope"),
                    ),
                ).toString()
                collector.add(
                    if (mandatory) ChapterContextKind.BIBLE_HARD_FACT else ChapterContextKind.RUNTIME_HISTORY,
                    content,
                    source.sourceType.name,
                    fact.canonFactId,
                    fact.sourceBibleRevisionId ?: fact.sourceChapterVersionId,
                    sha256(content),
                    relevanceMicros = ftsRelevance,
                    importance = if (mandatory) 100 else 80,
                    chapterIndex = source.chapterIndex,
                    storyOrder = fact.validFromStoryOrder,
                )
            }

            is TimelineEventSourceV1 -> {
                val event = source.timeline
                val content = JsonObject(
                    linkedMapOf(
                        "timelineEventId" to JsonPrimitive(event.timelineEventId),
                        "name" to JsonPrimitive(event.name),
                        "participants" to parseJson(event.participantsJson, "Timeline participants"),
                        "locationEntityId" to (event.locationEntityId?.let(::JsonPrimitive) ?: JsonNull),
                        "storyTimeExpression" to JsonPrimitive(event.storyTimeExpression),
                        "constraints" to parseJson(event.constraintsJson, "Timeline constraints"),
                    ),
                ).toString()
                collector.add(
                    ChapterContextKind.TIMELINE_HISTORY,
                    content,
                    source.sourceType.name,
                    event.timelineEventId,
                    event.sourceChapterVersionId,
                    sha256(content),
                    relevanceMicros = ftsRelevance,
                    importance = 60,
                    chapterIndex = source.chapterIndex,
                    storyOrder = event.storyOrder,
                )
            }

            is ForeshadowSourceV1 -> {
                val item = source.foreshadow
                val mandatory = MemoryContextRouteV1.MANDATORY_DUE_FORESHADOW in selection.routes
                val content = JsonObject(
                    linkedMapOf(
                        "foreshadowItemId" to JsonPrimitive(item.foreshadowItemId),
                        "description" to JsonPrimitive(item.description),
                        "status" to JsonPrimitive(item.foreshadowStatus.name),
                        "targetStartChapterIndex" to (
                            item.targetStartChapterIndex?.let(::JsonPrimitive) ?: JsonNull
                        ),
                        "targetEndChapterIndex" to (
                            item.targetEndChapterIndex?.let(::JsonPrimitive) ?: JsonNull
                        ),
                        "visibleEntityIds" to parseJson(
                            item.visibleEntityIdsJson,
                            "Foreshadow visible entities",
                        ),
                    ),
                ).toString()
                collector.add(
                    if (mandatory) ChapterContextKind.DUE_FORESHADOW else ChapterContextKind.OPEN_FORESHADOW,
                    content,
                    source.sourceType.name,
                    item.foreshadowItemId,
                    item.sourceChapterVersionId,
                    sha256(content),
                    relevanceMicros = if (mandatory) 1_000_000 else ftsRelevance,
                    importance = item.importance,
                    chapterIndex = source.chapterIndex ?: item.targetStartChapterIndex,
                )
            }
        }
    }

    private fun enrichManifest(
        policyManifest: String,
        stage: GenerationStageEntity,
        frozen: ChapterContextAssemblySourceV1,
        sources: AuthoritativeContextSources,
    ): String {
        val root = parseObject(policyManifest, "Policy context manifest")
        val evidence = JsonObject(
            linkedMapOf(
                "schemaVersion" to JsonPrimitive(1),
                "bookId" to JsonPrimitive(sources.bible.bookId),
                "targetChapterId" to JsonPrimitive(stage.targetId),
                "targetChapterIndex" to JsonPrimitive(frozen.targetChapterIndex),
                "contextInputVersionHash" to JsonPrimitive(stage.inputVersionHash),
                "promptBindingHash" to JsonPrimitive(frozen.promptBindingHash),
                "progressionEvidenceHash" to JsonPrimitive(frozen.progressionEvidenceHash),
                "bibleRevisionId" to JsonPrimitive(sources.bible.bibleRevisionId),
                "bibleContentHash" to JsonPrimitive(sources.bible.contentHash),
                "currentOutlineRevisionId" to JsonPrimitive(sources.currentOutline.outlineRevisionId),
                "currentOutlineContentHash" to JsonPrimitive(sources.currentOutline.contentHash),
                "targetWindowRevisionId" to JsonPrimitive(sources.targetWindow.outlineRevisionId),
                "targetWindowContentHash" to JsonPrimitive(sources.targetWindow.contentHash),
                "targetArcNodeId" to JsonPrimitive(sources.targetArcNode.outlineNodeId),
                "targetArcContentHash" to JsonPrimitive(sources.targetArcNode.contentHash),
                "targetChapterNodeId" to JsonPrimitive(sources.targetChapterNode.outlineNodeId),
                "targetChapterContentHash" to JsonPrimitive(sources.targetChapterNode.contentHash),
                "previousChapterVersionId" to (
                    sources.previousChapterVersionId?.let(::JsonPrimitive) ?: JsonNull
                ),
                "previousChapterContentHash" to (
                    sources.previousChapterContentHash?.let(::JsonPrimitive) ?: JsonNull
                ),
                "memorySelection" to memorySelectionEvidence(sources.memorySelection),
            ),
        )
        return JsonObject(root + ("assemblyEvidence" to evidence)).toString()
    }

    private fun memorySelectionEvidence(
        selection: MemoryContextRouteSelectionResultV1,
    ): JsonObject {
        val evidence = selection.evidence
        return JsonObject(
            linkedMapOf(
                "schemaVersion" to JsonPrimitive(1),
                "status" to JsonPrimitive(selection.status.name),
                "queryFingerprint" to (
                    selection.queryFingerprint?.let(::JsonPrimitive) ?: JsonNull
                ),
                "indexRebuildRequired" to JsonPrimitive(selection.indexRebuildRequired),
                "counts" to JsonObject(
                    linkedMapOf(
                        "mandatoryHardFacts" to JsonPrimitive(evidence.mandatoryHardFactCount),
                        "mandatoryDueForeshadows" to JsonPrimitive(
                            evidence.mandatoryDueForeshadowCount,
                        ),
                        "recentSummaries" to JsonPrimitive(evidence.recentSummaryCount),
                        "hydratedFtsHits" to JsonPrimitive(evidence.hydratedFtsHitCount),
                        "mergedFtsHits" to JsonPrimitive(evidence.mergedFtsHitCount),
                        "retainedNewFtsHits" to JsonPrimitive(evidence.retainedNewFtsHitCount),
                        "boundedOmittedFtsHits" to JsonPrimitive(
                            evidence.boundedOmittedFtsHitCount,
                        ),
                        "overflowCore" to JsonPrimitive(evidence.overflowCoreCount),
                        "compiledProbes" to JsonPrimitive(evidence.compiledProbeCount),
                        "omittedCompiledProbes" to JsonPrimitive(evidence.omittedCompiledProbeCount),
                        "executedProbes" to JsonPrimitive(evidence.executedProbeCount),
                        "executedTargetChapterProbes" to JsonPrimitive(
                            evidence.executedTargetChapterProbeCount,
                        ),
                        "executedUserAdditionProbes" to JsonPrimitive(
                            evidence.executedUserAdditionProbeCount,
                        ),
                        "executedTargetArcProbes" to JsonPrimitive(
                            evidence.executedTargetArcProbeCount,
                        ),
                        "omittedExecutionProbes" to JsonPrimitive(
                            evidence.omittedExecutionProbeCount,
                        ),
                        "omittedRankedDocuments" to JsonPrimitive(
                            evidence.omittedRankedDocumentCount,
                        ),
                        "rejectedPointers" to JsonPrimitive(evidence.rejectedPointerCount),
                        "hardLimit" to JsonPrimitive(evidence.hardLimit),
                    ),
                ),
                "routeCounts" to JsonObject(
                    MemoryContextRouteV1.entries.associate { route ->
                        route.name to JsonPrimitive(evidence.routeCounts.getValue(route))
                    },
                ),
                "items" to JsonArray(
                    selection.items.map { item ->
                        JsonObject(
                            linkedMapOf(
                                "sourceType" to JsonPrimitive(item.source.sourceType.name),
                                "sourceId" to JsonPrimitive(item.source.sourceId),
                                "routes" to JsonArray(
                                    MemoryContextRouteV1.entries.filter { it in item.routes }
                                        .map { JsonPrimitive(it.name) },
                                ),
                                "fts" to (item.ftsEvidence?.let { hits ->
                                    JsonObject(
                                        linkedMapOf(
                                            "targetChapter" to JsonPrimitive(
                                                hits.targetChapterProbeHits,
                                            ),
                                            "userAddition" to JsonPrimitive(
                                                hits.userAdditionProbeHits,
                                            ),
                                            "targetArc" to JsonPrimitive(hits.targetArcProbeHits),
                                        ),
                                    )
                                } ?: JsonNull),
                                "rank" to JsonPrimitive(item.rank),
                            ),
                        )
                    },
                ),
            ),
        )
    }

    private suspend fun requireCurrentAssemblyEvidence(
        manifest: JsonObject,
        contextStage: GenerationStageEntity,
        planStage: GenerationStageEntity,
        job: GenerationJobEntity,
        snapshot: ContextSnapshotEntity,
    ) {
        require(manifest.string("schemaId") == ChapterContextBudgetPolicyV1.MANIFEST_SCHEMA_ID)
        require(manifest.string("policyVersion") == ChapterContextBudgetPolicyV1.POLICY_VERSION)
        val evidence = manifest.objectValue("assemblyEvidence")
        require(
            evidence.string("bookId") == job.bookId &&
                evidence.string("targetChapterId") == planStage.targetId &&
                evidence.int("targetChapterIndex") == snapshot.targetChapterIndex &&
                evidence.string("contextInputVersionHash") == contextStage.inputVersionHash,
        ) { "Chapter-context target evidence is stale." }
        val bundle = PromptBundleBindingRepository(database).bindForBook(job.bookId)
        require(bundle.bindingHash == evidence.string("promptBindingHash")) {
            "Chapter-context Prompt Bundle binding changed."
        }
        val head = requireNotNull(database.memoryDao().findMemoryHead(job.bookId))
        val bible = requireNotNull(head.currentBibleRevisionId?.let {
            database.memoryDao().findBibleRevision(it)
        })
        val outline = requireNotNull(head.currentOutlineRevisionId?.let {
            database.memoryDao().findOutlineRevision(it)
        })
        require(
            bible.bibleRevisionId == evidence.string("bibleRevisionId") &&
                bible.contentHash == evidence.string("bibleContentHash") &&
                outline.outlineRevisionId == evidence.string("currentOutlineRevisionId") &&
                outline.contentHash == evidence.string("currentOutlineContentHash"),
        ) { "Chapter-context Bible or outline head changed." }
        val window = requireNotNull(
            database.memoryDao().findOutlineRevision(evidence.string("targetWindowRevisionId")),
        )
        val nodes = database.memoryDao().findOutlineNodes(window.outlineRevisionId)
        require(
            window.contentHash == evidence.string("targetWindowContentHash") &&
                nodes.any {
                    it.outlineNodeId == evidence.string("targetArcNodeId") &&
                        it.contentHash == evidence.string("targetArcContentHash")
                } &&
                nodes.any {
                    it.outlineNodeId == evidence.string("targetChapterNodeId") &&
                        it.contentHash == evidence.string("targetChapterContentHash") &&
                        it.plannedChapterIndex == snapshot.targetChapterIndex
                },
        ) { "Chapter-context target outline window changed." }
        val previousVersionId = evidence.optionalString("previousChapterVersionId")
        val previousHash = evidence.optionalString("previousChapterContentHash")
        if (snapshot.targetChapterIndex > 1) {
            val previous = database.libraryDao().chaptersForBook(job.bookId).singleOrNull {
                it.chapterIndex == snapshot.targetChapterIndex - 1
            }
            val version = previous?.currentVersionId?.let { database.libraryDao().findChapterVersion(it) }
            require(
                version != null && version.chapterVersionId == previousVersionId &&
                    version.contentHash == previousHash,
            ) { "Chapter-context previous chapter version changed." }
        } else {
            require(previousVersionId == null && previousHash == null)
        }
        val progression = parseObject(planStage.inputSourcesJson, "Chapter-plan progression input")
            .objectValue("chapterProgressionGate")
        require(progression.string("evidenceHash") == evidence.string("progressionEvidenceHash")) {
            "Chapter-context progression evidence changed."
        }
    }

    private suspend fun requireCurrentContextProjection(
        manifest: JsonObject,
        contextStage: GenerationStageEntity,
        job: GenerationJobEntity,
        snapshot: ContextSnapshotEntity,
    ) {
        val frozen = parseFrozenInput(contextStage)
        val bundle = PromptBundleBindingRepository(database).bindForBook(job.bookId)
        require(bundle.bindingHash == frozen.promptBindingHash) {
            "Chapter-context Prompt Bundle binding changed."
        }
        val sources = loadAuthoritativeSources(
            bookId = job.bookId,
            targetChapterIndex = snapshot.targetChapterIndex,
            userAddition = frozen.userAddition,
        )
        if (
            sources.memorySelection.status != MemoryContextSelectionStatusV1.OK ||
            sources.memorySelection.indexRebuildRequired
        ) {
            throw StaleGenerationStateException(
                "Chapter-context memory selection is no longer safe to send.",
            )
        }
        val rebuilt = ChapterContextBudgetPolicyV1.assemble(
            targetChapterIndex = snapshot.targetChapterIndex,
            budget = frozen.budget,
            candidates = buildCandidates(bundle, frozen, sources),
        ) as? ChapterContextAssemblyResult.Ready
            ?: throw StaleGenerationStateException(
                "Chapter-context memory changes no longer fit the frozen budget.",
            )
        val rebuiltManifest = enrichManifest(
            policyManifest = rebuilt.sourceManifestJson,
            stage = contextStage,
            frozen = frozen,
            sources = sources,
        )
        if (
            rebuilt.contentHash != snapshot.contentHash ||
            rebuiltManifest != manifest.toString()
        ) {
            throw StaleGenerationStateException(
                "Chapter-context dynamic memory changed after assembly.",
            )
        }
    }

    private suspend fun requireNextPlanStage(
        contextStage: GenerationStageEntity,
        job: GenerationJobEntity,
    ): GenerationStageEntity {
        val stages = database.generationDao().stagesForJob(job.jobId)
        val next = stages.singleOrNull { candidate ->
            if (candidate.phase != GenerationPhase.BUILD_CHAPTER_PLAN) return@singleOrNull false
            val input = parseObject(candidate.inputSourcesJson, "Chapter-plan dependency input")
            input.optionalStringOrMissing("contextAssemblyStageId") == contextStage.stageId
        } ?: throw IllegalStateException("Chapter-context next stage is missing or ambiguous.")
        require(
            stages.size == 2 && next.phase == GenerationPhase.BUILD_CHAPTER_PLAN &&
                next.targetType == GenerationTargetType.CHAPTER && next.targetId == contextStage.targetId &&
                next.status == GenerationStageStatus.PENDING,
        ) { "Chapter-context next stage is not the frozen chapter-plan dependency." }
        val input = parseObject(next.inputSourcesJson, "Chapter-plan dependency input")
        require(
            input.string("contextAssemblyStageId") == contextStage.stageId &&
                input.string("contextInputVersionHash") == contextStage.inputVersionHash,
        ) { "Chapter-plan stage froze different context evidence." }
        return next
    }

    private suspend fun persistBlocked(
        stage: GenerationStageEntity,
        job: GenerationJobEntity,
        leaseToken: GenerationLeaseToken,
        blockedAt: Long,
        reason: ChapterContextBlockReason,
        error: StandardErrorCode,
    ) {
        requireActiveLeases(stage, job, leaseToken, blockedAt)
        check(
            GenerationStageStateMachine.transition(stage.status, StageEvent.PRECONDITION_BLOCKED) ==
                GenerationStageStatus.BLOCKED,
        )
        if (
            database.generationDao().compareAndSetStageStatus(
                stageId = stage.stageId,
                expectedStatus = GenerationStageStatus.PREPARING,
                nextStatus = GenerationStageStatus.BLOCKED,
                errorCode = error,
                nextRetryAt = null,
                updatedAt = blockedAt,
            ) != 1
        ) {
            throw StaleGenerationStateException("Chapter-context block lost a concurrent stage update.")
        }
        val nextJob = if (job.status == GenerationJobStatus.PAUSING) {
            check(
                GenerationJobStateMachine.transition(job.status, JobEvent.SAFE_POINT_REACHED) ==
                    GenerationJobStatus.PAUSED,
            )
            GenerationJobStatus.PAUSED
        } else {
            check(
                GenerationJobStateMachine.transition(job.status, JobEvent.USER_ACTION_REQUIRED) ==
                    GenerationJobStatus.NEEDS_ACTION,
            )
            GenerationJobStatus.NEEDS_ACTION
        }
        if (
            database.generationDao().compareAndSetJobControlStatus(
                jobId = job.jobId,
                expectedStatus = job.status,
                nextStatus = nextJob,
                reason = "CHAPTER_CONTEXT:${reason.name}",
                updatedAt = blockedAt,
            ) != 1
        ) {
            throw StaleGenerationStateException("Chapter-context block lost a concurrent job update.")
        }
    }

    private fun requireActiveLeases(
        stage: GenerationStageEntity,
        job: GenerationJobEntity,
        token: GenerationLeaseToken,
        operationAt: Long,
    ) {
        require(stage.leaseOwnerId == token.ownerId && stage.leaseAcquiredAt == token.acquiredAt) {
            "Chapter-context worker does not own the stage lease."
        }
        val stageHeartbeat = requireNotNull(stage.leaseHeartbeatAt)
        require(job.leaseOwnerId == token.ownerId && job.leaseHeartbeatAt != null) {
            "Chapter-context worker does not own the parent job lease."
        }
        require(
            operationAt >= stage.updatedAt && operationAt >= stageHeartbeat &&
                operationAt >= job.updatedAt && operationAt >= requireNotNull(job.leaseHeartbeatAt),
        ) { "Chapter-context operation time cannot move backwards." }
        if (
            leasePolicy.isExpired(stageHeartbeat, operationAt) ||
            leasePolicy.isExpired(requireNotNull(job.leaseHeartbeatAt), operationAt)
        ) {
            throw StaleGenerationStateException("Chapter-context lease expired before assembly.")
        }
    }

    /**
     * Exact bound revalidation: the persisted rows must still match every finite fact of the Phase
     * 2B snapshot - RUNNING job, PREPARING current stage, exact Job and Stage lease tokens, the two
     * persisted heartbeats not older than their own acquisition times, a non-backwards operation
     * time, unexpired leases (60 seconds is already expired) and unchanged attempt bounds. Any
     * mismatch fails closed inside the caller's transaction before any business write.
     */
    private fun requireBoundExecutionEvidence(
        stage: GenerationStageEntity,
        job: GenerationJobEntity,
        snapshot: GenerationRunnerCurrentStageRouteSnapshot,
        operationAt: Long,
    ) {
        val lease = snapshot.executionLease
        if (job.status != GenerationJobStatus.RUNNING) {
            throw StaleGenerationStateException("Bound context Job is no longer RUNNING.")
        }
        if (stage.status != GenerationStageStatus.PREPARING || job.currentStageId != stage.stageId) {
            throw StaleGenerationStateException("Bound context Stage is no longer current PREPARING.")
        }
        if (stage.jobId != job.jobId || lease.jobId != job.jobId || lease.stageId != stage.stageId) {
            throw StaleGenerationStateException("Bound context execution identity changed.")
        }
        if (lease.jobLeaseToken.ownerId != lease.stageLeaseToken.ownerId) {
            throw StaleGenerationStateException("Bound context lease owners no longer match.")
        }
        if (job.leaseTokenOrNull() != lease.jobLeaseToken) {
            throw StaleGenerationStateException("Bound context Job lease token changed.")
        }
        if (stage.leaseTokenOrNull() != lease.stageLeaseToken) {
            throw StaleGenerationStateException("Bound context Stage lease token changed.")
        }
        val jobHeartbeatAt = requireNotNull(job.leaseHeartbeatAt) {
            "bound execution job lease heartbeat is missing."
        }
        val stageHeartbeatAt = requireNotNull(stage.leaseHeartbeatAt) {
            "bound execution stage lease heartbeat is missing."
        }
        if (
            jobHeartbeatAt < lease.jobLeaseToken.acquiredAt ||
            stageHeartbeatAt < lease.stageLeaseToken.acquiredAt ||
            jobHeartbeatAt < lease.jobHeartbeatAt ||
            stageHeartbeatAt < lease.stageHeartbeatAt
        ) {
            throw StaleGenerationStateException("Bound context lease timing regressed.")
        }
        require(
            operationAt >= job.updatedAt &&
                operationAt >= stage.updatedAt &&
                operationAt >= jobHeartbeatAt &&
                operationAt >= stageHeartbeatAt,
        ) { "bound execution time cannot move backwards." }
        if (
            leasePolicy.isExpired(jobHeartbeatAt, operationAt) ||
            leasePolicy.isExpired(stageHeartbeatAt, operationAt)
        ) {
            throw StaleGenerationStateException("bound execution lease expired before assembly.")
        }
        if (
            stage.attemptCount != snapshot.attemptCount ||
            stage.maxAttempts != snapshot.maxAttempts ||
            stage.attemptCount !in 0 until stage.maxAttempts
        ) {
            throw StaleGenerationStateException("Bound context attempt bounds changed.")
        }
    }

    private fun parseFrozenInput(stage: GenerationStageEntity): ChapterContextAssemblySourceV1 =
        ChapterContextAssemblyJobFactory.parseAndVerify(stage)

    private fun rebuildProviderPayload(manifest: JsonObject): String {
        require(manifest.int("schemaVersion") == 1)
        require(manifest.string("schemaId") == ChapterContextBudgetPolicyV1.MANIFEST_SCHEMA_ID)
        require(manifest.string("policyVersion") == ChapterContextBudgetPolicyV1.POLICY_VERSION)
        val selected = manifest.array("selected").map { element ->
            val item = element as? JsonObject
                ?: throw IllegalArgumentException("Selected context item is invalid.")
            val content = item.string("content")
            require(sha256(content) == item.string("contentHash")) {
                "Selected context item content hash changed."
            }
            Triple(item.string("itemId"), item.string("kind"), content)
        }
        val payload = buildString {
            append("{\"schemaVersion\":1,\"policyVersion\":")
            appendQuoted(ChapterContextBudgetPolicyV1.POLICY_VERSION)
            append(",\"targetChapterIndex\":")
            append(manifest.int("targetChapterIndex"))
            append(",\"layers\":[")
            selected.forEachIndexed { index, item ->
                if (index > 0) append(',')
                append("{\"itemId\":")
                appendQuoted(item.first)
                append(",\"kind\":")
                appendQuoted(item.second)
                append(",\"content\":")
                appendQuoted(item.third)
                append('}')
            }
            append("]}")
        }
        require(sha256(payload) == manifest.string("providerPayloadHash")) {
            "Chapter-context manifest payload hash is inconsistent."
        }
        return payload
    }

    private fun outputReference(
        snapshot: ContextSnapshotEntity,
        nextStageId: String,
        assembled: ChapterContextAssemblyResult.Ready,
        manifestHash: String,
    ): String = JsonObject(
        linkedMapOf(
            "schemaVersion" to JsonPrimitive(1),
            "outputSchemaId" to JsonPrimitive(ChapterContextBudgetPolicyV1.MANIFEST_SCHEMA_ID),
            "policyVersion" to JsonPrimitive(ChapterContextBudgetPolicyV1.POLICY_VERSION),
            "contextSnapshotId" to JsonPrimitive(snapshot.contextSnapshotId),
            "contentHash" to JsonPrimitive(snapshot.contentHash),
            "sourceManifestHash" to JsonPrimitive(manifestHash),
            "selectedItemCount" to JsonPrimitive(assembled.selectedItemCount),
            "omittedItemCount" to JsonPrimitive(assembled.omittedItemCount),
            "estimatedInputTokens" to JsonPrimitive(assembled.estimatedInputTokens),
            "inputBudgetTokens" to JsonPrimitive(assembled.inputBudgetTokens),
            "nextStageId" to JsonPrimitive(nextStageId),
        ),
    ).toString()

    private fun readyResult(
        snapshot: ContextSnapshotEntity,
        nextStageId: String,
        payload: String,
        outputJson: String,
        replayed: Boolean,
    ): ReadyChapterContext {
        val output = parseObject(outputJson, "Chapter-context output")
        return ReadyChapterContext(
            contextSnapshotId = snapshot.contextSnapshotId,
            contextStageId = snapshot.generationStageId,
            chapterPlanStageId = nextStageId,
            providerPayloadJson = payload,
            contentHash = snapshot.contentHash,
            sourceManifestHash = output.string("sourceManifestHash"),
            selectedItemCount = output.int("selectedItemCount"),
            omittedItemCount = output.int("omittedItemCount"),
            estimatedInputTokens = output.int("estimatedInputTokens"),
            inputBudgetTokens = output.int("inputBudgetTokens"),
            replayed = replayed,
        )
    }

    private fun deterministicSnapshotId(bookId: String, stageId: String): String =
        "context.${sha256("$bookId\u0000$stageId").take(32)}"

    private data class AuthoritativeContextSources(
        val bible: app.zhijuan.core.database.memory.StoryBibleRevisionEntity,
        val currentOutline: OutlineRevisionEntity,
        val targetWindow: OutlineRevisionEntity,
        val targetArcNode: OutlineNodeEntity,
        val targetChapterNode: OutlineNodeEntity,
        val rootNode: OutlineNodeEntity?,
        val previousChapterVersionId: String?,
        val previousChapterContentHash: String?,
        val entities: List<app.zhijuan.core.database.memory.StoryEntity>,
        val entityEvents: List<app.zhijuan.core.database.memory.EntityEventEntity>,
        val aggregateState: app.zhijuan.core.database.memory.AggregateStateProjectionEntity?,
        val memorySelection: MemoryContextRouteSelectionResultV1,
    )

    private class CandidateCollector {
        val items = mutableListOf<ChapterContextCandidate>()

        fun add(
            kind: ChapterContextKind,
            content: String,
            sourceType: String,
            sourceId: String,
            sourceVersionId: String?,
            sourceHash: String,
            relevanceMicros: Int = 0,
            importance: Int = 0,
            chapterIndex: Int? = null,
            storyOrder: Long? = null,
        ) {
            val ordinal = items.size
            val itemId = "ctx.${kind.name.lowercase()}.${
                sha256("$ordinal\u0000$sourceType\u0000$sourceId\u0000$content").take(32)
            }"
            items += ChapterContextCandidate(
                itemId = itemId,
                kind = kind,
                content = content,
                source = ChapterContextSourceRef(
                    sourceType = sourceType,
                    sourceId = sourceId,
                    sourceVersionId = sourceVersionId,
                    sourceContentHash = sourceHash,
                ),
                relevanceMicros = relevanceMicros,
                importance = importance,
                chapterIndex = chapterIndex,
                storyOrder = storyOrder,
            )
        }

        fun addOutline(kind: ChapterContextKind, node: OutlineNodeEntity, importance: Int) {
            add(
                kind = kind,
                content = JsonObject(
                    linkedMapOf(
                        "outlineNodeId" to JsonPrimitive(node.outlineNodeId),
                        "title" to JsonPrimitive(node.title),
                        "plan" to parseJson(node.planJson, "Outline node plan"),
                    ),
                ).toString(),
                sourceType = "OUTLINE_NODE",
                sourceId = node.outlineNodeId,
                sourceVersionId = node.outlineRevisionId,
                sourceHash = node.contentHash,
                importance = importance,
                chapterIndex = node.plannedChapterIndex,
                storyOrder = node.orderKey,
            )
        }
    }

    private companion object {
        val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
        const val MAX_ENTITIES = 64
        const val MAX_ENTITY_EVENTS = 512
        const val MAX_OUTLINE_CHAIN_DEPTH = 2_000
    }
}

private val CHAPTER_CONTEXT_JSON = Json { isLenient = false }

private fun parseJson(value: String, label: String): JsonElement = runCatching {
    CHAPTER_CONTEXT_JSON.parseToJsonElement(value)
}.getOrElse { throw IllegalArgumentException("$label is invalid JSON.") }

private fun parseObject(value: String, label: String): JsonObject =
    parseJson(value, label) as? JsonObject
        ?: throw IllegalArgumentException("$label must be a JSON object.")

private fun JsonObject.string(key: String): String = optionalString(key)
    ?: throw IllegalArgumentException("Required context field is missing: $key")

private fun JsonObject.optionalString(key: String): String? {
    val value = get(key) ?: throw IllegalArgumentException("Context field is missing: $key")
    if (value is JsonNull) return null
    return (value as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
        ?: throw IllegalArgumentException("Context string field is invalid: $key")
}

private fun JsonObject.optionalStringOrMissing(key: String): String? {
    val value = get(key) ?: return null
    if (value is JsonNull) return null
    return (value as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
        ?: throw IllegalArgumentException("Context string field is invalid: $key")
}

private fun JsonObject.int(key: String): Int =
    (get(key) as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull
        ?: throw IllegalArgumentException("Context integer field is invalid: $key")

private fun JsonObject.nullableInt(key: String): Int? {
    val value = get(key) ?: throw IllegalArgumentException("Context field is missing: $key")
    if (value is JsonNull) return null
    return (value as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull
        ?: throw IllegalArgumentException("Context integer field is invalid: $key")
}

private fun JsonObject.boolean(key: String): Boolean =
    (get(key) as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.booleanOrNull
        ?: throw IllegalArgumentException("Context boolean field is invalid: $key")

private fun JsonObject.objectValue(key: String): JsonObject = get(key) as? JsonObject
    ?: throw IllegalArgumentException("Context object field is invalid: $key")

private fun JsonObject.array(key: String): JsonArray = get(key) as? JsonArray
    ?: throw IllegalArgumentException("Context array field is invalid: $key")

private fun promptInstructionElement(id: String, text: String): JsonObject = JsonObject(
    linkedMapOf("id" to JsonPrimitive(id), "text" to JsonPrimitive(text)),
)

private fun promptInstructionJson(id: String, text: String): String =
    promptInstructionElement(id, text).toString()

private fun chapterRelevance(targetChapterIndex: Int, sourceChapterIndex: Int): Int =
    (1_000_000 - (targetChapterIndex - sourceChapterIndex).coerceAtLeast(0) * 100_000)
        .coerceIn(0, 1_000_000)

private fun ChapterContextBlockReason.toStandardErrorCode(): StandardErrorCode = when (this) {
    ChapterContextBlockReason.REQUIRED_SOURCE_MISSING,
    ChapterContextBlockReason.MEMORY_SEARCH_INDEX_INVALID,
    -> StandardErrorCode.FORMAT_INVALID
    ChapterContextBlockReason.UNKNOWN_CONTEXT_LIMIT_REQUIRES_CONFIRMATION,
    ChapterContextBlockReason.OUTPUT_RESERVE_LEAVES_NO_INPUT_BUDGET,
    ChapterContextBlockReason.REQUIRED_CONTEXT_EXCEEDS_BUDGET,
    ChapterContextBlockReason.MANDATORY_MEMORY_SELECTION_EXCEEDS_LIMIT,
    -> StandardErrorCode.CONTEXT_TOO_LARGE
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun StringBuilder.appendQuoted(value: String) {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) {
                append("\\u")
                append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
    append('"')
}
