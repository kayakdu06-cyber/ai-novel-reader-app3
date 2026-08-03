package app.zhijuan.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zhijuan.core.database.generation.PromptBundleBindingRepository
import app.zhijuan.core.database.library.BookCreationRepository
import app.zhijuan.core.database.library.BookCreationSnapshotEntity
import app.zhijuan.core.database.library.BookEntity
import app.zhijuan.core.model.BookLengthMode
import app.zhijuan.core.model.BookLengthPolicy
import app.zhijuan.core.model.BookPresentationPreset
import app.zhijuan.core.model.BookStatus
import app.zhijuan.core.model.TitleSource
import app.zhijuan.core.task.PromptBundleCatalogV1
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PromptBundleBindingDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: ZhijuanDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, ZhijuanDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(LibraryDatabaseGuards.callback)
            .build()
            .also { it.openHelper.writableDatabase }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun immutableCreationSnapshotBindsDetailedContractWithoutCreatingAJob() = runBlocking {
        seed(snapshot())

        val bound = PromptBundleBindingRepository(database).bindForBook(BOOK_ID)

        assertEquals(PromptBundleCatalogV1.BUNDLE_VERSION, bound.bundleVersion)
        assertEquals(BookPresentationPreset.DETAILED, bound.contentProfile.preset)
        assertEquals(4, bound.contentProfile.intimacyDetailLevel)
        assertEquals(1, bound.contentProfile.conflictDetailLevel)
        assertEquals(0, bound.contentProfile.graphicInjuryLevel)
        assertEquals(300, bound.minimumChapterCount)
        assertEquals(300, bound.targetChapterCount)
        assertNull(database.generationDao().findJob("job-not-created"))
        val stored = database.libraryDao().findCreationSnapshot(SNAPSHOT_ID)
        assertEquals(PromptBundleCatalogV1.UNASSIGNED_CREATION_BUNDLE_VERSION, stored?.promptBundleVersion)
        assertFalse(bound.toString().contains(CONTENT_HASH))
    }

    @Test
    fun mismatchedPersistedProfileFailsClosed() {
        runBlocking {
            seed(
                snapshot(
                    presentationJson = presentationJson(
                        resolvedConflict = 4,
                    ),
                ),
            )

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { PromptBundleBindingRepository(database).bindForBook(BOOK_ID) }
            }
        }
    }

    @Test
    fun unsupportedPromptStateAndMalformedSnapshotFailClosed() {
        runBlocking {
            seed(snapshot(promptBundleVersion = "legacy-prompt"))
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { PromptBundleBindingRepository(database).bindForBook(BOOK_ID) }
            }

            database.close()
            setUp()
            seed(snapshot(presentationJson = "{}"))
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { PromptBundleBindingRepository(database).bindForBook(BOOK_ID) }
            }
        }
    }

    private suspend fun seed(snapshot: BookCreationSnapshotEntity) {
        BookCreationRepository(database).create(
            snapshot = snapshot,
            book = BookEntity(
                bookId = BOOK_ID,
                creationSnapshotId = SNAPSHOT_ID,
                title = "绑定夹具",
                titleSource = TitleSource.USER,
                status = BookStatus.DRAFT,
                lengthMode = BookLengthMode.MEDIUM,
                targetCharacters = null,
                targetChapters = BookLengthPolicy.MEDIUM_MINIMUM_CHAPTERS,
                minimumChapters = BookLengthPolicy.MEDIUM_MINIMUM_CHAPTERS,
                lengthPolicySchemaVersion = BookLengthPolicy.SCHEMA_VERSION,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
    }

    private fun snapshot(
        promptBundleVersion: String = PromptBundleCatalogV1.UNASSIGNED_CREATION_BUNDLE_VERSION,
        presentationJson: String = presentationJson(),
    ) = BookCreationSnapshotEntity(
        snapshotId = SNAPSHOT_ID,
        rawInputJson = "{\"storyIdea\":\"fixture\"}",
        normalizedInputJson = "{\"storyIdea\":\"fixture\"}",
        inferenceProvenanceJson = "{\"schemaVersion\":1}",
        genrePayloadJson =
            "{\"contentDimensionBaseline\":{" +
                "\"conflictDetailLevel\":1," +
                "\"graphicInjuryLevel\":0," +
                "\"languageIntensityLevel\":2," +
                "\"emotionalPressureLevel\":3}}",
        presentationProfileJson = presentationJson,
        modelPreferenceJson = "{\"connectionId\":\"connection-1\",\"modelId\":\"model-1\"}",
        schemaVersion = 1,
        promptBundleVersion = promptBundleVersion,
        contentControlSchemaVersion = 1,
        contentHash = CONTENT_HASH,
        createdAt = 1L,
    )

    private fun presentationJson(resolvedConflict: Int = 1): String =
        "{\"directive\":{" +
            "\"preset\":\"DETAILED\"," +
            "\"narrativeDetailLevel\":4," +
            "\"intimacyDetailLevel\":4," +
            "\"fadePolicy\":\"AVOID\"," +
            "\"conflictDetailOverride\":null," +
            "\"graphicInjuryOverride\":null," +
            "\"languageIntensityOverride\":null," +
            "\"emotionalPressureOverride\":null," +
            "\"presentationMappingSchemaVersion\":1," +
            "\"contentControlSchemaVersion\":1}," +
            "\"resolvedProfile\":{" +
            "\"preset\":\"DETAILED\"," +
            "\"narrativeDetailLevel\":4," +
            "\"intimacyDetailLevel\":4," +
            "\"conflictDetailLevel\":$resolvedConflict," +
            "\"graphicInjuryLevel\":0," +
            "\"languageIntensityLevel\":2," +
            "\"emotionalPressureLevel\":3," +
            "\"fadePolicy\":\"AVOID\"," +
            "\"presentationMappingSchemaVersion\":1," +
            "\"contentControlSchemaVersion\":1}}"

    private companion object {
        const val BOOK_ID = "book-prompt-binding"
        const val SNAPSHOT_ID = "snapshot-prompt-binding"
        val CONTENT_HASH = "c".repeat(64)
    }
}
