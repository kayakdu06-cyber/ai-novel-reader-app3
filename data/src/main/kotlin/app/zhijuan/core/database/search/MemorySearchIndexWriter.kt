package app.zhijuan.core.database.search

import app.zhijuan.core.database.memory.CanonFactEntity
import app.zhijuan.core.database.memory.ChapterSummaryEntity
import app.zhijuan.core.database.memory.EntityEventEntity
import app.zhijuan.core.database.memory.ForeshadowItemEntity
import app.zhijuan.core.database.memory.MemoryDao
import app.zhijuan.core.database.memory.StoryEntity
import app.zhijuan.core.database.memory.TimelineEventEntity

internal data class MemorySearchSourceIdentityV1(
    val bookId: String,
    val sourceType: MemorySearchSourceTypeV1,
    val sourceId: String,
)

/** Must be called inside the same Room transaction that owns the authoritative source write. */
internal object MemorySearchIndexWriterV1 {
    suspend fun replaceStoryBible(
        search: MemorySearchDao,
        storyEntities: List<StoryEntity>,
        canonFacts: List<CanonFactEntity>,
    ) {
        search.replaceAll(
            storyEntities.mapNotNull(MemorySearchDocumentFactoryV1::from) +
                canonFacts.mapNotNull { MemorySearchDocumentFactoryV1.from(it, chapterIndex = null) },
        )
    }

    suspend fun replaceChapterMemory(
        search: MemorySearchDao,
        summary: ChapterSummaryEntity,
        entityEvents: List<EntityEventEntity>,
        canonFacts: List<CanonFactEntity>,
    ) {
        val chapterIndex = summary.chapterIndex
        search.replaceAll(
            listOfNotNull(MemorySearchDocumentFactoryV1.from(summary)) +
                entityEvents.mapNotNull { MemorySearchDocumentFactoryV1.from(it, chapterIndex) } +
                canonFacts.mapNotNull { MemorySearchDocumentFactoryV1.from(it, chapterIndex) },
        )
    }

    suspend fun replaceStoryTracking(
        search: MemorySearchDao,
        chapterIndex: Int,
        timelineEvents: List<TimelineEventEntity>,
        foreshadows: List<ForeshadowItemEntity>,
    ) {
        replaceStoryTrackingTimelines(search, chapterIndex, timelineEvents)
        search.replaceAll(
            foreshadows.mapNotNull { item ->
                val sourceChapterIndex = item.sourceChapterVersionId?.let { chapterIndex }
                MemorySearchDocumentFactoryV1.from(item, sourceChapterIndex)
            },
        )
        val inactive = foreshadows.filter {
            MemorySearchDocumentFactoryV1.from(it, chapterIndex = null) == null
        }.map { it.searchIdentity(MemorySearchSourceTypeV1.FORESHADOW) }
        search.deleteSources(inactive)
    }

    /** Repairs only immutable timeline documents without replaying mutable foreshadow state. */
    suspend fun replaceStoryTrackingTimelines(
        search: MemorySearchDao,
        chapterIndex: Int,
        timelineEvents: List<TimelineEventEntity>,
    ) {
        search.replaceAll(
            timelineEvents.mapNotNull { MemorySearchDocumentFactoryV1.from(it, chapterIndex) },
        )
    }

    /** Captures affected identities before the caller changes their authoritative status to STALE. */
    suspend fun identitiesForReplacedChapter(
        memory: MemoryDao,
        bookId: String,
        chapterVersionId: String,
    ): List<MemorySearchSourceIdentityV1> = buildList {
        memory.findSummaryForVersion(chapterVersionId)?.let {
            add(it.searchIdentity(MemorySearchSourceTypeV1.CHAPTER_SUMMARY, it.chapterSummaryId))
        }
        memory.entityEventsForVersion(chapterVersionId).forEach {
            add(it.searchIdentity(MemorySearchSourceTypeV1.ENTITY_EVENT, it.entityEventId))
        }
        memory.canonFactsForVersion(chapterVersionId).forEach {
            add(it.searchIdentity(MemorySearchSourceTypeV1.CANON_FACT, it.canonFactId))
        }
        memory.timelineEventsForVersion(chapterVersionId).forEach {
            add(it.searchIdentity(MemorySearchSourceTypeV1.TIMELINE_EVENT, it.timelineEventId))
        }
        memory.foreshadowsAffectedByVersion(bookId, chapterVersionId).forEach {
            add(it.searchIdentity(MemorySearchSourceTypeV1.FORESHADOW))
        }
    }.distinct()

    internal fun identitiesForTimelineEvents(
        timelineEvents: List<TimelineEventEntity>,
    ): List<MemorySearchSourceIdentityV1> = timelineEvents.map { event ->
        event.searchIdentity(MemorySearchSourceTypeV1.TIMELINE_EVENT, event.timelineEventId)
    }.distinct()

    private fun ForeshadowItemEntity.searchIdentity(type: MemorySearchSourceTypeV1) =
        MemorySearchSourceIdentityV1(bookId, type, foreshadowItemId)

    private fun ChapterSummaryEntity.searchIdentity(type: MemorySearchSourceTypeV1, id: String) =
        MemorySearchSourceIdentityV1(bookId, type, id)

    private fun EntityEventEntity.searchIdentity(type: MemorySearchSourceTypeV1, id: String) =
        MemorySearchSourceIdentityV1(bookId, type, id)

    private fun CanonFactEntity.searchIdentity(type: MemorySearchSourceTypeV1, id: String) =
        MemorySearchSourceIdentityV1(bookId, type, id)

    private fun TimelineEventEntity.searchIdentity(type: MemorySearchSourceTypeV1, id: String) =
        MemorySearchSourceIdentityV1(bookId, type, id)
}
