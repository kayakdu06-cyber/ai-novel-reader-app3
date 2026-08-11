package app.zhijuan.core.database.library

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.zhijuan.core.model.BookLengthMode
import app.zhijuan.core.model.BookStatus
import app.zhijuan.core.model.ChapterStatus
import app.zhijuan.core.model.ChapterVersionSource
import app.zhijuan.core.model.ConsistencyStatus
import app.zhijuan.core.model.TitleSource

@Entity(tableName = "book_creation_snapshot")
data class BookCreationSnapshotEntity(
    @PrimaryKey
    @ColumnInfo(name = "snapshot_id")
    val snapshotId: String,
    @ColumnInfo(name = "raw_input_json")
    val rawInputJson: String,
    @ColumnInfo(name = "normalized_input_json")
    val normalizedInputJson: String,
    @ColumnInfo(name = "inference_provenance_json")
    val inferenceProvenanceJson: String,
    @ColumnInfo(name = "genre_payload_json")
    val genrePayloadJson: String,
    @ColumnInfo(name = "presentation_profile_json")
    val presentationProfileJson: String,
    @ColumnInfo(name = "model_preference_json")
    val modelPreferenceJson: String,
    @ColumnInfo(name = "schema_version")
    val schemaVersion: Int,
    @ColumnInfo(name = "prompt_bundle_version")
    val promptBundleVersion: String,
    @ColumnInfo(name = "content_control_schema_version")
    val contentControlSchemaVersion: Int,
    @ColumnInfo(name = "content_hash")
    val contentHash: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

@Entity(
    tableName = "book",
    foreignKeys = [
        ForeignKey(
            entity = BookCreationSnapshotEntity::class,
            parentColumns = ["snapshot_id"],
            childColumns = ["creation_snapshot_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["book_id"],
            childColumns = ["branched_from_book_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = ChapterVersionEntity::class,
            parentColumns = ["chapter_version_id"],
            childColumns = ["branched_from_chapter_version_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["creation_snapshot_id"], unique = true),
        Index(value = ["branched_from_book_id"]),
        Index(value = ["branched_from_chapter_version_id"]),
        Index(value = ["status"]),
        Index(value = ["updated_at"]),
    ],
)
data class BookEntity(
    @PrimaryKey
    @ColumnInfo(name = "book_id")
    val bookId: String,
    @ColumnInfo(name = "creation_snapshot_id")
    val creationSnapshotId: String,
    val title: String,
    @ColumnInfo(name = "title_source")
    val titleSource: TitleSource,
    val status: BookStatus,
    @ColumnInfo(name = "length_mode")
    val lengthMode: BookLengthMode,
    @ColumnInfo(name = "target_characters")
    val targetCharacters: Int?,
    @ColumnInfo(name = "target_chapters")
    val targetChapters: Int?,
    @ColumnInfo(name = "minimum_chapters", defaultValue = "1")
    val minimumChapters: Int = 1,
    @ColumnInfo(name = "length_policy_schema_version", defaultValue = "0")
    val lengthPolicySchemaVersion: Int = 0,
    @ColumnInfo(name = "branched_from_book_id")
    val branchedFromBookId: String? = null,
    @ColumnInfo(name = "branched_from_chapter_version_id")
    val branchedFromChapterVersionId: String? = null,
    @ColumnInfo(name = "completed_chapter_count")
    val completedChapterCount: Int = 0,
    @ColumnInfo(name = "generation_status_summary")
    val generationStatusSummary: String = "",
    @ColumnInfo(name = "archived_at")
    val archivedAt: Long? = null,
    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "chapter",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["book_id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = ChapterVersionEntity::class,
            parentColumns = ["chapter_id", "chapter_version_id"],
            childColumns = ["chapter_id", "current_version_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["book_id", "chapter_index"], unique = true),
        Index(value = ["book_id", "chapter_id"], unique = true),
        Index(value = ["chapter_id", "current_version_id"]),
        Index(value = ["status"]),
    ],
)
data class ChapterEntity(
    @PrimaryKey
    @ColumnInfo(name = "chapter_id")
    val chapterId: String,
    @ColumnInfo(name = "book_id")
    val bookId: String,
    @ColumnInfo(name = "chapter_index")
    val chapterIndex: Int,
    @ColumnInfo(name = "planned_title")
    val plannedTitle: String,
    @ColumnInfo(name = "display_title")
    val displayTitle: String,
    val status: ChapterStatus,
    @ColumnInfo(name = "current_version_id")
    val currentVersionId: String? = null,
    @ColumnInfo(name = "consistency_status")
    val consistencyStatus: ConsistencyStatus = ConsistencyStatus.UNKNOWN,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "chapter_version",
    foreignKeys = [
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["chapter_id"],
            childColumns = ["chapter_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = ChapterVersionEntity::class,
            parentColumns = ["chapter_id", "chapter_version_id"],
            childColumns = ["chapter_id", "parent_version_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["chapter_id", "chapter_version_id"], unique = true),
        Index(value = ["chapter_id", "version_no"], unique = true),
        Index(value = ["chapter_id", "parent_version_id"]),
        Index(value = ["generation_stage_id", "content_hash"], unique = true),
        Index(value = ["created_at"]),
    ],
)
data class ChapterVersionEntity(
    @PrimaryKey
    @ColumnInfo(name = "chapter_version_id")
    val chapterVersionId: String,
    @ColumnInfo(name = "chapter_id")
    val chapterId: String,
    @ColumnInfo(name = "version_no")
    val versionNo: Int,
    val content: String,
    @ColumnInfo(name = "character_count")
    val characterCount: Int,
    @ColumnInfo(name = "content_hash")
    val contentHash: String,
    val source: ChapterVersionSource,
    @ColumnInfo(name = "parent_version_id")
    val parentVersionId: String?,
    @ColumnInfo(name = "generation_stage_id")
    val generationStageId: String?,
    @ColumnInfo(name = "model_snapshot_json")
    val modelSnapshotJson: String?,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
