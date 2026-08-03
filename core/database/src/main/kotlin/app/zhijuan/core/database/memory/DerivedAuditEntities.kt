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
        Index(value = ["book_id", "through_chapter_index"], unique = true),
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
