package app.zhijuan.core.database.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.zhijuan.core.model.ChapterStatus
import app.zhijuan.core.model.ConsistencyStatus

internal data class ChapterEditRebuildExecutionSourceRow(
    val chapterId: String,
    val chapterIndex: Int,
    val chapterStatus: ChapterStatus,
    val consistencyStatus: ConsistencyStatus,
    val chapterVersionId: String,
    val contentHash: String,
    val versionCreatedAt: Long,
)

@Dao
internal interface ChapterEditRebuildExecutionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertExecution(execution: ChapterEditRebuildExecutionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSteps(steps: List<ChapterEditRebuildStepEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTrackingRetirement(retirement: ChapterEditRebuildTrackingRetirementEntity)

    @Query("SELECT * FROM chapter_edit_rebuild_execution WHERE execution_id = :executionId")
    suspend fun findExecution(executionId: String): ChapterEditRebuildExecutionEntity?

    @Query("SELECT * FROM chapter_edit_rebuild_execution WHERE edited_chapter_version_id = :editedChapterVersionId")
    suspend fun findExecutionForEditedVersion(editedChapterVersionId: String): ChapterEditRebuildExecutionEntity?

    @Query("SELECT * FROM chapter_edit_rebuild_execution WHERE rewind_id = :rewindId")
    suspend fun findExecutionForRewind(rewindId: String): ChapterEditRebuildExecutionEntity?

    @Query("SELECT * FROM chapter_edit_rebuild_execution WHERE stable_fence_hash = :stableFenceHash")
    suspend fun findExecutionForStableFence(stableFenceHash: String): ChapterEditRebuildExecutionEntity?

    @Query(
        """
        SELECT * FROM chapter_edit_rebuild_step
        WHERE execution_id = :executionId
        ORDER BY step_ordinal ASC
        """,
    )
    suspend fun stepsForExecution(executionId: String): List<ChapterEditRebuildStepEntity>

    @Query(
        """
        SELECT * FROM chapter_edit_rebuild_tracking_retirement
        WHERE execution_id = :executionId AND step_ordinal = :stepOrdinal
        """,
    )
    suspend fun findTrackingRetirement(
        executionId: String,
        stepOrdinal: Int,
    ): ChapterEditRebuildTrackingRetirementEntity?

    @Query(
        """
        SELECT * FROM chapter_edit_rebuild_tracking_retirement
        WHERE execution_id = :executionId
        ORDER BY step_ordinal ASC
        """,
    )
    suspend fun trackingRetirementsForExecution(
        executionId: String,
    ): List<ChapterEditRebuildTrackingRetirementEntity>

    @Query(
        """
        SELECT chapter.chapter_id AS chapterId,
               chapter.chapter_index AS chapterIndex,
               chapter.status AS chapterStatus,
               chapter.consistency_status AS consistencyStatus,
               chapter_version.chapter_version_id AS chapterVersionId,
               chapter_version.content_hash AS contentHash,
               chapter_version.created_at AS versionCreatedAt
        FROM chapter
        INNER JOIN chapter_version
          ON chapter_version.chapter_id = chapter.chapter_id
         AND chapter_version.chapter_version_id = chapter.current_version_id
        WHERE chapter.book_id = :bookId
          AND chapter.chapter_index >= :firstChapterIndex
        ORDER BY chapter.chapter_index ASC
        """,
    )
    suspend fun currentSourcesFromChapter(
        bookId: String,
        firstChapterIndex: Int,
    ): List<ChapterEditRebuildExecutionSourceRow>
}
