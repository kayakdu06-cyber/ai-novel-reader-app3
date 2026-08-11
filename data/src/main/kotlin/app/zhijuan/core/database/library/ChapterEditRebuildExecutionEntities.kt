package app.zhijuan.core.database.library

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.zhijuan.core.database.generation.GenerationJobEntity
import app.zhijuan.core.database.generation.GenerationStageEntity
import app.zhijuan.core.database.memory.AggregateStateProjectionEntity
import app.zhijuan.core.database.memory.ChapterSummaryEntity
import app.zhijuan.core.database.memory.ChapterTrackingProjectionEntity
import app.zhijuan.core.database.memory.ForeshadowProjectionRewindEntity

enum class ChapterEditRebuildExecutionStatus {
    PREPARED,
}

enum class ChapterEditRebuildExecutionStepType {
    EDITED_MEMORY,
    TRACKING,
    AGGREGATE,
}

enum class ChapterEditRebuildPreparedStepState {
    PENDING,
    SATISFIED,
}

@Entity(
    tableName = "chapter_edit_rebuild_execution",
    foreignKeys = [
        ForeignKey(entity = BookEntity::class, parentColumns = ["book_id"], childColumns = ["book_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["book_id", "chapter_id"],
            childColumns = ["book_id", "edited_chapter_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(entity = ChapterVersionEntity::class, parentColumns = ["chapter_version_id"], childColumns = ["edited_chapter_version_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = ChapterVersionEntity::class, parentColumns = ["chapter_version_id"], childColumns = ["replaced_chapter_version_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = ForeshadowProjectionRewindEntity::class, parentColumns = ["rewind_id"], childColumns = ["rewind_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index(value = ["book_id", "edited_chapter_id"]),
        Index(value = ["edited_chapter_version_id"], unique = true),
        Index(value = ["replaced_chapter_version_id"]),
        Index(value = ["rewind_id"], unique = true),
        Index(value = ["stable_fence_hash"], unique = true),
        Index(value = ["book_id", "status", "prepared_at"]),
    ],
)
data class ChapterEditRebuildExecutionEntity(
    @PrimaryKey @ColumnInfo(name = "execution_id") val executionId: String,
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "edited_chapter_id") val editedChapterId: String,
    @ColumnInfo(name = "edited_chapter_version_id") val editedChapterVersionId: String,
    @ColumnInfo(name = "replaced_chapter_version_id") val replacedChapterVersionId: String,
    @ColumnInfo(name = "rewind_id") val rewindId: String,
    @ColumnInfo(name = "first_affected_chapter_index") val firstAffectedChapterIndex: Int,
    @ColumnInfo(name = "last_affected_chapter_index") val lastAffectedChapterIndex: Int,
    @ColumnInfo(name = "future_chapter_policy") val futureChapterPolicy: FutureChapterPolicy,
    @ColumnInfo(name = "plan_schema_version") val planSchemaVersion: Int,
    @ColumnInfo(name = "initial_plan_hash") val initialPlanHash: String,
    @ColumnInfo(name = "stable_fence_hash") val stableFenceHash: String,
    @ColumnInfo(name = "policy_version") val policyVersion: String,
    val status: ChapterEditRebuildExecutionStatus,
    @ColumnInfo(name = "prepared_at") val preparedAt: Long,
) {
    override fun toString(): String =
        "ChapterEditRebuildExecutionEntity(range=$firstAffectedChapterIndex..$lastAffectedChapterIndex, " +
            "policy=$futureChapterPolicy, status=$status, identifiers=redacted, hashes=redacted)"
}

@Entity(
    tableName = "chapter_edit_rebuild_step",
    primaryKeys = ["execution_id", "step_ordinal"],
    foreignKeys = [
        ForeignKey(entity = ChapterEditRebuildExecutionEntity::class, parentColumns = ["execution_id"], childColumns = ["execution_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = BookEntity::class, parentColumns = ["book_id"], childColumns = ["book_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["book_id", "chapter_id"],
            childColumns = ["book_id", "chapter_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(entity = ChapterVersionEntity::class, parentColumns = ["chapter_version_id"], childColumns = ["source_chapter_version_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = ChapterSummaryEntity::class, parentColumns = ["chapter_summary_id"], childColumns = ["baseline_summary_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = ChapterTrackingProjectionEntity::class, parentColumns = ["projection_id"], childColumns = ["baseline_tracking_projection_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = AggregateStateProjectionEntity::class, parentColumns = ["aggregate_state_id"], childColumns = ["baseline_aggregate_state_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index(value = ["book_id", "chapter_id"]),
        Index(value = ["source_chapter_version_id"]),
        Index(value = ["baseline_summary_id"]),
        Index(value = ["baseline_tracking_projection_id"]),
        Index(value = ["baseline_aggregate_state_id"]),
        Index(value = ["execution_id", "step_type", "chapter_index"], unique = true),
        Index(value = ["execution_id", "prepared_state", "step_ordinal"]),
    ],
)
data class ChapterEditRebuildStepEntity(
    @ColumnInfo(name = "execution_id") val executionId: String,
    @ColumnInfo(name = "step_ordinal") val stepOrdinal: Int,
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "chapter_id") val chapterId: String,
    @ColumnInfo(name = "chapter_index") val chapterIndex: Int,
    @ColumnInfo(name = "source_chapter_version_id") val sourceChapterVersionId: String,
    @ColumnInfo(name = "source_content_hash") val sourceContentHash: String,
    @ColumnInfo(name = "step_type") val stepType: ChapterEditRebuildExecutionStepType,
    @ColumnInfo(name = "needs_provider") val needsProvider: Boolean,
    @ColumnInfo(name = "prepared_state") val preparedState: ChapterEditRebuildPreparedStepState,
    @ColumnInfo(name = "baseline_summary_id") val baselineSummaryId: String?,
    @ColumnInfo(name = "baseline_summary_fingerprint") val baselineSummaryFingerprint: String?,
    @ColumnInfo(name = "baseline_tracking_projection_id") val baselineTrackingProjectionId: String?,
    @ColumnInfo(name = "baseline_tracking_fingerprint") val baselineTrackingFingerprint: String?,
    @ColumnInfo(name = "baseline_aggregate_state_id") val baselineAggregateStateId: String?,
    @ColumnInfo(name = "baseline_aggregate_fingerprint") val baselineAggregateFingerprint: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    override fun toString(): String =
        "ChapterEditRebuildStepEntity(ordinal=$stepOrdinal, chapterIndex=$chapterIndex, " +
            "type=$stepType, needsProvider=$needsProvider, state=$preparedState, " +
            "identifiers=redacted, hashes=redacted)"
}

@Entity(
    tableName = "chapter_edit_rebuild_tracking_retirement",
    primaryKeys = ["execution_id", "step_ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = ChapterEditRebuildStepEntity::class,
            parentColumns = ["execution_id", "step_ordinal"],
            childColumns = ["execution_id", "step_ordinal"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(entity = BookEntity::class, parentColumns = ["book_id"], childColumns = ["book_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["book_id", "chapter_id"],
            childColumns = ["book_id", "chapter_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(entity = ChapterVersionEntity::class, parentColumns = ["chapter_version_id"], childColumns = ["source_chapter_version_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = ChapterTrackingProjectionEntity::class, parentColumns = ["projection_id"], childColumns = ["baseline_tracking_projection_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = GenerationJobEntity::class, parentColumns = ["job_id"], childColumns = ["replacement_job_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = GenerationStageEntity::class, parentColumns = ["stage_id"], childColumns = ["replacement_stage_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index(value = ["book_id", "chapter_id"]),
        Index(value = ["source_chapter_version_id"]),
        Index(value = ["baseline_tracking_projection_id"], unique = true),
        Index(value = ["replacement_job_id"], unique = true),
        Index(value = ["replacement_stage_id"], unique = true),
        Index(value = ["execution_id", "chapter_index"], unique = true),
    ],
)
data class ChapterEditRebuildTrackingRetirementEntity(
    @ColumnInfo(name = "execution_id") val executionId: String,
    @ColumnInfo(name = "step_ordinal") val stepOrdinal: Int,
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "chapter_id") val chapterId: String,
    @ColumnInfo(name = "chapter_index") val chapterIndex: Int,
    @ColumnInfo(name = "source_chapter_version_id") val sourceChapterVersionId: String,
    @ColumnInfo(name = "baseline_tracking_projection_id") val baselineTrackingProjectionId: String,
    @ColumnInfo(name = "baseline_tracking_fingerprint") val baselineTrackingFingerprint: String,
    @ColumnInfo(name = "retired_tracking_fingerprint") val retiredTrackingFingerprint: String,
    @ColumnInfo(name = "baseline_timeline_event_count") val baselineTimelineEventCount: Int,
    @ColumnInfo(name = "baseline_timeline_event_ids_json") val baselineTimelineEventIdsJson: String,
    @ColumnInfo(name = "baseline_timeline_fingerprint") val baselineTimelineFingerprint: String,
    @ColumnInfo(name = "replacement_job_id") val replacementJobId: String,
    @ColumnInfo(name = "replacement_stage_id") val replacementStageId: String,
    @ColumnInfo(name = "policy_version") val policyVersion: String,
    @ColumnInfo(name = "retired_at") val retiredAt: Long,
) {
    override fun toString(): String =
        "ChapterEditRebuildTrackingRetirementEntity(ordinal=$stepOrdinal, chapterIndex=$chapterIndex, " +
            "timelineCount=$baselineTimelineEventCount, identifiers=redacted, hashes=redacted)"
}
