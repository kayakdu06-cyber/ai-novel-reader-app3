package app.zhijuan.core.database.search

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "search_document",
    indices = [
        Index(value = ["document_id"], unique = true),
        Index(value = ["book_id", "chapter_index"]),
    ],
)
data class SearchDocumentEntity(
    /** Internal SQLite row id only; stable cross-feature identity remains [documentId]. */
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowId: Long,
    @ColumnInfo(name = "document_id")
    val documentId: String,
    @ColumnInfo(name = "book_id")
    val bookId: String,
    @ColumnInfo(name = "chapter_index")
    val chapterIndex: Int,
    @ColumnInfo(name = "content")
    val content: String,
    @ColumnInfo(name = "content_hash")
    val contentHash: String,
    @ColumnInfo(name = "search_terms")
    val searchTerms: String,
)
