package app.zhijuan.core.database.search

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
internal interface MemorySearchDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(document: MemorySearchDocumentEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(documents: List<MemorySearchDocumentEntity>): List<Long>

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(document: MemorySearchDocumentEntity): Int

    @Query("SELECT * FROM memory_search_document WHERE document_id = :documentId")
    suspend fun findByDocumentId(documentId: String): MemorySearchDocumentEntity?

    @Query(
        """
        SELECT * FROM memory_search_document
        WHERE book_id = :bookId AND source_type = :sourceType AND source_id = :sourceId
        """,
    )
    suspend fun findBySource(
        bookId: String,
        sourceType: String,
        sourceId: String,
    ): MemorySearchDocumentEntity?

    @Query("DELETE FROM memory_search_document WHERE document_id = :documentId")
    suspend fun deleteByDocumentId(documentId: String): Int

    @Query(
        """
        DELETE FROM memory_search_document
        WHERE book_id = :bookId AND source_type = :sourceType AND source_id = :sourceId
        """,
    )
    suspend fun deleteBySource(
        bookId: String,
        sourceType: String,
        sourceId: String,
    ): Int

    @Query("SELECT * FROM memory_search_document WHERE book_id = :bookId ORDER BY document_id ASC")
    suspend fun documentsForBook(bookId: String): List<MemorySearchDocumentEntity>

    @Query(
        """
        SELECT memory_search_document.*
        FROM memory_search_document
        WHERE memory_search_document.book_id = :bookId
          AND (
            memory_search_document.chapter_index IS NULL
            OR memory_search_document.chapter_index < :targetChapterIndex
          )
          AND memory_search_document.rowid IN (
              SELECT rowid
              FROM memory_search_document_fts
              WHERE memory_search_document_fts MATCH :matchExpression
          )
        ORDER BY memory_search_document.importance DESC,
                 memory_search_document.chapter_index DESC,
                 memory_search_document.story_order DESC,
                 memory_search_document.document_id ASC
        LIMIT :limit
        """,
    )
    suspend fun searchBeforeChapter(
        bookId: String,
        matchExpression: String,
        targetChapterIndex: Int,
        limit: Int,
    ): List<MemorySearchDocumentEntity>

    @Query("SELECT COUNT(*) FROM memory_search_document")
    suspend fun count(): Long

    @Query("SELECT COUNT(*) FROM memory_search_document WHERE book_id = :bookId")
    suspend fun countByBook(bookId: String): Long

    @Query("DELETE FROM memory_search_document WHERE book_id = :bookId")
    suspend fun deleteByBook(bookId: String): Int

    /** Replaces source pointers without relying on REPLACE's implicit delete/reinsert semantics. */
    @Transaction
    suspend fun replaceAll(documents: List<MemorySearchDocumentEntity>) {
        val ordered = documents.sortedBy(MemorySearchDocumentEntity::documentId)
        require(ordered.map(MemorySearchDocumentEntity::documentId).distinct().size == ordered.size) {
            "Search replacement contains duplicate document identities."
        }
        require(
            ordered.map { Triple(it.bookId, it.sourceType, it.sourceId) }.distinct().size == ordered.size,
        ) { "Search replacement contains duplicate source identities." }
        ordered.forEach { document ->
            require(document.rowId == 0L) { "New search documents cannot choose a SQLite row id." }
            val bySource = findBySource(document.bookId, document.sourceType, document.sourceId)
            val byDocumentId = findByDocumentId(document.documentId)
            require(bySource == null || byDocumentId == null || bySource.rowId == byDocumentId.rowId) {
                "Search document and source identities resolve to different rows."
            }
            val existing = bySource ?: byDocumentId
            require(
                existing == null ||
                    (existing.bookId == document.bookId &&
                        existing.sourceType == document.sourceType &&
                        existing.sourceId == document.sourceId),
            ) { "Search document identity collides with another source." }
            if (existing == null) {
                insert(document)
            } else {
                check(update(document.copy(rowId = existing.rowId)) == 1) {
                    "Search document changed during replacement."
                }
            }
        }
    }

    @Transaction
    suspend fun deleteSources(sources: List<MemorySearchSourceIdentityV1>) {
        sources.distinct().sortedWith(SOURCE_IDENTITY_ORDER).forEach { source ->
            deleteBySource(source.bookId, source.sourceType.name, source.sourceId)
        }
    }

    private companion object {
        val SOURCE_IDENTITY_ORDER = compareBy<MemorySearchSourceIdentityV1>(
            MemorySearchSourceIdentityV1::bookId,
            { it.sourceType.name },
            MemorySearchSourceIdentityV1::sourceId,
        )
    }
}
