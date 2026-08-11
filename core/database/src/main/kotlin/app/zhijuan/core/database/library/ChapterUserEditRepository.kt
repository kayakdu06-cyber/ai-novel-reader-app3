package app.zhijuan.core.database.library

import androidx.room.withTransaction
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.database.memory.StaleCascadeResult
import app.zhijuan.core.database.search.MemorySearchIndexWriterV1
import app.zhijuan.core.model.ChapterStatus
import app.zhijuan.core.model.ChapterVersionSource
import app.zhijuan.core.model.ConsistencyStatus
import java.security.MessageDigest

data class ChapterUserEditCommand(
    val bookId: String,
    val chapterId: String,
    val expectedCurrentVersionId: String,
    val newVersionId: String,
    val content: String,
    val editedAt: Long,
) {
    override fun toString(): String =
        "ChapterUserEditCommand(editedAt=$editedAt, identifiers=redacted, content=redacted)"
}

data class ChapterUserEditStaleCascade(
    val summaries: Int,
    val entityEvents: Int,
    val canonFacts: Int,
    val timelineEvents: Int,
    val foreshadows: Int,
    val trackingProjections: Int,
    val foreshadowProjectionRevisions: Int,
    val foreshadowTransitions: Int,
    val aggregateStates: Int,
    val futureContexts: Int,
    val futureReports: Int,
    val futureChapters: Int,
    /** Number of affected source identities invalidated; some identities may not have had an index row. */
    val searchIdentitiesInvalidated: Int,
)

class ChapterUserEditResult internal constructor(
    val chapterVersionId: String,
    val chapterId: String,
    val versionNo: Int,
    val replayed: Boolean,
    val isCurrentVersion: Boolean,
    val staleCascade: ChapterUserEditStaleCascade?,
) {
    override fun toString(): String =
        "ChapterUserEditResult(versionNo=$versionNo, replayed=$replayed, " +
            "isCurrentVersion=$isCurrentVersion, identifiers=redacted, evidence=redacted)"
}

/**
 * Publishes a user-edited body and invalidates every derived pointer to the replaced version in one transaction.
 * Production callers must use this boundary instead of the low-level fixture-oriented LibraryDao commit method.
 */
class ChapterUserEditRepository(private val database: ZhijuanDatabase) {
    suspend fun commit(command: ChapterUserEditCommand): ChapterUserEditResult {
        validate(command)
        val contentHash = sha256Utf8(command.content)
        return database.withTransaction {
            val library = database.libraryDao()
            val memory = database.memoryDao()
            val chapter = requireNotNull(library.findChapter(command.chapterId)) {
                "Chapter does not exist."
            }
            val book = requireNotNull(library.findBook(command.bookId)) {
                "Book does not exist."
            }
            require(chapter.bookId == book.bookId) { "Chapter belongs to another book." }
            require(book.archivedAt == null && book.deletedAt == null) {
                "Archived or deleted books cannot accept chapter edits."
            }

            val replacedVersion = requireNotNull(
                library.findChapterVersion(command.expectedCurrentVersionId),
            ) { "Expected current chapter version does not exist." }
            require(replacedVersion.chapterId == chapter.chapterId) {
                "Expected current chapter version belongs to another chapter."
            }

            val existing = library.findChapterVersion(command.newVersionId)
            if (existing != null) {
                require(
                    existing.chapterId == chapter.chapterId &&
                        existing.parentVersionId == replacedVersion.chapterVersionId &&
                        existing.source == ChapterVersionSource.USER_EDIT &&
                        existing.generationStageId == null &&
                        existing.modelSnapshotJson == null &&
                        existing.contentHash == contentHash &&
                        existing.content == command.content,
                ) { "User-edit version id is already bound to different content or provenance." }
                if (
                    chapter.currentVersionId == existing.chapterVersionId &&
                    chapter.status == ChapterStatus.EDITED &&
                    chapter.consistencyStatus == ConsistencyStatus.UNKNOWN
                ) {
                    return@withTransaction ChapterUserEditResult(
                        chapterVersionId = existing.chapterVersionId,
                        chapterId = chapter.chapterId,
                        versionNo = existing.versionNo,
                        replayed = true,
                        isCurrentVersion = true,
                        staleCascade = null,
                    )
                }
                throw StaleChapterVersionException(
                    "The user edit was committed previously but is no longer the current version.",
                )
            }

            if (chapter.currentVersionId != replacedVersion.chapterVersionId) {
                throw StaleChapterVersionException(
                    "Chapter changed after editing started; refusing to overwrite it.",
                )
            }
            require(
                chapter.status in setOf(
                    ChapterStatus.READY,
                    ChapterStatus.EDITED,
                    ChapterStatus.CONSISTENCY_UNKNOWN,
                ),
            ) { "Only a completed chapter can be edited." }
            require(command.editedAt >= chapter.updatedAt) {
                "Edit time precedes the current chapter state."
            }

            val affectedSearchIdentities = MemorySearchIndexWriterV1.identitiesForReplacedChapter(
                memory = memory,
                bookId = book.bookId,
                chapterVersionId = replacedVersion.chapterVersionId,
            )
            val stale = memory.markDerivedDataStaleForReplacedChapter(
                bookId = book.bookId,
                replacedChapterVersionId = replacedVersion.chapterVersionId,
                updatedAt = command.editedAt,
            )
            val newVersion = ChapterVersionEntity(
                chapterVersionId = command.newVersionId,
                chapterId = chapter.chapterId,
                versionNo = library.maximumVersionNumber(chapter.chapterId) + 1,
                content = command.content,
                characterCount = command.content.codePointCount(0, command.content.length),
                contentHash = contentHash,
                source = ChapterVersionSource.USER_EDIT,
                parentVersionId = replacedVersion.chapterVersionId,
                generationStageId = null,
                modelSnapshotJson = null,
                createdAt = command.editedAt,
            )
            library.insertChapterVersion(newVersion)
            if (
                library.compareAndSetUserEditedCurrentVersion(
                    chapterId = chapter.chapterId,
                    expectedCurrentVersionId = replacedVersion.chapterVersionId,
                    expectedStatus = chapter.status,
                    expectedConsistencyStatus = chapter.consistencyStatus,
                    newVersionId = newVersion.chapterVersionId,
                    newStatus = ChapterStatus.EDITED,
                    newConsistencyStatus = ConsistencyStatus.UNKNOWN,
                    updatedAt = command.editedAt,
                ) != 1
            ) {
                throw StaleChapterVersionException(
                    "Chapter changed while the user edit was committing; the transaction was rolled back.",
                )
            }
            database.memorySearchDao().deleteSources(affectedSearchIdentities)

            ChapterUserEditResult(
                chapterVersionId = newVersion.chapterVersionId,
                chapterId = chapter.chapterId,
                versionNo = newVersion.versionNo,
                replayed = false,
                isCurrentVersion = true,
                staleCascade = stale.toUserEditCascade(affectedSearchIdentities.size),
            )
        }
    }

    private fun validate(command: ChapterUserEditCommand) {
        require(
            IDENTIFIER.matches(command.bookId) &&
                IDENTIFIER.matches(command.chapterId) &&
                IDENTIFIER.matches(command.expectedCurrentVersionId) &&
                IDENTIFIER.matches(command.newVersionId),
        ) { "User-edit identifiers are invalid." }
        require(command.newVersionId != command.expectedCurrentVersionId) {
            "A user edit must create a new chapter version."
        }
        require(command.content.isNotBlank() && command.editedAt >= 0L) {
            "A user edit requires non-empty content and a valid time."
        }
        require(utf8Size(command.content) <= MAX_CONTENT_BYTES) {
            "Chapter content exceeds the edit limit."
        }
    }

    private fun sha256Utf8(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return try {
            MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
        } finally {
            bytes.fill(0)
        }
    }

    private fun utf8Size(value: String): Int {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return try {
            bytes.size
        } finally {
            bytes.fill(0)
        }
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private companion object {
        const val MAX_CONTENT_BYTES = 4 * 1_024 * 1_024
        val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
    }
}

private fun StaleCascadeResult.toUserEditCascade(
    searchIdentitiesInvalidated: Int,
) = ChapterUserEditStaleCascade(
    summaries = summaries,
    entityEvents = entityEvents,
    canonFacts = canonFacts,
    timelineEvents = timelineEvents,
    foreshadows = foreshadows,
    trackingProjections = trackingProjections,
    foreshadowProjectionRevisions = foreshadowProjectionRevisions,
    foreshadowTransitions = foreshadowTransitions,
    aggregateStates = aggregateStates,
    futureContexts = futureContexts,
    futureReports = futureReports,
    futureChapters = futureChapters,
    searchIdentitiesInvalidated = searchIdentitiesInvalidated,
)
