package app.zhijuan.core.database.search

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface SearchDocumentDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(documents: List<SearchDocumentEntity>)

    @Update
    suspend fun update(document: SearchDocumentEntity)

    @Query(
        """
        SELECT search_document.*
        FROM search_document
        WHERE search_document.book_id = :bookId
          AND search_document.rowid IN (
              SELECT rowid
              FROM search_document_fts
              WHERE search_document_fts MATCH :matchExpression
          )
        ORDER BY search_document.chapter_index DESC
        LIMIT :limit
        """,
    )
    suspend fun search(
        bookId: String,
        matchExpression: String,
        limit: Int,
    ): List<SearchDocumentEntity>

    @Query("SELECT COUNT(*) FROM search_document")
    suspend fun count(): Long
}
