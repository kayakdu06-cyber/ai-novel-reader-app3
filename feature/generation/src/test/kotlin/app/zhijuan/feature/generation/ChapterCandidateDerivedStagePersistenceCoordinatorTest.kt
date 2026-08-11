package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.FinalUsageCommit
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ChapterCandidateDerivedStagePersistenceCoordinatorTest {
    @Test
    fun revisedMemoryCarriesExactRouteBindingIntoTrackingStage() {
        val candidate = candidate(revisionIndex = 1, routeBindingHash = sha256("revision-result"))
        val memory = memory(candidate)

        val draft = ChapterCandidateDerivedStagePlannerV1.memory(
            memory = memory,
            currentStageId = "stage.memory.1",
            sourceBindingHash = sha256("memory-request"),
            usage = FinalUsageCommit.UNKNOWN,
            spec = spec(candidate, "stage.tracking.1"),
        )
        val source = Json.parseToJsonElement(draft.nextStage.inputSourcesJson) as JsonObject

        assertEquals(candidate.routeBindingHash, source.getValue("routeBindingHash").jsonPrimitive.content)
        assertEquals(candidate.routeBindingHash, draft.routeBindingHash)
        assertEquals("TRACKING", source.getValue("artifactRole").jsonPrimitive.content)
        assertEquals(memory.contentHash, draft.canonicalOutputHash)
    }

    @Test
    fun revisedTrackingCarriesExactRouteBindingIntoConsistencyStage() {
        val candidate = candidate(revisionIndex = 1, routeBindingHash = sha256("revision-result"))
        val tracking = tracking(candidate)

        val draft = ChapterCandidateDerivedStagePlannerV1.tracking(
            tracking = tracking,
            currentStageId = "stage.tracking.1",
            sourceBindingHash = sha256("tracking-request"),
            usage = FinalUsageCommit.UNKNOWN,
            spec = spec(candidate, "stage.consistency.1"),
        )
        val source = Json.parseToJsonElement(draft.nextStage.inputSourcesJson) as JsonObject

        assertEquals(candidate.routeBindingHash, source.getValue("routeBindingHash").jsonPrimitive.content)
        assertEquals("CONSISTENCY", source.getValue("artifactRole").jsonPrimitive.content)
        assertEquals(tracking.contentHash, draft.canonicalOutputHash)
    }

    @Test
    fun revisedCandidateCannotBeConstructedWithoutRouteBinding() {
        assertThrows(IllegalArgumentException::class.java) {
            candidate(revisionIndex = 1, routeBindingHash = null)
        }
    }

    @Test
    fun derivedOutputFromDifferentCandidateIsRejected() {
        val candidate = candidate(revisionIndex = 0, routeBindingHash = null)
        val memory = memory(candidate).copy(sourceChapterContentHash = sha256("other-body"))

        assertThrows(IllegalArgumentException::class.java) {
            ChapterCandidateDerivedStagePlannerV1.memory(
                memory = memory,
                currentStageId = "stage.memory.0",
                sourceBindingHash = sha256("memory-request"),
                usage = FinalUsageCommit.UNKNOWN,
                spec = spec(candidate, "stage.tracking.0"),
            )
        }
    }

    private fun candidate(revisionIndex: Int, routeBindingHash: String?) = ChapterCandidatePipelineIdentityV1(
        chapterVersionId = "chapter.version.candidate.$revisionIndex",
        chapterId = "chapter.1",
        chapterIndex = 1,
        contentHash = sha256("body-$revisionIndex"),
        revisionIndex = revisionIndex,
        routeBindingHash = routeBindingHash,
    )

    private fun spec(candidate: ChapterCandidatePipelineIdentityV1, nextStageId: String) =
        ChapterCandidateDerivedStageAdvanceSpecV1(
            jobId = "job.candidate.1",
            candidate = candidate,
            nextStageId = nextStageId,
            nextStageMaximumAttempts = 2,
            sealedAt = 100L,
        )

    private fun memory(candidate: ChapterCandidatePipelineIdentityV1) = ChapterMemoryV1(
        sourceChapterVersionId = candidate.chapterVersionId,
        sourceChapterContentHash = candidate.contentHash,
        chapterId = candidate.chapterId,
        chapterIndex = candidate.chapterIndex,
        summary = ChapterMemorySummaryV1(
            objectiveOutcome = "done",
            keyEvents = emptyList(),
            decisions = emptyList(),
            relationshipChanges = emptyList(),
            endingState = "stable",
            unresolvedQuestions = emptyList(),
            importance = 50,
        ),
        entityEvents = emptyList(),
        facts = emptyList(),
        canonicalJson = "{}",
        contentHash = sha256("memory-output"),
    )

    private fun tracking(candidate: ChapterCandidatePipelineIdentityV1) = ChapterStoryTrackingV1(
        sourceChapterVersionId = candidate.chapterVersionId,
        sourceChapterContentHash = candidate.contentHash,
        chapterId = candidate.chapterId,
        chapterIndex = candidate.chapterIndex,
        memorySnapshotHash = sha256("memory-snapshot"),
        priorForeshadowSnapshotHash = sha256("foreshadow-snapshot"),
        knownEntitySnapshotHash = sha256("entity-snapshot"),
        timelineEvents = emptyList(),
        foreshadowOperations = emptyList(),
        canonicalJson = "{}",
        contentHash = sha256("tracking-output"),
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
