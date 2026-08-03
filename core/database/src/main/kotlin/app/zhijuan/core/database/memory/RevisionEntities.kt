package app.zhijuan.core.database.memory

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.zhijuan.core.database.generation.GenerationStageEntity
import app.zhijuan.core.database.library.BookEntity
import app.zhijuan.core.model.OutlineNodeType
import app.zhijuan.core.model.RevisionSource

@Entity(
    tableName = "story_bible_revision",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["book_id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = StoryBibleRevisionEntity::class,
            parentColumns = ["book_id", "bible_revision_id"],
            childColumns = ["book_id", "parent_revision_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = GenerationStageEntity::class,
            parentColumns = ["stage_id"],
            childColumns = ["generation_stage_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["book_id", "bible_revision_id"], unique = true),
        Index(value = ["book_id", "revision_no"], unique = true),
        Index(value = ["book_id", "parent_revision_id"]),
        Index(value = ["generation_stage_id"]),
        Index(value = ["content_hash"]),
    ],
)
data class StoryBibleRevisionEntity(
    @PrimaryKey
    @ColumnInfo(name = "bible_revision_id")
    val bibleRevisionId: String,
    @ColumnInfo(name = "book_id")
    val bookId: String,
    @ColumnInfo(name = "revision_no")
    val revisionNo: Int,
    @ColumnInfo(name = "parent_revision_id")
    val parentRevisionId: String?,
    val source: RevisionSource,
    @ColumnInfo(name = "schema_version")
    val schemaVersion: Int,
    @ColumnInfo(name = "content_control_schema_version")
    val contentControlSchemaVersion: Int,
    @ColumnInfo(name = "payload_json")
    val payloadJson: String,
    @ColumnInfo(name = "content_hash")
    val contentHash: String,
    @ColumnInfo(name = "generation_stage_id")
    val generationStageId: String?,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

@Entity(
    tableName = "outline_revision",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["book_id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = OutlineRevisionEntity::class,
            parentColumns = ["book_id", "outline_revision_id"],
            childColumns = ["book_id", "parent_revision_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = GenerationStageEntity::class,
            parentColumns = ["stage_id"],
            childColumns = ["generation_stage_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["book_id", "outline_revision_id"], unique = true),
        Index(value = ["book_id", "revision_no"], unique = true),
        Index(value = ["book_id", "parent_revision_id"]),
        Index(value = ["generation_stage_id"]),
        Index(value = ["content_hash"]),
    ],
)
data class OutlineRevisionEntity(
    @PrimaryKey
    @ColumnInfo(name = "outline_revision_id")
    val outlineRevisionId: String,
    @ColumnInfo(name = "book_id")
    val bookId: String,
    @ColumnInfo(name = "revision_no")
    val revisionNo: Int,
    @ColumnInfo(name = "parent_revision_id")
    val parentRevisionId: String?,
    val source: RevisionSource,
    @ColumnInfo(name = "schema_version")
    val schemaVersion: Int,
    @ColumnInfo(name = "summary_json")
    val summaryJson: String,
    @ColumnInfo(name = "content_hash")
    val contentHash: String,
    @ColumnInfo(name = "generation_stage_id")
    val generationStageId: String?,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

@Entity(
    tableName = "outline_node",
    foreignKeys = [
        ForeignKey(
            entity = OutlineRevisionEntity::class,
            parentColumns = ["outline_revision_id"],
            childColumns = ["outline_revision_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = OutlineNodeEntity::class,
            parentColumns = ["outline_revision_id", "outline_node_id"],
            childColumns = ["outline_revision_id", "parent_node_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["outline_revision_id", "outline_node_id"], unique = true),
        Index(value = ["outline_revision_id", "parent_node_id"]),
        Index(value = ["outline_revision_id", "order_key"], unique = true),
        Index(value = ["outline_revision_id", "planned_chapter_index"]),
    ],
)
data class OutlineNodeEntity(
    @PrimaryKey
    @ColumnInfo(name = "outline_node_id")
    val outlineNodeId: String,
    @ColumnInfo(name = "outline_revision_id")
    val outlineRevisionId: String,
    @ColumnInfo(name = "parent_node_id")
    val parentNodeId: String?,
    @ColumnInfo(name = "node_type")
    val nodeType: OutlineNodeType,
    @ColumnInfo(name = "order_key")
    val orderKey: Long,
    @ColumnInfo(name = "planned_chapter_index")
    val plannedChapterIndex: Int?,
    val title: String,
    @ColumnInfo(name = "plan_json")
    val planJson: String,
    @ColumnInfo(name = "content_hash")
    val contentHash: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

@Entity(
    tableName = "book_memory_head",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["book_id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = StoryBibleRevisionEntity::class,
            parentColumns = ["book_id", "bible_revision_id"],
            childColumns = ["book_id", "current_bible_revision_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = OutlineRevisionEntity::class,
            parentColumns = ["book_id", "outline_revision_id"],
            childColumns = ["book_id", "current_outline_revision_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["book_id", "current_bible_revision_id"]),
        Index(value = ["book_id", "current_outline_revision_id"]),
    ],
)
data class BookMemoryHeadEntity(
    @PrimaryKey
    @ColumnInfo(name = "book_id")
    val bookId: String,
    @ColumnInfo(name = "current_bible_revision_id")
    val currentBibleRevisionId: String?,
    @ColumnInfo(name = "current_outline_revision_id")
    val currentOutlineRevisionId: String?,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
