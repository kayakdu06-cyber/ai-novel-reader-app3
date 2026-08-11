package app.zhijuan.core.database.library

import app.zhijuan.core.database.memory.TimelineEventEntity
import app.zhijuan.core.model.DerivedDataStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ChapterEditRebuildTrackingRetirementEvidenceTest {
    @Test
    fun timelineIdentityEvidenceIsSortedStrictAndStatusIndependent() {
        val second = event("timeline-2", storyOrder = 2, status = DerivedDataStatus.VALID)
        val first = event("timeline-1", storyOrder = 1, status = DerivedDataStatus.VALID)

        val encoded = ChapterEditRebuildTrackingRetirementEvidenceV1.encodeTimelineIds(listOf(second, first))

        assertEquals(listOf("timeline-1", "timeline-2"), ChapterEditRebuildTrackingRetirementEvidenceV1.decodeTimelineIds(encoded))
        assertEquals(
            ChapterEditRebuildTrackingRetirementEvidenceV1.fingerprint(listOf(first, second)),
            ChapterEditRebuildTrackingRetirementEvidenceV1.fingerprint(
                listOf(first.copy(status = DerivedDataStatus.STALE), second.copy(status = DerivedDataStatus.STALE)),
            ),
        )
    }

    @Test
    fun timelineFingerprintChangesWhenImmutableContentChanges() {
        val original = event("timeline-1", storyOrder = 1, status = DerivedDataStatus.VALID)
        assertNotEquals(
            ChapterEditRebuildTrackingRetirementEvidenceV1.fingerprint(listOf(original)),
            ChapterEditRebuildTrackingRetirementEvidenceV1.fingerprint(listOf(original.copy(name = "篡改"))),
        )
    }

    @Test
    fun timelineIdentityEvidenceRejectsDuplicateIds() {
        val event = event("timeline-1", storyOrder = 1, status = DerivedDataStatus.VALID)
        assertThrows(IllegalArgumentException::class.java) {
            ChapterEditRebuildTrackingRetirementEvidenceV1.encodeTimelineIds(listOf(event, event))
        }
    }

    private fun event(
        id: String,
        storyOrder: Long,
        status: DerivedDataStatus,
    ) = TimelineEventEntity(
        timelineEventId = id,
        bookId = "book-1",
        name = "事件-$id",
        participantsJson = "[]",
        locationEntityId = null,
        storyTimeExpression = "当天",
        storyOrder = storyOrder,
        constraintsJson = "{}",
        sourceChapterVersionId = "chapter-version-1",
        status = status,
        createdAt = 10,
    )
}
