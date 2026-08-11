package app.zhijuan.core.database.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.zhijuan.core.model.ChapterStatus
import app.zhijuan.core.model.ChapterVersionSource
import app.zhijuan.core.model.BookLengthPolicy
import app.zhijuan.core.model.BookStatus
import app.zhijuan.core.model.ConsistencyStatus

internal data class CommitChapterVersionCommand(
    val chapterVersionId: String,
    val chapterId: String,
    val expectedCurrentVersionId: String?,
    val content: String,
    val contentHash: String,
    val source: ChapterVersionSource,
    val generationStageId: String?,
    val modelSnapshotJson: String?,
    val createdAt: Long,
)

internal data class CurrentChapterVersionSnapshot(
    val chapterId: String,
    val chapterIndex: Int,
    val chapterStatus: ChapterStatus,
    val consistencyStatus: ConsistencyStatus,
    val chapterVersionId: String,
    val contentHash: String,
)

internal class StaleChapterVersionException(message: String) : IllegalStateException(message)

@Dao
internal interface LibraryDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCreationSnapshot(snapshot: BookCreationSnapshotEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBook(book: BookEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertChapter(chapter: ChapterEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertChapterVersion(version: ChapterVersionEntity)

    @Query("SELECT * FROM book WHERE book_id = :bookId")
    suspend fun findBook(bookId: String): BookEntity?

    @Query("SELECT * FROM book_creation_snapshot WHERE snapshot_id = :snapshotId")
    suspend fun findCreationSnapshot(snapshotId: String): BookCreationSnapshotEntity?

    @Query("SELECT * FROM chapter WHERE chapter_id = :chapterId")
    suspend fun findChapter(chapterId: String): ChapterEntity?

    @Query("SELECT * FROM chapter_version WHERE chapter_version_id = :chapterVersionId")
    suspend fun findChapterVersion(chapterVersionId: String): ChapterVersionEntity?

    @Query(
        """
        SELECT chapter.book_id
        FROM chapter_version
        INNER JOIN chapter ON chapter.chapter_id = chapter_version.chapter_id
        WHERE chapter_version.chapter_version_id = :chapterVersionId
        """,
    )
    suspend fun findBookIdForChapterVersion(chapterVersionId: String): String?

    @Query(
        """
        SELECT * FROM chapter
        WHERE book_id = :bookId
        ORDER BY chapter_index ASC
        """,
    )
    suspend fun chaptersForBook(bookId: String): List<ChapterEntity>

    @Query(
        """
        SELECT chapter.chapter_id AS chapterId,
               chapter.chapter_index AS chapterIndex,
               chapter.status AS chapterStatus,
               chapter.consistency_status AS consistencyStatus,
               chapter_version.chapter_version_id AS chapterVersionId,
               chapter_version.content_hash AS contentHash
        FROM chapter
        INNER JOIN chapter_version
          ON chapter_version.chapter_id = chapter.chapter_id
         AND chapter_version.chapter_version_id = chapter.current_version_id
        WHERE chapter.book_id = :bookId
        ORDER BY chapter.chapter_index ASC
        """,
    )
    suspend fun currentChapterVersionSnapshotsForBook(bookId: String): List<CurrentChapterVersionSnapshot>

    @Query(
        """
        SELECT * FROM chapter_version
        WHERE chapter_id = :chapterId
        ORDER BY version_no ASC
        """,
    )
    suspend fun versionsForChapter(chapterId: String): List<ChapterVersionEntity>

    @Query("SELECT COALESCE(MAX(version_no), 0) FROM chapter_version WHERE chapter_id = :chapterId")
    suspend fun maximumVersionNumber(chapterId: String): Int

    @Query(
        """
        SELECT * FROM chapter_version
        WHERE generation_stage_id = :generationStageId
          AND content_hash = :contentHash
        LIMIT 1
        """,
    )
    suspend fun findCommittedGeneration(
        generationStageId: String,
        contentHash: String,
    ): ChapterVersionEntity?

    @Query("SELECT * FROM chapter_version WHERE generation_stage_id = :generationStageId")
    suspend fun versionsForGenerationStage(generationStageId: String): List<ChapterVersionEntity>

    @Query(
        """
        UPDATE chapter
        SET current_version_id = :newVersionId,
            status = :status,
            updated_at = :updatedAt
        WHERE chapter_id = :chapterId
          AND (
              (:expectedCurrentVersionId IS NULL AND current_version_id IS NULL)
              OR current_version_id = :expectedCurrentVersionId
          )
        """,
    )
    suspend fun compareAndSetCurrentVersion(
        chapterId: String,
        expectedCurrentVersionId: String?,
        newVersionId: String,
        status: ChapterStatus,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE chapter
        SET current_version_id = :newVersionId,
            status = :status,
            consistency_status = :consistencyStatus,
            updated_at = :updatedAt
        WHERE chapter_id = :chapterId
          AND updated_at <= :updatedAt
          AND (
              (:expectedCurrentVersionId IS NULL AND current_version_id IS NULL)
              OR current_version_id = :expectedCurrentVersionId
          )
        """,
    )
    suspend fun compareAndSetGeneratedCurrentVersion(
        chapterId: String,
        expectedCurrentVersionId: String?,
        newVersionId: String,
        status: ChapterStatus,
        consistencyStatus: ConsistencyStatus,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE chapter
        SET current_version_id = :newVersionId,
            status = :newStatus,
            consistency_status = :newConsistencyStatus,
            updated_at = :updatedAt
        WHERE chapter_id = :chapterId
          AND current_version_id = :expectedCurrentVersionId
          AND status = :expectedStatus
          AND consistency_status = :expectedConsistencyStatus
          AND updated_at <= :updatedAt
        """,
    )
    suspend fun compareAndSetUserEditedCurrentVersion(
        chapterId: String,
        expectedCurrentVersionId: String,
        expectedStatus: ChapterStatus,
        expectedConsistencyStatus: ConsistencyStatus,
        newVersionId: String,
        newStatus: ChapterStatus,
        newConsistencyStatus: ConsistencyStatus,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE book
        SET completed_chapter_count = completed_chapter_count + 1,
            updated_at = :updatedAt
        WHERE book_id = :bookId
        """,
    )
    suspend fun incrementCompletedChapterCount(bookId: String, updatedAt: Long): Int

    @Query(
        """
        UPDATE book
        SET completed_chapter_count = completed_chapter_count + :completedChapterIncrement,
            status = :status,
            generation_status_summary = :generationStatusSummary,
            updated_at = :updatedAt
        WHERE book_id = :bookId
          AND updated_at <= :updatedAt
        """,
    )
    suspend fun updateBookAfterGeneratedChapter(
        bookId: String,
        completedChapterIncrement: Int,
        status: BookStatus,
        generationStatusSummary: String,
        updatedAt: Long,
    ): Int

    @Query("SELECT COUNT(*) FROM book")
    suspend fun bookCount(): Long

    @Query("SELECT COUNT(*) FROM chapter_version WHERE chapter_id = :chapterId")
    suspend fun versionCount(chapterId: String): Long

    @Transaction
    suspend fun createBook(
        snapshot: BookCreationSnapshotEntity,
        book: BookEntity,
    ) {
        require(snapshot.snapshotId.isNotBlank()) { "Snapshot id must not be blank." }
        require(book.bookId.isNotBlank()) { "Book id must not be blank." }
        require(book.creationSnapshotId == snapshot.snapshotId) {
            "Book must reference the snapshot created in the same transaction."
        }
        require(book.title.isNotBlank()) { "Book title must not be blank." }
        require(book.targetCharacters == null || book.targetCharacters > 0) {
            "Target characters must be positive when present."
        }
        require(book.targetChapters == null || book.targetChapters > 0) {
            "Target chapters must be positive when present."
        }
        requireNotNull(book.targetChapters) { "A new book must have a chapter target." }
        BookLengthPolicy.requireValidSelection(
            mode = book.lengthMode,
            minimumChapterCount = book.minimumChapters,
            targetChapterCount = book.targetChapters,
            schemaVersion = book.lengthPolicySchemaVersion,
        )
        require(snapshot.schemaVersion > 0) { "Snapshot schema version must be positive." }
        require(snapshot.contentControlSchemaVersion > 0) {
            "Content-control schema version must be positive."
        }
        require(snapshot.contentHash.isNotBlank()) { "Snapshot content hash must not be blank." }
        val branchBookId = book.branchedFromBookId
        val branchVersionId = book.branchedFromChapterVersionId
        require((branchBookId == null) == (branchVersionId == null)) {
            "A book branch must identify both its source book and source chapter version."
        }
        if (branchBookId != null && branchVersionId != null) {
            require(branchBookId != book.bookId) { "A book cannot branch from itself." }
            require(findBookIdForChapterVersion(branchVersionId) == branchBookId) {
                "The branch chapter version does not belong to the declared source book."
            }
        }

        insertCreationSnapshot(snapshot)
        insertBook(book)
    }

    @Transaction
    suspend fun createChapter(chapter: ChapterEntity) {
        require(chapter.chapterId.isNotBlank()) { "Chapter id must not be blank." }
        require(chapter.bookId.isNotBlank()) { "Book id must not be blank." }
        require(chapter.chapterIndex > 0) { "Chapter index must be positive." }
        require(chapter.currentVersionId == null) {
            "A new chapter cannot point at a version that has not been committed."
        }
        insertChapter(chapter)
    }

    @Transaction
    suspend fun commitChapterVersion(command: CommitChapterVersionCommand): ChapterVersionEntity {
        require(command.chapterVersionId.isNotBlank()) { "Chapter version id must not be blank." }
        require(command.chapterId.isNotBlank()) { "Chapter id must not be blank." }
        require(command.content.isNotBlank()) { "Chapter content must not be blank." }
        require(command.contentHash.isNotBlank()) { "Content hash must not be blank." }
        require(command.generationStageId?.isNotBlank() != false) {
            "Generation stage id must be null or non-blank."
        }

        val chapter = requireNotNull(findChapter(command.chapterId)) {
            "Chapter ${command.chapterId} does not exist."
        }

        val existing = command.generationStageId?.let { stageId ->
            findCommittedGeneration(stageId, command.contentHash)
        }
        if (existing != null) {
            if (existing.chapterId == command.chapterId && chapter.currentVersionId == existing.chapterVersionId) {
                return existing
            }
            throw StaleChapterVersionException(
                "The generation output was already committed but is no longer the current chapter version.",
            )
        }

        if (chapter.currentVersionId != command.expectedCurrentVersionId) {
            throw StaleChapterVersionException(
                "Chapter changed after this edit or generation started; refusing to overwrite it.",
            )
        }

        val version = ChapterVersionEntity(
            chapterVersionId = command.chapterVersionId,
            chapterId = command.chapterId,
            versionNo = maximumVersionNumber(command.chapterId) + 1,
            content = command.content,
            characterCount = command.content.codePointCount(0, command.content.length),
            contentHash = command.contentHash,
            source = command.source,
            parentVersionId = command.expectedCurrentVersionId,
            generationStageId = command.generationStageId,
            modelSnapshotJson = command.modelSnapshotJson,
            createdAt = command.createdAt,
        )
        insertChapterVersion(version)

        val switched = compareAndSetCurrentVersion(
            chapterId = command.chapterId,
            expectedCurrentVersionId = command.expectedCurrentVersionId,
            newVersionId = command.chapterVersionId,
            status = ChapterStatus.READY,
            updatedAt = command.createdAt,
        )
        if (switched != 1) {
            throw StaleChapterVersionException(
                "Chapter changed while committing; the transaction will be rolled back.",
            )
        }

        if (command.expectedCurrentVersionId == null) {
            check(incrementCompletedChapterCount(chapter.bookId, command.createdAt) == 1) {
                "Owning book disappeared while committing a chapter."
            }
        }
        return version
    }
}
