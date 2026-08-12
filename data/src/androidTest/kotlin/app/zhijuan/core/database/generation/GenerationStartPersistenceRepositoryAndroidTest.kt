package app.zhijuan.core.database.generation

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zhijuan.core.contract.GenerationBudgetConfirmation
import app.zhijuan.core.contract.GenerationStartRequest
import app.zhijuan.core.database.LibraryDatabaseGuards
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.database.connection.ConnectionProfileEntity
import app.zhijuan.core.database.library.BookCreationRepository
import app.zhijuan.core.database.library.BookCreationSnapshotEntity
import app.zhijuan.core.database.library.BookEntity
import app.zhijuan.core.model.BookLengthMode
import app.zhijuan.core.model.BookStatus
import app.zhijuan.core.model.ExternalDataDestinationBindingV1
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.TitleSource
import app.zhijuan.core.task.PromptBundleCatalogV1
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GenerationStartPersistenceRepositoryAndroidTest {
    private lateinit var database: ZhijuanDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
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
    fun frozenConfirmationCreatesOneReadyEntryAndReplayReturnsIt() = runBlocking {
        seedBookAndConnection()
        val repository = GenerationStartPersistenceRepository(database)

        val firstResult = repository.start(request())
        assertTrue("unexpected start result: $firstResult", firstResult is GenerationStartPersistenceResult.Started)
        val first = firstResult as GenerationStartPersistenceResult.Started
        val replayResult = repository.start(request())
        assertTrue("unexpected replay result: $replayResult", replayResult is GenerationStartPersistenceResult.Started)
        val replay = replayResult as GenerationStartPersistenceResult.Started

        assertFalse(first.replayed)
        assertTrue(replay.replayed)
        assertEquals(first.jobId, replay.jobId)
        val job = requireNotNull(database.generationDao().findJob(first.jobId))
        assertEquals(GenerationJobStatus.READY, job.status)
        assertEquals(3, database.generationDao().stagesForJob(first.jobId).size)
        assertEquals(
            GenerationStageStatus.READY,
            database.generationDao().stagesForJob(first.jobId)
                .single { it.phase == app.zhijuan.core.model.GenerationPhase.BUILD_STORY_SEED }
                .status,
        )
        assertEquals(BookStatus.GENERATING, database.libraryDao().findBook(BOOK_ID)?.status)
        assertEquals(80_000L, PersistentBudgetPolicyRepository(database).currentBookPolicy(BOOK_ID)?.maxTokens)
        assertEquals(
            80_000L,
            PersistentBudgetPolicyRepository(database).currentDailyPolicy("Asia/Shanghai")?.maxTokens,
        )
    }

    @Test
    fun snapshotAndCurrentConnectionChangesFailWithoutCreatingJob() = runBlocking {
        seedBookAndConnection()
        val repository = GenerationStartPersistenceRepository(database)

        assertEquals(
            GenerationStartPersistenceFailure.CONFIRMATION_CHANGED,
            (repository.start(request().copy(creationSnapshotContentHash = "f".repeat(64))) as
                GenerationStartPersistenceResult.Failed).reason,
        )
        database.connectionDao().insertAndSelectCurrent(connection(CONNECTION_2, MODEL_2))
        assertEquals(
            GenerationStartPersistenceFailure.CONNECTION_CHANGED,
            (repository.start(request()) as GenerationStartPersistenceResult.Failed).reason,
        )
        assertEquals(BookStatus.DRAFT, database.libraryDao().findBook(BOOK_ID)?.status)
    }

    private suspend fun seedBookAndConnection() {
        BookCreationRepository(database).create(
            snapshot = BookCreationSnapshotEntity(
                snapshotId = SNAPSHOT_ID,
                rawInputJson = "{\"storyIdea\":\"成年人物测试故事\"}",
                normalizedInputJson = "{\"storyIdea\":\"成年人物测试故事\"}",
                inferenceProvenanceJson = "{}",
                genrePayloadJson = GENRE_JSON,
                presentationProfileJson = PRESENTATION_JSON,
                modelPreferenceJson = "{\"connectionId\":\"$CONNECTION_ID\",\"modelId\":\"$MODEL_ID\"}",
                schemaVersion = 1,
                promptBundleVersion = PromptBundleCatalogV1.UNASSIGNED_CREATION_BUNDLE_VERSION,
                contentControlSchemaVersion = 1,
                contentHash = SNAPSHOT_HASH,
                createdAt = 1L,
            ),
            book = BookEntity(
                bookId = BOOK_ID,
                creationSnapshotId = SNAPSHOT_ID,
                title = "启动测试",
                titleSource = TitleSource.USER,
                status = BookStatus.DRAFT,
                lengthMode = BookLengthMode.SHORT,
                targetCharacters = null,
                targetChapters = 80,
                minimumChapters = 80,
                lengthPolicySchemaVersion = 1,
                generationStatusSummary = "AWAITING_USAGE_CONFIRMATION",
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        database.connectionDao().insertAndSelectCurrent(connection(CONNECTION_ID, MODEL_ID))
    }

    private fun connection(connectionId: String, modelId: String): ConnectionProfileEntity {
        val binding = ExternalDataDestinationBindingV1.create(BASE_URL, PROTOCOL)
        return ConnectionProfileEntity(
            connectionId = connectionId,
            displayName = "测试连接",
            serviceId = "CUSTOM",
            protocolId = PROTOCOL,
            baseUrl = BASE_URL,
            normalizedDestination = binding.normalizedDestination,
            secretRefId = "secret.$connectionId",
            secretLastFour = "test",
            selectedModelId = modelId,
            availableModelsJson = "[\"$modelId\"]",
            modelVerification = "DISCOVERED",
            basicVerifiedAt = 1L,
            fullVerifiedAt = null,
            dataDisclosureVersion = null,
            dataDisclosureAcceptedAt = null,
            dataDisclosureBindingHash = null,
            createdAt = 1L,
            updatedAt = 1L,
        )
    }

    private fun request(): GenerationStartRequest {
        val binding = ExternalDataDestinationBindingV1.create(BASE_URL, PROTOCOL)
        return GenerationStartRequest(
            bookId = BOOK_ID,
            creationSnapshotId = SNAPSHOT_ID,
            creationSnapshotContentHash = SNAPSHOT_HASH,
            connectionId = CONNECTION_ID,
            modelId = MODEL_ID,
            normalizedDestination = binding.normalizedDestination,
            destinationProtocolId = binding.protocolId,
            destinationDisclosureVersion = binding.disclosureVersion,
            destinationBindingHash = binding.bindingHash,
            budget = GenerationBudgetConfirmation(
                requestTokenHardLimit = 16_000L,
                bookTokenHardLimit = 80_000L,
                dailyTokenHardLimit = 80_000L,
                dailyZoneId = "Asia/Shanghai",
                priceUnknownAccepted = true,
            ),
            confirmedAt = 10L,
        )
    }

    private companion object {
        const val BOOK_ID = "book-start-1"
        const val SNAPSHOT_ID = "snapshot-start-1"
        const val CONNECTION_ID = "connection-start-1"
        const val CONNECTION_2 = "connection-start-2"
        const val MODEL_ID = "deepseek-chat"
        const val MODEL_2 = "other-model"
        const val BASE_URL = "https://api.example.com/v1"
        const val PROTOCOL = "OPENAI_CHAT_COMPAT"
        val SNAPSHOT_HASH = "a".repeat(64)
        val GENRE_JSON = """{"contentDimensionBaseline":{"conflictDetailLevel":1,"graphicInjuryLevel":0,"languageIntensityLevel":2,"emotionalPressureLevel":3}}"""
        val PRESENTATION_JSON = """{"directive":{"preset":"DETAILED","narrativeDetailLevel":4,"intimacyDetailLevel":4,"fadePolicy":"AVOID","conflictDetailOverride":null,"graphicInjuryOverride":null,"languageIntensityOverride":null,"emotionalPressureOverride":null,"presentationMappingSchemaVersion":1,"contentControlSchemaVersion":1},"resolvedProfile":{"preset":"DETAILED","narrativeDetailLevel":4,"intimacyDetailLevel":4,"conflictDetailLevel":1,"graphicInjuryLevel":0,"languageIntensityLevel":2,"emotionalPressureLevel":3,"fadePolicy":"AVOID","presentationMappingSchemaVersion":1,"contentControlSchemaVersion":1}}"""
    }
}
