package app.zhijuan.feature.generation

import app.zhijuan.core.database.memory.NarrativeStatePersistenceMapperV1
import app.zhijuan.core.database.memory.NarrativeStatePersistenceSpecV1
import app.zhijuan.core.database.memory.NarrativeStateValidationInputV1
import app.zhijuan.core.model.ConsistencyIssueSeverity
import app.zhijuan.core.task.ChapterLocalConsistencyReport
import app.zhijuan.core.task.ChapterSceneConsistencyContractV1

data class ChapterPostAnalysisMappingSpecV1(
    val bookId: String,
    val generationStageId: String,
    val modelSnapshotJson: String,
    val createdAt: Long,
    val expectation: ChapterPostAnalysisExpectationV1,
    val localReport: ChapterLocalConsistencyReport,
    val sceneContract: ChapterSceneConsistencyContractV1,
)

data class ChapterPostAnalysisDerivedDraftV1(
    val memory: ChapterMemoryDerivedDraft,
    val tracking: ChapterTrackingProjectionDerivedDraft,
    val consistency: ChapterConsistencyDerivedDraftV1,
) {
    override fun toString(): String =
        "ChapterPostAnalysisDerivedDraftV1(eventCount=${memory.entityEvents.size}, " +
            "factCount=${memory.canonFacts.size}, content=redacted)"
}

/** Maps one fully validated merged response into the existing atomic-commit row drafts. */
object ChapterPostAnalysisPersistenceMapperV1 {
    fun map(
        analysis: ChapterPostAnalysisV1,
        spec: ChapterPostAnalysisMappingSpecV1,
    ): ChapterPostAnalysisDerivedDraftV1 {
        require(!analysis.severeRevisionRequired)
        require(analysis.repetitionFindings.isEmpty())
        require(analysis.consistencyFindings.none {
            it.severity == ConsistencyIssueSeverity.BLOCKER || it.severity == ConsistencyIssueSeverity.MAJOR
        })
        require(spec.expectation.consistency.sourceChapterVersionId == analysis.sourceChapterVersionId)
        require(spec.expectation.consistency.sourceChapterContentHash == analysis.sourceChapterContentHash)
        require(spec.localReport.contentHash == analysis.sourceChapterContentHash)
        require(spec.sceneContract.contractHash == analysis.sceneContractHash)

        val memory = ChapterMemoryExtractionPersistenceMapper.map(
            memory = analysis.asMemory(),
            spec = ChapterMemoryExtractionMappingSpec(
                bookId = spec.bookId,
                generationStageId = spec.generationStageId,
                modelSnapshotJson = spec.modelSnapshotJson,
                createdAt = spec.createdAt,
            ),
        )
        val narrativeInput = NarrativeStateValidationInputV1(
            activeNamespaces = spec.expectation.narrative.activeNamespaces,
            priorObligations = spec.expectation.narrative.priorObligations,
            obligationUpdates = analysis.completedAndOpenObligations,
            currentStateValues = spec.expectation.narrative.currentStateValues,
            stateDeltas = analysis.storyStateDeltas,
        )
        val narrative = NarrativeStatePersistenceMapperV1.mapValidated(
            narrativeInput,
            NarrativeStatePersistenceSpecV1(
                bookId = spec.bookId,
                chapterVersionId = analysis.sourceChapterVersionId,
                generationStageId = spec.generationStageId,
                chapterIndex = analysis.chapterIndex,
                createdAt = spec.createdAt,
                sourceChapterContentHash = analysis.sourceChapterContentHash,
                existingEntityEventCount = memory.entityEvents.size,
                existingCanonFactCount = memory.canonFacts.size,
            ),
        )
        val mergedMemory = memory.copy(
            entityEvents = memory.entityEvents + narrative.stateEvents,
            canonFacts = memory.canonFacts + narrative.obligationFacts,
        )
        val tracking = ChapterTrackingProjectionPersistenceMapper.map(
            tracking = analysis.asTracking(spec.expectation.tracking),
            spec = ChapterTrackingProjectionMappingSpec(
                bookId = spec.bookId,
                generationStageId = spec.generationStageId,
                modelSnapshotJson = spec.modelSnapshotJson,
                createdAt = spec.createdAt,
            ),
        )
        val consistency = ChapterConsistencyPersistenceMapperV1.map(
            local = spec.localReport,
            model = analysis.asConsistency(),
            expectation = spec.expectation.consistency,
            scene = spec.sceneContract,
            spec = ChapterConsistencyMappingSpecV1(
                bookId = spec.bookId,
                generationStageId = spec.generationStageId,
                modelSnapshotJson = spec.modelSnapshotJson,
                createdAt = spec.createdAt,
            ),
        )
        require(consistency.gate.decision == ChapterConsistencyGateDecisionV1.ACCEPT_CANDIDATE)
        return ChapterPostAnalysisDerivedDraftV1(mergedMemory, tracking, consistency)
    }

    private fun ChapterPostAnalysisV1.asMemory() = ChapterMemoryV1(
        sourceChapterVersionId, sourceChapterContentHash, chapterId, chapterIndex,
        summary, entityEvents, canonFacts, canonicalJson, contentHash,
    )

    private fun ChapterPostAnalysisV1.asTracking(expected: ChapterTrackingExpectation) = ChapterStoryTrackingV1(
        sourceChapterVersionId, sourceChapterContentHash, chapterId, chapterIndex,
        expected.memorySnapshotHash, expected.priorForeshadowSnapshotHash, expected.knownEntitySnapshotHash,
        timelineEvents, foreshadowTransitions, canonicalJson, contentHash,
    )

    private fun ChapterPostAnalysisV1.asConsistency() = ChapterConsistencyReportV1(
        sourceChapterVersionId, sourceChapterContentHash, chapterId, chapterIndex,
        checkSourceSnapshotHash, sceneContractHash, criterionResults, requiredProcessResults,
        consistencyFindings, canonicalJson, contentHash,
    )
}
