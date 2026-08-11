package app.zhijuan.core.database.memory

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.zhijuan.core.database.generation.GenerationStageEntity
import app.zhijuan.core.database.library.BookEntity
import app.zhijuan.core.database.library.ChapterEntity
import app.zhijuan.core.database.library.ChapterVersionEntity
import app.zhijuan.core.model.DerivedDataStatus

@Entity(
    tableName = "context_snapshot",
    foreignKeys = [
        ForeignKey(entity = BookEntity::class, parentColumns = ["book_id"], childColumns = ["book_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["book_id", "chapter_id"],
            childColumns = ["book_id", "target_chapter_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(entity = GenerationStageEntity::class, parentColumns = ["stage_id"], childColumns = ["generation_stage_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index(value = ["book_id", "target_chapter_id"]),
        Index(value = ["book_id", "target_chapter_index"]),
        Index(value = ["generation_stage_id"], unique = true),
        Index(value = ["status"]),
    ],
)
data class ContextSnapshotEntity(
    @PrimaryKey @ColumnInfo(name = "context_snapshot_id") val contextSnapshotId: String,
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "target_chapter_id") val targetChapterId: String,
    @ColumnInfo(name = "target_chapter_index") val targetChapterIndex: Int,
    @ColumnInfo(name = "generation_stage_id") val generationStageId: String,
    @ColumnInfo(name = "source_manifest_json") val sourceManifestJson: String,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    val status: DerivedDataStatus,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "consistency_report",
    foreignKeys = [
        ForeignKey(entity = BookEntity::class, parentColumns = ["book_id"], childColumns = ["book_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = ChapterVersionEntity::class, parentColumns = ["chapter_version_id"], childColumns = ["target_chapter_version_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = GenerationStageEntity::class, parentColumns = ["stage_id"], childColumns = ["generation_stage_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index(value = ["target_chapter_version_id"]),
        Index(value = ["book_id", "target_chapter_index"]),
        Index(value = ["generation_stage_id"]),
        Index(value = ["status"]),
    ],
)
data class ConsistencyReportEntity(
    @PrimaryKey @ColumnInfo(name = "consistency_report_id") val consistencyReportId: String,
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "target_chapter_version_id") val targetChapterVersionId: String,
    @ColumnInfo(name = "target_chapter_index") val targetChapterIndex: Int,
    @ColumnInfo(name = "generation_stage_id") val generationStageId: String?,
    @ColumnInfo(name = "checker_version") val checkerVersion: String,
    @ColumnInfo(name = "issues_json") val issuesJson: String,
    val status: DerivedDataStatus,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "aggregate_state_projection",
    foreignKeys = [
        ForeignKey(entity = BookEntity::class, parentColumns = ["book_id"], childColumns = ["book_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = ChapterVersionEntity::class, parentColumns = ["chapter_version_id"], childColumns = ["source_through_chapter_version_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index(value = ["book_id", "through_chapter_index"]),
        Index(value = ["source_through_chapter_version_id"]),
        Index(value = ["status"]),
    ],
)
data class AggregateStateProjectionEntity(
    @PrimaryKey @ColumnInfo(name = "aggregate_state_id") val aggregateStateId: String,
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "through_chapter_index") val throughChapterIndex: Int,
    @ColumnInfo(name = "source_through_chapter_version_id") val sourceThroughChapterVersionId: String,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int,
    @ColumnInfo(name = "state_json") val stateJson: String,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    val status: DerivedDataStatus,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "foreshadow_projection_revision",
    foreignKeys = [
        ForeignKey(entity = BookEntity::class, parentColumns = ["book_id"], childColumns = ["book_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(
            entity = ForeshadowItemEntity::class,
            parentColumns = ["book_id", "foreshadow_item_id"],
            childColumns = ["book_id", "foreshadow_item_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(entity = ChapterVersionEntity::class, parentColumns = ["chapter_version_id"], childColumns = ["source_chapter_version_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = GenerationStageEntity::class, parentColumns = ["stage_id"], childColumns = ["generation_stage_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = ForeshadowTransitionEntity::class, parentColumns = ["transition_id"], childColumns = ["transition_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index(value = ["book_id", "foreshadow_item_id"]),
        Index(value = ["source_chapter_version_id"]),
        Index(value = ["generation_stage_id"]),
        Index(value = ["transition_id"], unique = true),
        Index(value = ["book_id", "chapter_index", "status"]),
        Index(value = ["book_id", "foreshadow_item_id", "story_order", "status"]),
        Index(value = ["book_id", "foreshadow_item_id", "chapter_index", "story_order", "status"]),
        Index(value = ["status"]),
    ],
)
data class ForeshadowProjectionRevisionEntity(
    @PrimaryKey @ColumnInfo(name = "revision_id") val revisionId: String,
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "foreshadow_item_id") val foreshadowItemId: String,
    @ColumnInfo(name = "source_chapter_version_id") val sourceChapterVersionId: String,
    @ColumnInfo(name = "generation_stage_id") val generationStageId: String,
    @ColumnInfo(name = "transition_id") val transitionId: String,
    @ColumnInfo(name = "chapter_index") val chapterIndex: Int,
    @ColumnInfo(name = "story_order") val storyOrder: Long,
    @ColumnInfo(name = "snapshot_schema_version") val snapshotSchemaVersion: Int,
    @ColumnInfo(name = "snapshot_json") val snapshotJson: String,
    @ColumnInfo(name = "snapshot_hash") val snapshotHash: String,
    val status: DerivedDataStatus,
    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    override fun toString(): String =
        "ForeshadowProjectionRevisionEntity(chapterIndex=$chapterIndex, storyOrder=$storyOrder, " +
            "status=$status, identifiers=redacted, snapshot=redacted)"
}

@Entity(
    tableName = "foreshadow_projection_rewind",
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
    ],
    indices = [
        Index(value = ["book_id", "edited_chapter_id"]),
        Index(value = ["edited_chapter_version_id"]),
        Index(value = ["replaced_chapter_version_id"]),
        Index(value = ["book_id", "first_affected_chapter_index", "created_at"]),
        Index(value = ["plan_hash"], unique = true),
    ],
)
data class ForeshadowProjectionRewindEntity(
    @PrimaryKey @ColumnInfo(name = "rewind_id") val rewindId: String,
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "edited_chapter_id") val editedChapterId: String,
    @ColumnInfo(name = "edited_chapter_version_id") val editedChapterVersionId: String,
    @ColumnInfo(name = "replaced_chapter_version_id") val replacedChapterVersionId: String,
    @ColumnInfo(name = "first_affected_chapter_index") val firstAffectedChapterIndex: Int,
    @ColumnInfo(name = "last_affected_chapter_index") val lastAffectedChapterIndex: Int,
    @ColumnInfo(name = "plan_hash") val planHash: String,
    @ColumnInfo(name = "before_projection_set_hash") val beforeProjectionSetHash: String,
    @ColumnInfo(name = "trusted_baseline_set_hash") val trustedBaselineSetHash: String,
    @ColumnInfo(name = "after_projection_set_hash") val afterProjectionSetHash: String,
    @ColumnInfo(name = "affected_item_count") val affectedItemCount: Int,
    @ColumnInfo(name = "baseline_item_count") val baselineItemCount: Int,
    @ColumnInfo(name = "absent_item_count") val absentItemCount: Int,
    @ColumnInfo(name = "stale_revision_count") val staleRevisionCount: Int,
    @ColumnInfo(name = "stale_transition_count") val staleTransitionCount: Int,
    @ColumnInfo(name = "policy_version") val policyVersion: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    override fun toString(): String =
        "ForeshadowProjectionRewindEntity(range=$firstAffectedChapterIndex..$lastAffectedChapterIndex, " +
            "counts=${listOf(affectedItemCount, baselineItemCount, absentItemCount)}, " +
            "identifiers=redacted, hashes=redacted)"
}
