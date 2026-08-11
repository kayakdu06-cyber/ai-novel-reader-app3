package app.zhijuan.feature.generation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.database.generation.ArcWindowPlanningCommitRepository
import app.zhijuan.core.database.generation.ArcWindowPlanningJobFactory
import app.zhijuan.core.database.generation.ArcWindowPlanningJobSpec
import app.zhijuan.core.database.generation.ChapterProgressionAuthorization
import app.zhijuan.core.database.generation.ChapterProgressionGateRepository
import app.zhijuan.core.database.generation.FirstChapterFastLaneCommitRepository
import app.zhijuan.core.database.generation.FirstChapterFastLaneJobFactory
import app.zhijuan.core.database.generation.FirstChapterFastLaneJobSpec
import app.zhijuan.core.database.generation.GenerationJobSetup
import app.zhijuan.core.database.generation.GenerationJobSetupRepository
import app.zhijuan.core.database.generation.GenerationStageSetup
import app.zhijuan.core.database.generation.GenerationOutputValidationRepository
import app.zhijuan.core.database.generation.GenerationStateRepository
import app.zhijuan.core.database.generation.GenerationStreamingDraftRepository
import app.zhijuan.core.database.generation.InitialPlanningCommitRepository
import app.zhijuan.core.database.generation.InitialPlanningJobFactory
import app.zhijuan.core.database.generation.InitialPlanningJobSpec
import app.zhijuan.core.database.generation.InitialPlanningStageIds
import app.zhijuan.core.database.generation.PostFirstChapterPlanningJobFactory
import app.zhijuan.core.database.generation.PostFirstChapterPlanningJobSpec
import app.zhijuan.core.database.generation.PersistedStreamingRequest
import app.zhijuan.core.database.generation.RequestIntentDraft
import app.zhijuan.core.model.AdultStatus
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationJobType
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.model.UsageLedgerStatus
import app.zhijuan.core.security.AndroidProtectedArtifactStore
import app.zhijuan.core.task.ActiveArcAnchor
import app.zhijuan.core.task.ArcPlanningWindowInput
import app.zhijuan.core.task.ChapterProgressionBlockReason
import app.zhijuan.core.task.FirstChapterGenerationMode
import app.zhijuan.core.task.JobEvent
import app.zhijuan.core.task.StageEvent
import app.zhijuan.provider.common.CapabilityResult
import app.zhijuan.provider.common.ConnectionTestResult
import app.zhijuan.provider.common.GenerationParameters
import app.zhijuan.provider.common.GenerationRequest
import app.zhijuan.provider.common.ModelListResult
import app.zhijuan.provider.common.PromptLayer
import app.zhijuan.provider.common.PromptPart
import app.zhijuan.provider.common.ProviderAdapter
import app.zhijuan.provider.common.ProviderCancellationResult
import app.zhijuan.provider.common.ProviderConnectionProfile
import app.zhijuan.provider.common.ProviderFinishReason
import app.zhijuan.provider.common.ProviderModelId
import app.zhijuan.provider.common.ProviderPrompt
import app.zhijuan.provider.common.ProviderProtocol
import app.zhijuan.provider.common.ProviderRequestRecoveryCapability
import app.zhijuan.provider.common.ProviderRequestRecoveryResult
import app.zhijuan.provider.common.ProviderStreamEvent
import app.zhijuan.provider.common.ProviderTimeoutPolicy
import app.zhijuan.provider.common.ProviderUsage
import app.zhijuan.provider.common.ProviderUsageQuality
import app.zhijuan.provider.common.SensitiveProviderText
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InitialPlanningEndToEndTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: ZhijuanDatabase
    private lateinit var artifactStore: AndroidProtectedArtifactStore
    private lateinit var drafts: GenerationStreamingDraftRepository
    private lateinit var outputs: GenerationOutputValidationRepository
    private lateinit var states: GenerationStateRepository
    private lateinit var commits: InitialPlanningCommitRepository
    private val parser = InitialPlanningOutputParser()
    private val windowParser = ArcWindowPlanningOutputParser()

    @Before
    fun setUp() = runBlocking {
        artifactStore = AndroidProtectedArtifactStore(context)
        cleanArtifacts()
        database = Room.inMemoryDatabaseBuilder(context, ZhijuanDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { it.openHelper.writableDatabase }
        seedBook()
        BudgetedGenerationTestSupport.seedBudgetedRequestEnvironment(
            database = database,
            bookId = BOOK_ID,
            connectionId = "connection.fixture",
        )
        GenerationJobSetupRepository(database).create(
            InitialPlanningJobFactory.create(
                InitialPlanningJobSpec(
                    jobId = JOB_ID,
                    bookId = BOOK_ID,
                    userIntentJson = "{\"targetChapterCount\":80}",
                    budgetSnapshotJson = "{\"mode\":\"fixture\"}",
                    creationSnapshotHash = "a".repeat(64),
                    stageIds = InitialPlanningStageIds(SEED_STAGE, BIBLE_STAGE, OUTLINE_STAGE),
                    createdAt = 1L,
                ),
            ),
        )
        states = GenerationStateRepository(database)
        states.transitionJob(JOB_ID, GenerationJobStatus.CREATED, JobEvent.VALIDATION_PASSED, 2L)
        states.transitionStage(
            SEED_STAGE,
            GenerationStageStatus.PENDING,
            StageEvent.DEPENDENCIES_SATISFIED,
            updatedAt = 2L,
        )
        states.acquireJobLease(JOB_ID, "job-worker", 3L)
        drafts = GenerationStreamingDraftRepository(database, artifactStore)
        outputs = GenerationOutputValidationRepository(database, artifactStore)
        commits = InitialPlanningCommitRepository(database, artifactStore)
    }

    @After
    fun tearDown() {
        runCatching { cleanArtifacts() }
        database.close()
    }

    @Test
    fun fakeProviderCompletesSeedBibleOutlineAndAdvancesImmutableHeads() = runBlocking {
        val seedRaw = seedJson()
        val seed = parsedSeed(seedRaw)
        val seedAccepted = executeAndValidate(
            stageId = SEED_STAGE,
            attemptId = "attempt.seed",
            raw = seedRaw,
            contract = StorySeedOutputContractV1,
            acquiredAt = 4L,
            validatedAt = 20L,
        )
        val seedDraft = InitialPlanningPersistenceMapper.storySeed(
            seed = seed,
            expectedTargetChapterCount = 80,
            nextStageId = BIBLE_STAGE,
            committedAt = 21L,
        )
        val seedCommit = commits.commitStorySeed(seedAccepted.commitPermit, seedDraft)
        assertEquals(BIBLE_STAGE, seedCommit.nextStageId)
        assertEquals(GenerationStageStatus.READY, states.findStage(BIBLE_STAGE)?.status)

        val bibleRaw = bibleJson(seed.contentHash)
        val bible = parsedBible(bibleRaw)
        val bibleAccepted = executeAndValidate(
            stageId = BIBLE_STAGE,
            attemptId = "attempt.bible",
            raw = bibleRaw,
            contract = StoryBibleOutputContractV1,
            acquiredAt = 22L,
            validatedAt = 40L,
        )
        val bibleDraft = InitialPlanningPersistenceMapper.storyBible(
            seed = seed,
            bible = bible,
            bookId = BOOK_ID,
            bibleRevisionId = BIBLE_REVISION,
            bibleStageId = BIBLE_STAGE,
            nextStageId = OUTLINE_STAGE,
            committedAt = 41L,
        )
        val bibleCommit = commits.commitStoryBible(bibleAccepted.commitPermit, bibleDraft)
        assertEquals(OUTLINE_STAGE, bibleCommit.nextStageId)
        assertEquals(GenerationStageStatus.READY, states.findStage(OUTLINE_STAGE)?.status)

        val outlineRaw = outlineJson(bible.contentHash)
        val outline = parsedOutline(outlineRaw)
        val outlineAccepted = executeAndValidate(
            stageId = OUTLINE_STAGE,
            attemptId = "attempt.outline",
            raw = outlineRaw,
            contract = MasterOutlineOutputContractV1,
            acquiredAt = 42L,
            validatedAt = 60L,
        )
        val outlineDraft = InitialPlanningPersistenceMapper.masterOutline(
            bible = bible,
            outline = outline,
            expectedTargetChapterCount = 80,
            bookId = BOOK_ID,
            outlineRevisionId = OUTLINE_REVISION,
            outlineStageId = OUTLINE_STAGE,
            committedAt = 61L,
        )
        val outlineCommit = commits.commitMasterOutline(outlineAccepted.commitPermit, outlineDraft)

        assertTrue(outlineCommit.jobCompleted)
        assertEquals(GenerationJobStatus.COMPLETED, states.findJob(JOB_ID)?.status)
        assertEquals(GenerationStageStatus.SUCCEEDED, states.findStage(SEED_STAGE)?.status)
        assertEquals(GenerationStageStatus.SUCCEEDED, states.findStage(BIBLE_STAGE)?.status)
        assertEquals(GenerationStageStatus.SUCCEEDED, states.findStage(OUTLINE_STAGE)?.status)
        assertEquals(
            BIBLE_REVISION,
            scalarString("SELECT current_bible_revision_id FROM book_memory_head WHERE book_id = '$BOOK_ID'"),
        )
        assertEquals(
            OUTLINE_REVISION,
            scalarString("SELECT current_outline_revision_id FROM book_memory_head WHERE book_id = '$BOOK_ID'"),
        )
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM story_entity"))
        assertEquals(2L, scalarLong("SELECT COUNT(*) FROM canon_fact"))
        assertEquals(3L, scalarLong("SELECT COUNT(*) FROM memory_search_document"))
        assertEquals(AdultStatus.CONFIRMED_ADULT.name, scalarString("SELECT adult_status FROM story_entity"))
        assertEquals(22L, scalarLong("SELECT age_years FROM story_entity"))
        assertEquals(80L, outline.targetChapterCount.toLong())
        assertEquals(3L, scalarLong("SELECT COUNT(*) FROM usage_ledger WHERE status = 'FINAL'"))
        assertEquals(3, artifactStore.listArtifactReferenceIds().size)

        val replay = commits.commitStorySeed(seedAccepted.commitPermit, seedDraft)
        assertTrue(replay.replayed)
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM story_bible_revision"))
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM outline_revision"))
    }

    @Test
    fun crossStageBibleRevisionMismatchRollsBackWithoutAdvancingTheJob() = runBlocking {
        val seedRaw = seedJson()
        val seed = parsedSeed(seedRaw)
        val seedAccepted = executeAndValidate(
            SEED_STAGE,
            "attempt.rollback.seed",
            seedRaw,
            StorySeedOutputContractV1,
            4L,
            20L,
        )
        commits.commitStorySeed(
            seedAccepted.commitPermit,
            InitialPlanningPersistenceMapper.storySeed(seed, 80, BIBLE_STAGE, 21L),
        )

        val bibleRaw = bibleJson(seed.contentHash)
        val bible = parsedBible(bibleRaw)
        val accepted = executeAndValidate(
            BIBLE_STAGE,
            "attempt.rollback.bible",
            bibleRaw,
            StoryBibleOutputContractV1,
            22L,
            40L,
        )
        val validDraft = InitialPlanningPersistenceMapper.storyBible(
            seed,
            bible,
            BOOK_ID,
            BIBLE_REVISION,
            BIBLE_STAGE,
            OUTLINE_STAGE,
            41L,
        )
        val failure = expectFailure {
            commits.commitStoryBible(
                accepted.commitPermit,
                validDraft.copy(
                    revision = validDraft.revision.copy(generationStageId = SEED_STAGE),
                ),
            )
        }

        assertTrue(failure is IllegalArgumentException)
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM story_bible_revision"))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM story_entity"))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM canon_fact"))
        assertEquals(GenerationStageStatus.COMMITTING, states.findStage(BIBLE_STAGE)?.status)
        assertEquals(BIBLE_STAGE, states.findJob(JOB_ID)?.currentStageId)
        assertEquals(
            UsageLedgerStatus.PROVISIONAL.name,
            scalarString("SELECT status FROM usage_ledger WHERE attempt_id = 'attempt.rollback.bible'"),
        )
    }

    @Test
    fun boundedArcWindowCommitsOnlyEightChapterBriefsAndCanReplay() = runBlocking {
        val outline = completeInitialPlanningForWindow()
        val setup = createWindowJob(outline, WINDOW_JOB, WINDOW_STAGE, 63L)
        val raw = arcWindowJson(outline.contentHash)
        val plan = parsedArcWindow(raw)
        val accepted = executeAndValidate(
            stageId = WINDOW_STAGE,
            attemptId = "attempt.window",
            raw = raw,
            contract = ArcWindowPlanOutputContractV1,
            acquiredAt = 65L,
            validatedAt = 80L,
            generationId = WINDOW_JOB,
        )
        val expected = ArcWindowPlanningExpectation(
            masterOutlineContentHash = outline.contentHash,
            parentOutlineContentHash = outline.contentHash,
            targetChapterCount = 80,
            selection = setup.selection,
        )
        val draft = ArcWindowPlanningPersistenceMapper.map(
            plan = plan,
            expected = expected,
            ids = ArcWindowPlanningPersistenceIds(
                bookId = BOOK_ID,
                masterOutlineRevisionId = OUTLINE_REVISION,
                parentOutlineRevisionId = OUTLINE_REVISION,
                parentRevisionNo = 1,
                outlineRevisionId = WINDOW_REVISION,
                generationStageId = WINDOW_STAGE,
            ),
            committedAt = 81L,
        )
        val repository = ArcWindowPlanningCommitRepository(database, artifactStore)
        val result = repository.commit(accepted.commitPermit, draft)

        assertEquals(1, result.windowStartChapter)
        assertEquals(8, result.windowEndChapter)
        assertEquals(9, result.nextWindowStartChapter)
        assertEquals(GenerationJobStatus.COMPLETED, states.findJob(WINDOW_JOB)?.status)
        assertEquals(WINDOW_REVISION, scalarString("SELECT current_outline_revision_id FROM book_memory_head"))
        assertEquals(2L, scalarLong("SELECT COUNT(*) FROM outline_revision"))
        assertEquals(8L, scalarLong("SELECT COUNT(*) FROM outline_node WHERE node_type = 'CHAPTER'"))
        assertEquals(8L, scalarLong("SELECT MAX(planned_chapter_index) FROM outline_node"))
        assertEquals(
            UsageLedgerStatus.FINAL.name,
            scalarString("SELECT status FROM usage_ledger WHERE attempt_id = 'attempt.window'"),
        )

        val replay = repository.commit(accepted.commitPermit, draft)
        assertTrue(replay.replayed)
        assertEquals(2L, scalarLong("SELECT COUNT(*) FROM outline_revision"))
        assertEquals(10L, scalarLong("SELECT COUNT(*) FROM outline_node WHERE outline_revision_id = '$WINDOW_REVISION'"))

        val arcNodeHash = draft.nodes.single { it.nodeType.name == "ARC" }.contentHash
        val secondSetup = createWindowJob(
            outline = outline,
            jobId = SECOND_WINDOW_JOB,
            stageId = SECOND_WINDOW_STAGE,
            createdAt = 90L,
            nextChapterIndex = 9,
            parentOutlineRevisionId = WINDOW_REVISION,
            parentOutlineContentHash = plan.contentHash,
            activeArc = ActiveArcAnchor(plan.arcId, plan.arcStartChapter, plan.arcEndChapter, arcNodeHash),
        )
        val secondRaw = arcWindowJson(
            outlineHash = outline.contentHash,
            parentHash = plan.contentHash,
            windowStart = 9,
            windowEnd = 16,
            nextWindow = 17,
        )
        val secondPlan = parsedArcWindow(secondRaw)
        val secondAccepted = executeAndValidate(
            SECOND_WINDOW_STAGE,
            "attempt.window.second",
            secondRaw,
            ArcWindowPlanOutputContractV1,
            93L,
            108L,
            SECOND_WINDOW_JOB,
        )
        val secondDraft = ArcWindowPlanningPersistenceMapper.map(
            secondPlan,
            ArcWindowPlanningExpectation(
                outline.contentHash,
                plan.contentHash,
                80,
                secondSetup.selection,
            ),
            ArcWindowPlanningPersistenceIds(
                BOOK_ID,
                OUTLINE_REVISION,
                WINDOW_REVISION,
                2,
                SECOND_WINDOW_REVISION,
                SECOND_WINDOW_STAGE,
            ),
            109L,
        )
        val secondResult = repository.commit(secondAccepted.commitPermit, secondDraft)
        assertEquals(9, secondResult.windowStartChapter)
        assertEquals(16, secondResult.windowEndChapter)
        assertEquals(SECOND_WINDOW_REVISION, scalarString("SELECT current_outline_revision_id FROM book_memory_head"))
        assertEquals(3L, scalarLong("SELECT COUNT(*) FROM outline_revision"))
        assertEquals(16L, scalarLong("SELECT COUNT(*) FROM outline_node WHERE node_type = 'CHAPTER'"))
        assertEquals(16L, scalarLong("SELECT MAX(planned_chapter_index) FROM outline_node"))
    }

    @Test
    fun changedParentHashRollsBackArcWindowWithoutMovingTheOutlineHead() = runBlocking {
        val outline = completeInitialPlanningForWindow()
        val setup = createWindowJob(outline, WINDOW_JOB, WINDOW_STAGE, 63L)
        val raw = arcWindowJson(outline.contentHash)
        val plan = parsedArcWindow(raw)
        val accepted = executeAndValidate(
            WINDOW_STAGE,
            "attempt.window.rollback",
            raw,
            ArcWindowPlanOutputContractV1,
            65L,
            80L,
            WINDOW_JOB,
        )
        val valid = ArcWindowPlanningPersistenceMapper.map(
            plan,
            ArcWindowPlanningExpectation(
                outline.contentHash,
                outline.contentHash,
                80,
                setup.selection,
            ),
            ArcWindowPlanningPersistenceIds(
                BOOK_ID,
                OUTLINE_REVISION,
                OUTLINE_REVISION,
                1,
                WINDOW_REVISION,
                WINDOW_STAGE,
            ),
            81L,
        )
        val failure = expectFailure {
            ArcWindowPlanningCommitRepository(database, artifactStore).commit(
                accepted.commitPermit,
                valid.copy(parentOutlineContentHash = "f".repeat(64)),
            )
        }

        assertTrue(failure is IllegalArgumentException)
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM outline_revision"))
        assertEquals(OUTLINE_REVISION, scalarString("SELECT current_outline_revision_id FROM book_memory_head"))
        assertEquals(GenerationStageStatus.COMMITTING, states.findStage(WINDOW_STAGE)?.status)
        assertEquals(WINDOW_STAGE, states.findJob(WINDOW_JOB)?.currentStageId)
        assertEquals(
            UsageLedgerStatus.PROVISIONAL.name,
            scalarString("SELECT status FROM usage_ledger WHERE attempt_id = 'attempt.window.rollback'"),
        )
    }

    @Test
    fun fastLaneBootstrapAllowsOnlyChapterOneAndProviderOpenRechecksPersistedEvidence() = runBlocking {
        val seedRaw = seedJson()
        val seed = parsedSeed(seedRaw)
        val seedAccepted = executeAndValidate(
            SEED_STAGE,
            "attempt.fast.seed",
            seedRaw,
            StorySeedOutputContractV1,
            4L,
            20L,
        )
        commits.commitStorySeed(
            seedAccepted.commitPermit,
            InitialPlanningPersistenceMapper.storySeed(seed, 80, BIBLE_STAGE, 21L),
        )
        insertPlannedChapter("chapter.fast.1", 1)
        insertPlannedChapter("chapter.fast.2", 2)

        val fastSetup = FirstChapterFastLaneJobFactory.create(
            FirstChapterFastLaneJobSpec(
                jobId = FAST_JOB,
                stageId = FAST_STAGE,
                bookId = BOOK_ID,
                chapterId = "chapter.fast.1",
                chapterIndex = 1,
                userIntentJson = "{}",
                budgetSnapshotJson = "{}",
                creationSnapshotHash = "a".repeat(64),
                promptBindingHash = "c".repeat(64),
                seedStageId = SEED_STAGE,
                seedRawOutputHash = sha256(seedRaw),
                seedContentHash = seed.contentHash,
                createdAt = 22L,
            ),
        )
        GenerationJobSetupRepository(database).create(fastSetup)
        states.transitionJob(FAST_JOB, GenerationJobStatus.CREATED, JobEvent.VALIDATION_PASSED, 23L)
        states.transitionStage(
            FAST_STAGE,
            GenerationStageStatus.PENDING,
            StageEvent.DEPENDENCIES_SATISFIED,
            23L,
        )
        states.acquireJobLease(FAST_JOB, "job-worker.fast", 24L)
        val bootstrapRaw = firstChapterBootstrapJson(seed.contentHash)
        val bootstrap = parsedFirstChapterBootstrap(bootstrapRaw)
        assertTrue(FirstChapterBootstrapValidator.validate(bootstrap, seed) is FirstChapterBootstrapValidationResult.Valid)
        val bootstrapAccepted = executeAndValidate(
            FAST_STAGE,
            "attempt.fast.bootstrap",
            bootstrapRaw,
            FirstChapterBootstrapOutputContractV1,
            25L,
            40L,
            FAST_JOB,
        )
        val commitDraft = FirstChapterFastLanePersistenceMapper.map(
            bootstrap,
            app.zhijuan.core.database.generation.FinalUsageCommit.UNKNOWN,
            41L,
        )
        val tamperedBootstrap = commitDraft.canonicalJson.replace("\"ageYears\":22", "\"ageYears\":23")
        expectFailure {
            FirstChapterFastLaneCommitRepository(database, artifactStore).commit(
                bootstrapAccepted.commitPermit,
                commitDraft.copy(
                    canonicalJson = tamperedBootstrap,
                    contentHash = sha256(tamperedBootstrap.encodeToByteArray()),
                ),
            )
        }
        assertEquals(GenerationStageStatus.COMMITTING, states.findStage(FAST_STAGE)?.status)
        assertEquals(
            null,
            scalarString("SELECT output_reference_json FROM generation_stage WHERE stage_id = '$FAST_STAGE'"),
        )
        val fastCommit = FirstChapterFastLaneCommitRepository(database, artifactStore).commit(
            bootstrapAccepted.commitPermit,
            commitDraft,
        )
        assertTrue(fastCommit.jobCompleted)
        assertTrue(
            FirstChapterFastLaneCommitRepository(database, artifactStore)
                .commit(bootstrapAccepted.commitPermit, commitDraft).replayed,
        )

        val gates = ChapterProgressionGateRepository(database)
        val first = gates.authorize(
            BOOK_ID,
            "chapter.fast.1",
            FirstChapterGenerationMode.FAST_LANE,
            SEED_STAGE,
            FAST_STAGE,
        ) as ChapterProgressionAuthorization.Ready
        val second = gates.authorize(
            BOOK_ID,
            "chapter.fast.2",
            FirstChapterGenerationMode.FAST_LANE,
            SEED_STAGE,
            FAST_STAGE,
        ) as ChapterProgressionAuthorization.Blocked
        assertEquals(ChapterProgressionBlockReason.STORY_BIBLE_MISSING, second.reason)

        createGuardedDraftJob(
            jobId = GUARDED_FIRST_JOB,
            stageId = GUARDED_FIRST_STAGE,
            chapterId = "chapter.fast.1",
            inputSourcesJson = first.permit.bindInto("{}"),
            createdAt = 42L,
        )
        val prepared = drafts.prepareBeforeSend(
            requestIntent("attempt.fast.chapter1", GUARDED_FIRST_STAGE, 45L),
            BudgetedGenerationTestSupport.budgetedDraft(
                attemptId = "attempt.fast.chapter1",
                connectionId = "connection.fixture",
            ),
            requireNotNull(states.findStage(GUARDED_FIRST_STAGE)?.leaseToken),
        )
        drafts.claimForProviderOpen(prepared, 46L)

        insertCommittedChapterVersion(
            chapterId = "chapter.fast.1",
            versionId = "chapter.fast.1.version.1",
            contentHash = "8".repeat(64),
        )
        val postFirstSetup = PostFirstChapterPlanningJobFactory.create(
            PostFirstChapterPlanningJobSpec(
                jobId = POST_FIRST_JOB,
                bookId = BOOK_ID,
                userIntentJson = "{}",
                budgetSnapshotJson = "{}",
                seedStageId = SEED_STAGE,
                seedRawOutputHash = sha256(seedRaw),
                seedContentHash = seed.contentHash,
                chapterId = "chapter.fast.1",
                chapterVersionId = "chapter.fast.1.version.1",
                chapterContentHash = "8".repeat(64),
                bibleStageId = POST_FIRST_BIBLE_STAGE,
                outlineStageId = POST_FIRST_OUTLINE_STAGE,
                createdAt = 47L,
            ),
        )
        GenerationJobSetupRepository(database).create(postFirstSetup)
        states.transitionJob(POST_FIRST_JOB, GenerationJobStatus.CREATED, JobEvent.VALIDATION_PASSED, 48L)
        states.transitionStage(
            POST_FIRST_BIBLE_STAGE,
            GenerationStageStatus.PENDING,
            StageEvent.DEPENDENCIES_SATISFIED,
            48L,
        )
        states.acquireJobLease(POST_FIRST_JOB, "worker.post-first", 49L)
        val postBibleRaw = bibleJson(seed.contentHash)
        val postBible = parsedBible(postBibleRaw)
        val postBibleAccepted = executeAndValidate(
            POST_FIRST_BIBLE_STAGE,
            "attempt.post-first.bible",
            postBibleRaw,
            StoryBibleOutputContractV1,
            50L,
            65L,
            POST_FIRST_JOB,
        )
        commits.commitStoryBible(
            postBibleAccepted.commitPermit,
            InitialPlanningPersistenceMapper.storyBible(
                seed,
                postBible,
                BOOK_ID,
                POST_FIRST_BIBLE_REVISION,
                POST_FIRST_BIBLE_STAGE,
                POST_FIRST_OUTLINE_STAGE,
                66L,
            ),
        )
        val postOutlineRaw = outlineJson(postBible.contentHash)
        val postOutline = parsedOutline(postOutlineRaw)
        val postOutlineAccepted = executeAndValidate(
            POST_FIRST_OUTLINE_STAGE,
            "attempt.post-first.outline",
            postOutlineRaw,
            MasterOutlineOutputContractV1,
            67L,
            80L,
            POST_FIRST_JOB,
        )
        commits.commitMasterOutline(
            postOutlineAccepted.commitPermit,
            InitialPlanningPersistenceMapper.masterOutline(
                postBible,
                postOutline,
                80,
                BOOK_ID,
                POST_FIRST_OUTLINE_REVISION,
                POST_FIRST_OUTLINE_STAGE,
                81L,
            ),
        )
        val postWindowSetup = createWindowJob(
            outline = postOutline,
            jobId = POST_FIRST_WINDOW_JOB,
            stageId = POST_FIRST_WINDOW_STAGE,
            createdAt = 83L,
            masterOutlineRevisionId = POST_FIRST_OUTLINE_REVISION,
            parentOutlineRevisionId = POST_FIRST_OUTLINE_REVISION,
            parentOutlineContentHash = postOutline.contentHash,
        )
        val postWindowRaw = arcWindowJson(postOutline.contentHash)
        val postWindowPlan = parsedArcWindow(postWindowRaw)
        val postWindowAccepted = executeAndValidate(
            POST_FIRST_WINDOW_STAGE,
            "attempt.post-first.window",
            postWindowRaw,
            ArcWindowPlanOutputContractV1,
            86L,
            100L,
            POST_FIRST_WINDOW_JOB,
        )
        ArcWindowPlanningCommitRepository(database, artifactStore).commit(
            postWindowAccepted.commitPermit,
            ArcWindowPlanningPersistenceMapper.map(
                postWindowPlan,
                ArcWindowPlanningExpectation(
                    postOutline.contentHash,
                    postOutline.contentHash,
                    80,
                    postWindowSetup.selection,
                ),
                ArcWindowPlanningPersistenceIds(
                    BOOK_ID,
                    POST_FIRST_OUTLINE_REVISION,
                    POST_FIRST_OUTLINE_REVISION,
                    1,
                    POST_FIRST_WINDOW_REVISION,
                    POST_FIRST_WINDOW_STAGE,
                ),
                101L,
            ),
        )
        val unlockedSecond = gates.authorize(
            BOOK_ID,
            "chapter.fast.2",
            FirstChapterGenerationMode.FAST_LANE,
        ) as ChapterProgressionAuthorization.Ready

        createGuardedDraftJob(
            jobId = GUARDED_SECOND_JOB,
            stageId = GUARDED_SECOND_STAGE,
            chapterId = "chapter.fast.2",
            inputSourcesJson =
                "{\"chapterProgressionGate\":{\"mode\":\"FAST_LANE\",\"evidenceHash\":\"${"f".repeat(64)}\"}}",
            createdAt = 110L,
        )
        val blockedPrepared = drafts.prepareBeforeSend(
            requestIntent("attempt.fast.chapter2", GUARDED_SECOND_STAGE, 113L),
            BudgetedGenerationTestSupport.budgetedDraft(
                attemptId = "attempt.fast.chapter2",
                connectionId = "connection.fixture",
            ),
            requireNotNull(states.findStage(GUARDED_SECOND_STAGE)?.leaseToken),
        )
        expectFailure { drafts.claimForProviderOpen(blockedPrepared, 114L) }
        assertEquals(
            null,
            scalarString("SELECT sent_at FROM request_attempt WHERE attempt_id = 'attempt.fast.chapter2'"),
        )
        createGuardedDraftJob(
            jobId = GUARDED_UNLOCKED_SECOND_JOB,
            stageId = GUARDED_UNLOCKED_SECOND_STAGE,
            chapterId = "chapter.fast.2",
            inputSourcesJson = unlockedSecond.permit.bindInto("{}"),
            createdAt = 120L,
        )
        val unlockedPrepared = drafts.prepareBeforeSend(
            requestIntent("attempt.fast.chapter2.unlocked", GUARDED_UNLOCKED_SECOND_STAGE, 123L),
            BudgetedGenerationTestSupport.budgetedDraft(
                attemptId = "attempt.fast.chapter2.unlocked",
                connectionId = "connection.fixture",
            ),
            requireNotNull(states.findStage(GUARDED_UNLOCKED_SECOND_STAGE)?.leaseToken),
        )
        val unlockedClaim = drafts.claimForProviderOpen(unlockedPrepared, 124L)
        assertEquals("attempt.fast.chapter2.unlocked", unlockedClaim.attemptId)
    }

    @Test
    fun fullPlanningGateBlocksChapterTwoUntilItsArcWindowIsCommitted() = runBlocking {
        val outline = completeInitialPlanningForWindow()
        insertPlannedChapter("chapter.full.1", 1)
        insertPlannedChapter("chapter.full.2", 2)
        insertCommittedChapterVersion(
            chapterId = "chapter.full.1",
            versionId = "chapter.full.1.version.1",
            contentHash = "7".repeat(64),
        )
        val gates = ChapterProgressionGateRepository(database)
        val before = gates.authorize(
            BOOK_ID,
            "chapter.full.2",
            FirstChapterGenerationMode.FULL_PLANNING,
        ) as ChapterProgressionAuthorization.Blocked
        assertEquals(ChapterProgressionBlockReason.TARGET_CHAPTER_WINDOW_MISSING, before.reason)

        val setup = createWindowJob(outline, WINDOW_JOB, WINDOW_STAGE, 63L)
        val raw = arcWindowJson(outline.contentHash)
        val plan = parsedArcWindow(raw)
        val accepted = executeAndValidate(
            WINDOW_STAGE,
            "attempt.gate.window",
            raw,
            ArcWindowPlanOutputContractV1,
            65L,
            80L,
            WINDOW_JOB,
        )
        val draft = ArcWindowPlanningPersistenceMapper.map(
            plan,
            ArcWindowPlanningExpectation(outline.contentHash, outline.contentHash, 80, setup.selection),
            ArcWindowPlanningPersistenceIds(
                BOOK_ID,
                OUTLINE_REVISION,
                OUTLINE_REVISION,
                1,
                WINDOW_REVISION,
                WINDOW_STAGE,
            ),
            81L,
        )
        ArcWindowPlanningCommitRepository(database, artifactStore).commit(accepted.commitPermit, draft)

        assertTrue(
            gates.authorize(
                BOOK_ID,
                "chapter.full.2",
                FirstChapterGenerationMode.FULL_PLANNING,
            ) is ChapterProgressionAuthorization.Ready,
        )
        val incompatibleFastLane = gates.authorize(
            BOOK_ID,
            "chapter.full.2",
            FirstChapterGenerationMode.FAST_LANE,
        ) as ChapterProgressionAuthorization.Blocked
        assertEquals(
            ChapterProgressionBlockReason.FULL_PLANNING_NOT_ADAPTED_TO_FIRST_CHAPTER,
            incompatibleFastLane.reason,
        )
        assertTrue(
            gates.authorize(
                BOOK_ID,
                "chapter.full.1",
                FirstChapterGenerationMode.FULL_PLANNING,
            ) is ChapterProgressionAuthorization.Ready,
        )
    }

    private suspend fun executeAndValidate(
        stageId: String,
        attemptId: String,
        raw: ByteArray,
        contract: StructuredOutputContract,
        acquiredAt: Long,
        validatedAt: Long,
        generationId: String = JOB_ID,
    ): StructuredOutputValidationDecision.Accepted {
        states.acquireStageLease(stageId, "worker.$stageId", acquiredAt)
        val token = requireNotNull(states.findStage(stageId)?.leaseToken)
        val prepared = drafts.prepareBeforeSend(
            RequestIntentDraft(
                attemptId = attemptId,
                usageLedgerId = "ledger.$attemptId",
                stageId = stageId,
                retryParentAttemptId = null,
                connectionSnapshotJson = "{\"secretRefId\":\"fixture-only\"}",
                modelSnapshotJson = "{\"model\":\"local-fake\"}",
                protocolSnapshotJson = "{\"protocol\":\"fixture\"}",
                inputHash = "b".repeat(64),
                streamDraftRef = null,
                createdAt = acquiredAt,
            ),
            BudgetedGenerationTestSupport.budgetedDraft(
                attemptId = attemptId,
                connectionId = "connection.fixture",
            ),
            token,
        )
        val completed = AuditedStreamingProviderExecutor(
            drafts = drafts,
            outputs = outputs,
            clock = PlanningClock(acquiredAt + 1L),
        ).execute(
            prepared,
            PlanningFakeAdapter(raw.decodeToString()),
            ProviderConnectionProfile.create(
                connectionId = "connection.fixture",
                protocol = ProviderProtocol.OPENAI_CHAT_COMPAT,
                baseUrl = "https://example.invalid",
            ),
            GenerationRequest(
                requestId = "request.$attemptId",
                generationId = generationId,
                stageId = stageId,
                attemptId = attemptId,
                modelId = ProviderModelId.from("local-fake"),
                prompt = ProviderPrompt(
                    listOf(PromptPart(PromptLayer.STAGE_CONTRACT, SensitiveProviderText.from("fixture"))),
                ),
                parameters = GenerationParameters(maxOutputTokens = 2_048),
                structuredOutputSchema = contract.providerSchema,
                stream = true,
                timeouts = ProviderTimeoutPolicy(1_000, 1_000, 1_000, 2_000),
                idempotencyKey = "request-idem.$attemptId",
            ),
        ) as AuditedStreamingExecutionResult.Completed
        val decision = StructuredOutputValidationCoordinator(outputs).validate(completed, contract, validatedAt)
        assertTrue(decision is StructuredOutputValidationDecision.Accepted)
        return decision as StructuredOutputValidationDecision.Accepted
    }

    private fun parsedSeed(raw: ByteArray): StorySeedV1 = when (val result = parser.storySeed(raw)) {
        is PlanningOutputValidationResult.Valid -> result.value
        is PlanningOutputValidationResult.Invalid -> error(result.report.toString())
    }

    private fun parsedBible(raw: ByteArray): StoryBibleV1 = when (val result = parser.storyBible(raw)) {
        is PlanningOutputValidationResult.Valid -> result.value
        is PlanningOutputValidationResult.Invalid -> error(result.report.toString())
    }

    private fun parsedOutline(raw: ByteArray): MasterOutlineV1 = when (val result = parser.masterOutline(raw)) {
        is PlanningOutputValidationResult.Valid -> result.value
        is PlanningOutputValidationResult.Invalid -> error(result.report.toString())
    }

    private fun parsedArcWindow(raw: ByteArray): ArcWindowPlanV1 = when (val result = windowParser.parse(raw)) {
        is PlanningOutputValidationResult.Valid -> result.value
        is PlanningOutputValidationResult.Invalid -> error(result.report.toString())
    }

    private fun parsedFirstChapterBootstrap(raw: ByteArray): FirstChapterBootstrapV1 =
        when (val result = FirstChapterBootstrapOutputParser().parse(raw)) {
            is PlanningOutputValidationResult.Valid -> result.value
            is PlanningOutputValidationResult.Invalid -> error(result.report.toString())
        }

    private suspend fun createGuardedDraftJob(
        jobId: String,
        stageId: String,
        chapterId: String,
        inputSourcesJson: String,
        createdAt: Long,
    ) {
        GenerationJobSetupRepository(database).create(
            GenerationJobSetup(
                jobId = jobId,
                bookId = BOOK_ID,
                jobType = GenerationJobType.CONTINUE_BOOK,
                userIntentJson = "{}",
                budgetSnapshotJson = "{}",
                promptBundleVersion = app.zhijuan.core.task.PromptBundleCatalogV1.BUNDLE_VERSION,
                stages = listOf(
                    GenerationStageSetup(
                        stageId = stageId,
                        phase = GenerationPhase.DRAFT_CHAPTER,
                        targetType = GenerationTargetType.CHAPTER,
                        targetId = chapterId,
                        inputVersionHash = "e".repeat(64),
                        idempotencyKey = "idem.$stageId",
                        maxAttempts = 2,
                        inputSourcesJson = inputSourcesJson,
                    ),
                ),
                createdAt = createdAt,
            ),
        )
        states.transitionJob(jobId, GenerationJobStatus.CREATED, JobEvent.VALIDATION_PASSED, createdAt + 1L)
        states.transitionStage(
            stageId,
            GenerationStageStatus.PENDING,
            StageEvent.DEPENDENCIES_SATISFIED,
            createdAt + 1L,
        )
        states.acquireJobLease(jobId, "worker.$jobId", createdAt + 2L)
        states.acquireStageLease(stageId, "worker.$stageId", createdAt + 2L)
    }

    private fun requestIntent(attemptId: String, stageId: String, createdAt: Long) = RequestIntentDraft(
        attemptId = attemptId,
        usageLedgerId = "ledger.$attemptId",
        stageId = stageId,
        retryParentAttemptId = null,
        connectionSnapshotJson = "{\"secretRefId\":\"fixture-only\"}",
        modelSnapshotJson = "{\"model\":\"local-fake\"}",
        protocolSnapshotJson = "{\"protocol\":\"fixture\"}",
        inputHash = "d".repeat(64),
        streamDraftRef = null,
        createdAt = createdAt,
    )

    private fun insertPlannedChapter(chapterId: String, chapterIndex: Int) {
        database.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO chapter (
                chapter_id, book_id, chapter_index, planned_title, display_title, status,
                current_version_id, consistency_status, created_at, updated_at
            ) VALUES (
                '$chapterId', '$BOOK_ID', $chapterIndex, 'Chapter $chapterIndex', 'Chapter $chapterIndex',
                'PLANNED', NULL, 'UNKNOWN', 1, 1
            )
            """.trimIndent(),
        )
    }

    private fun insertCommittedChapterVersion(
        chapterId: String,
        versionId: String,
        contentHash: String,
    ) {
        val sql = database.openHelper.writableDatabase
        sql.execSQL(
            """
            INSERT INTO chapter_version (
                chapter_version_id, chapter_id, version_no, content, character_count, content_hash,
                source, parent_version_id, generation_stage_id, model_snapshot_json, created_at
            ) VALUES (
                '$versionId', '$chapterId', 1, 'Committed first chapter', 23, '$contentHash',
                'USER_EDIT', NULL, NULL, NULL, 62
            )
            """.trimIndent(),
        )
        sql.execSQL(
            """
            UPDATE chapter
            SET current_version_id = '$versionId', status = 'READY', consistency_status = 'VALID', updated_at = 62
            WHERE chapter_id = '$chapterId'
            """.trimIndent(),
        )
    }

    private suspend fun completeInitialPlanningForWindow(): MasterOutlineV1 {
        val seedRaw = seedJson()
        val seed = parsedSeed(seedRaw)
        val seedAccepted = executeAndValidate(
            SEED_STAGE,
            "attempt.window.seed",
            seedRaw,
            StorySeedOutputContractV1,
            4L,
            20L,
        )
        commits.commitStorySeed(
            seedAccepted.commitPermit,
            InitialPlanningPersistenceMapper.storySeed(seed, 80, BIBLE_STAGE, 21L),
        )
        val bibleRaw = bibleJson(seed.contentHash)
        val bible = parsedBible(bibleRaw)
        val bibleAccepted = executeAndValidate(
            BIBLE_STAGE,
            "attempt.window.bible",
            bibleRaw,
            StoryBibleOutputContractV1,
            22L,
            40L,
        )
        commits.commitStoryBible(
            bibleAccepted.commitPermit,
            InitialPlanningPersistenceMapper.storyBible(
                seed,
                bible,
                BOOK_ID,
                BIBLE_REVISION,
                BIBLE_STAGE,
                OUTLINE_STAGE,
                41L,
            ),
        )
        val outlineRaw = outlineJson(bible.contentHash)
        val outline = parsedOutline(outlineRaw)
        val outlineAccepted = executeAndValidate(
            OUTLINE_STAGE,
            "attempt.window.outline",
            outlineRaw,
            MasterOutlineOutputContractV1,
            42L,
            60L,
        )
        commits.commitMasterOutline(
            outlineAccepted.commitPermit,
            InitialPlanningPersistenceMapper.masterOutline(
                bible,
                outline,
                80,
                BOOK_ID,
                OUTLINE_REVISION,
                OUTLINE_STAGE,
                61L,
            ),
        )
        return outline
    }

    private suspend fun createWindowJob(
        outline: MasterOutlineV1,
        jobId: String,
        stageId: String,
        createdAt: Long,
        masterOutlineRevisionId: String = OUTLINE_REVISION,
        nextChapterIndex: Int = 1,
        parentOutlineRevisionId: String = masterOutlineRevisionId,
        parentOutlineContentHash: String = outline.contentHash,
        activeArc: ActiveArcAnchor? = null,
    ): app.zhijuan.core.database.generation.ArcWindowPlanningJobSetup {
        val setup = ArcWindowPlanningJobFactory.create(
            ArcWindowPlanningJobSpec(
                jobId = jobId,
                stageId = stageId,
                bookId = BOOK_ID,
                userIntentJson = "{\"nextChapterIndex\":1}",
                budgetSnapshotJson = "{\"mode\":\"fixture\"}",
                masterOutlineRevisionId = masterOutlineRevisionId,
                masterOutlineContentHash = outline.contentHash,
                parentOutlineRevisionId = parentOutlineRevisionId,
                parentOutlineContentHash = parentOutlineContentHash,
                windowInput = ArcPlanningWindowInput(80, nextChapterIndex, 1, 26, activeArc),
                createdAt = createdAt,
            ),
        )
        GenerationJobSetupRepository(database).create(setup.generationSetup)
        states.transitionJob(jobId, GenerationJobStatus.CREATED, JobEvent.VALIDATION_PASSED, createdAt + 1L)
        states.transitionStage(
            stageId,
            GenerationStageStatus.PENDING,
            StageEvent.DEPENDENCIES_SATISFIED,
            updatedAt = createdAt + 1L,
        )
        states.acquireJobLease(jobId, "job-worker.window", createdAt + 2L)
        return setup
    }

    private fun seedJson(): ByteArray = """
        {"schemaVersion":1,"targetChapterCount":80,"premise":"An archive restorer discovers that the city's memory is being rewritten.","centralConflict":"She must protect her identity while exposing the alteration network.","storyPromise":"A steadily escalating mystery tests relationships and evidence.","endingDirection":"The evidence becomes public and every choice has a lasting cost.","characters":[{"entityId":"char.lin","name":"Lin Lan","ageYears":22,"adultStatus":"CONFIRMED_ADULT","realIdentifiablePerson":false,"intimacyRole":true,"storyRole":"protagonist","desire":"Recover her missing family history","obstacle":"Her own testimony may have been altered"}],"openQuestions":[]}
    """.trimIndent().encodeToByteArray()

    private fun bibleJson(seedHash: String): ByteArray = """
        {"schemaVersion":1,"seedContentHash":"$seedHash","characters":[{"entityId":"char.lin","canonicalName":"Lin Lan","aliases":[],"ageYears":22,"adultStatus":"CONFIRMED_ADULT","realIdentifiablePerson":false,"storyRole":"protagonist and archive restorer","stableTraits":["careful","evidence-driven"],"goals":["Recover her family history"],"boundaries":["Does not harm uninvolved people"]}],"worldRules":[{"ruleId":"rule.memory","text":"Altered memories leave verifiable marks on physical records."}],"hardFacts":[{"factId":"fact.job","entityId":"char.lin","text":"Lin Lan restores historical archives for a living."}],"themes":["memory and identity"],"writingStyle":["limited viewpoint","progressive clues"],"forbiddenChanges":["Do not explain every alteration as an unconditional dream"]}
    """.trimIndent().encodeToByteArray()

    private fun outlineJson(bibleHash: String): ByteArray = """
        {"schemaVersion":1,"bibleContentHash":"$bibleHash","targetChapterCount":80,"title":"Warmth on Paper","endingPromise":"The final evidence becomes public while relationships retain the cost.","beats":[{"beatId":"beat.open","title":"The first seam","startChapter":1,"endChapter":26,"goal":"Establish the anomaly and protagonist goal","turningPoint":"Her signature appears in an archive that should not exist","outcome":"She begins a private investigation"},{"beatId":"beat.pressure","title":"Testimony pushes back","startChapter":27,"endChapter":53,"goal":"Increase opposition and relationship costs","turningPoint":"A trusted ally reports a contradictory memory","outcome":"The protagonist loses her safe position"},{"beatId":"beat.resolve","title":"The cost of disclosure","startChapter":54,"endChapter":80,"goal":"Complete the evidence chain and resolve choices","turningPoint":"The protagonist confirms her role in the early experiment","outcome":"She publishes the truth and begins rebuilding relationships"}]}
    """.trimIndent().encodeToByteArray()

    private fun arcWindowJson(
        outlineHash: String,
        parentHash: String = outlineHash,
        windowStart: Int = 1,
        windowEnd: Int = 8,
        nextWindow: Int? = 9,
    ): ByteArray {
        val chapters = (windowStart..windowEnd).joinToString(",") { chapter ->
            """{"chapterIndex":$chapter,"title":"Chapter $chapter","goal":"Advance the investigation","conflict":"Evidence creates resistance","turn":"A clue changes meaning","outcome":"The next choice becomes unavoidable","hook":"A new contradiction appears","continuityCarry":["Preserve known locations and evidence"]}"""
        }
        val next = nextWindow?.toString() ?: "null"
        return """
            {"schemaVersion":1,"policyVersion":"zhijuan.arc-window-policy.v1","masterOutlineContentHash":"$outlineHash","parentOutlineContentHash":"$parentHash","targetChapterCount":80,"arc":{"arcId":"arc.1.26","startChapter":1,"endChapter":26,"title":"The first seam","dramaticQuestion":"Can the evidence survive institutional pressure?","openingState":"The anomaly is private and unverified.","closingState":"The protagonist holds a verifiable chain at personal cost.","milestones":[{"milestoneId":"milestone.first","chapterIndex":8,"purpose":"Confirm the anomaly","consequence":"Opposition identifies the investigation"}],"continuityConstraints":["Physical evidence cannot reset without an explained cause"]},"chapterWindow":{"windowId":"window.$windowStart.$windowEnd","startChapter":$windowStart,"endChapter":$windowEnd,"chapters":[$chapters]},"nextWindowStartChapter":$next}
        """.trimIndent().encodeToByteArray()
    }

    private fun firstChapterBootstrapJson(seedHash: String): ByteArray = """
        {"schemaVersion":1,"contractVersion":"zhijuan.first-chapter-fast-lane.v1","seedContentHash":"$seedHash","characters":[{"entityId":"char.lin","ageYears":22,"adultStatus":"CONFIRMED_ADULT","realIdentifiablePerson":false,"intimacyRole":true}],"coreWorldRules":["Altered memories leave verifiable marks on physical records."],"endingDirection":"The evidence becomes public and every choice has a lasting cost.","roughChapters":[{"chapterIndex":1,"goal":"Find the first anomaly","conflict":"The record contradicts memory","turn":"A signature matches","outcome":"A private inquiry begins","hook":"A second record appears"},{"chapterIndex":2,"goal":"Verify the signature","conflict":"Access is restricted","turn":"An ally remembers differently","outcome":"The evidence chain grows","hook":"The archive is monitored"},{"chapterIndex":3,"goal":"Protect the evidence","conflict":"A record is removed","turn":"A physical copy survives","outcome":"Opposition becomes visible","hook":"The copy names the protagonist"}],"chapterOnePlan":{"pointOfViewEntityId":"char.lin","openingState":"Routine restoration work","sceneSequence":["Discover an impossible archival seam","Verify the physical marks","Choose to preserve a private copy"],"closingState":"The anomaly is privately verified","finalHook":"Her signature appears on an impossible record"}}
    """.trimIndent().encodeToByteArray()

    private fun seedBook() {
        val sql = database.openHelper.writableDatabase
        sql.execSQL(
            """
            INSERT INTO book_creation_snapshot VALUES (
                'snapshot.initial', '{}', '{}', '{}', '{}', '{}', '{}',
                1, 'prompt-unassigned', 1, 'snapshot-hash', 1
            )
            """.trimIndent(),
        )
        sql.execSQL(
            """
            INSERT INTO book (
                book_id, creation_snapshot_id, title, title_source, status, length_mode,
                target_characters, target_chapters, minimum_chapters, length_policy_schema_version,
                branched_from_book_id, branched_from_chapter_version_id, completed_chapter_count,
                generation_status_summary, archived_at, deleted_at, created_at, updated_at
            ) VALUES (
                '$BOOK_ID', 'snapshot.initial', 'fixture', 'USER', 'DRAFT', 'SHORT',
                80000, 80, 80, 1, NULL, NULL, 0, 'ready', NULL, NULL, 1, 1
            )
            """.trimIndent(),
        )
    }

    private fun scalarString(sql: String): String? =
        database.openHelper.readableDatabase.query(sql).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }

    private fun scalarLong(sql: String): Long? =
        database.openHelper.readableDatabase.query(sql).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }

    private fun cleanArtifacts() {
        artifactStore.unlockAfterAuthentication()
        artifactStore.listArtifactReferenceIds().forEach(artifactStore::delete)
    }

    private suspend fun GenerationStreamingDraftRepository.claimForProviderOpen(
        request: PersistedStreamingRequest,
        validatedAt: Long,
    ) = claimForProviderOpen(
        request,
        validatedAt,
        BudgetedGenerationTestSupport.budgetedDestinationEvidence("connection.fixture"),
    )

    private fun sha256(value: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private suspend fun expectFailure(block: suspend () -> Unit): Throwable = try {
        block()
        throw AssertionError("Expected operation to fail.")
    } catch (error: Throwable) {
        if (error is AssertionError && error.message == "Expected operation to fail.") throw error
        error
    }

    private companion object {
        const val BOOK_ID = "book.initial"
        const val JOB_ID = "job.initial"
        const val SEED_STAGE = "stage.seed"
        const val BIBLE_STAGE = "stage.bible"
        const val OUTLINE_STAGE = "stage.outline"
        const val BIBLE_REVISION = "bible.initial.1"
        const val OUTLINE_REVISION = "outline.initial.1"
        const val WINDOW_JOB = "job.window.1"
        const val WINDOW_STAGE = "stage.window.1"
        const val WINDOW_REVISION = "outline.window.2"
        const val SECOND_WINDOW_JOB = "job.window.2"
        const val SECOND_WINDOW_STAGE = "stage.window.2"
        const val SECOND_WINDOW_REVISION = "outline.window.3"
        const val FAST_JOB = "job.fast.bootstrap"
        const val FAST_STAGE = "stage.fast.bootstrap"
        const val GUARDED_FIRST_JOB = "job.fast.chapter1"
        const val GUARDED_FIRST_STAGE = "stage.fast.chapter1"
        const val GUARDED_SECOND_JOB = "job.fast.chapter2"
        const val GUARDED_SECOND_STAGE = "stage.fast.chapter2"
        const val POST_FIRST_JOB = "job.fast.post-first"
        const val POST_FIRST_BIBLE_STAGE = "stage.fast.post-first.bible"
        const val POST_FIRST_OUTLINE_STAGE = "stage.fast.post-first.outline"
        const val POST_FIRST_BIBLE_REVISION = "bible.fast.post-first.1"
        const val POST_FIRST_OUTLINE_REVISION = "outline.fast.post-first.1"
        const val POST_FIRST_WINDOW_JOB = "job.fast.post-first.window"
        const val POST_FIRST_WINDOW_STAGE = "stage.fast.post-first.window"
        const val POST_FIRST_WINDOW_REVISION = "outline.fast.post-first.window.2"
        const val GUARDED_UNLOCKED_SECOND_JOB = "job.fast.chapter2.unlocked"
        const val GUARDED_UNLOCKED_SECOND_STAGE = "stage.fast.chapter2.unlocked"
    }
}

private class PlanningClock(startAt: Long) : GenerationExecutionClock {
    private val next = AtomicLong(startAt)
    override fun nowMillis(): Long = next.getAndIncrement()
}

private class PlanningFakeAdapter(
    private val output: String,
) : ProviderAdapter {
    override val protocol = ProviderProtocol.OPENAI_CHAT_COMPAT
    override val adapterVersion = "local-fixture-1"
    override val requestRecoveryCapability = ProviderRequestRecoveryCapability.NOT_SUPPORTED

    override suspend fun testConnection(profile: ProviderConnectionProfile): ConnectionTestResult =
        error("No connection is used by the local planning fixture.")

    override suspend fun listModels(profile: ProviderConnectionProfile): ModelListResult =
        error("No model list is used by the local planning fixture.")

    override suspend fun getCapabilities(
        profile: ProviderConnectionProfile,
        modelId: ProviderModelId,
    ): CapabilityResult = error("No capability call is used by the local planning fixture.")

    override fun generate(
        profile: ProviderConnectionProfile,
        request: GenerationRequest,
    ): Flow<ProviderStreamEvent> = flow {
        emit(ProviderStreamEvent.Started())
        emit(ProviderStreamEvent.StructuredDelta(SensitiveProviderText.from(output)))
        emit(
            ProviderStreamEvent.UsageUpdate(
                ProviderUsage(
                    inputTokens = 10,
                    outputTokens = 20,
                    cachedInputTokens = null,
                    cachedWriteTokens = null,
                    reasoningTokens = null,
                    totalTokens = 30,
                    quality = ProviderUsageQuality.PROVIDER_REPORTED,
                ),
            ),
        )
        emit(ProviderStreamEvent.Completed(ProviderFinishReason.STOP))
    }

    override suspend fun cancel(
        profile: ProviderConnectionProfile,
        requestId: String,
    ) = ProviderCancellationResult.ALREADY_TERMINAL

    override suspend fun queryRequestRecovery(
        profile: ProviderConnectionProfile,
        remoteRequestId: app.zhijuan.provider.common.ProviderRemoteRequestId,
    ): ProviderRequestRecoveryResult = ProviderRequestRecoveryResult.NotSupported
}
