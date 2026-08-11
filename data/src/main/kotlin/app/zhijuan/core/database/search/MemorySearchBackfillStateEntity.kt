package app.zhijuan.core.database.search

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import app.zhijuan.core.database.library.BookEntity

@Entity(
    tableName = "memory_search_backfill_state",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["book_id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
)
internal data class MemorySearchBackfillStateEntity(
    @PrimaryKey @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "index_schema_version") val indexSchemaVersion: Int,
    @ColumnInfo(name = "completed_at") val completedAt: Long,
) {
    init {
        require(bookId.isNotBlank()) { "Search backfill book identity is invalid." }
        require(indexSchemaVersion >= 1) { "Search backfill schema version is invalid." }
        require(completedAt >= 0L) { "Search backfill completion time is invalid." }
    }
}
