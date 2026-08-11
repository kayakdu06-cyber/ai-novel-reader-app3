package app.zhijuan.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zhijuan.core.database.generation.ForeshadowProjectionRevisionWriterV1
import app.zhijuan.core.database.generation.GenerationJobEntity
import app.zhijuan.core.database.generation.GenerationStageEntity
import app.zhijuan.core.database.library.BookCreationSnapshotEntity
import app.zhijuan.core.database.library.BookEntity
import app.zhijuan.core.database.library.ChapterEditRebuildPlanRepository
import app.zhijuan.core.database.library.ChapterEditRebuildPlanRequest
import app.zhijuan.core.database.library.ChapterEntity
import app.zhijuan.core.database.library.ChapterUserEditCommand
import app.zhijuan.core.database.library.ChapterUserEditRepository
import app.zhijuan.core.database.library.CommitChapterVersionCommand
import app.zhijuan.core.database.library.ForeshadowProjectionRewindCommand
import app.zhijuan.core.database.library.ForeshadowProjectionRewindRepository
import app.zhijuan.core.database.memory.ForeshadowItemEntity
import app.zhijuan.core.database.memory.ForeshadowTransitionEntity
import app.zhijuan.core.database.search.MemorySearchIndexWriterV1
import app.zhijuan.core.database.search.MemorySearchSourceTypeV1
import app.zhijuan.core.model.BookLengthMode
import app.zhijuan.core.model.BookStatus
import app.zhijuan.core.model.ChapterStatus
import app.zhijuan.core.model.ChapterVersionSource
import app.zhijuan.core.model.ConsistencyStatus
import app.zhijuan.core.model.DerivedDataStatus
import app.zhijuan.core.model.ForeshadowStatus
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationJobType
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.model.MemorySource
import app.zhijuan.core.model.TitleSource
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ForeshadowProjectionRewindDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: ZhijuanDatabase

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, ZhijuanDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(LibraryDatabaseGuards.callback)
            .build()
            .also { it.openHelper.writableDatabase }
        createBook()
        seedCommittedChapters(3)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun rewindRestoresTrustedBaselineStalesRangeAndReplaysExactly() = runBlocking {
        val baselineA = seedRevisionBackedHistory()
        editSecondChapter()
        val plan = ChapterEditRebuildPlanRepository(database).plan(planRequest())
        val repository = ForeshadowProjectionRewindRepository(database)
        val command = ForeshadowProjectionRewindCommand(plan, REWIND_ID, 120)

        val result = repository.rewind(command)

        assertFalse(result.replayed)
        assertEquals(2, result.affectedItemCount)
        assertEquals(1, result.baselineItemCount)
        assertEquals(1, result.absentItemCount)
        assertEquals(2, result.staleRevisionCount)
        assertEquals(2, result.staleTransitionCount)
        assertEquals(baselineA, database.memoryDao().findForeshadow(ITEM_A))
        assertEquals(DerivedDataStatus.STALE, database.memoryDao().findForeshadow(ITEM_B)?.memoryStatus)
        assertEquals(120L, database.memoryDao().findForeshadow(ITEM_B)?.updatedAt)
        assertEquals(
            listOf(DerivedDataStatus.VALID, DerivedDataStatus.STALE, DerivedDataStatus.STALE),
            projectionRevisionStatuses(ITEM_A),
        )
        assertEquals(
            listOf(DerivedDataStatus.VALID, DerivedDataStatus.STALE, DerivedDataStatus.STALE),
            transitionStatuses(ITEM_A),
        )
        assertNotNull(
            database.memorySearchDao().findBySource(
                BOOK_ID,
                MemorySearchSourceTypeV1.FORESHADOW.name,
                ITEM_A,
            ),
        )
        assertNull(
            database.memorySearchDao().findBySource(
                BOOK_ID,
                MemorySearchSourceTypeV1.FORESHADOW.name,
                ITEM_B,
            ),
        )
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM foreshadow_projection_rewind"))

        val replay = repository.rewind(command)
        assertTrue(replay.replayed)
        assertEquals(result.affectedItemCount, replay.affectedItemCount)
        assertEquals(result.staleRevisionCount, replay.staleRevisionCount)
        val conflictingId = expectFailure {
            repository.rewind(command.copy(rewindId = "rewind-conflict"))
        }
        assertTrue(conflictingId is IllegalArgumentException)
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM foreshadow_projection_rewind"))
        assertFalse(result.toString().contains(REWIND_ID))
        assertFalse(command.toString().contains(plan.planHash))
    }

    @Test
    fun legacyDevelopWithoutTrustedPreEditRevisionFailsWithoutPartialWrites() = runBlocking {
        val memory = database.memoryDao()
        val original = foreshadow(
            id = ITEM_A,
            status = ForeshadowStatus.PLANTED,
            sourceVersionId = versionId(1),
            plantedVersionId = versionId(1),
            resolvedVersionId = null,
            importance = 70,
            createdAt = 10,
            updatedAt = 10,
        )
        memory.insertForeshadows(listOf(original))
        createStage(3)
        check(
            memory.compareAndTransitionForeshadow(
                foreshadowItemId = ITEM_A,
                bookId = BOOK_ID,
                fromStatus = ForeshadowStatus.PLANTED.name,
                toStatus = ForeshadowStatus.DEVELOPING.name,
                sourceChapterVersionId = versionId(3),
                resolvedChapterVersionId = null,
                visibleEntityIdsJson = "[]",
                importance = 75,
                updatedAt = 30,
            ) == 1,
        )
        val legacyTransition = transition(
            id = "legacy-develop",
            itemId = ITEM_A,
            chapterIndex = 3,
            operation = "DEVELOP",
            from = ForeshadowStatus.PLANTED,
            to = ForeshadowStatus.DEVELOPING,
            createdAt = 30,
        )
        memory.insertForeshadowTransitions(listOf(legacyTransition))
        val currentBeforeEdit = requireNotNull(memory.findForeshadow(ITEM_A))
        editSecondChapter()
        val currentBeforeRewind = requireNotNull(memory.findForeshadow(ITEM_A))
        assertEquals(currentBeforeEdit, currentBeforeRewind)
        val plan = ChapterEditRebuildPlanRepository(database).plan(planRequest())

        val error = expectFailure {
            ForeshadowProjectionRewindRepository(database).rewind(
                ForeshadowProjectionRewindCommand(plan, "legacy-rewind", 120),
            )
        }

        assertTrue(error is IllegalArgumentException)
        assertEquals(currentBeforeRewind, memory.findForeshadow(ITEM_A))
        assertEquals(DerivedDataStatus.VALID, transitionStatuses(ITEM_A).single())
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM foreshadow_projection_rewind"))
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM foreshadow_projection_revision"))
    }

    @Test
    fun itemAlreadyStaledByEditKeepsItsOriginalStaleTimestamp() = runBlocking {
        val memory = database.memoryDao()
        createStage(2)
        val item = foreshadow(
            id = ITEM_A,
            status = ForeshadowStatus.PLANTED,
            sourceVersionId = versionId(2),
            plantedVersionId = versionId(2),
            resolvedVersionId = null,
            importance = 70,
            createdAt = 20,
            updatedAt = 20,
        )
        val planted = transition(
            id = "transition-edit-plant",
            itemId = ITEM_A,
            chapterIndex = 2,
            operation = "PLANT",
            from = null,
            to = ForeshadowStatus.PLANTED,
            createdAt = 20,
        )
        memory.insertForeshadows(listOf(item))
        memory.insertForeshadowTransitions(listOf(planted))
        captureRevision(2, listOf(planted))
        editSecondChapter()
        assertEquals(100L, memory.findForeshadow(ITEM_A)?.updatedAt)
        val plan = ChapterEditRebuildPlanRepository(database).plan(planRequest())

        val result = ForeshadowProjectionRewindRepository(database).rewind(
            ForeshadowProjectionRewindCommand(plan, "already-stale-rewind", 120),
        )

        assertFalse(result.replayed)
        assertEquals(1, result.affectedItemCount)
        assertEquals(0, result.baselineItemCount)
        assertEquals(1, result.absentItemCount)
        assertEquals(0, result.staleRevisionCount)
        assertEquals(0, result.staleTransitionCount)
        assertEquals(DerivedDataStatus.STALE, memory.findForeshadow(ITEM_A)?.memoryStatus)
        assertEquals(100L, memory.findForeshadow(ITEM_A)?.updatedAt)
    }

    private suspend fun seedRevisionBackedHistory(): ForeshadowItemEntity {
        val memory = database.memoryDao()
        createStage(1)
        createStage(2)
        createStage(3)
        val baseline = foreshadow(
            id = ITEM_A,
            status = ForeshadowStatus.PLANTED,
            sourceVersionId = versionId(1),
            plantedVersionId = versionId(1),
            resolvedVersionId = null,
            importance = 70,
            createdAt = 10,
            updatedAt = 10,
        )
        val plantedA = transition(
            id = "transition-a-1",
            itemId = ITEM_A,
            chapterIndex = 1,
            operation = "PLANT",
            from = null,
            to = ForeshadowStatus.PLANTED,
            createdAt = 10,
        )
        memory.insertForeshadows(listOf(baseline))
        memory.insertForeshadowTransitions(listOf(plantedA))
        captureRevision(1, listOf(plantedA))

        check(
            memory.compareAndTransitionForeshadow(
                ITEM_A,
                BOOK_ID,
                ForeshadowStatus.PLANTED.name,
                ForeshadowStatus.DEVELOPING.name,
                versionId(2),
                null,
                "[]",
                80,
                20,
            ) == 1,
        )
        val developedA = transition(
            id = "transition-a-2",
            itemId = ITEM_A,
            chapterIndex = 2,
            operation = "DEVELOP",
            from = ForeshadowStatus.PLANTED,
            to = ForeshadowStatus.DEVELOPING,
            createdAt = 20,
        )
        memory.insertForeshadowTransitions(listOf(developedA))
        captureRevision(2, listOf(developedA))

        check(
            memory.compareAndTransitionForeshadow(
                ITEM_A,
                BOOK_ID,
                ForeshadowStatus.DEVELOPING.name,
                ForeshadowStatus.RESOLVED.name,
                versionId(3),
                versionId(3),
                "[]",
                90,
                30,
            ) == 1,
        )
        val resolvedA = transition(
            id = "transition-a-3",
            itemId = ITEM_A,
            chapterIndex = 3,
            operation = "RESOLVE",
            from = ForeshadowStatus.DEVELOPING,
            to = ForeshadowStatus.RESOLVED,
            createdAt = 30,
        )
        val plantedBItem = foreshadow(
            id = ITEM_B,
            status = ForeshadowStatus.PLANTED,
            sourceVersionId = versionId(3),
            plantedVersionId = versionId(3),
            resolvedVersionId = null,
            importance = 60,
            createdAt = 31,
            updatedAt = 31,
        )
        val plantedB = transition(
            id = "transition-b-3",
            itemId = ITEM_B,
            chapterIndex = 3,
            operation = "PLANT",
            from = null,
            to = ForeshadowStatus.PLANTED,
            createdAt = 31,
            storyOffset = 1,
        )
        memory.insertForeshadows(listOf(plantedBItem))
        memory.insertForeshadowTransitions(listOf(resolvedA, plantedB))
        captureRevision(3, listOf(resolvedA, plantedB))
        MemorySearchIndexWriterV1.replaceStoryTracking(
            database.memorySearchDao(),
            3,
            emptyList(),
            listOf(requireNotNull(memory.findForeshadow(ITEM_A)), plantedBItem),
        )
        return baseline
    }

    private suspend fun captureRevision(
        chapterIndex: Int,
        transitions: List<ForeshadowTransitionEntity>,
    ) {
        ForeshadowProjectionRevisionWriterV1(database.memoryDao()).persistAfterStates(
            bookId = BOOK_ID,
            chapterIndex = chapterIndex,
            sourceChapterVersionId = versionId(chapterIndex),
            generationStageId = stageId(chapterIndex),
            transitions = transitions,
        )
    }

    private fun transition(
        id: String,
        itemId: String,
        chapterIndex: Int,
        operation: String,
        from: ForeshadowStatus?,
        to: ForeshadowStatus,
        createdAt: Long,
        storyOffset: Int = 0,
    ) = ForeshadowTransitionEntity(
        transitionId = id,
        foreshadowItemId = itemId,
        bookId = BOOK_ID,
        sourceChapterVersionId = versionId(chapterIndex),
        generationStageId = stageId(chapterIndex),
        storyOrder = chapterIndex * 1_000_000L + storyOffset,
        operation = operation,
        fromStatus = from,
        toStatus = to,
        evidenceJson = "{}",
        status = DerivedDataStatus.VALID,
        createdAt = createdAt,
    )

    private fun foreshadow(
        id: String,
        status: ForeshadowStatus,
        sourceVersionId: String,
        plantedVersionId: String,
        resolvedVersionId: String?,
        importance: Int,
        createdAt: Long,
        updatedAt: Long,
    ) = ForeshadowItemEntity(
        foreshadowItemId = id,
        bookId = BOOK_ID,
        description = "sealed clue $id",
        foreshadowStatus = status,
        memoryStatus = DerivedDataStatus.VALID,
        targetStartChapterIndex = 2,
        targetEndChapterIndex = 5,
        sourceChapterVersionId = sourceVersionId,
        plantedChapterVersionId = plantedVersionId,
        resolvedChapterVersionId = resolvedVersionId,
        visibleEntityIdsJson = "[]",
        importance = importance,
        source = MemorySource.CHAPTER_EXTRACTION,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private suspend fun createStage(chapterIndex: Int) {
        database.generationDao().createJob(
            GenerationJobEntity(
                jobId = jobId(chapterIndex),
                bookId = BOOK_ID,
                jobType = GenerationJobType.REBUILD_MEMORY,
                status = GenerationJobStatus.CREATED,
                userIntentJson = "{}",
                budgetSnapshotJson = "{}",
                promptBundleVersion = "prompt-1",
                createdAt = chapterIndex.toLong(),
                updatedAt = chapterIndex.toLong(),
            ),
            listOf(
                GenerationStageEntity(
                    stageId = stageId(chapterIndex),
                    jobId = jobId(chapterIndex),
                    phase = GenerationPhase.EXTRACT_MEMORY,
                    targetType = GenerationTargetType.CHAPTER,
                    targetId = chapterId(chapterIndex),
                    status = GenerationStageStatus.PENDING,
                    inputVersionHash = "input-$chapterIndex",
                    idempotencyKey = "idem-$chapterIndex",
                    maxAttempts = 2,
                    inputSourcesJson = "[]",
                    createdAt = chapterIndex.toLong(),
                    updatedAt = chapterIndex.toLong(),
                ),
            ),
        )
    }

    private suspend fun editSecondChapter() {
        ChapterUserEditRepository(database).commit(
            ChapterUserEditCommand(
                bookId = BOOK_ID,
                chapterId = chapterId(2),
                expectedCurrentVersionId = versionId(2),
                newVersionId = EDITED_VERSION_ID,
                content = "edited chapter two",
                editedAt = 100,
            ),
        )
    }

    private fun planRequest() = ChapterEditRebuildPlanRequest(
        bookId = BOOK_ID,
        editedChapterId = chapterId(2),
        editedVersionId = EDITED_VERSION_ID,
    )

    private suspend fun seedCommittedChapters(count: Int) {
        repeat(count) { offset ->
            val index = offset + 1
            database.libraryDao().createChapter(
                ChapterEntity(
                    chapterId = chapterId(index),
                    bookId = BOOK_ID,
                    chapterIndex = index,
                    plannedTitle = "Chapter $index",
                    displayTitle = "Chapter $index",
                    status = ChapterStatus.PLANNED,
                    consistencyStatus = ConsistencyStatus.UNKNOWN,
                    createdAt = index.toLong(),
                    updatedAt = index.toLong(),
                ),
            )
            val content = "chapter body $index"
            database.libraryDao().commitChapterVersion(
                CommitChapterVersionCommand(
                    chapterVersionId = versionId(index),
                    chapterId = chapterId(index),
                    expectedCurrentVersionId = null,
                    content = content,
                    contentHash = sha256(content),
                    source = ChapterVersionSource.IMPORTED,
                    generationStageId = null,
                    modelSnapshotJson = null,
                    createdAt = index.toLong(),
                ),
            )
        }
    }

    private suspend fun createBook() {
        database.libraryDao().createBook(
            BookCreationSnapshotEntity(
                snapshotId = "rewind-snapshot",
                rawInputJson = "{}",
                normalizedInputJson = "{}",
                inferenceProvenanceJson = "{}",
                genrePayloadJson = "{}",
                presentationProfileJson = "{}",
                modelPreferenceJson = "{}",
                schemaVersion = 1,
                promptBundleVersion = "prompt-1",
                contentControlSchemaVersion = 1,
                contentHash = sha256("rewind-snapshot"),
                createdAt = 1,
            ),
            BookEntity(
                bookId = BOOK_ID,
                creationSnapshotId = "rewind-snapshot",
                title = "Rewind fixture",
                titleSource = TitleSource.USER,
                status = BookStatus.DRAFT,
                lengthMode = BookLengthMode.LONG,
                targetCharacters = 500_000,
                targetChapters = 500,
                minimumChapters = 301,
                lengthPolicySchemaVersion = 1,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
    }

    private fun projectionRevisionStatuses(itemId: String): List<DerivedDataStatus> =
        database.openHelper.writableDatabase.query(
            "SELECT status FROM foreshadow_projection_revision WHERE foreshadow_item_id = ? ORDER BY chapter_index ASC",
            arrayOf(itemId),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(DerivedDataStatus.valueOf(cursor.getString(0)))
            }
        }

    private fun transitionStatuses(itemId: String): List<DerivedDataStatus> =
        database.openHelper.writableDatabase.query(
            "SELECT status FROM foreshadow_transition WHERE foreshadow_item_id = ? ORDER BY story_order ASC",
            arrayOf(itemId),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(DerivedDataStatus.valueOf(cursor.getString(0)))
            }
        }

    private fun scalarInt(sql: String): Int = database.openHelper.writableDatabase.query(sql).use { cursor ->
        assertTrue(cursor.moveToFirst())
        cursor.getInt(0)
    }

    private suspend fun expectFailure(block: suspend () -> Unit): Throwable = try {
        block()
        throw AssertionError("Expected operation to fail.")
    } catch (error: Throwable) {
        if (error is AssertionError && error.message == "Expected operation to fail.") throw error
        error
    }

    private fun chapterId(index: Int) = "rewind-chapter-$index"
    private fun versionId(index: Int) = "rewind-version-$index-v1"
    private fun jobId(index: Int) = "rewind-job-$index"
    private fun stageId(index: Int) = "rewind-stage-$index"

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private companion object {
        const val BOOK_ID = "foreshadow-rewind-book"
        const val EDITED_VERSION_ID = "rewind-version-2-v2"
        const val ITEM_A = "rewind-foreshadow-a"
        const val ITEM_B = "rewind-foreshadow-b"
        const val REWIND_ID = "foreshadow-rewind-1"
    }
}
