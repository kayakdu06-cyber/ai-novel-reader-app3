package app.zhijuan.core.database.search

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4

@Entity(tableName = "search_document_fts")
@Fts4(contentEntity = SearchDocumentEntity::class)
data class SearchDocumentFtsEntity(
    @ColumnInfo(name = "search_terms")
    val searchTerms: String,
)
