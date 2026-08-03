package app.zhijuan.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zhijuan.core.database.library.BookCreationSnapshotEntity
import app.zhijuan.core.database.library.BookCreationRepository
import app.zhijuan.core.database.library.BookEntity
import app.zhijuan.core.database.library.ChapterEntity
import app.zhijuan.core.database.library.ChapterVersionEntity
import app.zhijuan.core.database.library.CommitChapterVersionCommand
import app.zhijuan.core.database.library.LibraryDao
import app.zhijuan.core.database.library.StaleChapterVersionException
import app.zhijuan.core.model.BookLengthMode
import app.zhijuan.core.model.BookStatus
import app.zhijuan.core.model.ChapterStatus
import app.zhijuan.core.model.ChapterVersionSource
import app.zhijuan.core.model.ConsistencyStatus
import app.zhijuan.core.model.TitleSource
import app.zhijuan.core.security.AndroidKeystoreAesGcm
import app.zhijuan.core.security.DatabasePassphraseStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream

@RunWith(AndroidJUnit4::class)
class LibraryDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: ZhijuanDatabase
    private lateinit var dao: LibraryDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, ZhijuanDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(LibraryDatabaseGuards.callback)
            .build()
            .also { it.openHelper.writableDatabase }
        dao = database.libraryDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun createBookAndChapterEnforcesAtomicOwnershipAndStableOrder() = runBlocking {
        dao.createBook(snapshot(), book())
        dao.createChapter(chapter("chapter-1", 1))

        assertEquals(1, dao.bookCount())
        assertNotNull(dao.findCreationSnapshot("snapshot-1"))
        assertEquals(listOf("chapter-1"), dao.chaptersForBook(BOOK_ID).map { it.chapterId })

        expectFailure {
            dao.createChapter(chapter("chapter-duplicate-index", 1))
        }
        expectFailure {
            dao.createChapter(chapter("chapter-no-book", 2).copy(bookId = "missing-book"))
        }
        assertEquals(listOf("chapter-1"), dao.chaptersForBook(BOOK_ID).map { it.chapterId })

        val mismatchedSnapshot = snapshot(id = "snapshot-rollback")
        expectFailure {
            dao.createBook(
                mismatchedSnapshot,
                book(id = "book-rollback").copy(creationSnapshotId = "different-snapshot"),
            )
        }
        assertEquals(null, dao.findCreationSnapshot(mismatchedSnapshot.snapshotId))
    }

    @Test
    fun newBookLengthPolicyEnforcesMinimumsAndRollsBackInvalidSnapshots() = runBlocking {
        val validSelections = listOf(
            Triple(BookLengthMode.SHORT, 80, 80),
            Triple(BookLengthMode.MEDIUM, 300, 300),
            Triple(BookLengthMode.LONG, 301, 888),
        )
        validSelections.forEachIndexed { index, (mode, minimum, target) ->
            val snapshot = snapshot("snapshot-length-valid-$index")
            dao.createBook(
                snapshot,
                book("book-length-valid-$index").copy(
                    creationSnapshotId = snapshot.snapshotId,
                    lengthMode = mode,
                    minimumChapters = minimum,
                    targetChapters = target,
                ),
            )
            assertEquals(minimum, dao.findBook("book-length-valid-$index")?.minimumChapters)
        }

        val invalidSelections = listOf(
            Triple(BookLengthMode.SHORT, 80, 79),
            Triple(BookLengthMode.MEDIUM, 300, 299),
            Triple(BookLengthMode.LONG, 301, 300),
        )
        invalidSelections.forEachIndexed { index, (mode, minimum, target) ->
            val snapshot = snapshot("snapshot-length-invalid-$index")
            expectFailure {
                dao.createBook(
                    snapshot,
                    book("book-length-invalid-$index").copy(
                        creationSnapshotId = snapshot.snapshotId,
                        lengthMode = mode,
                        minimumChapters = minimum,
                        targetChapters = target,
                    ),
                )
            }
            assertEquals(null, dao.findCreationSnapshot(snapshot.snapshotId))
        }

        expectFailure {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE book SET target_chapters = 79 WHERE book_id = 'book-length-valid-0'",
            )
        }
        assertEquals(80, dao.findBook("book-length-valid-0")?.targetChapters)
    }

    @Test
    fun creationRepositoryReadsConfirmationSummaryFromCommittedSnapshot() = runBlocking {
        val repository = BookCreationRepository(database)
        val snapshot = snapshot("snapshot-confirmation").copy(
            modelPreferenceJson = "{\"connectionId\":\"connection-1\",\"modelId\":\"deepseek-chat\"}",
            contentHash = "a".repeat(64),
        )
        val book = book("book-confirmation").copy(
            creationSnapshotId = snapshot.snapshotId,
            lengthMode = BookLengthMode.MEDIUM,
            minimumChapters = 300,
            targetChapters = 300,
        )

        val committed = repository.create(snapshot, book)
        val reloaded = repository.findCreationSummary(book.bookId)

        assertEquals(book.bookId, committed.bookId)
        assertEquals(snapshot.snapshotId, committed.snapshotId)
        assertEquals(300, committed.minimumChapterCount)
        assertEquals(300, committed.targetChapterCount)
        assertEquals(snapshot.modelPreferenceJson, committed.modelPreferenceJson)
        assertEquals(snapshot.contentHash, committed.contentHash)
        assertEquals(committed, reloaded)
    }

    @Test
    fun versionCommitSwitchesCurrentAtomicallyAndKeepsHistory() = runBlocking {
        seedChapter()
        val first = dao.commitChapterVersion(
            command(
                id = "version-1",
                expected = null,
                content = "第一版正文🙂",
                hash = "hash-1",
                stageId = "stage-1",
            ),
        )

        assertEquals(6, first.characterCount)
        assertEquals(1, first.versionNo)
        assertEquals(null, first.parentVersionId)
        assertEquals("version-1", dao.findChapter(CHAPTER_ID)?.currentVersionId)
        assertEquals(ChapterStatus.READY, dao.findChapter(CHAPTER_ID)?.status)
        assertEquals(1, dao.findBook(BOOK_ID)?.completedChapterCount)

        val second = dao.commitChapterVersion(
            command(
                id = "version-2",
                expected = "version-1",
                content = "用户修改后的第二版",
                hash = "hash-2",
                stageId = null,
                source = ChapterVersionSource.USER_EDIT,
            ),
        )

        assertEquals(2, second.versionNo)
        assertEquals("version-1", second.parentVersionId)
        assertEquals("version-2", dao.findChapter(CHAPTER_ID)?.currentVersionId)
        assertEquals(ChapterStatus.READY, dao.findChapter(CHAPTER_ID)?.status)
        assertEquals(1, dao.findBook(BOOK_ID)?.completedChapterCount)
        assertEquals(
            listOf("version-1", "version-2"),
            dao.versionsForChapter(CHAPTER_ID).map { it.chapterVersionId },
        )
    }

    @Test
    fun staleWriterCannotOverwriteOrLeaveOrphanVersion() = runBlocking {
        seedChapter()
        dao.commitChapterVersion(command("version-1", null, "已提交正文", "hash-1", "stage-1"))

        val error = expectFailure {
            dao.commitChapterVersion(
                command("stale-version", null, "过期任务正文", "stale-hash", "stale-stage"),
            )
        }

        assertTrue(error is StaleChapterVersionException)
        assertEquals("version-1", dao.findChapter(CHAPTER_ID)?.currentVersionId)
        assertEquals(1, dao.versionCount(CHAPTER_ID))
    }

    @Test
    fun replayedGenerationCommitReturnsExistingVersionWithoutDuplication() = runBlocking {
        seedChapter()
        val original = command("version-1", null, "可幂等提交的正文", "same-hash", "same-stage")
        val first = dao.commitChapterVersion(original)
        val replay = dao.commitChapterVersion(original.copy(chapterVersionId = "ignored-replay-id"))

        assertEquals(first, replay)
        assertEquals(1, dao.versionCount(CHAPTER_ID))
        assertEquals(1, dao.findBook(BOOK_ID)?.completedChapterCount)
    }

    @Test
    fun immutableSnapshotAndVersionRejectRawUpdates() = runBlocking {
        seedChapter()
        dao.commitChapterVersion(command("version-1", null, "不可原地修改", "hash-1", "stage-1"))

        expectFailure {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE chapter_version SET content = '被覆盖' WHERE chapter_version_id = 'version-1'",
            )
        }
        expectFailure {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE book_creation_snapshot SET raw_input_json = '{}' WHERE snapshot_id = 'snapshot-1'",
            )
        }

        assertEquals("不可原地修改", dao.versionsForChapter(CHAPTER_ID).single().content)
        assertEquals("{\"idea\":\"fixture\"}", dao.findCreationSnapshot("snapshot-1")?.rawInputJson)
    }

    @Test
    fun compositeForeignKeysRejectCrossChapterCurrentAndParentVersions() = runBlocking {
        dao.createBook(snapshot(), book())
        dao.createChapter(chapter(CHAPTER_ID, 1))
        dao.createChapter(chapter("chapter-2", 2))
        dao.commitChapterVersion(command("version-1", null, "第一章版本", "hash-1", "stage-1"))

        expectFailure {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE chapter SET current_version_id = 'version-1' WHERE chapter_id = 'chapter-2'",
            )
        }
        expectFailure {
            dao.insertChapterVersion(
                ChapterVersionEntity(
                    chapterVersionId = "cross-parent",
                    chapterId = "chapter-2",
                    versionNo = 1,
                    content = "错误父链",
                    characterCount = 4,
                    contentHash = "cross-hash",
                    source = ChapterVersionSource.USER_EDIT,
                    parentVersionId = "version-1",
                    generationStageId = null,
                    modelSnapshotJson = null,
                    createdAt = 3L,
                ),
            )
        }
        assertEquals(null, dao.findChapter("chapter-2")?.currentVersionId)
        assertEquals(0, dao.versionCount("chapter-2"))
    }

    @Test
    fun coreContentPreventsCascadeDeletion() = runBlocking {
        seedChapter()

        expectFailure {
            database.openHelper.writableDatabase.execSQL("DELETE FROM book WHERE book_id = '$BOOK_ID'")
        }

        assertNotNull(dao.findBook(BOOK_ID))
        assertNotNull(dao.findChapter(CHAPTER_ID))
    }

    @Test
    fun bookBranchRequiresMatchingSourceBookAndChapterVersion() = runBlocking {
        seedChapter()
        dao.commitChapterVersion(command("version-1", null, "分支起点", "hash-1", "stage-1"))

        val branchSnapshot = snapshot("snapshot-branch")
        dao.createBook(
            branchSnapshot,
            book("book-branch").copy(
                creationSnapshotId = branchSnapshot.snapshotId,
                branchedFromBookId = BOOK_ID,
                branchedFromChapterVersionId = "version-1",
            ),
        )
        assertEquals(BOOK_ID, dao.findBook("book-branch")?.branchedFromBookId)
        assertEquals("version-1", dao.findBook("book-branch")?.branchedFromChapterVersionId)

        val invalidSnapshot = snapshot("snapshot-invalid-branch")
        expectFailure {
            dao.createBook(
                invalidSnapshot,
                book("book-invalid-branch").copy(
                    creationSnapshotId = invalidSnapshot.snapshotId,
                    branchedFromBookId = "missing-book",
                    branchedFromChapterVersionId = "version-1",
                ),
            )
        }
        assertEquals(null, dao.findCreationSnapshot(invalidSnapshot.snapshotId))
    }

    @Test
    fun productionFactoryEncryptsAndReopensFormalSchema() = runBlocking {
        val databaseName = "zhijuan-library-${System.nanoTime()}.db"
        val keyAlias = "app.zhijuan.reader.test.library.${System.nanoTime()}"
        val passphraseStore = DatabasePassphraseStore(context, AndroidKeystoreAesGcm(keyAlias))
        val envelopeFile = File(context.noBackupFilesDir, "security/database-passphrase.zjes")
        val novelCanary = "ZHIJUAN_FORMAL_NOVEL_PLAINTEXT_CANARY_015_长篇正文".toByteArray()
        context.deleteDatabase(databaseName)
        envelopeFile.delete()
        try {
            EncryptedZhijuanDatabaseFactory(context, passphraseStore).open(databaseName).use { handle ->
                val encryptedDao = handle.database.libraryDao()
                encryptedDao.createBook(snapshot(), book())
                encryptedDao.createChapter(chapter(CHAPTER_ID, 1))
                encryptedDao.commitChapterVersion(
                    command(
                        id = "encrypted-version-1",
                        expected = null,
                        content = novelCanary.toString(Charsets.UTF_8),
                        hash = "encrypted-novel-hash",
                        stageId = "encrypted-stage-1",
                    ),
                )
                databaseFiles(databaseName).forEach { file ->
                    assertFalse(
                        "Formal novel plaintext found in open database file ${file.name}",
                        file.readBytes().containsSubsequence(novelCanary),
                    )
                }
            }

            val databaseFile = context.getDatabasePath(databaseName)
            val header = ByteArray(16)
            FileInputStream(databaseFile).use { input ->
                assertEquals(header.size, input.read(header))
            }
            assertFalse(header.contentEquals("SQLite format 3\u0000".toByteArray()))
            assertFalse(databaseFile.readBytes().containsSubsequence("fixture".toByteArray()))
            databaseFiles(databaseName).forEach { file ->
                assertFalse(
                    "Formal novel plaintext found after close in ${file.name}",
                    file.readBytes().containsSubsequence(novelCanary),
                )
            }

            EncryptedZhijuanDatabaseFactory(context, passphraseStore).open(databaseName).use { handle ->
                assertEquals(1, handle.database.libraryDao().bookCount())
                assertEquals(
                    novelCanary.toString(Charsets.UTF_8),
                    handle.database.libraryDao().versionsForChapter(CHAPTER_ID).single().content,
                )
            }
        } finally {
            novelCanary.fill(0)
            context.deleteDatabase(databaseName)
            AndroidKeystoreAesGcm(keyAlias).deleteKey()
            envelopeFile.delete()
        }
    }

    private fun databaseFiles(databaseName: String): List<File> =
        context.getDatabasePath(databaseName).parentFile
            ?.listFiles()
            ?.filter { file -> file.name == databaseName || file.name.startsWith("$databaseName-") }
            .orEmpty()

    private suspend fun seedChapter() {
        dao.createBook(snapshot(), book())
        dao.createChapter(chapter(CHAPTER_ID, 1))
    }

    private fun snapshot(id: String = "snapshot-1") = BookCreationSnapshotEntity(
        snapshotId = id,
        rawInputJson = "{\"idea\":\"fixture\"}",
        normalizedInputJson = "{}",
        inferenceProvenanceJson = "{}",
        genrePayloadJson = "{}",
        presentationProfileJson = "{}",
        modelPreferenceJson = "{}",
        schemaVersion = 1,
        promptBundleVersion = "prompt-1",
        contentControlSchemaVersion = 1,
        contentHash = "snapshot-hash-$id",
        createdAt = 1L,
    )

    private fun book(id: String = BOOK_ID) = BookEntity(
        bookId = id,
        creationSnapshotId = "snapshot-1",
        title = "测试书",
        titleSource = TitleSource.USER,
        status = BookStatus.DRAFT,
        lengthMode = BookLengthMode.LONG,
        targetCharacters = 500_000,
        targetChapters = 500,
        minimumChapters = 301,
        lengthPolicySchemaVersion = 1,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun chapter(id: String, index: Int) = ChapterEntity(
        chapterId = id,
        bookId = BOOK_ID,
        chapterIndex = index,
        plannedTitle = "第${index}章",
        displayTitle = "第${index}章",
        status = ChapterStatus.PLANNED,
        consistencyStatus = ConsistencyStatus.UNKNOWN,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun command(
        id: String,
        expected: String?,
        content: String,
        hash: String,
        stageId: String?,
        source: ChapterVersionSource = ChapterVersionSource.AI_GENERATED,
    ) = CommitChapterVersionCommand(
        chapterVersionId = id,
        chapterId = CHAPTER_ID,
        expectedCurrentVersionId = expected,
        content = content,
        contentHash = hash,
        source = source,
        generationStageId = stageId,
        modelSnapshotJson = if (stageId == null) null else "{\"model\":\"fixture\"}",
        createdAt = 2L,
    )

    private suspend fun expectFailure(block: suspend () -> Unit): Throwable = try {
        block()
        throw AssertionError("Expected operation to fail.")
    } catch (error: Throwable) {
        if (error is AssertionError && error.message == "Expected operation to fail.") throw error
        error
    }

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        return indices.any { start ->
            start + needle.size <= size && needle.indices.all { offset ->
                this[start + offset] == needle[offset]
            }
        }
    }

    private companion object {
        const val BOOK_ID = "book-1"
        const val CHAPTER_ID = "chapter-1"
    }
}
