package app.zhijuan.core.database.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.zhijuan.core.model.ChapterStatus
import app.zhijuan.core.model.ConsistencyStatus

internal data class ChapterVersionLocation(
    val bookId: String,
    val chapterIndex: Int,
)

internal data class StaleCascadeResult(
    val summaries: Int,
    val entityEvents: Int,
    val canonFacts: Int,
    val timelineEvents: Int,
    val foreshadows: Int,
    val trackingProjections: Int,
    val foreshadowTransitions: Int,
    val aggregateStates: Int,
    val futureContexts: Int,
    val futureReports: Int,
    val futureChapters: Int,
)

@Dao
internal interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBibleRevision(revision: StoryBibleRevisionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOutlineRevision(revision: OutlineRevisionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOutlineNodes(nodes: List<OutlineNodeEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun ensureMemoryHead(head: BookMemoryHeadEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSummary(summary: ChapterSummaryEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertStoryEntity(entity: StoryEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEntityEvents(events: List<EntityEventEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCanonFacts(facts: List<CanonFactEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTimelineEvents(events: List<TimelineEventEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertForeshadows(items: List<ForeshadowItemEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTrackingProjection(projection: ChapterTrackingProjectionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertForeshadowTransitions(items: List<ForeshadowTransitionEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertContextSnapshot(snapshot: ContextSnapshotEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertConsistencyReport(report: ConsistencyReportEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAggregateState(state: AggregateStateProjectionEntity)

    @Query("SELECT * FROM book_memory_head WHERE book_id = :bookId")
    suspend fun findMemoryHead(bookId: String): BookMemoryHeadEntity?

    @Query("SELECT * FROM story_bible_revision WHERE bible_revision_id = :revisionId")
    suspend fun findBibleRevision(revisionId: String): StoryBibleRevisionEntity?

    @Query("SELECT * FROM outline_revision WHERE outline_revision_id = :revisionId")
    suspend fun findOutlineRevision(revisionId: String): OutlineRevisionEntity?

    @Query("SELECT * FROM outline_node WHERE outline_revision_id = :revisionId ORDER BY order_key ASC")
    suspend fun findOutlineNodes(revisionId: String): List<OutlineNodeEntity>

    @Query("SELECT * FROM context_snapshot WHERE generation_stage_id = :stageId")
    suspend fun findContextSnapshotForStage(stageId: String): ContextSnapshotEntity?

    @Query(
        """
        SELECT * FROM story_entity
        WHERE book_id = :bookId
          AND source_bible_revision_id = :bibleRevisionId
          AND archived_at IS NULL
        ORDER BY entity_id ASC
        LIMIT :limit
        """,
    )
    suspend fun activeEntitiesForBible(
        bookId: String,
        bibleRevisionId: String,
        limit: Int,
    ): List<StoryEntity>

    @Query(
        """
        SELECT canon_fact.* FROM canon_fact
        LEFT JOIN chapter_version
          ON chapter_version.chapter_version_id = canon_fact.source_chapter_version_id
        LEFT JOIN chapter
          ON chapter.chapter_id = chapter_version.chapter_id
        WHERE canon_fact.book_id = :bookId
          AND canon_fact.status = 'VALID'
          AND (
            canon_fact.source_bible_revision_id = :bibleRevisionId
            OR (
              canon_fact.source_chapter_version_id IS NOT NULL
              AND chapter.book_id = :bookId
              AND chapter.chapter_index < :targetChapterIndex
              AND chapter.current_version_id = canon_fact.source_chapter_version_id
            )
          )
        ORDER BY canon_fact.created_at ASC, canon_fact.canon_fact_id ASC
        LIMIT :limit
        """,
    )
    suspend fun validCanonFactsForContext(
        bookId: String,
        bibleRevisionId: String,
        targetChapterIndex: Int,
        limit: Int,
    ): List<CanonFactEntity>

    @Query(
        """
        SELECT chapter_summary.* FROM chapter_summary
        INNER JOIN chapter_version
          ON chapter_version.chapter_version_id = chapter_summary.chapter_version_id
        INNER JOIN chapter
          ON chapter.chapter_id = chapter_version.chapter_id
        WHERE chapter_summary.book_id = :bookId
          AND chapter_summary.status = 'VALID'
          AND chapter.book_id = :bookId
          AND chapter.chapter_index < :targetChapterIndex
          AND chapter.current_version_id = chapter_summary.chapter_version_id
        ORDER BY chapter.chapter_index DESC
        LIMIT :limit
        """,
    )
    suspend fun recentValidSummaries(
        bookId: String,
        targetChapterIndex: Int,
        limit: Int,
    ): List<ChapterSummaryEntity>

    @Query(
        """
        SELECT entity_event.* FROM entity_event
        INNER JOIN chapter_version
          ON chapter_version.chapter_version_id = entity_event.source_chapter_version_id
        INNER JOIN chapter
          ON chapter.chapter_id = chapter_version.chapter_id
        WHERE entity_event.book_id = :bookId
          AND entity_event.status = 'VALID'
          AND chapter.book_id = :bookId
          AND chapter.chapter_index < :targetChapterIndex
          AND chapter.current_version_id = entity_event.source_chapter_version_id
        ORDER BY entity_event.story_order DESC, entity_event.entity_event_id ASC
        LIMIT :limit
        """,
    )
    suspend fun validEntityEventsBefore(
        bookId: String,
        targetChapterIndex: Int,
        limit: Int,
    ): List<EntityEventEntity>

    @Query(
        """
        SELECT timeline_event.* FROM timeline_event
        INNER JOIN chapter_version
          ON chapter_version.chapter_version_id = timeline_event.source_chapter_version_id
        INNER JOIN chapter
          ON chapter.chapter_id = chapter_version.chapter_id
        WHERE timeline_event.book_id = :bookId
          AND timeline_event.status = 'VALID'
          AND chapter.book_id = :bookId
          AND chapter.chapter_index < :targetChapterIndex
          AND chapter.current_version_id = timeline_event.source_chapter_version_id
        ORDER BY timeline_event.story_order DESC, timeline_event.timeline_event_id ASC
        LIMIT :limit
        """,
    )
    suspend fun validTimelineEventsBefore(
        bookId: String,
        targetChapterIndex: Int,
        limit: Int,
    ): List<TimelineEventEntity>

    @Query(
        """
        SELECT * FROM foreshadow_item
        WHERE book_id = :bookId
          AND memory_status = 'VALID'
          AND foreshadow_status NOT IN ('RESOLVED', 'ABANDONED')
        ORDER BY importance DESC, updated_at DESC, foreshadow_item_id ASC
        LIMIT :limit
        """,
    )
    suspend fun activeForeshadowsForContext(bookId: String, limit: Int): List<ForeshadowItemEntity>

    @Query(
        """
        SELECT * FROM foreshadow_item
        WHERE book_id = :bookId
          AND memory_status = 'VALID'
          AND foreshadow_status NOT IN ('RESOLVED', 'ABANDONED')
        ORDER BY foreshadow_item_id ASC
        LIMIT :limit
        """,
    )
    suspend fun activeForeshadowsForProjection(bookId: String, limit: Int): List<ForeshadowItemEntity>

    @Query(
        """
        SELECT aggregate_state_projection.* FROM aggregate_state_projection
        INNER JOIN chapter_version
          ON chapter_version.chapter_version_id = aggregate_state_projection.source_through_chapter_version_id
        INNER JOIN chapter
          ON chapter.chapter_id = chapter_version.chapter_id
        WHERE aggregate_state_projection.book_id = :bookId
          AND aggregate_state_projection.status = 'VALID'
          AND aggregate_state_projection.through_chapter_index < :targetChapterIndex
          AND chapter.current_version_id = aggregate_state_projection.source_through_chapter_version_id
        ORDER BY aggregate_state_projection.through_chapter_index DESC
        LIMIT 1
        """,
    )
    suspend fun latestValidAggregateStateBefore(
        bookId: String,
        targetChapterIndex: Int,
    ): AggregateStateProjectionEntity?

    @Query("SELECT COALESCE(MAX(revision_no), 0) FROM story_bible_revision WHERE book_id = :bookId")
    suspend fun maximumBibleRevision(bookId: String): Int

    @Query("SELECT COALESCE(MAX(revision_no), 0) FROM outline_revision WHERE book_id = :bookId")
    suspend fun maximumOutlineRevision(bookId: String): Int

    @Query(
        """
        UPDATE book_memory_head
        SET current_bible_revision_id = :revisionId, updated_at = :updatedAt
        WHERE book_id = :bookId
        """,
    )
    suspend fun setCurrentBibleRevision(bookId: String, revisionId: String, updatedAt: Long): Int

    @Query(
        """
        UPDATE book_memory_head
        SET current_outline_revision_id = :revisionId, updated_at = :updatedAt
        WHERE book_id = :bookId
        """,
    )
    suspend fun setCurrentOutlineRevision(bookId: String, revisionId: String, updatedAt: Long): Int

    @Query(
        """
        SELECT chapter.book_id AS bookId, chapter.chapter_index AS chapterIndex
        FROM chapter_version
        INNER JOIN chapter ON chapter.chapter_id = chapter_version.chapter_id
        WHERE chapter_version.chapter_version_id = :chapterVersionId
        """,
    )
    suspend fun locateChapterVersion(chapterVersionId: String): ChapterVersionLocation?

    @Query("SELECT status FROM chapter_summary WHERE chapter_version_id = :chapterVersionId")
    suspend fun summaryStatus(chapterVersionId: String): String?

    @Query("SELECT * FROM chapter_summary WHERE chapter_version_id = :chapterVersionId")
    suspend fun findSummaryForVersion(chapterVersionId: String): ChapterSummaryEntity?

    @Query("SELECT * FROM entity_event WHERE source_chapter_version_id = :chapterVersionId ORDER BY story_order ASC, entity_event_id ASC")
    suspend fun entityEventsForVersion(chapterVersionId: String): List<EntityEventEntity>

    @Query("SELECT * FROM canon_fact WHERE source_chapter_version_id = :chapterVersionId ORDER BY canon_fact_id ASC")
    suspend fun canonFactsForVersion(chapterVersionId: String): List<CanonFactEntity>

    @Query("SELECT * FROM timeline_event WHERE source_chapter_version_id = :chapterVersionId ORDER BY story_order ASC, timeline_event_id ASC")
    suspend fun timelineEventsForVersion(chapterVersionId: String): List<TimelineEventEntity>

    @Query("SELECT * FROM chapter_tracking_projection WHERE chapter_version_id = :chapterVersionId")
    suspend fun findTrackingProjectionForVersion(chapterVersionId: String): ChapterTrackingProjectionEntity?

    @Query("SELECT * FROM foreshadow_transition WHERE generation_stage_id = :stageId ORDER BY story_order ASC, transition_id ASC")
    suspend fun foreshadowTransitionsForStage(stageId: String): List<ForeshadowTransitionEntity>

    @Query("SELECT * FROM foreshadow_item WHERE foreshadow_item_id = :foreshadowItemId")
    suspend fun findForeshadow(foreshadowItemId: String): ForeshadowItemEntity?

    @Query(
        """
        UPDATE foreshadow_item
        SET foreshadow_status = :toStatus,
            source_chapter_version_id = :sourceChapterVersionId,
            resolved_chapter_version_id = :resolvedChapterVersionId,
            visible_entity_ids_json = :visibleEntityIdsJson,
            importance = :importance,
            updated_at = :updatedAt
        WHERE foreshadow_item_id = :foreshadowItemId
          AND book_id = :bookId
          AND memory_status = 'VALID'
          AND foreshadow_status = :fromStatus
        """,
    )
    suspend fun compareAndTransitionForeshadow(
        foreshadowItemId: String,
        bookId: String,
        fromStatus: String,
        toStatus: String,
        sourceChapterVersionId: String,
        resolvedChapterVersionId: String?,
        visibleEntityIdsJson: String,
        importance: Int,
        updatedAt: Long,
    ): Int

    @Query("SELECT status FROM context_snapshot WHERE context_snapshot_id = :id")
    suspend fun contextStatus(id: String): String?

    @Query("SELECT status FROM consistency_report WHERE consistency_report_id = :id")
    suspend fun reportStatus(id: String): String?

    @Query("SELECT * FROM consistency_report WHERE consistency_report_id = :id")
    suspend fun findConsistencyReport(id: String): ConsistencyReportEntity?

    @Query("SELECT status FROM aggregate_state_projection WHERE aggregate_state_id = :id")
    suspend fun aggregateStatus(id: String): String?

    @Query("SELECT COUNT(*) FROM entity_event WHERE source_chapter_version_id = :chapterVersionId AND status = 'STALE'")
    suspend fun staleEntityEventCount(chapterVersionId: String): Int

    @Query("SELECT COUNT(*) FROM canon_fact WHERE source_chapter_version_id = :chapterVersionId AND status = 'STALE'")
    suspend fun staleCanonFactCount(chapterVersionId: String): Int

    @Query("SELECT adult_status FROM story_entity WHERE entity_id = :entityId")
    suspend fun adultStatus(entityId: String): String?

    @Query("UPDATE chapter_summary SET status = 'STALE', updated_at = :updatedAt WHERE chapter_version_id = :chapterVersionId AND status = 'VALID'")
    suspend fun staleSummary(chapterVersionId: String, updatedAt: Long): Int

    @Query("UPDATE entity_event SET status = 'STALE' WHERE source_chapter_version_id = :chapterVersionId AND status = 'VALID'")
    suspend fun staleEntityEvents(chapterVersionId: String): Int

    @Query("UPDATE canon_fact SET status = 'STALE' WHERE source_chapter_version_id = :chapterVersionId AND status = 'VALID'")
    suspend fun staleCanonFacts(chapterVersionId: String): Int

    @Query("UPDATE timeline_event SET status = 'STALE' WHERE source_chapter_version_id = :chapterVersionId AND status = 'VALID'")
    suspend fun staleTimelineEvents(chapterVersionId: String): Int

    @Query(
        """
        UPDATE foreshadow_item
        SET memory_status = 'STALE', updated_at = :updatedAt
        WHERE memory_status = 'VALID'
          AND (
              source_chapter_version_id = :chapterVersionId
              OR planted_chapter_version_id = :chapterVersionId
              OR resolved_chapter_version_id = :chapterVersionId
              OR foreshadow_item_id IN (
                  SELECT foreshadow_item_id FROM foreshadow_transition
                  WHERE source_chapter_version_id = :chapterVersionId
              )
          )
        """,
    )
    suspend fun staleForeshadows(chapterVersionId: String, updatedAt: Long): Int

    @Query("UPDATE chapter_tracking_projection SET status = 'STALE', updated_at = :updatedAt WHERE chapter_version_id = :chapterVersionId AND status = 'VALID'")
    suspend fun staleTrackingProjection(chapterVersionId: String, updatedAt: Long): Int

    @Query("UPDATE foreshadow_transition SET status = 'STALE' WHERE source_chapter_version_id = :chapterVersionId AND status = 'VALID'")
    suspend fun staleForeshadowTransitions(chapterVersionId: String): Int

    @Query("UPDATE aggregate_state_projection SET status = 'STALE', updated_at = :updatedAt WHERE book_id = :bookId AND through_chapter_index >= :chapterIndex AND status = 'VALID'")
    suspend fun staleAggregateStates(bookId: String, chapterIndex: Int, updatedAt: Long): Int

    @Query("UPDATE context_snapshot SET status = 'STALE', updated_at = :updatedAt WHERE book_id = :bookId AND target_chapter_index > :chapterIndex AND status = 'VALID'")
    suspend fun staleFutureContexts(bookId: String, chapterIndex: Int, updatedAt: Long): Int

    @Query("UPDATE consistency_report SET status = 'STALE', updated_at = :updatedAt WHERE book_id = :bookId AND target_chapter_index > :chapterIndex AND status = 'VALID'")
    suspend fun staleFutureReports(bookId: String, chapterIndex: Int, updatedAt: Long): Int

    @Query(
        """
        UPDATE chapter
        SET status = :chapterStatus,
            consistency_status = :consistencyStatus,
            updated_at = :updatedAt
        WHERE book_id = :bookId
          AND chapter_index > :chapterIndex
          AND current_version_id IS NOT NULL
          AND status IN ('DRAFT_READY', 'READY', 'EDITED')
        """,
    )
    suspend fun markFutureChaptersUnknown(
        bookId: String,
        chapterIndex: Int,
        chapterStatus: ChapterStatus,
        consistencyStatus: ConsistencyStatus,
        updatedAt: Long,
    ): Int

    @Transaction
    suspend fun createBibleRevision(revision: StoryBibleRevisionEntity) {
        require(revision.revisionNo == maximumBibleRevision(revision.bookId) + 1) {
            "Bible revision number must be the next number for this book."
        }
        val parent = revision.parentRevisionId?.let { requireNotNull(findBibleRevision(it)) }
        require(parent == null || parent.bookId == revision.bookId) { "Bible parent must belong to the same book." }
        require((revision.revisionNo == 1) == (parent == null)) {
            "Only the first Bible revision can omit a parent."
        }
        require(
            revision.contentHash.isNotBlank() &&
                revision.schemaVersion > 0 &&
                revision.contentControlSchemaVersion > 0,
        ) {
            "Bible revision requires a versioned non-empty payload hash."
        }
        insertBibleRevision(revision)
        ensureMemoryHead(BookMemoryHeadEntity(revision.bookId, null, null, revision.createdAt))
        check(setCurrentBibleRevision(revision.bookId, revision.bibleRevisionId, revision.createdAt) == 1)
    }

    @Transaction
    suspend fun createOutlineRevision(
        revision: OutlineRevisionEntity,
        nodes: List<OutlineNodeEntity>,
    ) {
        require(revision.revisionNo == maximumOutlineRevision(revision.bookId) + 1) {
            "Outline revision number must be the next number for this book."
        }
        val parent = revision.parentRevisionId?.let { requireNotNull(findOutlineRevision(it)) }
        require(parent == null || parent.bookId == revision.bookId) { "Outline parent must belong to the same book." }
        require((revision.revisionNo == 1) == (parent == null)) {
            "Only the first outline revision can omit a parent."
        }
        require(nodes.isNotEmpty() && nodes.all { it.outlineRevisionId == revision.outlineRevisionId }) {
            "Outline nodes must be non-empty and belong to the new revision."
        }
        insertOutlineRevision(revision)
        insertOutlineNodes(nodes)
        ensureMemoryHead(BookMemoryHeadEntity(revision.bookId, null, null, revision.createdAt))
        check(setCurrentOutlineRevision(revision.bookId, revision.outlineRevisionId, revision.createdAt) == 1)
    }

    @Transaction
    suspend fun markDerivedDataStaleForReplacedChapter(
        bookId: String,
        replacedChapterVersionId: String,
        updatedAt: Long,
    ): StaleCascadeResult {
        val location = requireNotNull(locateChapterVersion(replacedChapterVersionId)) {
            "Replaced chapter version does not exist."
        }
        require(location.bookId == bookId) { "Replaced chapter version belongs to another book." }
        return StaleCascadeResult(
            summaries = staleSummary(replacedChapterVersionId, updatedAt),
            entityEvents = staleEntityEvents(replacedChapterVersionId),
            canonFacts = staleCanonFacts(replacedChapterVersionId),
            timelineEvents = staleTimelineEvents(replacedChapterVersionId),
            foreshadows = staleForeshadows(replacedChapterVersionId, updatedAt),
            trackingProjections = staleTrackingProjection(replacedChapterVersionId, updatedAt),
            foreshadowTransitions = staleForeshadowTransitions(replacedChapterVersionId),
            aggregateStates = staleAggregateStates(bookId, location.chapterIndex, updatedAt),
            futureContexts = staleFutureContexts(bookId, location.chapterIndex, updatedAt),
            futureReports = staleFutureReports(bookId, location.chapterIndex, updatedAt),
            futureChapters = markFutureChaptersUnknown(
                bookId,
                location.chapterIndex,
                ChapterStatus.CONSISTENCY_UNKNOWN,
                ConsistencyStatus.UNKNOWN,
                updatedAt,
            ),
        )
    }
}
