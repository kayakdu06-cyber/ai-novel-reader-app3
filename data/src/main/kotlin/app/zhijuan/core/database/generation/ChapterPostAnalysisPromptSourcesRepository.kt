package app.zhijuan.core.database.generation

import androidx.room.withTransaction
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.database.memory.ForeshadowItemEntity
import app.zhijuan.core.database.memory.StoryEntity
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.security.AndroidProtectedArtifactStore
import app.zhijuan.core.security.ProtectedArtifactFileCodec
import app.zhijuan.core.security.ProtectedArtifactType
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

data class ChapterPostAnalysisPromptSources(
    val stageId: String,
    val stageInputVersionHash: String,
    val stageIdempotencyKey: String,
    val bookId: String,
    val candidateChapterVersionId: String,
    val candidateContentHash: String,
    val candidateContentHashHistory: List<String>,
    val chapterId: String,
    val chapterIndex: Int,
    val revisionIndex: Int,
    val routeBindingHash: String?,
    val bodyText: String,
    val canonicalPlanJson: String,
    val context: ReadyChapterContext,
    val frozenPlan: ChapterPlanV2FrozenSources,
    val knownEntities: List<StoryEntity>,
    val priorForeshadows: List<ForeshadowItemEntity>,
    val expectedCurrentVersionId: String?,
) {
    override fun toString(): String =
        "ChapterPostAnalysisPromptSources(chapterIndex=$chapterIndex, revisionIndex=$revisionIndex, " +
            "entityCount=${knownEntities.size}, foreshadowCount=${priorForeshadows.size}, content=redacted)"
}

/** Restores the exact sealed BODY, plan, context, and current memory inputs for post-analysis. */
class ChapterPostAnalysisPromptSourcesRepository(
    private val database: ZhijuanDatabase,
    private val artifactStore: AndroidProtectedArtifactStore,
    private val leasePolicy: GenerationLeasePolicy = GenerationLeasePolicy(),
) {
    suspend fun loadBound(
        snapshot: GenerationRunnerCurrentStageRouteSnapshot,
        loadedAt: Long,
    ): ChapterPostAnalysisPromptSources = database.withTransaction {
        require(snapshot.route in CANDIDATE_CHAPTER_POST_ANALYSIS_ROUTES)
        val lease = snapshot.executionLease
        val generation = database.generationDao()
        val stage = requireNotNull(generation.findStage(lease.stageId)) {
            "Chapter post-analysis Stage is missing."
        }
        val job = requireNotNull(generation.findJob(lease.jobId)) {
            "Chapter post-analysis Job is missing."
        }
        val jobHeartbeat = requireNotNull(job.leaseHeartbeatAt) {
            "Chapter post-analysis Job heartbeat is missing."
        }
        val stageHeartbeat = requireNotNull(stage.leaseHeartbeatAt) {
            "Chapter post-analysis Stage heartbeat is missing."
        }
        if (
            job.jobId != lease.jobId || stage.jobId != job.jobId || stage.stageId != lease.stageId ||
            job.status != GenerationJobStatus.RUNNING || stage.status != GenerationStageStatus.PREPARING ||
            job.currentStageId != stage.stageId || job.pauseOrStopReason != null ||
            job.leaseTokenOrNull() != lease.jobLeaseToken ||
            stage.leaseTokenOrNull() != lease.stageLeaseToken ||
            jobHeartbeat < lease.jobHeartbeatAt || stageHeartbeat < lease.stageHeartbeatAt ||
            stage.attemptCount != snapshot.attemptCount || stage.maxAttempts != snapshot.maxAttempts ||
            GenerationRunnerStageRouteResolver.resolve(stage) != snapshot.route
        ) {
            throw StaleGenerationStateException("Chapter post-analysis bound source snapshot changed.")
        }
        require(
            loadedAt >= job.updatedAt && loadedAt >= stage.updatedAt &&
                loadedAt >= jobHeartbeat && loadedAt >= stageHeartbeat,
        ) { "Chapter post-analysis source load time cannot move backwards." }
        if (
            leasePolicy.isExpired(jobHeartbeat, loadedAt) ||
            leasePolicy.isExpired(stageHeartbeat, loadedAt)
        ) {
            throw StaleGenerationStateException(
                "Chapter post-analysis execution lease expired before source load.",
            )
        }

        val source = ChapterCandidateStageBindingV1.parseAndVerify(stage)
        require(source.role == ChapterCandidateArtifactRoleV1.POST_ANALYSIS)
        ChapterCandidateStageSourceGuard(database).requireProviderOpenAllowedIfBound(
            stage = stage,
            job = job,
            requestInputHash = stage.inputVersionHash,
        )
        val bodyStage = requireNotNull(generation.findStage(source.predecessorStageId)) {
            "Chapter post-analysis BODY Stage is missing."
        }
        val bodyEvidence = ChapterCandidateSealedStageEvidenceParserV1.parseAndVerify(bodyStage)
        require(
            bodyEvidence.role == ChapterCandidateArtifactRoleV1.BODY &&
                bodyEvidence.candidateChapterVersionId == source.candidateChapterVersionId &&
                bodyEvidence.candidateContentHash == source.candidateContentHash &&
                bodyEvidence.chapterId == source.chapterId &&
                bodyEvidence.chapterIndex == source.chapterIndex &&
                bodyEvidence.revisionIndex == source.revisionIndex &&
                bodyEvidence.nextStageId == stage.stageId,
        ) { "Chapter post-analysis BODY evidence changed after binding." }
        val bodyText = readBody(bodyEvidence)
        val lineage = traceInitialBody(bodyStage, job)
        InitialChapterDraftSourceGuard(database).requireProviderOpenAllowedIfBound(
            lineage.initialBodyStage,
            job,
        )
        val planStage = requireNotNull(generation.findStage(lineage.initialSource.planStageId)) {
            "Chapter post-analysis frozen plan Stage is missing."
        }
        val frozenPlan = ChapterPlanV2StageBinding.parseAndVerify(planStage).frozen
        val context = ChapterContextAssemblyRepository(database).requireProviderOpenAllowed(planStage, job)
        val chapter = requireNotNull(database.libraryDao().findChapter(source.chapterId)) {
            "Chapter post-analysis chapter is missing."
        }
        require(chapter.bookId == job.bookId && chapter.chapterIndex == source.chapterIndex)
        val memory = database.memoryDao()
        val head = requireNotNull(memory.findMemoryHead(job.bookId)) {
            "Chapter post-analysis memory head is missing."
        }
        val bibleRevisionId = requireNotNull(head.currentBibleRevisionId) {
            "Chapter post-analysis story Bible is missing."
        }
        val entities = memory.activeEntitiesForBible(
            job.bookId,
            bibleRevisionId,
            ChapterTrackingProjectionSourceRepository.MAX_ENTITIES + 1,
        )
        require(
            entities.isNotEmpty() &&
                entities.size <= ChapterTrackingProjectionSourceRepository.MAX_ENTITIES,
        ) { "Chapter post-analysis entity snapshot is empty or too large." }
        val foreshadows = memory.activeForeshadowsForProjection(
            job.bookId,
            ChapterTrackingProjectionSourceRepository.MAX_FORESHADOWS + 1,
        )
        require(foreshadows.size <= ChapterTrackingProjectionSourceRepository.MAX_FORESHADOWS) {
            "Chapter post-analysis foreshadow snapshot is too large."
        }
        ChapterPostAnalysisPromptSources(
            stageId = stage.stageId,
            stageInputVersionHash = stage.inputVersionHash,
            stageIdempotencyKey = stage.idempotencyKey,
            bookId = job.bookId,
            candidateChapterVersionId = source.candidateChapterVersionId,
            candidateContentHash = source.candidateContentHash,
            candidateContentHashHistory = lineage.candidateContentHashHistory,
            chapterId = source.chapterId,
            chapterIndex = source.chapterIndex,
            revisionIndex = source.revisionIndex,
            routeBindingHash = source.routeBindingHash,
            bodyText = bodyText,
            canonicalPlanJson = lineage.initialSource.canonicalPlanJson,
            context = context,
            frozenPlan = frozenPlan,
            knownEntities = entities,
            priorForeshadows = foreshadows,
            expectedCurrentVersionId = chapter.currentVersionId,
        )
    }

    private suspend fun traceInitialBody(
        newestBodyStage: GenerationStageEntity,
        job: GenerationJobEntity,
    ): InitialBodyLineage {
        val generation = database.generationDao()
        val visited = linkedSetOf<String>()
        val hashesNewestFirst = mutableListOf<String>()
        var bodyStage = newestBodyStage
        while (true) {
            if (!visited.add(bodyStage.stageId) || visited.size > MAXIMUM_LINEAGE_DEPTH) {
                throw StaleGenerationStateException("Chapter post-analysis BODY lineage is cyclic or too deep.")
            }
            val bodyEvidence = ChapterCandidateSealedStageEvidenceParserV1.parseAndVerify(bodyStage)
            require(
                bodyEvidence.role == ChapterCandidateArtifactRoleV1.BODY &&
                    bodyStage.jobId == job.jobId && bodyStage.status == GenerationStageStatus.SUCCEEDED,
            ) { "Chapter post-analysis lineage contains an invalid BODY Stage." }
            hashesNewestFirst += bodyEvidence.candidateContentHash
            val initialSource = runCatching {
                InitialChapterDraftStageBinding.parseAndVerify(bodyStage)
            }.getOrNull()
            if (initialSource != null) {
                return InitialBodyLineage(
                    initialBodyStage = bodyStage,
                    initialSource = initialSource,
                    candidateContentHashHistory = hashesNewestFirst.asReversed(),
                )
            }
            val revisedBodySource = ChapterCandidateStageBindingV1.parseAndVerify(bodyStage)
            require(
                revisedBodySource.role == ChapterCandidateArtifactRoleV1.BODY &&
                    bodyStage.phase == GenerationPhase.REVISE_CHAPTER,
            ) { "Chapter post-analysis lineage does not end at an initial BODY Stage." }
            var predecessorId = revisedBodySource.predecessorStageId
            while (true) {
                val predecessor = requireNotNull(generation.findStage(predecessorId)) {
                    "Chapter post-analysis lineage predecessor is missing."
                }
                val evidence = ChapterCandidateSealedStageEvidenceParserV1.parseAndVerify(predecessor)
                if (evidence.role == ChapterCandidateArtifactRoleV1.BODY) {
                    if (predecessor.stageId in visited) {
                        throw StaleGenerationStateException("Chapter post-analysis lineage is cyclic.")
                    }
                    bodyStage = predecessor
                    break
                }
                if (!visited.add(predecessor.stageId) || visited.size > MAXIMUM_LINEAGE_DEPTH) {
                    throw StaleGenerationStateException("Chapter post-analysis lineage is cyclic or too deep.")
                }
                val predecessorSource = ChapterCandidateStageBindingV1.parseAndVerify(predecessor)
                predecessorId = predecessorSource.predecessorStageId
            }
        }
    }

    private fun readBody(evidence: ChapterCandidateSealedStageEvidenceV1): String =
        artifactStore.readBytes(
            artifactRefId = evidence.artifactRefId,
            expectedType = ProtectedArtifactType.STREAM_DRAFT,
            maximumBytes = ProtectedArtifactFileCodec.MAX_IN_MEMORY_BYTES,
        ).use { lease ->
            require(lease.descriptor.revision == evidence.artifactRevision) {
                "Chapter post-analysis BODY artifact revision changed."
            }
            lease.withBytes { bytes ->
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString(separator = "") { byte -> "%02x".format(byte) }
                require(hash == evidence.rawOutputHash && hash == evidence.candidateContentHash) {
                    "Chapter post-analysis BODY artifact changed after sealing."
                }
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            }
        }

    private data class InitialBodyLineage(
        val initialBodyStage: GenerationStageEntity,
        val initialSource: InitialChapterDraftSourceV1,
        val candidateContentHashHistory: List<String>,
    )

    private companion object {
        const val MAXIMUM_LINEAGE_DEPTH = 16
    }
}
