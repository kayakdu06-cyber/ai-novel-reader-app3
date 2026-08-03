package app.zhijuan.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zhijuan.core.database.library.BookCreationSnapshotEntity
import app.zhijuan.core.database.library.BookEntity
import app.zhijuan.core.database.library.ChapterEntity
import app.zhijuan.core.database.library.CommitChapterVersionCommand
import app.zhijuan.core.database.template.TemplateEntity
import app.zhijuan.core.database.template.TemplateRevisionEntity
import app.zhijuan.core.database.template.TemplateTagEntity
import app.zhijuan.core.database.template.TemplateUseSnapshotEntity
import app.zhijuan.core.model.BookLengthMode
import app.zhijuan.core.model.BookStatus
import app.zhijuan.core.model.ChapterStatus
import app.zhijuan.core.model.ChapterVersionSource
import app.zhijuan.core.model.ConsistencyStatus
import app.zhijuan.core.model.TemplateOriginType
import app.zhijuan.core.model.TemplateTagDimension
import app.zhijuan.core.model.TemplateTagSource
import app.zhijuan.core.model.TemplateUseMode
import app.zhijuan.core.model.TitleSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TemplateDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: ZhijuanDatabase

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, ZhijuanDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(LibraryDatabaseGuards.callback)
            .build()
            .also { it.openHelper.writableDatabase }
        createBook(SOURCE_BOOK_ID, "Source Book")
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun revisionsAreImmutableWhilePresentationCanChangeWithoutChangingOrigin() = runBlocking {
        val dao = database.templateDao()
        val firstRevision = revision("revision-1", "template-1")
        dao.createTemplate(template("template-1"), firstRevision)
        createBookFromRevision("book-from-revision-1", firstRevision)
        dao.createRevision(
            revision(
                id = "revision-2",
                templateId = "template-1",
                revisionNo = 2,
                parent = "revision-1",
                root = "revision-1",
                chain = "[\"revision-1\",\"revision-2\"]",
                hash = "hash-2",
            ),
        )

        assertEquals("revision-2", dao.findTemplate("template-1")?.currentRevisionId)
        assertEquals("revision-1", dao.findUseSnapshotForBook("book-from-revision-1")?.templateRevisionId)
        assertEquals("hash-revision-1", dao.findUseSnapshotForBook("book-from-revision-1")?.contentHash)
        assertEquals(20L, dao.findTemplate("template-1")?.lastUsedAt)
        expectFailure {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE template_revision SET story_seed_json = '{}' WHERE template_revision_id = 'revision-1'",
            )
        }
        assertEquals(
            1,
            dao.updatePresentation("template-1", "Renamed", "Changed", true, true, null, 10),
        )
        assertEquals("Renamed", dao.findTemplate("template-1")?.displayName)
        assertEquals(TemplateOriginType.USER_CREATED, dao.findTemplate("template-1")?.originType)
        expectFailure {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE template SET origin_type = 'IMPORTED' WHERE template_id = 'template-1'",
            )
        }
        Unit
    }

    @Test
    fun forkChainOfDepthTenKeepsRootAndCannotSelfParent() = runBlocking {
        val dao = database.templateDao()
        dao.createTemplate(template("template-root"), revision("revision-root", "template-root"))
        var parentRevision = "revision-root"
        repeat(9) { index ->
            val templateId = "template-fork-${index + 1}"
            val revisionId = "revision-fork-${index + 1}"
            dao.createTemplate(
                template(templateId, TemplateOriginType.TEMPLATE_FORK),
                revision(
                    id = revisionId,
                    templateId = templateId,
                    parent = parentRevision,
                    root = "revision-root",
                    chain = "[\"revision-root\",\"$parentRevision\",\"$revisionId\"]",
                    hash = "hash-$revisionId",
                ),
            )
            parentRevision = revisionId
        }

        var cursor: String? = parentRevision
        var depth = 0
        while (cursor != null) {
            val current = requireNotNull(dao.findRevision(cursor))
            assertEquals("revision-root", current.originRootRevisionId)
            cursor = current.parentTemplateRevisionId
            depth += 1
        }
        assertEquals(10, depth)

        expectFailure {
            dao.createTemplate(
                template("template-cycle", TemplateOriginType.TEMPLATE_FORK),
                revision(
                    id = "revision-cycle",
                    templateId = "template-cycle",
                    parent = "revision-cycle",
                    root = "revision-cycle",
                    chain = "[\"revision-cycle\"]",
                ),
            )
        }
        Unit
    }

    @Test
    fun bookDerivedExtractionIsIdempotentAndSurvivesSourceBookDeletion() = runBlocking {
        val dao = database.templateDao()
        val first = dao.getOrCreateBookDerivedTemplate(
            template("template-book", TemplateOriginType.BOOK_DERIVED),
            revision(
                id = "revision-book",
                templateId = "template-book",
                sourceBookId = SOURCE_BOOK_ID,
                sourceBookTitle = "Source Book",
                derivationKey = "derive-source-v1-hash",
                hash = "same-content-hash",
            ),
        )
        val replay = dao.getOrCreateBookDerivedTemplate(
            template("template-ignored", TemplateOriginType.BOOK_DERIVED),
            revision(
                id = "revision-ignored",
                templateId = "template-ignored",
                sourceBookId = SOURCE_BOOK_ID,
                sourceBookTitle = "Source Book",
                derivationKey = "derive-source-v1-hash",
                hash = "same-content-hash",
            ),
        )
        assertEquals(first, replay)
        assertEquals(1, dao.templateCount())
        assertEquals(1, dao.revisionCount())

        database.openHelper.writableDatabase.execSQL("DELETE FROM book WHERE book_id = '$SOURCE_BOOK_ID'")
        val retained = requireNotNull(dao.findRevision("revision-book"))
        assertEquals(SOURCE_BOOK_ID, retained.sourceBookId)
        assertEquals("Source Book", retained.sourceBookTitleSnapshot)

        createBookFromRevision("new-book-after-delete", retained)
        assertNotNull(dao.findUseSnapshotForBook("new-book-after-delete"))
        Unit
    }

    @Test
    fun newBookFromTemplateFreezesUseSnapshotAndCopiesNoChaptersOrRuntimeRows() = runBlocking {
        val library = database.libraryDao()
        val dao = database.templateDao()
        library.createChapter(
            ChapterEntity(
                chapterId = "source-chapter",
                bookId = SOURCE_BOOK_ID,
                chapterIndex = 1,
                plannedTitle = "Old chapter",
                displayTitle = "Old chapter",
                status = ChapterStatus.PLANNED,
                consistencyStatus = ConsistencyStatus.UNKNOWN,
                createdAt = 2,
                updatedAt = 2,
            ),
        )
        library.commitChapterVersion(
            CommitChapterVersionCommand(
                chapterVersionId = "source-version",
                chapterId = "source-chapter",
                expectedCurrentVersionId = null,
                content = "Old body must stay in the source book.",
                contentHash = "source-body-hash",
                source = ChapterVersionSource.USER_EDIT,
                generationStageId = null,
                modelSnapshotJson = null,
                createdAt = 3,
            ),
        )
        val extracted = revision(
            id = "revision-book",
            templateId = "template-book",
            sourceBookId = SOURCE_BOOK_ID,
            sourceBookTitle = "Source Book",
            derivationKey = "derive-with-body-excluded",
            hash = "final-template-hash",
        )
        dao.createTemplate(template("template-book", TemplateOriginType.BOOK_DERIVED), extracted)
        createBookFromRevision("new-book", extracted)

        assertEquals(1, library.chaptersForBook(SOURCE_BOOK_ID).size)
        assertTrue(library.chaptersForBook("new-book").isEmpty())
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM generation_job WHERE book_id = 'new-book'"))
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM chapter_summary WHERE book_id = 'new-book'"))
        assertEquals("revision-book", dao.findUseSnapshotForBook("new-book")?.templateRevisionId)

        expectFailure {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE template_use_snapshot SET story_seed_json = '{}' WHERE book_id = 'new-book'",
            )
        }
        Unit
    }

    @Test
    fun unsafeRuntimeFieldsRollbackAndEqualHashesFromDifferentSourcesRemainDistinct() = runBlocking {
        val dao = database.templateDao()
        expectFailure {
            dao.createTemplate(
                template("unsafe-template"),
                revision("unsafe-revision", "unsafe-template").copy(
                    storySeedJson = "{\"authorization\":\"must-not-enter-template\"}",
                ),
            )
        }
        assertEquals(0, dao.templateCount())

        dao.createTemplate(
            template("template-a"),
            revision("revision-a", "template-a", hash = "duplicate-content"),
        )
        dao.createTemplate(
            template("template-b", TemplateOriginType.IMPORTED),
            revision("revision-b", "template-b", hash = "duplicate-content"),
        )
        assertEquals(2, dao.templateCount())
        assertEquals(2, dao.revisionCount())
        Unit
    }

    @Test
    fun tagsKeepProvenanceAndLowConfidenceTagsCannotBecomePrimary() = runBlocking {
        val dao = database.templateDao()
        dao.createTemplate(template("template-1"), revision("revision-1", "template-1"))
        dao.createTemplate(template("template-2"), revision("revision-2", "template-2"))
        dao.insertTag(tag("tag-primary", "template-1", "revision-1", 900_000, true))

        expectFailure { dao.insertTag(tag("tag-second", "template-1", "revision-1", 950_000, true, "mystery")) }
        expectFailure {
            dao.insertTag(
                tag("tag-low", "template-1", "revision-1", 500_000, true, "dark").copy(
                    dimension = TemplateTagDimension.MOOD,
                ),
            )
        }
        expectFailure { dao.insertTag(tag("tag-cross", "template-1", "revision-2", 900_000, false, "cross")) }
        dao.insertTag(
            tag("tag-user", "template-1", null, 1_000_000, false, "custom").copy(
                source = TemplateTagSource.USER,
                isConfirmed = true,
            ),
        )
        assertEquals(2, dao.tagsForTemplate("template-1").size)

        expectFailure {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE template_tag SET source = 'USER' WHERE template_tag_id = 'tag-primary'",
            )
        }
        Unit
    }

    private suspend fun createBook(bookId: String, title: String, contentHash: String = "hash-$bookId") {
        val snapshotId = "snapshot-$bookId"
        database.libraryDao().createBook(
            BookCreationSnapshotEntity(
                snapshotId = snapshotId,
                rawInputJson = "{}",
                normalizedInputJson = "{}",
                inferenceProvenanceJson = "{}",
                genrePayloadJson = "{}",
                presentationProfileJson = "{}",
                modelPreferenceJson = "{}",
                schemaVersion = 1,
                promptBundleVersion = "prompt-1",
                contentControlSchemaVersion = 1,
                contentHash = contentHash,
                createdAt = 1,
            ),
            book(bookId, snapshotId, title),
        )
    }

    private suspend fun createBookFromRevision(bookId: String, revision: TemplateRevisionEntity) {
        val snapshotId = "snapshot-$bookId"
        val creation = BookCreationSnapshotEntity(
            snapshotId = snapshotId,
            rawInputJson = "{}",
            normalizedInputJson = "{}",
            inferenceProvenanceJson = "{}",
            genrePayloadJson = revision.genreJson,
            presentationProfileJson = revision.presentationJson,
            modelPreferenceJson = revision.modelRolePreferencesJson,
            schemaVersion = 1,
            promptBundleVersion = revision.promptBundleVersion,
            contentControlSchemaVersion = revision.contentControlSchemaVersion,
            contentHash = revision.contentHash,
            createdAt = 20,
        )
        database.templateDao().createBookFromTemplate(
            creation,
            book(bookId, snapshotId, "Regenerated $bookId"),
            useSnapshot("use-$bookId", bookId, revision),
        )
    }

    private fun book(bookId: String, snapshotId: String, title: String) = BookEntity(
        bookId = bookId,
        creationSnapshotId = snapshotId,
        title = title,
        titleSource = TitleSource.USER,
        status = BookStatus.DRAFT,
        lengthMode = BookLengthMode.LONG,
        targetCharacters = 500_000,
        targetChapters = 500,
        minimumChapters = 301,
        lengthPolicySchemaVersion = 1,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun template(id: String, origin: TemplateOriginType = TemplateOriginType.USER_CREATED) = TemplateEntity(
        templateId = id,
        displayName = "Template $id",
        description = "fixture",
        originType = origin,
        systemPresetKey = if (origin == TemplateOriginType.SYSTEM_PRESET) "preset-$id" else null,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun revision(
        id: String,
        templateId: String,
        revisionNo: Int = 1,
        parent: String? = null,
        root: String = id,
        chain: String = "[\"$root\"]",
        sourceBookId: String? = null,
        sourceBookTitle: String? = null,
        derivationKey: String? = null,
        hash: String = "hash-$id",
    ) = TemplateRevisionEntity(
        templateRevisionId = id,
        templateId = templateId,
        revisionNo = revisionNo,
        parentTemplateRevisionId = parent,
        sourceBookId = sourceBookId,
        sourceBookTitleSnapshot = sourceBookTitle,
        originRootRevisionId = root,
        originChainJson = chain,
        derivationKey = derivationKey,
        storySeedJson = "{}",
        genreJson = "{}",
        stableCharactersJson = "[]",
        worldRulesJson = "{}",
        writingStyleJson = "{}",
        structureJson = "{}",
        presentationJson = "{}",
        contentRulesJson = "{}",
        generationStrategyJson = "{}",
        modelRolePreferencesJson = "{}",
        extensionJson = "{}",
        contentHash = hash,
        templateSchemaVersion = 1,
        promptBundleVersion = "prompt-1",
        contentControlSchemaVersion = 1,
        createdByAppVersion = "test-1",
        extractionModelSnapshotJson = null,
        createdAt = revisionNo.toLong(),
    )

    private fun useSnapshot(id: String, bookId: String, revision: TemplateRevisionEntity) =
        TemplateUseSnapshotEntity(
            templateUseSnapshotId = id,
            bookId = bookId,
            templateId = revision.templateId,
            templateRevisionId = revision.templateRevisionId,
            useMode = TemplateUseMode.ALL_SETTINGS,
            userOverridesJson = "{}",
            sourceProvenanceJson = revision.originChainJson,
            storySeedJson = revision.storySeedJson,
            genreJson = revision.genreJson,
            stableCharactersJson = revision.stableCharactersJson,
            worldRulesJson = revision.worldRulesJson,
            writingStyleJson = revision.writingStyleJson,
            structureJson = revision.structureJson,
            presentationJson = revision.presentationJson,
            contentRulesJson = revision.contentRulesJson,
            generationStrategyJson = revision.generationStrategyJson,
            modelRolePreferencesJson = revision.modelRolePreferencesJson,
            extensionJson = revision.extensionJson,
            capabilityResolutionJson = "{}",
            contentHash = revision.contentHash,
            templateSchemaVersion = revision.templateSchemaVersion,
            promptBundleVersion = revision.promptBundleVersion,
            contentControlSchemaVersion = revision.contentControlSchemaVersion,
            createdAt = 20,
        )

    private fun tag(
        id: String,
        templateId: String,
        revisionId: String?,
        confidence: Int,
        primary: Boolean,
        value: String = "urban",
    ) = TemplateTagEntity(
        templateTagId = id,
        templateId = templateId,
        derivedFromRevisionId = revisionId,
        dimension = TemplateTagDimension.GENRE,
        normalizedValue = value,
        displayName = value,
        source = TemplateTagSource.AI_INFERRED,
        confidenceMicros = confidence,
        isConfirmed = false,
        isPrimary = primary,
        createdAt = 1,
        updatedAt = 1,
    )

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

    private companion object {
        const val SOURCE_BOOK_ID = "source-book"
    }
}
