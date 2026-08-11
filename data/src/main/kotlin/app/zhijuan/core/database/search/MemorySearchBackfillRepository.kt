package app.zhijuan.core.database.search

import androidx.room.withTransaction
import app.zhijuan.core.database.ZhijuanDatabase

internal enum class MemorySearchBackfillDispositionV1 {
    ALREADY_READY,
    REBUILT,
}

internal data class MemorySearchBackfillResultV1(
    val disposition: MemorySearchBackfillDispositionV1,
    val indexedDocumentCount: Long,
)

internal class MemorySearchBackfillRepositoryV1(
    private val database: ZhijuanDatabase,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) {
    init {
        require(pageSize in 1..MAXIMUM_PAGE_SIZE) { "Search backfill page size is invalid." }
    }

    suspend fun ensureReady(
        bookId: String,
        completedAt: Long,
    ): MemorySearchBackfillResultV1 {
        require(bookId.isNotBlank()) { "Search backfill book identity is invalid." }
        require(completedAt >= 0L) { "Search backfill completion time is invalid." }
        return database.withTransaction {
            requireNotNull(database.libraryDao().findBook(bookId)) { "Search backfill book does not exist." }
            val markerDao = database.memorySearchBackfillStateDao()
            val marker = markerDao.find(bookId)
            if (marker != null) {
                require(marker.indexSchemaVersion <= INDEX_SCHEMA_VERSION) {
                    "Search backfill marker uses a newer unsupported schema."
                }
                if (marker.indexSchemaVersion == INDEX_SCHEMA_VERSION) {
                    return@withTransaction MemorySearchBackfillResultV1(
                        disposition = MemorySearchBackfillDispositionV1.ALREADY_READY,
                        indexedDocumentCount = database.memorySearchDao().countByBook(bookId),
                    )
                }
            }

            rebuildLocked(bookId, completedAt)
        }
    }

    suspend fun rebuild(
        bookId: String,
        completedAt: Long,
    ): MemorySearchBackfillResultV1 {
        require(bookId.isNotBlank()) { "Search backfill book identity is invalid." }
        require(completedAt >= 0L) { "Search backfill completion time is invalid." }
        return database.withTransaction {
            requireNotNull(database.libraryDao().findBook(bookId)) { "Search backfill book does not exist." }
            rebuildLocked(bookId, completedAt)
        }
    }

    private suspend fun rebuildLocked(
        bookId: String,
        completedAt: Long,
    ): MemorySearchBackfillResultV1 {
        val memory = database.memoryDao()
        val search = database.memorySearchDao()
        search.deleteByBook(bookId)
        var indexed = 0L

        indexed += page(
            load = { afterId -> memory.pageStoryEntitiesForSearchBackfill(bookId, afterId, pageSize) },
            idOf = { it.entityId },
            transform = MemorySearchDocumentFactoryV1::from,
        )
        indexed += page(
            load = { afterId -> memory.pageChapterSummariesForSearchBackfill(bookId, afterId, pageSize) },
            idOf = { it.chapterSummaryId },
            transform = MemorySearchDocumentFactoryV1::from,
        )
        indexed += page(
            load = { afterId -> memory.pageEntityEventsForSearchBackfill(bookId, afterId, pageSize) },
            idOf = { it.entityEvent.entityEventId },
            transform = { MemorySearchDocumentFactoryV1.from(it.entityEvent, it.chapterIndex) },
        )
        indexed += page(
            load = { afterId -> memory.pageCanonFactsForSearchBackfill(bookId, afterId, pageSize) },
            idOf = { it.canonFact.canonFactId },
            transform = { MemorySearchDocumentFactoryV1.from(it.canonFact, it.chapterIndex) },
        )
        indexed += page(
            load = { afterId -> memory.pageTimelineEventsForSearchBackfill(bookId, afterId, pageSize) },
            idOf = { it.timelineEvent.timelineEventId },
            transform = { MemorySearchDocumentFactoryV1.from(it.timelineEvent, it.chapterIndex) },
        )
        indexed += page(
            load = { afterId -> memory.pageForeshadowsForSearchBackfill(bookId, afterId, pageSize) },
            idOf = { it.foreshadow.foreshadowItemId },
            transform = { MemorySearchDocumentFactoryV1.from(it.foreshadow, it.chapterIndex) },
        )

        check(search.countByBook(bookId) == indexed) {
            "Search backfill document count is inconsistent."
        }
        database.memorySearchBackfillStateDao().store(
            MemorySearchBackfillStateEntity(
                bookId = bookId,
                indexSchemaVersion = INDEX_SCHEMA_VERSION,
                completedAt = completedAt,
            ),
        )
        return MemorySearchBackfillResultV1(MemorySearchBackfillDispositionV1.REBUILT, indexed)
    }

    private suspend fun <T> page(
        load: suspend (afterId: String?) -> List<T>,
        idOf: (T) -> String,
        transform: (T) -> MemorySearchDocumentEntity?,
    ): Long {
        var afterId: String? = null
        var indexed = 0L
        while (true) {
            val rows = load(afterId)
            require(rows.size <= pageSize) { "Search backfill page exceeded its fixed limit." }
            if (rows.isEmpty()) return indexed
            val ids = rows.map(idOf)
            require(ids.zipWithNext().all { (left, right) -> left < right }) {
                "Search backfill page is not in stable keyset order."
            }
            afterId?.let { previous ->
                require(ids.first() > previous) { "Search backfill keyset did not advance." }
            }
            val documents = rows.mapNotNull(transform)
            database.memorySearchDao().replaceAll(documents)
            indexed = Math.addExact(indexed, documents.size.toLong())
            afterId = ids.last()
            if (rows.size < pageSize) return indexed
        }
    }

    private companion object {
        // v2 encodes CJK bigrams as one tokenizer-safe alphanumeric FTS term.
        const val INDEX_SCHEMA_VERSION = 2
        const val DEFAULT_PAGE_SIZE = 250
        const val MAXIMUM_PAGE_SIZE = 500
    }
}
