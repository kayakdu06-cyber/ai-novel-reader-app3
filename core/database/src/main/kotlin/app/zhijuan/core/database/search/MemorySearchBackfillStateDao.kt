package app.zhijuan.core.database.search

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
internal interface MemorySearchBackfillStateDao {
    @Query("SELECT * FROM memory_search_backfill_state WHERE book_id = :bookId")
    suspend fun find(bookId: String): MemorySearchBackfillStateEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(state: MemorySearchBackfillStateEntity)

    @Query(
        """
        UPDATE memory_search_backfill_state
        SET index_schema_version = :indexSchemaVersion, completed_at = :completedAt
        WHERE book_id = :bookId
        """,
    )
    suspend fun update(
        bookId: String,
        indexSchemaVersion: Int,
        completedAt: Long,
    ): Int

    @Query("DELETE FROM memory_search_backfill_state WHERE book_id = :bookId")
    suspend fun deleteByBook(bookId: String): Int

    @Transaction
    suspend fun store(state: MemorySearchBackfillStateEntity) {
        val existing = find(state.bookId)
        if (existing == null) {
            insert(state)
        } else {
            require(state.completedAt >= existing.completedAt) {
                "Search backfill completion time cannot move backwards."
            }
            check(update(state.bookId, state.indexSchemaVersion, state.completedAt) == 1) {
                "Search backfill marker changed during update."
            }
        }
    }
}
