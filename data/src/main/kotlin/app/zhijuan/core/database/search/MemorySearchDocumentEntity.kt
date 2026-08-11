package app.zhijuan.core.database.search

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.zhijuan.core.database.library.BookEntity

/**
 * A derived search pointer stored beside its source data in the encrypted production database.
 *
 * This table intentionally stores only deterministic search tokens and enough metadata to reload
 * the authoritative source row. It must never become a second copy of chapter or memory prose.
 */
@Entity(
    tableName = "memory_search_document",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["book_id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["document_id"], unique = true),
        Index(value = ["book_id", "source_type", "source_id"], unique = true),
        Index(value = ["book_id", "source_type", "chapter_index"]),
        Index(value = ["book_id", "story_order"]),
    ],
)
internal data class MemorySearchDocumentEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "rowid")
    val rowId: Long = 0,
    @ColumnInfo(name = "document_id")
    val documentId: String,
    @ColumnInfo(name = "book_id")
    val bookId: String,
    @ColumnInfo(name = "source_type")
    val sourceType: String,
    @ColumnInfo(name = "source_id")
    val sourceId: String,
    @ColumnInfo(name = "chapter_index")
    val chapterIndex: Int?,
    @ColumnInfo(name = "story_order")
    val storyOrder: Long?,
    val importance: Int,
    @ColumnInfo(name = "source_content_hash")
    val sourceContentHash: String,
    @ColumnInfo(name = "search_terms")
    val searchTerms: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
