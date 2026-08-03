package app.zhijuan.core.database.generation

import androidx.room.withTransaction
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.database.memory.ContextSnapshotEntity
import app.zhijuan.core.database.memory.OutlineNodeEntity
import app.zhijuan.core.database.memory.OutlineRevisionEntity
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
 * reproduce the exact Provider payload without retaining a second mutable copy.
 */
class ChapterContextAssemblyRepository(
    private val database: ZhijuanDatabase,
    private val leasePolicy: GenerationLeasePolicy = GenerationLeasePolicy(),
) {
    suspend fun assemble(
        stageId: String,
        leaseToken: GenerationLeaseToken,
        assembledAt: Long,
    ): PersistedChapterContextAssemblyResult {
        require(IDENTIFIER.matches(stageId)) { "Chapter-context stage id is invalid." }
        require(assembledAt >= 0L) { "Chapter-context assembly time is invalid." }
        return database.withTransaction {
            val generation = database.generationDao()
            val memory = database.memoryDao()
            val stage = requireNotNull(generation.findStage(stageId)) {
                "Chapter-context stage does not exist."
            }
            val job = requireNotNull(generation.findJob(stage.jobId)) {
                "Chapter-context job does not exist."
            }
            if (stage.status == GenerationStageStatus.SUCCEEDED) {
                return@withTransaction PersistedChapterContextAssemblyResult.Ready(
                    replaySucceeded(stage, job),
                )
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
            val sources = loadAuthoritativeSources(job.bookId, chapter.chapterIndex)
            val candidates = buildCandidates(bundle, frozen, sources)
            return@withTransaction when (
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
            canonFacts = memory.validCanonFactsForContext(
                bookId,
                bible.bibleRevisionId,
                targetChapterIndex,
                MAX_CANON_FACTS,
            ),
            summaries = memory.recentValidSummaries(bookId, targetChapterIndex, MAX_SUMMARIES),
            entityEvents = memory.validEntityEventsBefore(bookId, targetChapterIndex, MAX_ENTITY_EVENTS),
            timelineEvents = memory.validTimelineEventsBefore(bookId, targetChapterIndex, MAX_TIMELINE_EVENTS),
            foreshadows = memory.activeForeshadowsForContext(bookId, MAX_FORESHADOWS),
            aggregateState = memory.latestValidAggregateStateBefore(bookId, targetChapterIndex),
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
        frozen: FrozenContextInput,
        sources: AuthoritativeContextSources,
    ): List<ChapterContextCandidate> {
        val collector = CandidateCollector()
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
        sources.canonFacts.forEach { fact ->
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
                if (fact.canonLevel in setOf(CanonLevel.HARD_CANON, CanonLevel.STORY_CANON)) {
                    ChapterContextKind.BIBLE_HARD_FACT
                } else {
                    ChapterContextKind.RUNTIME_HISTORY
                },
                content,
                "CANON_FACT",
                fact.canonFactId,
                fact.sourceBibleRevisionId ?: fact.sourceChapterVersionId,
                sha256(content),
                importance = if (fact.canonLevel == CanonLevel.HARD_CANON) 100 else 80,
                storyOrder = fact.validFromStoryOrder,
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

        val previousSummary = if (frozen.targetChapterIndex > 1) {
            sources.summaries.singleOrNull {
                it.chapterIndex == frozen.targetChapterIndex - 1 &&
                    it.chapterVersionId == sources.previousChapterVersionId
            }
        } else {
            null
        }
        previousSummary?.let { summary ->
            collector.add(
                ChapterContextKind.PREVIOUS_CHAPTER_SUMMARY,
                summary.summaryJson,
                "CHAPTER_SUMMARY",
                summary.chapterSummaryId,
                summary.chapterVersionId,
                sha256(summary.summaryJson),
                importance = summary.importance,
                chapterIndex = summary.chapterIndex,
            )
        }
        sources.summaries.filterNot { it.chapterSummaryId == previousSummary?.chapterSummaryId }
            .forEach { summary ->
                collector.add(
                    ChapterContextKind.RECENT_CHAPTER_SUMMARY,
                    summary.summaryJson,
                    "CHAPTER_SUMMARY",
                    summary.chapterSummaryId,
                    summary.chapterVersionId,
                    sha256(summary.summaryJson),
                    relevanceMicros = chapterRelevance(frozen.targetChapterIndex, summary.chapterIndex),
                    importance = summary.importance,
                    chapterIndex = summary.chapterIndex,
                )
            }
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
                if (isLatest) ChapterContextKind.CURRENT_STATE else ChapterContextKind.RUNTIME_HISTORY,
                content,
                "ENTITY_EVENT",
                event.entityEventId,
                event.sourceChapterVersionId,
                sha256(content),
                relevanceMicros = event.confidenceMicros,
                importance = if (isLatest) 100 else 60,
                storyOrder = event.storyOrder,
            )
        }
        sources.timelineEvents.forEach { event ->
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
                "TIMELINE_EVENT",
                event.timelineEventId,
                event.sourceChapterVersionId,
                sha256(content),
                relevanceMicros = 700_000,
                importance = 60,
                storyOrder = event.storyOrder,
            )
        }
        sources.foreshadows.forEach { item ->
            val due = item.targetStartChapterIndex?.let { it <= frozen.targetChapterIndex } == true ||
                item.targetEndChapterIndex?.let { it <= frozen.targetChapterIndex } == true
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
                if (due) ChapterContextKind.DUE_FORESHADOW else ChapterContextKind.OPEN_FORESHADOW,
                content,
                "FORESHADOW_ITEM",
                item.foreshadowItemId,
                item.sourceChapterVersionId,
                sha256(content),
                relevanceMicros = if (due) 1_000_000 else 500_000,
                importance = item.importance,
                chapterIndex = item.targetStartChapterIndex,
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

    private fun enrichManifest(
        policyManifest: String,
        stage: GenerationStageEntity,
        frozen: FrozenContextInput,
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
            ),
        )
        return JsonObject(root + ("assemblyEvidence" to evidence)).toString()
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

    private fun parseFrozenInput(stage: GenerationStageEntity): FrozenContextInput {
        val root = parseObject(stage.inputSourcesJson, "Chapter-context input sources")
        require(root.int("schemaVersion") == 1)
        require(root.string("promptBundleVersion") == PromptBundleCatalogV1.BUNDLE_VERSION)
        require(root.string("outputSchemaId") == ChapterContextBudgetPolicyV1.MANIFEST_SCHEMA_ID)
        val context = root.objectValue("contextAssembly")
        require(context.string("policyVersion") == ChapterContextBudgetPolicyV1.POLICY_VERSION)
        require(context.string("targetPhase") == GenerationPhase.BUILD_CHAPTER_PLAN.name)
        val progression = root.objectValue("chapterProgressionGate")
        return FrozenContextInput(
            targetChapterIndex = context.int("targetChapterIndex"),
            promptBindingHash = context.string("promptBindingHash"),
            progressionEvidenceHash = progression.string("evidenceHash"),
            budget = ChapterContextBudgetSpec(
                contextLimitTokens = context.nullableInt("contextLimitTokens"),
                maximumOutputTokens = context.nullableInt("maximumOutputTokens"),
                requestedOutputTokens = context.int("requestedOutputTokens"),
                limitSource = ChapterContextLimitSource.valueOf(context.string("limitSource")),
                unknownLimitConfirmed = context.boolean("unknownLimitConfirmed"),
                tokenizerFamily = context.string("tokenizerFamily"),
            ),
            userAddition = context.optionalString("userAddition"),
        )
    }

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

    private data class FrozenContextInput(
        val targetChapterIndex: Int,
        val promptBindingHash: String,
        val progressionEvidenceHash: String,
        val budget: ChapterContextBudgetSpec,
        val userAddition: String?,
    )

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
        val canonFacts: List<app.zhijuan.core.database.memory.CanonFactEntity>,
        val summaries: List<app.zhijuan.core.database.memory.ChapterSummaryEntity>,
        val entityEvents: List<app.zhijuan.core.database.memory.EntityEventEntity>,
        val timelineEvents: List<app.zhijuan.core.database.memory.TimelineEventEntity>,
        val foreshadows: List<app.zhijuan.core.database.memory.ForeshadowItemEntity>,
        val aggregateState: app.zhijuan.core.database.memory.AggregateStateProjectionEntity?,
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
        const val MAX_CANON_FACTS = 512
        const val MAX_SUMMARIES = 8
        const val MAX_ENTITY_EVENTS = 512
        const val MAX_TIMELINE_EVENTS = 256
        const val MAX_FORESHADOWS = 128
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
    ChapterContextBlockReason.REQUIRED_SOURCE_MISSING -> StandardErrorCode.FORMAT_INVALID
    ChapterContextBlockReason.UNKNOWN_CONTEXT_LIMIT_REQUIRES_CONFIRMATION,
    ChapterContextBlockReason.OUTPUT_RESERVE_LEAVES_NO_INPUT_BUDGET,
    ChapterContextBlockReason.REQUIRED_CONTEXT_EXCEEDS_BUDGET,
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
