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
    val foreshadowProjectionRevisions: Int,
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
    suspend fun insertForeshadowProjectionRevisions(items: List<ForeshadowProjectionRevisionEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertForeshadowProjectionRewind(rewind: ForeshadowProjectionRewindEntity)

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
        SELECT event.* FROM entity_event AS event
        INNER JOIN chapter_version AS event_version
          ON event_version.chapter_version_id = event.source_chapter_version_id
        INNER JOIN chapter AS event_chapter
          ON event_chapter.chapter_id = event_version.chapter_id
        WHERE event.book_id = :bookId
          AND event.status = 'VALID'
          AND event_chapter.book_id = :bookId
          AND event_chapter.chapter_index < :targetChapterIndex
          AND event_chapter.current_version_id = event.source_chapter_version_id
          AND NOT EXISTS (
              SELECT 1 FROM entity_event AS newer
              INNER JOIN chapter_version AS newer_version
                ON newer_version.chapter_version_id = newer.source_chapter_version_id
              INNER JOIN chapter AS newer_chapter
                ON newer_chapter.chapter_id = newer_version.chapter_id
              WHERE newer.book_id = event.book_id
                AND newer.entity_id = event.entity_id
                AND newer.attribute_key = event.attribute_key
                AND newer.status = 'VALID'
                AND newer_chapter.book_id = :bookId
                AND newer_chapter.chapter_index < :targetChapterIndex
                AND newer_chapter.current_version_id = newer.source_chapter_version_id
                AND (
                    newer.story_order > event.story_order
                    OR (
                        newer.story_order = event.story_order
                        AND newer.entity_event_id < event.entity_event_id
                    )
                )
          )
        ORDER BY event.entity_id ASC, event.attribute_key ASC,
                 event.story_order DESC, event.entity_event_id ASC
        LIMIT :limit
        """,
    )
    suspend fun latestValidEntityStatesThrough(
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
        SELECT story_entity.* FROM story_entity
        WHERE story_entity.book_id = :bookId
          AND story_entity.archived_at IS NULL
          AND (:afterId IS NULL OR story_entity.entity_id > :afterId)
        ORDER BY story_entity.entity_id ASC
        LIMIT :pageSize
        """,
    )
    suspend fun pageStoryEntitiesForSearchBackfill(
        bookId: String,
        afterId: String?,
        pageSize: Int,
    ): List<StoryEntity>

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
          AND chapter.current_version_id = chapter_summary.chapter_version_id
          AND (:afterId IS NULL OR chapter_summary.chapter_summary_id > :afterId)
        ORDER BY chapter_summary.chapter_summary_id ASC
        LIMIT :pageSize
        """,
    )
    suspend fun pageChapterSummariesForSearchBackfill(
        bookId: String,
        afterId: String?,
        pageSize: Int,
    ): List<ChapterSummaryEntity>

    @Query(
        """
        SELECT entity_event.*, chapter.chapter_index AS chapter_index FROM entity_event
        INNER JOIN chapter_version
          ON chapter_version.chapter_version_id = entity_event.source_chapter_version_id
        INNER JOIN chapter
          ON chapter.chapter_id = chapter_version.chapter_id
        WHERE entity_event.book_id = :bookId
          AND entity_event.status = 'VALID'
          AND chapter.book_id = :bookId
          AND chapter.current_version_id = entity_event.source_chapter_version_id
          AND (:afterId IS NULL OR entity_event.entity_event_id > :afterId)
        ORDER BY entity_event.entity_event_id ASC
        LIMIT :pageSize
        """,
    )
    suspend fun pageEntityEventsForSearchBackfill(
        bookId: String,
        afterId: String?,
        pageSize: Int,
    ): List<EntityEventSearchBackfillRow>

    @Query(
        """
        SELECT canon_fact.*, chapter.chapter_index AS chapter_index FROM canon_fact
        LEFT JOIN chapter_version
          ON chapter_version.chapter_version_id = canon_fact.source_chapter_version_id
        LEFT JOIN chapter
          ON chapter.chapter_id = chapter_version.chapter_id
        WHERE canon_fact.book_id = :bookId
          AND canon_fact.status = 'VALID'
          AND (
            canon_fact.source_chapter_version_id IS NULL
            OR (
              chapter.book_id = :bookId
              AND chapter.current_version_id = canon_fact.source_chapter_version_id
            )
          )
          AND (:afterId IS NULL OR canon_fact.canon_fact_id > :afterId)
        ORDER BY canon_fact.canon_fact_id ASC
        LIMIT :pageSize
        """,
    )
    suspend fun pageCanonFactsForSearchBackfill(
        bookId: String,
        afterId: String?,
        pageSize: Int,
    ): List<CanonFactSearchBackfillRow>

    @Query(
        """
        SELECT timeline_event.*, chapter.chapter_index AS chapter_index FROM timeline_event
        INNER JOIN chapter_version
          ON chapter_version.chapter_version_id = timeline_event.source_chapter_version_id
        INNER JOIN chapter
          ON chapter.chapter_id = chapter_version.chapter_id
        WHERE timeline_event.book_id = :bookId
          AND timeline_event.status = 'VALID'
          AND chapter.book_id = :bookId
          AND chapter.current_version_id = timeline_event.source_chapter_version_id
          AND (:afterId IS NULL OR timeline_event.timeline_event_id > :afterId)
        ORDER BY timeline_event.timeline_event_id ASC
        LIMIT :pageSize
        """,
    )
    suspend fun pageTimelineEventsForSearchBackfill(
        bookId: String,
        afterId: String?,
        pageSize: Int,
    ): List<TimelineEventSearchBackfillRow>

    @Query(
        """
        SELECT foreshadow_item.*, chapter.chapter_index AS chapter_index FROM foreshadow_item
        LEFT JOIN chapter_version
          ON chapter_version.chapter_version_id = foreshadow_item.source_chapter_version_id
        LEFT JOIN chapter
          ON chapter.chapter_id = chapter_version.chapter_id
        WHERE foreshadow_item.book_id = :bookId
          AND foreshadow_item.memory_status = 'VALID'
          AND foreshadow_item.foreshadow_status NOT IN ('RESOLVED', 'ABANDONED')
          AND (
            foreshadow_item.source_chapter_version_id IS NULL
            OR (
              chapter.book_id = :bookId
              AND chapter.current_version_id = foreshadow_item.source_chapter_version_id
            )
          )
          AND (:afterId IS NULL OR foreshadow_item.foreshadow_item_id > :afterId)
        ORDER BY foreshadow_item.foreshadow_item_id ASC
        LIMIT :pageSize
        """,
    )
    suspend fun pageForeshadowsForSearchBackfill(
        bookId: String,
        afterId: String?,
        pageSize: Int,
    ): List<ForeshadowSearchBackfillRow>

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
        SELECT item.* FROM foreshadow_item AS item
        WHERE item.book_id = :bookId
          AND item.memory_status = 'VALID'
          AND item.foreshadow_status NOT IN ('RESOLVED', 'ABANDONED')
          AND item.resolved_chapter_version_id IS NULL
          AND (
              item.source_chapter_version_id IS NULL
              OR EXISTS (
                  SELECT 1 FROM chapter_version AS source_version
                  INNER JOIN chapter AS source_chapter
                    ON source_chapter.chapter_id = source_version.chapter_id
                  WHERE source_version.chapter_version_id = item.source_chapter_version_id
                    AND source_chapter.book_id = :bookId
                    AND source_chapter.chapter_index < :targetChapterIndex
                    AND source_chapter.current_version_id = item.source_chapter_version_id
              )
          )
          AND (
              item.planted_chapter_version_id IS NULL
              OR EXISTS (
                  SELECT 1 FROM chapter_version AS planted_version
                  INNER JOIN chapter AS planted_chapter
                    ON planted_chapter.chapter_id = planted_version.chapter_id
                  WHERE planted_version.chapter_version_id = item.planted_chapter_version_id
                    AND planted_chapter.book_id = :bookId
                    AND planted_chapter.chapter_index < :targetChapterIndex
                    AND planted_chapter.current_version_id = item.planted_chapter_version_id
              )
          )
        ORDER BY item.foreshadow_item_id ASC
        LIMIT :limit
        """,
    )
    suspend fun activeForeshadowsThroughProjection(
        bookId: String,
        targetChapterIndex: Int,
        limit: Int,
    ): List<ForeshadowItemEntity>

    @Query(
        """
        SELECT COUNT(*) FROM foreshadow_item AS item
        WHERE item.book_id = :bookId
          AND item.memory_status = 'VALID'
          AND item.foreshadow_status NOT IN ('RESOLVED', 'ABANDONED')
          AND NOT (
              item.resolved_chapter_version_id IS NULL
              AND (
                  item.source_chapter_version_id IS NULL
                  OR EXISTS (
                      SELECT 1 FROM chapter_version AS source_version
                      INNER JOIN chapter AS source_chapter
                        ON source_chapter.chapter_id = source_version.chapter_id
                      WHERE source_version.chapter_version_id = item.source_chapter_version_id
                        AND source_chapter.book_id = :bookId
                        AND source_chapter.chapter_index < :targetChapterIndex
                        AND source_chapter.current_version_id = item.source_chapter_version_id
                  )
              )
              AND (
                  item.planted_chapter_version_id IS NULL
                  OR EXISTS (
                      SELECT 1 FROM chapter_version AS planted_version
                      INNER JOIN chapter AS planted_chapter
                        ON planted_chapter.chapter_id = planted_version.chapter_id
                      WHERE planted_version.chapter_version_id = item.planted_chapter_version_id
                        AND planted_chapter.book_id = :bookId
                        AND planted_chapter.chapter_index < :targetChapterIndex
                        AND planted_chapter.current_version_id = item.planted_chapter_version_id
                  )
              )
          )
        """,
    )
    suspend fun invalidActiveForeshadowProjectionCountThrough(
        bookId: String,
        targetChapterIndex: Int,
    ): Int

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

    @Query("SELECT * FROM aggregate_state_projection WHERE aggregate_state_id = :aggregateStateId")
    suspend fun findAggregateState(aggregateStateId: String): AggregateStateProjectionEntity?

    @Query(
        """
        SELECT aggregate_state_projection.* FROM aggregate_state_projection
        INNER JOIN chapter_version
          ON chapter_version.chapter_version_id = aggregate_state_projection.source_through_chapter_version_id
        INNER JOIN chapter
          ON chapter.chapter_id = chapter_version.chapter_id
        WHERE aggregate_state_projection.book_id = :bookId
          AND aggregate_state_projection.status = 'VALID'
          AND aggregate_state_projection.through_chapter_index >= :firstChapterIndex
          AND chapter.book_id = :bookId
          AND chapter.current_version_id = aggregate_state_projection.source_through_chapter_version_id
        ORDER BY aggregate_state_projection.through_chapter_index ASC
        """,
    )
    suspend fun validAggregateStatesFromChapter(
        bookId: String,
        firstChapterIndex: Int,
    ): List<AggregateStateProjectionEntity>

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

    @Query(
        """
        SELECT status FROM chapter_summary
        WHERE chapter_version_id = :chapterVersionId
        ORDER BY created_at DESC, chapter_summary_id DESC
        LIMIT 1
        """,
    )
    suspend fun latestSummaryHistoryStatus(chapterVersionId: String): String?

    @Query(
        """
        SELECT * FROM chapter_summary
        WHERE chapter_version_id = :chapterVersionId AND status = 'VALID'
        ORDER BY created_at DESC, chapter_summary_id DESC
        LIMIT 1
        """,
    )
    suspend fun findSummaryForVersion(chapterVersionId: String): ChapterSummaryEntity?

    @Query("SELECT * FROM entity_event WHERE source_chapter_version_id = :chapterVersionId AND status = 'VALID' ORDER BY story_order ASC, entity_event_id ASC")
    suspend fun entityEventsForVersion(chapterVersionId: String): List<EntityEventEntity>

    @Query("SELECT * FROM canon_fact WHERE source_chapter_version_id = :chapterVersionId AND status = 'VALID' ORDER BY canon_fact_id ASC")
    suspend fun canonFactsForVersion(chapterVersionId: String): List<CanonFactEntity>

    @Query("SELECT * FROM timeline_event WHERE source_chapter_version_id = :chapterVersionId AND status = 'VALID' ORDER BY story_order ASC, timeline_event_id ASC")
    suspend fun timelineEventsForVersion(chapterVersionId: String): List<TimelineEventEntity>

    @Query(
        """
        SELECT * FROM chapter_tracking_projection
        WHERE chapter_version_id = :chapterVersionId AND status = 'VALID'
        ORDER BY created_at DESC, projection_id DESC
        LIMIT 1
        """,
    )
    suspend fun findTrackingProjectionForVersion(chapterVersionId: String): ChapterTrackingProjectionEntity?

    @Query("SELECT * FROM chapter_tracking_projection WHERE projection_id = :projectionId")
    suspend fun findTrackingProjection(projectionId: String): ChapterTrackingProjectionEntity?

    @Query(
        """
        SELECT chapter_tracking_projection.* FROM chapter_tracking_projection
        INNER JOIN chapter_version
          ON chapter_version.chapter_version_id = chapter_tracking_projection.chapter_version_id
        INNER JOIN chapter
          ON chapter.chapter_id = chapter_version.chapter_id
        WHERE chapter_tracking_projection.book_id = :bookId
          AND chapter_tracking_projection.chapter_index >= :firstChapterIndex
          AND chapter_tracking_projection.status = 'VALID'
          AND chapter.book_id = :bookId
          AND chapter.current_version_id = chapter_tracking_projection.chapter_version_id
        ORDER BY chapter_tracking_projection.chapter_index ASC,
                 chapter_tracking_projection.projection_id ASC
        """,
    )
    suspend fun validTrackingProjectionsFromChapter(
        bookId: String,
        firstChapterIndex: Int,
    ): List<ChapterTrackingProjectionEntity>

    @Query(
        """
        SELECT * FROM chapter_tracking_projection
        WHERE book_id = :bookId AND chapter_index >= :firstChapterIndex
        ORDER BY chapter_index ASC, created_at ASC, projection_id ASC
        """,
    )
    suspend fun trackingProjectionHistoryFromChapter(
        bookId: String,
        firstChapterIndex: Int,
    ): List<ChapterTrackingProjectionEntity>

    @Query("SELECT * FROM foreshadow_transition WHERE generation_stage_id = :stageId AND status = 'VALID' ORDER BY story_order ASC, transition_id ASC")
    suspend fun foreshadowTransitionsForStage(stageId: String): List<ForeshadowTransitionEntity>

    @Query("SELECT * FROM foreshadow_projection_revision WHERE generation_stage_id = :stageId AND status = 'VALID' ORDER BY story_order ASC, transition_id ASC")
    suspend fun foreshadowProjectionRevisionsForStage(stageId: String): List<ForeshadowProjectionRevisionEntity>

    @Query("SELECT * FROM foreshadow_projection_revision WHERE transition_id = :transitionId ORDER BY created_at ASC, revision_id ASC")
    suspend fun foreshadowProjectionRevisionHistoryForTransition(
        transitionId: String,
    ): List<ForeshadowProjectionRevisionEntity>

    @Query("SELECT * FROM foreshadow_projection_rewind WHERE rewind_id = :rewindId")
    suspend fun findForeshadowProjectionRewind(rewindId: String): ForeshadowProjectionRewindEntity?

    @Query("SELECT * FROM foreshadow_projection_rewind WHERE plan_hash = :planHash")
    suspend fun findForeshadowProjectionRewindForPlan(planHash: String): ForeshadowProjectionRewindEntity?

    @Query(
        """
        SELECT foreshadow_transition.*
        FROM foreshadow_transition
        INNER JOIN chapter_version
          ON chapter_version.chapter_version_id = foreshadow_transition.source_chapter_version_id
        INNER JOIN chapter
          ON chapter.chapter_id = chapter_version.chapter_id
        WHERE foreshadow_transition.book_id = :bookId
          AND chapter.book_id = :bookId
          AND chapter.chapter_index BETWEEN :firstChapterIndex AND :lastChapterIndex
        ORDER BY foreshadow_transition.story_order ASC, foreshadow_transition.transition_id ASC
        """,
    )
    suspend fun foreshadowTransitionHistoryForChapterRange(
        bookId: String,
        firstChapterIndex: Int,
        lastChapterIndex: Int,
    ): List<ForeshadowTransitionEntity>

    @Query(
        """
        SELECT revision.*
        FROM foreshadow_projection_revision AS revision
        INNER JOIN chapter_version
          ON chapter_version.chapter_version_id = revision.source_chapter_version_id
        INNER JOIN chapter
          ON chapter.chapter_id = chapter_version.chapter_id
        WHERE revision.book_id = :bookId
          AND revision.foreshadow_item_id IN (:foreshadowItemIds)
          AND revision.status = 'VALID'
          AND revision.chapter_index < :beforeChapterIndex
          AND chapter.book_id = :bookId
          AND chapter.current_version_id = revision.source_chapter_version_id
          AND NOT EXISTS (
              SELECT 1
              FROM foreshadow_projection_revision AS newer
              INNER JOIN chapter_version AS newer_version
                ON newer_version.chapter_version_id = newer.source_chapter_version_id
              INNER JOIN chapter AS newer_chapter
                ON newer_chapter.chapter_id = newer_version.chapter_id
              WHERE newer.book_id = revision.book_id
                AND newer.foreshadow_item_id = revision.foreshadow_item_id
                AND newer.status = 'VALID'
                AND newer.chapter_index < :beforeChapterIndex
                AND newer_chapter.book_id = :bookId
                AND newer_chapter.current_version_id = newer.source_chapter_version_id
                AND (
                    newer.chapter_index > revision.chapter_index
                    OR (newer.chapter_index = revision.chapter_index AND newer.story_order > revision.story_order)
                    OR (
                        newer.chapter_index = revision.chapter_index
                        AND newer.story_order = revision.story_order
                        AND newer.created_at > revision.created_at
                    )
                    OR (
                        newer.chapter_index = revision.chapter_index
                        AND newer.story_order = revision.story_order
                        AND newer.created_at = revision.created_at
                        AND newer.revision_id > revision.revision_id
                    )
                )
          )
        ORDER BY revision.foreshadow_item_id ASC
        """,
    )
    suspend fun latestTrustedForeshadowProjectionRevisionsBeforeChapter(
        bookId: String,
        foreshadowItemIds: List<String>,
        beforeChapterIndex: Int,
    ): List<ForeshadowProjectionRevisionEntity>

    @Query("SELECT * FROM foreshadow_transition WHERE transition_id IN (:transitionIds) ORDER BY transition_id ASC")
    suspend fun foreshadowTransitionsByIds(transitionIds: List<String>): List<ForeshadowTransitionEntity>

    @Query("SELECT * FROM chapter_summary WHERE chapter_version_id = :chapterVersionId ORDER BY created_at ASC, chapter_summary_id ASC")
    suspend fun summaryHistoryForVersion(chapterVersionId: String): List<ChapterSummaryEntity>

    @Query("SELECT * FROM entity_event WHERE source_chapter_version_id = :chapterVersionId ORDER BY created_at ASC, entity_event_id ASC")
    suspend fun entityEventHistoryForVersion(chapterVersionId: String): List<EntityEventEntity>

    @Query("SELECT * FROM canon_fact WHERE source_chapter_version_id = :chapterVersionId ORDER BY created_at ASC, canon_fact_id ASC")
    suspend fun canonFactHistoryForVersion(chapterVersionId: String): List<CanonFactEntity>

    @Query("SELECT * FROM timeline_event WHERE source_chapter_version_id = :chapterVersionId ORDER BY created_at ASC, timeline_event_id ASC")
    suspend fun timelineEventHistoryForVersion(chapterVersionId: String): List<TimelineEventEntity>

    @Query("SELECT * FROM timeline_event WHERE timeline_event_id IN (:timelineEventIds) ORDER BY timeline_event_id ASC")
    suspend fun timelineEventsByIds(timelineEventIds: List<String>): List<TimelineEventEntity>

    @Query("SELECT * FROM chapter_tracking_projection WHERE chapter_version_id = :chapterVersionId ORDER BY created_at ASC, projection_id ASC")
    suspend fun trackingProjectionHistoryForVersion(chapterVersionId: String): List<ChapterTrackingProjectionEntity>

    @Query(
        """
        SELECT * FROM foreshadow_transition
        WHERE foreshadow_item_id = :foreshadowItemId
          AND source_chapter_version_id = :chapterVersionId
        ORDER BY created_at ASC, transition_id ASC
        """,
    )
    suspend fun foreshadowTransitionHistoryForSlot(
        foreshadowItemId: String,
        chapterVersionId: String,
    ): List<ForeshadowTransitionEntity>

    @Query(
        """
        SELECT * FROM aggregate_state_projection
        WHERE book_id = :bookId AND through_chapter_index = :throughChapterIndex
        ORDER BY created_at ASC, aggregate_state_id ASC
        """,
    )
    suspend fun aggregateStateHistoryForChapter(
        bookId: String,
        throughChapterIndex: Int,
    ): List<AggregateStateProjectionEntity>

    @Query("SELECT * FROM foreshadow_item WHERE foreshadow_item_id = :foreshadowItemId")
    suspend fun findForeshadow(foreshadowItemId: String): ForeshadowItemEntity?

    @Query(
        """
        SELECT * FROM foreshadow_item
        WHERE book_id = :bookId AND foreshadow_item_id IN (:foreshadowItemIds)
        ORDER BY foreshadow_item_id ASC
        """,
    )
    suspend fun foreshadowsByIds(
        bookId: String,
        foreshadowItemIds: List<String>,
    ): List<ForeshadowItemEntity>

    @Query(
        """
        SELECT * FROM foreshadow_item
        WHERE book_id = :bookId
          AND (
              source_chapter_version_id = :chapterVersionId
              OR planted_chapter_version_id = :chapterVersionId
              OR resolved_chapter_version_id = :chapterVersionId
              OR foreshadow_item_id IN (
                  SELECT foreshadow_item_id FROM foreshadow_transition
                  WHERE source_chapter_version_id = :chapterVersionId
              )
          )
        ORDER BY foreshadow_item_id ASC
        """,
    )
    suspend fun foreshadowsAffectedByVersion(
        bookId: String,
        chapterVersionId: String,
    ): List<ForeshadowItemEntity>

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

    @Query(
        """
        UPDATE foreshadow_item
        SET description = :newDescription,
            foreshadow_status = :newForeshadowStatus,
            memory_status = :newMemoryStatus,
            target_start_chapter_index = :newTargetStartChapterIndex,
            target_end_chapter_index = :newTargetEndChapterIndex,
            source_chapter_version_id = :newSourceChapterVersionId,
            planted_chapter_version_id = :newPlantedChapterVersionId,
            resolved_chapter_version_id = :newResolvedChapterVersionId,
            visible_entity_ids_json = :newVisibleEntityIdsJson,
            importance = :newImportance,
            source = :newSource,
            created_at = :newCreatedAt,
            updated_at = :newUpdatedAt
        WHERE foreshadow_item_id = :foreshadowItemId
          AND book_id = :bookId
          AND description = :expectedDescription
          AND foreshadow_status = :expectedForeshadowStatus
          AND memory_status = :expectedMemoryStatus
          AND target_start_chapter_index IS :expectedTargetStartChapterIndex
          AND target_end_chapter_index IS :expectedTargetEndChapterIndex
          AND source_chapter_version_id IS :expectedSourceChapterVersionId
          AND planted_chapter_version_id IS :expectedPlantedChapterVersionId
          AND resolved_chapter_version_id IS :expectedResolvedChapterVersionId
          AND visible_entity_ids_json = :expectedVisibleEntityIdsJson
          AND importance = :expectedImportance
          AND source = :expectedSource
          AND created_at = :expectedCreatedAt
          AND updated_at = :expectedUpdatedAt
        """,
    )
    suspend fun compareAndSetForeshadowProjectionForRewind(
        foreshadowItemId: String,
        bookId: String,
        expectedDescription: String,
        expectedForeshadowStatus: String,
        expectedMemoryStatus: String,
        expectedTargetStartChapterIndex: Int?,
        expectedTargetEndChapterIndex: Int?,
        expectedSourceChapterVersionId: String?,
        expectedPlantedChapterVersionId: String?,
        expectedResolvedChapterVersionId: String?,
        expectedVisibleEntityIdsJson: String,
        expectedImportance: Int,
        expectedSource: String,
        expectedCreatedAt: Long,
        expectedUpdatedAt: Long,
        newDescription: String,
        newForeshadowStatus: String,
        newMemoryStatus: String,
        newTargetStartChapterIndex: Int?,
        newTargetEndChapterIndex: Int?,
        newSourceChapterVersionId: String?,
        newPlantedChapterVersionId: String?,
        newResolvedChapterVersionId: String?,
        newVisibleEntityIdsJson: String,
        newImportance: Int,
        newSource: String,
        newCreatedAt: Long,
        newUpdatedAt: Long,
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

    @Query(
        """
        UPDATE chapter_tracking_projection
        SET status = 'STALE', updated_at = :retiredAt
        WHERE projection_id = :projectionId
          AND chapter_version_id = :chapterVersionId
          AND status = 'VALID'
          AND updated_at = :expectedUpdatedAt
        """,
    )
    suspend fun retireTrackingProjection(
        projectionId: String,
        chapterVersionId: String,
        expectedUpdatedAt: Long,
        retiredAt: Long,
    ): Int

    @Query(
        """
        UPDATE timeline_event
        SET status = 'STALE'
        WHERE timeline_event_id IN (:timelineEventIds)
          AND source_chapter_version_id = :chapterVersionId
          AND status = 'VALID'
        """,
    )
    suspend fun retireTimelineEvents(
        chapterVersionId: String,
        timelineEventIds: List<String>,
    ): Int

    @Query("UPDATE foreshadow_projection_revision SET status = 'STALE' WHERE source_chapter_version_id = :chapterVersionId AND status = 'VALID'")
    suspend fun staleForeshadowProjectionRevisions(chapterVersionId: String): Int

    @Query("UPDATE foreshadow_transition SET status = 'STALE' WHERE source_chapter_version_id = :chapterVersionId AND status = 'VALID'")
    suspend fun staleForeshadowTransitions(chapterVersionId: String): Int

    @Query(
        """
        UPDATE foreshadow_projection_revision
        SET status = 'STALE'
        WHERE book_id = :bookId
          AND chapter_index BETWEEN :firstChapterIndex AND :lastChapterIndex
          AND status = 'VALID'
        """,
    )
    suspend fun staleForeshadowProjectionRevisionsForChapterRange(
        bookId: String,
        firstChapterIndex: Int,
        lastChapterIndex: Int,
    ): Int

    @Query(
        """
        UPDATE foreshadow_transition
        SET status = 'STALE'
        WHERE book_id = :bookId
          AND source_chapter_version_id IN (
              SELECT chapter_version.chapter_version_id
              FROM chapter_version
              INNER JOIN chapter ON chapter.chapter_id = chapter_version.chapter_id
              WHERE chapter.book_id = :bookId
                AND chapter.chapter_index BETWEEN :firstChapterIndex AND :lastChapterIndex
          )
          AND status = 'VALID'
        """,
    )
    suspend fun staleForeshadowTransitionsForChapterRange(
        bookId: String,
        firstChapterIndex: Int,
        lastChapterIndex: Int,
    ): Int

    @Query(
        """
        SELECT COUNT(*) FROM foreshadow_projection_revision
        WHERE book_id = :bookId
          AND chapter_index BETWEEN :firstChapterIndex AND :lastChapterIndex
          AND status = 'VALID'
        """,
    )
    suspend fun validForeshadowProjectionRevisionCountForChapterRange(
        bookId: String,
        firstChapterIndex: Int,
        lastChapterIndex: Int,
    ): Int

    @Query(
        """
        SELECT COUNT(*) FROM foreshadow_transition
        WHERE book_id = :bookId
          AND source_chapter_version_id IN (
              SELECT chapter_version.chapter_version_id
              FROM chapter_version
              INNER JOIN chapter ON chapter.chapter_id = chapter_version.chapter_id
              WHERE chapter.book_id = :bookId
                AND chapter.chapter_index BETWEEN :firstChapterIndex AND :lastChapterIndex
          )
          AND status = 'VALID'
        """,
    )
    suspend fun validForeshadowTransitionCountForChapterRange(
        bookId: String,
        firstChapterIndex: Int,
        lastChapterIndex: Int,
    ): Int

    @Query("UPDATE aggregate_state_projection SET status = 'STALE', updated_at = :updatedAt WHERE book_id = :bookId AND through_chapter_index >= :chapterIndex AND status = 'VALID'")
    suspend fun staleAggregateStates(bookId: String, chapterIndex: Int, updatedAt: Long): Int

    @Query(
        """
        UPDATE aggregate_state_projection
        SET status = 'STALE', updated_at = :updatedAt
        WHERE book_id = :bookId
          AND through_chapter_index = :throughChapterIndex
          AND status = 'VALID'
        """,
    )
    suspend fun staleAggregateStateSlot(
        bookId: String,
        throughChapterIndex: Int,
        updatedAt: Long,
    ): Int

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
            foreshadowProjectionRevisions = staleForeshadowProjectionRevisions(replacedChapterVersionId),
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

    @Query(
        """
        SELECT * FROM story_entity
        WHERE book_id = :bookId
          AND entity_id IN (:ids)
          AND archived_at IS NULL
        ORDER BY entity_id ASC
        """,
    )
    suspend fun hydrateStoryEntities(
        bookId: String,
        ids: List<String>,
    ): List<StoryEntity>

    @Query(
        """
        SELECT chapter_summary.* FROM chapter_summary
        INNER JOIN chapter_version
          ON chapter_version.chapter_version_id = chapter_summary.chapter_version_id
        INNER JOIN chapter
          ON chapter.chapter_id = chapter_version.chapter_id
        WHERE chapter_summary.book_id = :bookId
          AND chapter_summary.chapter_summary_id IN (:ids)
          AND chapter_summary.status = 'VALID'
          AND chapter.book_id = :bookId
          AND chapter.chapter_index < :targetChapterIndex
          AND chapter.current_version_id = chapter_summary.chapter_version_id
        ORDER BY chapter_summary.chapter_summary_id ASC
        """,
    )
    suspend fun hydrateChapterSummaries(
        bookId: String,
        targetChapterIndex: Int,
        ids: List<String>,
    ): List<ChapterSummaryEntity>

    @Query(
        """
        SELECT entity_event.*, chapter.chapter_index AS chapter_index FROM entity_event
        INNER JOIN chapter_version
          ON chapter_version.chapter_version_id = entity_event.source_chapter_version_id
        INNER JOIN chapter
          ON chapter.chapter_id = chapter_version.chapter_id
        WHERE entity_event.book_id = :bookId
          AND entity_event.entity_event_id IN (:ids)
          AND entity_event.status = 'VALID'
          AND chapter.book_id = :bookId
          AND chapter.chapter_index < :targetChapterIndex
          AND chapter.current_version_id = entity_event.source_chapter_version_id
        ORDER BY entity_event.entity_event_id ASC
        """,
    )
    suspend fun hydrateEntityEvents(
        bookId: String,
        targetChapterIndex: Int,
        ids: List<String>,
    ): List<EntityEventSearchBackfillRow>

    @Query(
        """
        SELECT canon_fact.*,
               chapter.chapter_index AS chapter_index,
               CASE
                 WHEN canon_fact.source_bible_revision_id IS NOT NULL
                  AND canon_fact.source_bible_revision_id = book_memory_head.current_bible_revision_id
                 THEN 1 ELSE 0
               END AS bible_source_is_current
        FROM canon_fact
        LEFT JOIN chapter_version
          ON chapter_version.chapter_version_id = canon_fact.source_chapter_version_id
        LEFT JOIN chapter
          ON chapter.chapter_id = chapter_version.chapter_id
        LEFT JOIN book_memory_head
          ON book_memory_head.book_id = canon_fact.book_id
        WHERE canon_fact.book_id = :bookId
          AND canon_fact.canon_fact_id IN (:ids)
          AND canon_fact.status = 'VALID'
          AND (
            (
              canon_fact.source_bible_revision_id IS NOT NULL
              AND canon_fact.source_bible_revision_id = book_memory_head.current_bible_revision_id
            )
            OR (
              canon_fact.source_chapter_version_id IS NOT NULL
              AND chapter.book_id = :bookId
              AND chapter.chapter_index < :targetChapterIndex
              AND chapter.current_version_id = canon_fact.source_chapter_version_id
            )
          )
        ORDER BY canon_fact.canon_fact_id ASC
        """,
    )
    suspend fun hydrateCanonFacts(
        bookId: String,
        targetChapterIndex: Int,
        ids: List<String>,
    ): List<CanonFactSearchHydrationRow>

    @Query(
        """
        SELECT timeline_event.*, chapter.chapter_index AS chapter_index FROM timeline_event
        INNER JOIN chapter_version
          ON chapter_version.chapter_version_id = timeline_event.source_chapter_version_id
        INNER JOIN chapter
          ON chapter.chapter_id = chapter_version.chapter_id
        WHERE timeline_event.book_id = :bookId
          AND timeline_event.timeline_event_id IN (:ids)
          AND timeline_event.status = 'VALID'
          AND chapter.book_id = :bookId
          AND chapter.chapter_index < :targetChapterIndex
          AND chapter.current_version_id = timeline_event.source_chapter_version_id
        ORDER BY timeline_event.timeline_event_id ASC
        """,
    )
    suspend fun hydrateTimelineEvents(
        bookId: String,
        targetChapterIndex: Int,
        ids: List<String>,
    ): List<TimelineEventSearchBackfillRow>

    @Query(
        """
        SELECT foreshadow_item.*, chapter.chapter_index AS chapter_index FROM foreshadow_item
        LEFT JOIN chapter_version
          ON chapter_version.chapter_version_id = foreshadow_item.source_chapter_version_id
        LEFT JOIN chapter
          ON chapter.chapter_id = chapter_version.chapter_id
        WHERE foreshadow_item.book_id = :bookId
          AND foreshadow_item.foreshadow_item_id IN (:ids)
          AND foreshadow_item.memory_status = 'VALID'
          AND foreshadow_item.foreshadow_status NOT IN ('RESOLVED', 'ABANDONED')
          AND (
            foreshadow_item.source_chapter_version_id IS NULL
            OR (
              chapter.book_id = :bookId
              AND chapter.chapter_index < :targetChapterIndex
              AND chapter.current_version_id = foreshadow_item.source_chapter_version_id
            )
          )
        ORDER BY foreshadow_item.foreshadow_item_id ASC
        """,
    )
    suspend fun hydrateForeshadows(
        bookId: String,
        targetChapterIndex: Int,
        ids: List<String>,
    ): List<ForeshadowSearchBackfillRow>

    @Query(
        """
        SELECT canon_fact.*,
               chapter.chapter_index AS chapter_index,
               CASE
                 WHEN canon_fact.source_bible_revision_id IS NOT NULL
                  AND canon_fact.source_bible_revision_id = book_memory_head.current_bible_revision_id
                 THEN 1 ELSE 0
               END AS bible_source_is_current
        FROM canon_fact
        LEFT JOIN chapter_version
          ON chapter_version.chapter_version_id = canon_fact.source_chapter_version_id
        LEFT JOIN chapter
          ON chapter.chapter_id = chapter_version.chapter_id
        LEFT JOIN book_memory_head
          ON book_memory_head.book_id = canon_fact.book_id
        WHERE canon_fact.book_id = :bookId
          AND canon_fact.status = 'VALID'
          AND canon_fact.canon_level = 'HARD_CANON'
          AND (
            (
              canon_fact.source_bible_revision_id IS NOT NULL
              AND canon_fact.source_bible_revision_id = book_memory_head.current_bible_revision_id
            )
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
    suspend fun contextHardCanonFacts(
        bookId: String,
        targetChapterIndex: Int,
        limit: Int,
    ): List<CanonFactSearchHydrationRow>

    @Query(
        """
        SELECT foreshadow_item.*, chapter.chapter_index AS chapter_index
        FROM foreshadow_item
        LEFT JOIN chapter_version
          ON chapter_version.chapter_version_id = foreshadow_item.source_chapter_version_id
        LEFT JOIN chapter
          ON chapter.chapter_id = chapter_version.chapter_id
        WHERE foreshadow_item.book_id = :bookId
          AND foreshadow_item.memory_status = 'VALID'
          AND foreshadow_item.foreshadow_status NOT IN ('RESOLVED', 'ABANDONED')
          AND (
            foreshadow_item.source_chapter_version_id IS NULL
            OR (
              chapter.book_id = :bookId
              AND chapter.chapter_index < :targetChapterIndex
              AND chapter.current_version_id = foreshadow_item.source_chapter_version_id
            )
          )
          AND (
            (
              foreshadow_item.target_start_chapter_index IS NOT NULL
              AND foreshadow_item.target_start_chapter_index <= :targetChapterIndex
            )
            OR (
              foreshadow_item.target_end_chapter_index IS NOT NULL
              AND foreshadow_item.target_end_chapter_index <= :targetChapterIndex
            )
          )
        ORDER BY foreshadow_item.importance DESC,
                 foreshadow_item.updated_at DESC,
                 foreshadow_item.foreshadow_item_id ASC
        LIMIT :limit
        """,
    )
    suspend fun dueForeshadowsForContext(
        bookId: String,
        targetChapterIndex: Int,
        limit: Int,
    ): List<ForeshadowSearchBackfillRow>
}
