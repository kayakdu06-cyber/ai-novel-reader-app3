package app.zhijuan.core.database.generation

import androidx.room.withTransaction
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.database.library.ChapterVersionEntity
import app.zhijuan.core.database.library.StaleChapterVersionException
import app.zhijuan.core.database.memory.CanonFactEntity
import app.zhijuan.core.database.memory.ChapterSummaryEntity
import app.zhijuan.core.database.memory.ChapterTrackingProjectionEntity
import app.zhijuan.core.database.memory.ConsistencyReportEntity
import app.zhijuan.core.database.memory.EntityEventEntity
import app.zhijuan.core.database.memory.ForeshadowItemEntity
import app.zhijuan.core.database.memory.ForeshadowTransitionEntity
import app.zhijuan.core.database.memory.TimelineEventEntity
import app.zhijuan.core.database.search.MemorySearchIndexWriterV1
import app.zhijuan.core.model.BookStatus
import app.zhijuan.core.model.CanonLevel
import app.zhijuan.core.model.ChapterStatus
import app.zhijuan.core.model.ChapterVersionSource
import app.zhijuan.core.model.ConsistencyStatus
import app.zhijuan.core.model.DerivedDataStatus
import app.zhijuan.core.model.ForeshadowStatus
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.model.MemorySource
import app.zhijuan.core.model.RequestAttemptStatus
import app.zhijuan.core.model.UsageLedgerStatus
import app.zhijuan.core.security.AndroidProtectedArtifactStore
import app.zhijuan.core.security.ProtectedArtifactType
import app.zhijuan.core.task.GenerationJobStateMachine
import app.zhijuan.core.task.GenerationStageStateMachine
import app.zhijuan.core.task.JobEvent
import app.zhijuan.core.task.StageEvent
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

data class ChapterFinalCandidateArtifactEvidenceV1(
    val role: ChapterCandidateArtifactRoleV1,
    val stageId: String,
    val attemptId: String,
    val artifactRefId: String,
    val artifactRevision: Int,
    val rawOutputHash: String,
    val canonicalOutputHash: String,
    val sourceBindingHash: String,
)

data class ChapterFinalCandidateCommitDraftV1(
    val chapterVersionId: String,
    val chapterId: String,
    val expectedCurrentVersionId: String?,
    val content: String,
    val revisionIndex: Int,
    val maximumAutomaticRevisions: Int,
    val candidateContentHashHistory: List<String>,
    val artifacts: List<ChapterFinalCandidateArtifactEvidenceV1>,
    val summary: ChapterSummaryEntity,
    val entityEvents: List<EntityEventEntity>,
    val canonFacts: List<CanonFactEntity>,
    val memoryOutputContentHash: String,
    val trackingProjection: ChapterTrackingProjectionEntity,
    val timelineEvents: List<TimelineEventEntity>,
    val newForeshadows: List<ForeshadowItemEntity>,
    val existingForeshadowUpdates: List<ForeshadowProjectionUpdate>,
    val foreshadowTransitions: List<ForeshadowTransitionEntity>,
    val trackingOutputContentHash: String,
    val consistencyReport: ConsistencyReportEntity,
    val consistencyReportContentHash: String,
    val consistencyOutputContentHash: String,
    val committedAt: Long,
) {
    override fun toString(): String =
        "ChapterFinalCandidateCommitDraftV1(revisionIndex=$revisionIndex, artifactCount=${artifacts.size}, " +
            "derivedCounts=${listOf(entityEvents.size, canonFacts.size, timelineEvents.size, newForeshadows.size, foreshadowTransitions.size)}, content=redacted)"
}

data class ChapterFinalCandidateCommitResultV1(
    val chapterVersionId: String,
    val chapterId: String,
    val stageId: String,
    val revisionIndex: Int,
    val replayed: Boolean,
    val isCurrentVersion: Boolean,
    val staleCascade: ChapterStaleCascade?,
)

/** Publishes the accepted candidate and every derived row as one SQLCipher transaction. */
class ChapterFinalCandidateCommitRepositoryV1(
    private val database: ZhijuanDatabase,
    private val artifactStore: AndroidProtectedArtifactStore,
    private val leasePolicy: GenerationLeasePolicy = GenerationLeasePolicy(),
) {
    suspend fun commit(
        stageId: String,
        leaseToken: GenerationLeaseToken,
        draft: ChapterFinalCandidateCommitDraftV1,
    ): ChapterFinalCandidateCommitResultV1 {
        validateDraft(draft)
        val contentHash = sha256(draft.content)
        require(contentHash == draft.candidateContentHashHistory.last())
        val artifactsByRole = draft.artifacts.associateBy { it.role }
        if (database.generationDao().findStage(stageId)?.status != GenerationStageStatus.SUCCEEDED) {
            verifyArtifactFiles(draft, artifactsByRole, contentHash)
        }
        val payloadHash = commitPayloadHash(draft, contentHash)
        val outputReference = outputReferenceJson(stageId, draft, artifactsByRole, contentHash, payloadHash)

        return database.withTransaction {
            val generation = database.generationDao()
            val library = database.libraryDao()
            val memory = database.memoryDao()
            val finalStage = requireNotNull(generation.findStage(stageId)) { "Final chapter Stage no longer exists." }
            val job = requireNotNull(generation.findJob(finalStage.jobId)) { "Final chapter Job no longer exists." }
            val chapter = requireNotNull(library.findChapter(draft.chapterId)) { "Final chapter target no longer exists." }
            val book = requireNotNull(library.findBook(chapter.bookId)) { "Final chapter book no longer exists." }
            require(
                finalStage.phase == GenerationPhase.COMMIT_CHAPTER &&
                    finalStage.targetType == GenerationTargetType.CHAPTER &&
                    finalStage.targetId == chapter.chapterId &&
                    job.bookId == book.bookId,
            ) { "Final commit Stage does not target this chapter and book." }
            val persistedEvidence = requireArtifactEvidence(
                draft = draft,
                artifactsByRole = artifactsByRole,
                finalStage = finalStage,
                jobId = job.jobId,
                bookId = book.bookId,
            )
            requireDerivedOwnership(draft, book.bookId, chapter.chapterIndex, persistedEvidence)
            requireFinalCommitStageBinding(draft, finalStage, persistedEvidence, chapter.chapterIndex)

            if (finalStage.status == GenerationStageStatus.SUCCEEDED) {
                return@withTransaction replayCommitted(finalStage, job, draft, contentHash, outputReference)
            }
            require(finalStage.status == GenerationStageStatus.COMMITTING) {
                "Final chapter publication can only commit from COMMITTING."
            }
            require(
                job.status in setOf(GenerationJobStatus.RUNNING, GenerationJobStatus.PAUSING) &&
                    job.currentStageId == finalStage.stageId,
            ) { "Final chapter Job is not running the publication Stage." }
            requireActiveLease(finalStage, leaseToken, draft.committedAt)
            require(book.status in setOf(BookStatus.DRAFT, BookStatus.GENERATING))
            require(chapter.currentVersionId == draft.expectedCurrentVersionId) {
                "Chapter changed after the candidate pipeline started."
            }
            require(library.versionsForGenerationStage(finalStage.stageId).isEmpty()) {
                "Final Stage already owns a chapter version without a completed commit record."
            }

            val replacedSearchIdentities = draft.expectedCurrentVersionId?.let { replacedVersionId ->
                MemorySearchIndexWriterV1.identitiesForReplacedChapter(memory, book.bookId, replacedVersionId)
            }
            val stale = draft.expectedCurrentVersionId?.let { replacedVersionId ->
                memory.markDerivedDataStaleForReplacedChapter(book.bookId, replacedVersionId, draft.committedAt)
            }
            val bodyAttempt = persistedEvidence.getValue(ChapterCandidateArtifactRoleV1.BODY).second
            val version = ChapterVersionEntity(
                chapterVersionId = draft.chapterVersionId,
                chapterId = chapter.chapterId,
                versionNo = library.maximumVersionNumber(chapter.chapterId) + 1,
                content = draft.content,
                characterCount = draft.content.codePointCount(0, draft.content.length),
                contentHash = contentHash,
                source = ChapterVersionSource.AI_GENERATED,
                parentVersionId = draft.expectedCurrentVersionId,
                generationStageId = finalStage.stageId,
                modelSnapshotJson = bodyAttempt.modelSnapshotJson,
                createdAt = draft.committedAt,
            )
            library.insertChapterVersion(version)
            memory.insertSummary(draft.summary)
            if (draft.entityEvents.isNotEmpty()) memory.insertEntityEvents(draft.entityEvents)
            if (draft.canonFacts.isNotEmpty()) memory.insertCanonFacts(draft.canonFacts)
            if (draft.timelineEvents.isNotEmpty()) memory.insertTimelineEvents(draft.timelineEvents)
            if (draft.newForeshadows.isNotEmpty()) memory.insertForeshadows(draft.newForeshadows)
            draft.existingForeshadowUpdates.forEach { update ->
                check(
                    memory.compareAndTransitionForeshadow(
                        foreshadowItemId = update.foreshadowItemId,
                        bookId = book.bookId,
                        fromStatus = update.expectedFromStatus.name,
                        toStatus = update.toStatus.name,
                        sourceChapterVersionId = version.chapterVersionId,
                        resolvedChapterVersionId = update.resolvedChapterVersionId,
                        visibleEntityIdsJson = update.visibleEntityIdsJson,
                        importance = update.importance,
                        updatedAt = draft.committedAt,
                    ) == 1,
                ) { "A foreshadow changed after the accepted candidate source was frozen." }
            }
            if (draft.foreshadowTransitions.isNotEmpty()) {
                memory.insertForeshadowTransitions(draft.foreshadowTransitions)
            }
            ForeshadowProjectionRevisionWriterV1(memory).persistAfterStates(
                bookId = book.bookId,
                chapterIndex = chapter.chapterIndex,
                sourceChapterVersionId = version.chapterVersionId,
                generationStageId = draft.trackingProjection.generationStageId,
                transitions = draft.foreshadowTransitions,
            )
            memory.insertTrackingProjection(draft.trackingProjection)
            memory.insertConsistencyReport(draft.consistencyReport)

            val search = database.memorySearchDao()
            replacedSearchIdentities?.let { search.deleteSources(it) }
            MemorySearchIndexWriterV1.replaceChapterMemory(
                search = search,
                summary = draft.summary,
                entityEvents = draft.entityEvents,
                canonFacts = draft.canonFacts,
            )
            MemorySearchIndexWriterV1.replaceStoryTracking(
                search = search,
                chapterIndex = chapter.chapterIndex,
                timelineEvents = draft.timelineEvents,
                foreshadows = (
                    draft.newForeshadows + draft.existingForeshadowUpdates.map { update ->
                        requireNotNull(memory.findForeshadow(update.foreshadowItemId)) {
                            "Updated foreshadow disappeared during final publication."
                        }
                    }
                ).sortedBy { it.foreshadowItemId },
            )

            if (
                library.compareAndSetGeneratedCurrentVersion(
                    chapterId = chapter.chapterId,
                    expectedCurrentVersionId = draft.expectedCurrentVersionId,
                    newVersionId = version.chapterVersionId,
                    status = ChapterStatus.READY,
                    consistencyStatus = ConsistencyStatus.VALID,
                    updatedAt = draft.committedAt,
                ) != 1
            ) throw StaleChapterVersionException("Chapter changed while the final candidate was committing.")
            check(
                library.updateBookAfterGeneratedChapter(
                    bookId = book.bookId,
                    completedChapterIncrement = if (draft.expectedCurrentVersionId == null) 1 else 0,
                    status = BookStatus.GENERATING,
                    generationStatusSummary = "CHAPTER_READY:${chapter.chapterIndex}",
                    updatedAt = draft.committedAt,
                ) == 1,
            ) { "Book changed during final chapter publication." }
            check(
                GenerationStageStateMachine.transition(finalStage.status, StageEvent.COMMIT_SUCCEEDED) ==
                    GenerationStageStatus.SUCCEEDED,
            )
            if (
                generation.compareAndCommitStageOutput(
                    stageId = finalStage.stageId,
                    leaseOwnerId = leaseToken.ownerId,
                    leaseAcquiredAt = leaseToken.acquiredAt,
                    outputReferenceJson = outputReference,
                    updatedAt = draft.committedAt,
                ) != 1
            ) throw StaleGenerationStateException("Final publication lost the current Stage lease.")
            require(generation.countNonSucceededStages(job.jobId) == 0) {
                "Final publication cannot complete while another candidate Stage is unfinished."
            }
            check(
                GenerationJobStateMachine.transition(job.status, JobEvent.ALL_STAGES_COMPLETED) ==
                    GenerationJobStatus.COMPLETED,
            )
            if (
                generation.compareAndCompleteJobAfterStage(
                    jobId = job.jobId,
                    expectedCurrentStageId = finalStage.stageId,
                    updatedAt = draft.committedAt,
                ) != 1
            ) throw StaleGenerationStateException("Final publication lost the current Job state.")

            ChapterFinalCandidateCommitResultV1(
                version.chapterVersionId,
                chapter.chapterId,
                finalStage.stageId,
                draft.revisionIndex,
                replayed = false,
                isCurrentVersion = true,
                staleCascade = stale?.toPublic(),
            )
        }
    }

    private suspend fun requireArtifactEvidence(
        draft: ChapterFinalCandidateCommitDraftV1,
        artifactsByRole: Map<ChapterCandidateArtifactRoleV1, ChapterFinalCandidateArtifactEvidenceV1>,
        finalStage: GenerationStageEntity,
        jobId: String,
        bookId: String,
    ): Map<ChapterCandidateArtifactRoleV1, Pair<GenerationStageEntity, RequestAttemptEntity>> {
        val dao = database.generationDao()
        val result = linkedMapOf<ChapterCandidateArtifactRoleV1, Pair<GenerationStageEntity, RequestAttemptEntity>>()
        artifactsByRole.keys.sortedBy { it.ordinal }.forEach { role ->
            val evidence = artifactsByRole.getValue(role)
            val stage = requireNotNull(dao.findStage(evidence.stageId)) { "Candidate evidence Stage is missing." }
            val attempt = requireNotNull(dao.findAttempt(evidence.attemptId)) { "Candidate evidence Attempt is missing." }
            val usage = requireNotNull(dao.findUsageForAttempt(attempt.attemptId)) { "Candidate Usage is missing." }
            require(
                stage.jobId == jobId && stage.status == GenerationStageStatus.SUCCEEDED &&
                    stage.phase in role.allowedPhases && stage.targetType == GenerationTargetType.CHAPTER &&
                    stage.targetId == draft.chapterId && attempt.stageId == stage.stageId &&
                    attempt.status == RequestAttemptStatus.SUCCEEDED && attempt.standardErrorCode == null &&
                    attempt.inputHash == evidence.sourceBindingHash && attempt.outputHash == evidence.rawOutputHash &&
                    attempt.streamDraftRef == evidence.artifactRefId &&
                    dao.attemptsForStage(stage.stageId).lastOrNull()?.attemptId == attempt.attemptId &&
                    usage.bookId == bookId && usage.status == UsageLedgerStatus.FINAL,
            ) { "Candidate evidence changed or its Usage is not final." }
            val output = parseObject(requireNotNull(stage.outputReferenceJson), "Candidate output reference")
            require(
                output.string("pipelineVersion") == ChapterCandidateArtifactSealRepositoryV1.PIPELINE_VERSION &&
                    output.string("artifactRole") == role.name && output.string("outputSchemaId") == role.schemaId &&
                    output.string("attemptId") == evidence.attemptId &&
                    output.string("artifactRefId") == evidence.artifactRefId &&
                    output.int("artifactRevision") == evidence.artifactRevision &&
                    output.string("rawOutputHash") == evidence.rawOutputHash &&
                    output.string("canonicalOutputHash") == evidence.canonicalOutputHash &&
                    output.string("sourceBindingHash") == evidence.sourceBindingHash &&
                    output.string("candidateChapterVersionId") == draft.chapterVersionId &&
                    output.string("candidateContentHash") == draft.candidateContentHashHistory.last() &&
                    output.string("chapterId") == draft.chapterId &&
                    output.int("revisionIndex") == draft.revisionIndex,
            ) { "Candidate Stage seal does not match the final publication evidence." }
            result[role] = stage to attempt
        }
        val body = parseObject(
            requireNotNull(result.getValue(ChapterCandidateArtifactRoleV1.BODY).first.outputReferenceJson),
            "Body seal",
        )
        val postAnalysis = result[ChapterCandidateArtifactRoleV1.POST_ANALYSIS]
        if (postAnalysis != null) {
            val post = parseObject(requireNotNull(postAnalysis.first.outputReferenceJson), "Post-analysis seal")
            require(
                body.string("nextStageId") == postAnalysis.first.stageId &&
                    post.string("nextStageId") == finalStage.stageId,
            ) { "Candidate evidence is not one contiguous body-post-analysis chain." }
        } else {
            val memory = parseObject(requireNotNull(result.getValue(ChapterCandidateArtifactRoleV1.MEMORY).first.outputReferenceJson), "Memory seal")
            val tracking = parseObject(requireNotNull(result.getValue(ChapterCandidateArtifactRoleV1.TRACKING).first.outputReferenceJson), "Tracking seal")
            val consistency = parseObject(requireNotNull(result.getValue(ChapterCandidateArtifactRoleV1.CONSISTENCY).first.outputReferenceJson), "Consistency seal")
            require(
                body.string("nextStageId") == artifactsByRole.getValue(ChapterCandidateArtifactRoleV1.MEMORY).stageId &&
                    memory.string("nextStageId") == artifactsByRole.getValue(ChapterCandidateArtifactRoleV1.TRACKING).stageId &&
                    tracking.string("nextStageId") == artifactsByRole.getValue(ChapterCandidateArtifactRoleV1.CONSISTENCY).stageId &&
                    consistency.string("nextStageId") == finalStage.stageId,
            ) { "Candidate evidence is not one contiguous body-memory-tracking-check chain." }
        }
        return result
    }

    private fun requireFinalCommitStageBinding(
        draft: ChapterFinalCandidateCommitDraftV1,
        finalStage: GenerationStageEntity,
        evidence: Map<ChapterCandidateArtifactRoleV1, Pair<GenerationStageEntity, RequestAttemptEntity>>,
        chapterIndex: Int,
    ) {
        val source = ChapterFinalCommitStageBindingV1.parseAndVerify(finalStage)
        val analysisRole = if (ChapterCandidateArtifactRoleV1.POST_ANALYSIS in evidence) {
            ChapterCandidateArtifactRoleV1.POST_ANALYSIS
        } else {
            ChapterCandidateArtifactRoleV1.CONSISTENCY
        }
        val analysisStage = evidence.getValue(analysisRole).first
        val analysisOutput = parseObject(
            requireNotNull(analysisStage.outputReferenceJson) { "Analysis seal output is missing." },
            "Analysis seal",
        )
        require(
            source.candidateChapterVersionId == draft.chapterVersionId &&
                source.candidateContentHash == draft.candidateContentHashHistory.last() &&
                source.chapterId == draft.chapterId && source.chapterIndex == chapterIndex &&
                source.revisionIndex == draft.revisionIndex &&
                source.expectedCurrentVersionId == draft.expectedCurrentVersionId &&
                source.maximumAutomaticRevisions == draft.maximumAutomaticRevisions &&
                source.candidateContentHashHistory == draft.candidateContentHashHistory &&
                source.predecessorStageId == analysisStage.stageId &&
                source.routeBindingHash == analysisOutput.string("routeBindingHash") &&
                source.consistencyRequestSourceBindingHash == analysisOutput.string("sourceBindingHash"),
        ) { "Final commit Stage source does not match the frozen publication draft." }
    }

    private fun requireDerivedOwnership(
        draft: ChapterFinalCandidateCommitDraftV1,
        bookId: String,
        chapterIndex: Int,
        evidence: Map<ChapterCandidateArtifactRoleV1, Pair<GenerationStageEntity, RequestAttemptEntity>>,
    ) {
        val versionId = draft.chapterVersionId
        val contentHash = draft.candidateContentHashHistory.last()
        val postAnalysis = evidence[ChapterCandidateArtifactRoleV1.POST_ANALYSIS]
        val memoryEvidence = postAnalysis ?: evidence.getValue(ChapterCandidateArtifactRoleV1.MEMORY)
        val trackingEvidence = postAnalysis ?: evidence.getValue(ChapterCandidateArtifactRoleV1.TRACKING)
        val consistencyEvidence = postAnalysis ?: evidence.getValue(ChapterCandidateArtifactRoleV1.CONSISTENCY)
        val memoryAttempt = memoryEvidence.second
        val trackingAttempt = trackingEvidence.second
        val consistencyAttempt = consistencyEvidence.second
        require(
            draft.summary.bookId == bookId && draft.summary.chapterVersionId == versionId &&
                draft.summary.chapterIndex == chapterIndex && draft.summary.status == DerivedDataStatus.VALID &&
                draft.summary.modelSnapshotJson == memoryAttempt.modelSnapshotJson &&
                draft.summary.createdAt == draft.committedAt && draft.summary.updatedAt == draft.committedAt,
        ) { "Final chapter summary provenance is invalid." }
        require(sourceHash(draft.summary.summaryJson) == contentHash)
        draft.entityEvents.forEach { event ->
            require(
                event.bookId == bookId && event.sourceChapterVersionId == versionId &&
                    event.status == DerivedDataStatus.VALID && event.createdAt == draft.committedAt &&
                    event.canonLevel in setOf(CanonLevel.STORY_CANON, CanonLevel.INFERRED) &&
                    event.confidenceMicros in 0..1_000_000 && sourceHash(event.evidenceJson) == contentHash,
            ) { "Final entity-event provenance is invalid." }
        }
        draft.canonFacts.forEach { fact ->
            require(
                fact.bookId == bookId && fact.sourceChapterVersionId == versionId && fact.sourceBibleRevisionId == null &&
                    fact.status == DerivedDataStatus.VALID && fact.createdAt == draft.committedAt &&
                    fact.canonLevel in setOf(CanonLevel.STORY_CANON, CanonLevel.INFERRED) &&
                    sourceHash(fact.factPayloadJson) == contentHash,
            ) { "Final canon-fact provenance is invalid." }
        }
        val projection = draft.trackingProjection
        val trackingStage = trackingEvidence.first
        require(
            projection.bookId == bookId && projection.chapterVersionId == versionId &&
                projection.chapterIndex == chapterIndex && projection.generationStageId == trackingStage.stageId &&
                projection.sourceChapterContentHash == contentHash && projection.status == DerivedDataStatus.VALID &&
                projection.modelSnapshotJson == trackingAttempt.modelSnapshotJson &&
                projection.outputContentHash == draft.trackingOutputContentHash &&
                projection.timelineEventCount == draft.timelineEvents.size &&
                projection.foreshadowTransitionCount == draft.foreshadowTransitions.size &&
                projection.createdAt == draft.committedAt && projection.updatedAt == draft.committedAt &&
                projection.payloadHash == ChapterTrackingPayloadHasher.hash(
                    draft.timelineEvents,
                    draft.newForeshadows,
                    draft.existingForeshadowUpdates,
                    draft.foreshadowTransitions,
                ),
        ) { "Final story-tracking projection provenance is invalid." }
        validateTrackingRows(draft, bookId, versionId, trackingStage.stageId, contentHash)
        val report = draft.consistencyReport
        val consistencyStage = consistencyEvidence.first
        require(
            report.bookId == bookId && report.targetChapterVersionId == versionId &&
                report.targetChapterIndex == chapterIndex && report.generationStageId == consistencyStage.stageId &&
                report.status == DerivedDataStatus.VALID && report.createdAt == draft.committedAt &&
                report.updatedAt == draft.committedAt && sha256(report.issuesJson) == draft.consistencyReportContentHash,
        ) { "Final consistency-report provenance is invalid." }
        val reportJson = parseObject(report.issuesJson, "Consistency report")
        val modelSnapshot = canonical(parseObject(consistencyAttempt.modelSnapshotJson, "Consistency model snapshot"))
        require(
            reportJson.string("decision") == "ACCEPT_CANDIDATE" &&
                reportJson.string("chapterConsistencyStatus") == ConsistencyStatus.VALID.name &&
                reportJson.int("blockerCount") == 0 && reportJson.int("majorCount") == 0 &&
                reportJson.string("sourceChapterVersionId") == versionId &&
                reportJson.string("sourceChapterContentHash") == contentHash &&
                reportJson.string("chapterId") == draft.chapterId && reportJson.int("chapterIndex") == chapterIndex &&
                reportJson["modelSnapshot"] == modelSnapshot,
        ) { "Only a source-bound accepted consistency report can publish a chapter." }
        val mergedOutputHash = draft.artifacts.singleOrNull {
            it.role == ChapterCandidateArtifactRoleV1.POST_ANALYSIS
        }?.canonicalOutputHash
        require(
            if (mergedOutputHash != null) {
                draft.memoryOutputContentHash == mergedOutputHash &&
                    draft.trackingOutputContentHash == mergedOutputHash &&
                    draft.consistencyOutputContentHash == mergedOutputHash
            } else {
                draft.memoryOutputContentHash == draft.artifacts.single {
                    it.role == ChapterCandidateArtifactRoleV1.MEMORY
                }.canonicalOutputHash &&
                    draft.trackingOutputContentHash == draft.artifacts.single {
                        it.role == ChapterCandidateArtifactRoleV1.TRACKING
                    }.canonicalOutputHash &&
                    draft.consistencyOutputContentHash == draft.artifacts.single {
                        it.role == ChapterCandidateArtifactRoleV1.CONSISTENCY
                    }.canonicalOutputHash
            },
        )
    }

    private fun validateTrackingRows(
        draft: ChapterFinalCandidateCommitDraftV1,
        bookId: String,
        versionId: String,
        trackingStageId: String,
        contentHash: String,
    ) {
        require(draft.newForeshadows.size + draft.existingForeshadowUpdates.size == draft.foreshadowTransitions.size)
        draft.timelineEvents.forEach { event ->
            require(
                event.bookId == bookId && event.sourceChapterVersionId == versionId &&
                    event.status == DerivedDataStatus.VALID && event.createdAt == draft.committedAt &&
                    sourceHash(event.constraintsJson) == contentHash,
            )
        }
        draft.newForeshadows.forEach { item ->
            require(
                item.bookId == bookId && item.foreshadowStatus == ForeshadowStatus.PLANTED &&
                    item.memoryStatus == DerivedDataStatus.VALID && item.source == MemorySource.CHAPTER_EXTRACTION &&
                    item.sourceChapterVersionId == versionId && item.plantedChapterVersionId == versionId &&
                    item.resolvedChapterVersionId == null && item.createdAt == draft.committedAt &&
                    item.updatedAt == draft.committedAt && item.importance in 0..100,
            )
        }
        draft.existingForeshadowUpdates.forEach { update ->
            require(update.importance in 0..100)
            require(update.expectedFromStatus in ACTIVE_FORESHADOW_STATES)
            require(update.toStatus in TERMINAL_OR_DEVELOPING_FORESHADOW_STATES)
            require((update.toStatus == ForeshadowStatus.RESOLVED) == (update.resolvedChapterVersionId == versionId))
        }
        val transitionByItem = draft.foreshadowTransitions.associateBy { it.foreshadowItemId }
        require(transitionByItem.size == draft.foreshadowTransitions.size)
        draft.foreshadowTransitions.forEach { transition ->
            require(
                transition.bookId == bookId && transition.sourceChapterVersionId == versionId &&
                    transition.generationStageId == trackingStageId && transition.status == DerivedDataStatus.VALID &&
                    transition.createdAt == draft.committedAt && transition.operation in OPERATIONS &&
                    sourceHash(transition.evidenceJson) == contentHash,
            )
        }
        draft.newForeshadows.forEach { item ->
            val transition = requireNotNull(transitionByItem[item.foreshadowItemId])
            require(transition.operation == "PLANT" && transition.fromStatus == null && transition.toStatus == ForeshadowStatus.PLANTED)
        }
        draft.existingForeshadowUpdates.forEach { update ->
            val transition = requireNotNull(transitionByItem[update.foreshadowItemId])
            val operation = when (update.toStatus) {
                ForeshadowStatus.DEVELOPING -> "DEVELOP"
                ForeshadowStatus.RESOLVED -> "RESOLVE"
                ForeshadowStatus.ABANDONED -> "ABANDON"
                else -> error("Unsupported foreshadow target state.")
            }
            require(transition.operation == operation && transition.fromStatus == update.expectedFromStatus && transition.toStatus == update.toStatus)
        }
    }

    private suspend fun replayCommitted(
        stage: GenerationStageEntity,
        job: GenerationJobEntity,
        draft: ChapterFinalCandidateCommitDraftV1,
        contentHash: String,
        outputReference: String,
    ): ChapterFinalCandidateCommitResultV1 {
        val library = database.libraryDao()
        val memory = database.memoryDao()
        val versions = library.versionsForGenerationStage(stage.stageId)
        require(
            versions.size == 1 && versions.single().chapterVersionId == draft.chapterVersionId &&
                versions.single().chapterId == draft.chapterId && versions.single().contentHash == contentHash &&
                stage.outputReferenceJson == outputReference,
        ) { "Completed final Stage does not match the replayed publication payload." }
        require(memory.findSummaryForVersion(draft.chapterVersionId) == draft.summary)
        require(memory.entityEventsForVersion(draft.chapterVersionId) == draft.entityEvents.sortedWith(EVENT_ORDER))
        require(memory.canonFactsForVersion(draft.chapterVersionId) == draft.canonFacts.sortedBy { it.canonFactId })
        require(memory.timelineEventsForVersion(draft.chapterVersionId) == draft.timelineEvents.sortedWith(TIMELINE_ORDER))
        require(memory.findTrackingProjectionForVersion(draft.chapterVersionId) == draft.trackingProjection)
        require(memory.foreshadowTransitionsForStage(draft.trackingProjection.generationStageId) == draft.foreshadowTransitions.sortedWith(TRANSITION_ORDER))
        val recordedForeshadowAfterStates = ForeshadowProjectionRevisionWriterV1(memory).requireStoredAfterStates(
            bookId = draft.summary.bookId,
            chapterIndex = draft.summary.chapterIndex,
            sourceChapterVersionId = draft.chapterVersionId,
            generationStageId = draft.trackingProjection.generationStageId,
            transitions = draft.foreshadowTransitions,
        )
        require(memory.findConsistencyReport(draft.consistencyReport.consistencyReportId) == draft.consistencyReport)
        MemorySearchIndexWriterV1.replaceChapterMemory(
            search = database.memorySearchDao(),
            summary = draft.summary,
            entityEvents = draft.entityEvents,
            canonFacts = draft.canonFacts,
        )
        MemorySearchIndexWriterV1.replaceStoryTracking(
            search = database.memorySearchDao(),
            chapterIndex = draft.summary.chapterIndex,
            timelineEvents = memory.timelineEventsForVersion(draft.chapterVersionId),
            foreshadows = recordedForeshadowAfterStates.mapNotNull { recorded ->
                memory.findForeshadow(recorded.foreshadowItemId)?.takeIf { current -> current == recorded }
            },
        )
        require(job.status == GenerationJobStatus.COMPLETED)
        return ChapterFinalCandidateCommitResultV1(
            draft.chapterVersionId,
            draft.chapterId,
            stage.stageId,
            draft.revisionIndex,
            replayed = true,
            isCurrentVersion = library.findChapter(draft.chapterId)?.currentVersionId == draft.chapterVersionId,
            staleCascade = null,
        )
    }

    private fun validateDraft(draft: ChapterFinalCandidateCommitDraftV1) {
        require(IDENTIFIER.matches(draft.chapterVersionId) && IDENTIFIER.matches(draft.chapterId))
        require(draft.expectedCurrentVersionId == null || IDENTIFIER.matches(draft.expectedCurrentVersionId))
        require(draft.content.isNotBlank() && utf8Size(draft.content) <= MAX_CHAPTER_BYTES && draft.committedAt >= 0L)
        require(draft.maximumAutomaticRevisions in 1..2 && draft.revisionIndex in 0..draft.maximumAutomaticRevisions)
        require(
            draft.candidateContentHashHistory.size == draft.revisionIndex + 1 &&
                draft.candidateContentHashHistory.all(HASH::matches) &&
                draft.candidateContentHashHistory.distinct().size == draft.candidateContentHashHistory.size,
        ) { "Final candidate lineage is incomplete or cyclic." }
        val roles = draft.artifacts.map { it.role }.toSet()
        require(draft.artifacts.size == roles.size && roles in SUPPORTED_ARTIFACT_ROLE_SETS)
        draft.artifacts.forEach { evidence ->
            require(listOf(evidence.stageId, evidence.attemptId, evidence.artifactRefId).all(IDENTIFIER::matches))
            require(evidence.artifactRevision > 0)
            require(listOf(evidence.rawOutputHash, evidence.canonicalOutputHash, evidence.sourceBindingHash).all(HASH::matches))
        }
        require(HASH.matches(draft.memoryOutputContentHash) && HASH.matches(draft.trackingOutputContentHash))
        require(HASH.matches(draft.consistencyReportContentHash) && HASH.matches(draft.consistencyOutputContentHash))
        requireDistinct(draft.entityEvents.map { it.entityEventId })
        requireDistinct(draft.canonFacts.map { it.canonFactId })
        requireDistinct(draft.timelineEvents.map { it.timelineEventId })
        requireDistinct(draft.newForeshadows.map { it.foreshadowItemId })
        requireDistinct(draft.existingForeshadowUpdates.map { it.foreshadowItemId })
        requireDistinct(draft.foreshadowTransitions.map { it.transitionId })
        require((draft.newForeshadows.map { it.foreshadowItemId } intersect draft.existingForeshadowUpdates.map { it.foreshadowItemId }.toSet()).isEmpty())
        val json = buildList {
            add(draft.summary.summaryJson)
            addAll(draft.entityEvents.flatMap { listOfNotNull(it.oldValueJson, it.newValueJson, it.evidenceJson) })
            addAll(draft.canonFacts.flatMap { listOf(it.factPayloadJson, it.scopeJson) })
            addAll(draft.timelineEvents.flatMap { listOf(it.participantsJson, it.constraintsJson) })
            addAll(draft.newForeshadows.map { it.visibleEntityIdsJson })
            addAll(draft.existingForeshadowUpdates.map { it.visibleEntityIdsJson })
            addAll(draft.foreshadowTransitions.map { it.evidenceJson })
            add(draft.consistencyReport.issuesJson)
        }
        require(json.sumOf { it.length.toLong() } <= MAX_DERIVED_JSON_CHARACTERS)
        json.forEach { parseJson(it) }
    }

    private fun verifyArtifactFiles(
        draft: ChapterFinalCandidateCommitDraftV1,
        artifactsByRole: Map<ChapterCandidateArtifactRoleV1, ChapterFinalCandidateArtifactEvidenceV1>,
        contentHash: String,
    ) {
        artifactsByRole.keys.sortedBy { it.ordinal }.forEach { role ->
            val evidence = artifactsByRole.getValue(role)
            artifactStore.readBytes(
                evidence.artifactRefId,
                ProtectedArtifactType.STREAM_DRAFT,
                if (role == ChapterCandidateArtifactRoleV1.BODY) MAX_CHAPTER_BYTES else MAX_STRUCTURED_BYTES,
            ).use { lease ->
                require(lease.descriptor.revision == evidence.artifactRevision)
                lease.withBytes { bytes ->
                    require(sha256(bytes) == evidence.rawOutputHash)
                    val canonicalHash = if (role == ChapterCandidateArtifactRoleV1.BODY) {
                        evidence.rawOutputHash
                    } else {
                        sha256(parseObject(bytes.decodeToString(), "Candidate artifact").toString())
                    }
                    require(canonicalHash == evidence.canonicalOutputHash)
                }
            }
        }
        require(artifactsByRole.getValue(ChapterCandidateArtifactRoleV1.BODY).canonicalOutputHash == contentHash)
    }

    private fun outputReferenceJson(
        stageId: String,
        draft: ChapterFinalCandidateCommitDraftV1,
        artifacts: Map<ChapterCandidateArtifactRoleV1, ChapterFinalCandidateArtifactEvidenceV1>,
        contentHash: String,
        payloadHash: String,
    ) = JsonObject(
        linkedMapOf(
            "schemaVersion" to JsonPrimitive(1),
            "pipelineVersion" to JsonPrimitive(ChapterCandidateArtifactSealRepositoryV1.PIPELINE_VERSION),
            "stageId" to JsonPrimitive(stageId),
            "chapterVersionId" to JsonPrimitive(draft.chapterVersionId),
            "chapterId" to JsonPrimitive(draft.chapterId),
            "chapterContentHash" to JsonPrimitive(contentHash),
            "revisionIndex" to JsonPrimitive(draft.revisionIndex),
            "maximumAutomaticRevisions" to JsonPrimitive(draft.maximumAutomaticRevisions),
            "candidateHistoryHash" to JsonPrimitive(hashList(draft.candidateContentHashHistory)),
            "bodyStageId" to JsonPrimitive(artifacts.getValue(ChapterCandidateArtifactRoleV1.BODY).stageId),
            "memoryStageId" to JsonPrimitive(derivedStageId(artifacts, ChapterCandidateArtifactRoleV1.MEMORY)),
            "trackingStageId" to JsonPrimitive(derivedStageId(artifacts, ChapterCandidateArtifactRoleV1.TRACKING)),
            "consistencyStageId" to JsonPrimitive(derivedStageId(artifacts, ChapterCandidateArtifactRoleV1.CONSISTENCY)),
            "memoryOutputContentHash" to JsonPrimitive(draft.memoryOutputContentHash),
            "trackingOutputContentHash" to JsonPrimitive(draft.trackingOutputContentHash),
            "consistencyOutputContentHash" to JsonPrimitive(draft.consistencyOutputContentHash),
            "consistencyReportContentHash" to JsonPrimitive(draft.consistencyReportContentHash),
            "commitPayloadHash" to JsonPrimitive(payloadHash),
        ),
    ).toString()

    private fun commitPayloadHash(draft: ChapterFinalCandidateCommitDraftV1, contentHash: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun put(value: Any?) {
            val bytes = (value?.toString() ?: "<null>").toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
            bytes.fill(0)
        }
        listOf(
            1, draft.chapterVersionId, draft.chapterId, draft.expectedCurrentVersionId, contentHash,
            draft.revisionIndex, draft.maximumAutomaticRevisions, hashList(draft.candidateContentHashHistory),
            draft.memoryOutputContentHash, draft.trackingOutputContentHash,
            draft.consistencyOutputContentHash, draft.consistencyReportContentHash,
        ).forEach(::put)
        draft.artifacts.sortedBy { it.role.ordinal }.forEach { evidence ->
            listOf(
                evidence.role.name, evidence.stageId, evidence.attemptId, evidence.artifactRefId,
                evidence.artifactRevision, evidence.rawOutputHash, evidence.canonicalOutputHash,
                evidence.sourceBindingHash,
            ).forEach(::put)
        }
        with(draft.summary) {
            listOf(chapterSummaryId, bookId, chapterVersionId, chapterIndex, schemaVersion, summaryJson, importance,
                status.name, modelSnapshotJson, createdAt, updatedAt).forEach(::put)
        }
        draft.entityEvents.sortedBy { it.entityEventId }.forEach { put(rowHash(it)) }
        draft.canonFacts.sortedBy { it.canonFactId }.forEach { put(rowHash(it)) }
        with(draft.trackingProjection) {
            listOf(projectionId, bookId, chapterVersionId, chapterIndex, generationStageId, sourceChapterContentHash,
                sourceMemorySnapshotHash, priorForeshadowSnapshotHash, outputContentHash, payloadHash, status.name,
                modelSnapshotJson, timelineEventCount, foreshadowTransitionCount, createdAt, updatedAt).forEach(::put)
        }
        draft.timelineEvents.sortedWith(TIMELINE_ORDER).forEach { put(rowHash(it)) }
        draft.newForeshadows.sortedBy { it.foreshadowItemId }.forEach { put(rowHash(it)) }
        draft.existingForeshadowUpdates.sortedBy { it.foreshadowItemId }.forEach { put(rowHash(it)) }
        draft.foreshadowTransitions.sortedWith(TRANSITION_ORDER).forEach { put(rowHash(it)) }
        with(draft.consistencyReport) {
            listOf(consistencyReportId, bookId, targetChapterVersionId, targetChapterIndex, generationStageId,
                checkerVersion, issuesJson, status.name, createdAt, updatedAt).forEach(::put)
        }
        return digest.digest().toHex()
    }

    private fun rowHash(value: Any): String = sha256(value.toString())

    private fun derivedStageId(
        artifacts: Map<ChapterCandidateArtifactRoleV1, ChapterFinalCandidateArtifactEvidenceV1>,
        legacyRole: ChapterCandidateArtifactRoleV1,
    ): String = artifacts[ChapterCandidateArtifactRoleV1.POST_ANALYSIS]?.stageId
        ?: artifacts.getValue(legacyRole).stageId

    private fun requireActiveLease(stage: GenerationStageEntity, token: GenerationLeaseToken, at: Long) {
        require(stage.leaseOwnerId == token.ownerId && stage.leaseAcquiredAt == token.acquiredAt)
        val heartbeatAt = requireNotNull(stage.leaseHeartbeatAt)
        require(at >= stage.updatedAt && at >= heartbeatAt)
        if (leasePolicy.isExpired(heartbeatAt, at)) throw StaleGenerationStateException("Final commit Stage lease expired.")
    }

    private fun sourceHash(json: String): String? =
        (parseJson(json) as? JsonObject)?.get("sourceChapterContentHash")?.jsonPrimitive?.content

    private fun JsonObject.string(name: String): String =
        requireNotNull(this[name]) { "Missing $name." }.jsonPrimitive.content

    private fun JsonObject.int(name: String): Int =
        requireNotNull(this[name]) { "Missing $name." }.jsonPrimitive.int

    private fun canonical(value: JsonObject): JsonObject = JsonObject(
        value.entries.sortedBy { it.key }.associate { (key, element) ->
            key to when (element) {
                is JsonObject -> canonical(element)
                is JsonArray -> JsonArray(element.map { item -> if (item is JsonObject) canonical(item) else item })
                else -> element
            }
        },
    )

    private fun parseObject(value: String, label: String): JsonObject =
        runCatching { STRICT_JSON.parseToJsonElement(value) as JsonObject }
            .getOrElse { throw IllegalArgumentException("$label must be a JSON object.") }

    private fun parseJson(value: String) = runCatching { STRICT_JSON.parseToJsonElement(value) }
        .getOrElse { throw IllegalArgumentException("Final derived data contains invalid JSON.") }

    private fun requireDistinct(ids: List<String>) {
        require(ids.all(IDENTIFIER::matches) && ids.distinct().size == ids.size)
    }

    private fun hashList(values: List<String>) = sha256(values.joinToString("\u0000"))

    private fun utf8Size(value: String) = value.toByteArray(Charsets.UTF_8).size

    private fun sha256(value: String) = sha256(value.toByteArray(Charsets.UTF_8))

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .toHex()

    private fun ByteArray.toHex() = joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        val LEGACY_ARTIFACT_ROLES = setOf(
            ChapterCandidateArtifactRoleV1.BODY,
            ChapterCandidateArtifactRoleV1.MEMORY,
            ChapterCandidateArtifactRoleV1.TRACKING,
            ChapterCandidateArtifactRoleV1.CONSISTENCY,
        )
        val MERGED_ARTIFACT_ROLES = setOf(
            ChapterCandidateArtifactRoleV1.BODY,
            ChapterCandidateArtifactRoleV1.POST_ANALYSIS,
        )
        val SUPPORTED_ARTIFACT_ROLE_SETS = setOf(LEGACY_ARTIFACT_ROLES, MERGED_ARTIFACT_ROLES)
        val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
        val HASH = Regex("[0-9a-f]{64}")
        val STRICT_JSON = Json { isLenient = false; ignoreUnknownKeys = false }
        val EVENT_ORDER = compareBy<EntityEventEntity>({ it.storyOrder }, { it.entityEventId })
        val TIMELINE_ORDER = compareBy<TimelineEventEntity>({ it.storyOrder }, { it.timelineEventId })
        val TRANSITION_ORDER = compareBy<ForeshadowTransitionEntity>({ it.storyOrder }, { it.transitionId })
        val ACTIVE_FORESHADOW_STATES = setOf(ForeshadowStatus.PLANNED, ForeshadowStatus.PLANTED, ForeshadowStatus.DEVELOPING)
        val TERMINAL_OR_DEVELOPING_FORESHADOW_STATES = setOf(ForeshadowStatus.DEVELOPING, ForeshadowStatus.RESOLVED, ForeshadowStatus.ABANDONED)
        val OPERATIONS = setOf("PLANT", "DEVELOP", "RESOLVE", "ABANDON")
        const val MAX_CHAPTER_BYTES = 4 * 1_024 * 1_024
        const val MAX_STRUCTURED_BYTES = 512 * 1_024
        const val MAX_DERIVED_JSON_CHARACTERS = 4L * 1_024 * 1_024
    }
}
