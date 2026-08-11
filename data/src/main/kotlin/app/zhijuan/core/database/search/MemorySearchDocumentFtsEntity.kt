package app.zhijuan.core.database.search

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4

@Entity(tableName = "memory_search_document_fts")
@Fts4(contentEntity = MemorySearchDocumentEntity::class)
internal data class MemorySearchDocumentFtsEntity(
    @ColumnInfo(name = "search_terms")
    val searchTerms: String,
)
