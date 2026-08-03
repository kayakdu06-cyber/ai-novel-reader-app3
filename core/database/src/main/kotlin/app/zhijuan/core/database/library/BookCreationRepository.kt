package app.zhijuan.core.database.library

import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.model.BookLengthMode

data class StoredBookCreationSummary(
    val bookId: String,
    val snapshotId: String,
    val title: String,
    val lengthMode: BookLengthMode,
    val minimumChapterCount: Int,
    val targetChapterCount: Int,
    val lengthPolicySchemaVersion: Int,
    val modelPreferenceJson: String,
    val contentHash: String,
)

class BookCreationRepository(
    private val database: ZhijuanDatabase,
) {
    suspend fun create(
        snapshot: BookCreationSnapshotEntity,
        book: BookEntity,
    ): StoredBookCreationSummary {
        require(snapshot.rawInputJson.isNotBlank())
        require(snapshot.normalizedInputJson.isNotBlank())
        require(snapshot.inferenceProvenanceJson.isNotBlank())
        require(snapshot.genrePayloadJson.isNotBlank())
        require(snapshot.presentationProfileJson.isNotBlank())
        require(snapshot.modelPreferenceJson.isNotBlank())
        require(snapshot.contentHash.matches(SHA_256_HEX)) {
            "A creation snapshot must use a SHA-256 content hash."
        }
        database.libraryDao().createBook(snapshot, book)
        return requireNotNull(findCreationSummary(book.bookId)) {
            "The committed creation snapshot could not be read back."
        }
    }

    suspend fun findCreationSummary(bookId: String): StoredBookCreationSummary? {
        if (bookId.isBlank()) return null
        val dao = database.libraryDao()
        val book = dao.findBook(bookId) ?: return null
        val snapshot = dao.findCreationSnapshot(book.creationSnapshotId) ?: return null
        val targetChapterCount = book.targetChapters ?: return null
        if (!snapshot.contentHash.matches(SHA_256_HEX)) return null
        return StoredBookCreationSummary(
            bookId = book.bookId,
            snapshotId = snapshot.snapshotId,
            title = book.title,
            lengthMode = book.lengthMode,
            minimumChapterCount = book.minimumChapters,
            targetChapterCount = targetChapterCount,
            lengthPolicySchemaVersion = book.lengthPolicySchemaVersion,
            modelPreferenceJson = snapshot.modelPreferenceJson,
            contentHash = snapshot.contentHash,
        )
    }

    private companion object {
        val SHA_256_HEX = Regex("[0-9a-f]{64}")
    }
}
