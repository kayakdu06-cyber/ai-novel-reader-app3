package app.zhijuan.feature.generation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zhijuan.core.database.LibraryDatabaseGuards
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.database.generation.ChapterEditRebuildEditedMemoryStageCommand
import app.zhijuan.core.database.generation.ChapterEditRebuildStageRepository
import app.zhijuan.core.database.generation.ChapterEditRebuildTrackingStageCommand
import app.zhijuan.core.database.generation.ChapterMemoryExtractionCommitDraft
import app.zhijuan.core.database.generation.ChapterMemoryExtractionCommitRepository
import app.zhijuan.core.database.generation.ChapterMemoryExtractionJobFactory
import app.zhijuan.core.database.generation.ChapterMemoryExtractionJobSpec
import app.zhijuan.core.database.generation.ChapterMemoryExtractionSourceV1
import app.zhijuan.core.database.generation.FinalUsageCommit
import app.zhijuan.core.database.generation.GenerationJobSetupRepository
import app.zhijuan.core.database.generation.GenerationOutputValidationRepository
import app.zhijuan.core.database.generation.GenerationStateRepository
import app.zhijuan.core.database.generation.GenerationStreamingDraftRepository
import app.zhijuan.core.database.generation.RequestIntentDraft
import app.zhijuan.core.database.library.ChapterEditRebuildExecutionPrepareCommand
import app.zhijuan.core.database.library.ChapterEditRebuildExecutionRepository
import app.zhijuan.core.database.library.ChapterEditRebuildPlanRequest
import app.zhijuan.core.database.library.ChapterEditRebuildPlanRepository
import app.zhijuan.core.model.AdultStatus
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.StoryEntityType
import app.zhijuan.core.model.UsageSource
import app.zhijuan.core.security.AndroidProtectedArtifactStore
import app.zhijuan.core.task.JobEvent
import app.zhijuan.core.task.StageEvent
import app.zhijuan.provider.common.CapabilityResult
import app.zhijuan.provider.common.ConnectionTestResult
import app.zhijuan.provider.common.GenerationRequest
import app.zhijuan.provider.common.ModelListResult
import app.zhijuan.provider.common.ProviderAdapter
import app.zhijuan.provider.common.ProviderCancellationResult
import app.zhijuan.provider.common.ProviderConnectionProfile
import app.zhijuan.provider.common.ProviderFinishReason
import app.zhijuan.provider.common.ProviderModelId
import app.zhijuan.provider.common.ProviderProtocol
import app.zhijuan.provider.common.ProviderStreamEvent
import app.zhijuan.provider.common.ProviderTimeoutPolicy
import app.zhijuan.provider.common.ProviderUsage
import app.zhijuan.provider.common.ProviderUsageQuality
import app.zhijuan.provider.common.SensitiveProviderText
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
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
class ChapterMemoryExtractionEndToEndTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: ZhijuanDatabase
    private lateinit var artifactStore: AndroidProtectedArtifactStore
    private lateinit var states: GenerationStateRepository
    private lateinit var drafts: GenerationStreamingDraftRepository
    private lateinit var outputs: GenerationOutputValidationRepository

    @Before
    fun setUp() = runBlocking {
        artifactStore = AndroidProtectedArtifactStore(context)
        cleanArtifacts()
        database = Room.inMemoryDatabaseBuilder(context, ZhijuanDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(LibraryDatabaseGuards.callback)
            .build()
            .also { it.openHelper.writableDatabase }
        states = GenerationStateRepository(database)
        drafts = GenerationStreamingDraftRepository(database, artifactStore)
        outputs = GenerationOutputValidationRepository(database, artifactStore)
        seedBookChapterAndEntity()
        BudgetedGenerationTestSupport.seedBudgetedRequestEnvironment(
            database = database,
            bookId = BOOK_ID,
            connectionId = "connection.memory",
        )
    }

    @After
    fun tearDown() {
        runCatching { cleanArtifacts() }
        database.close()
    }

    @Test
    fun validExtractionCommitsExactVersionAndReplaysWithoutDuplicateMemory() = runBlocking {
        val runtime = createRunningExtractionJob("job.memory.valid", "stage.memory.valid", 10L)
        val prepared = prepare(runtime, "attempt.memory.valid", "ledger.memory.valid", 13L)
        val bound = request(runtime, "attempt.memory.valid")
        val result = coordinator(20L).execute(
            persistedRequest = prepared,
            adapter = MemoryFakeAdapter(
                events = successfulEvents(validMemoryJson()),
            ),
            profile = profile(),
            boundRequest = bound,
        )

        assertTrue(result is ChapterMemoryExtractionResult.Accepted)
        result as ChapterMemoryExtractionResult.Accepted
        val derived = ChapterMemoryExtractionPersistenceMapper.map(
            memory = result.memory,
            spec = ChapterMemoryExtractionMappingSpec(
                bookId = BOOK_ID,
                generationStageId = runtime.stageId,
                modelSnapshotJson = MODEL_SNAPSHOT,
                createdAt = 60L,
            ),
        )
        val commitDraft = ChapterMemoryExtractionCommitDraft(
            source = runtime.source,
            extractionContentHash = derived.extractionContentHash,
            summary = derived.summary,
            entityEvents = derived.entityEvents,
            canonFacts = derived.canonFacts,
            usage = FinalUsageCommit(
                source = UsageSource.PROVIDER_REPORTED,
                inputTokens = 120L,
                outputTokens = 80L,
                cachedTokens = null,
                reasoningTokens = null,
                totalTokens = 200L,
            ),
            committedAt = 60L,
        )
        val repository = ChapterMemoryExtractionCommitRepository(database, artifactStore)

        val committed = repository.commit(result.commitPermit, commitDraft)
        val replayed = repository.commit(result.commitPermit, commitDraft)

        assertEquals(false, committed.replayed)
        assertEquals(true, replayed.replayed)
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM chapter_summary"))
        assertEquals(2L, scalarLong("SELECT COUNT(*) FROM entity_event"))
        assertEquals(2L, scalarLong("SELECT COUNT(*) FROM canon_fact"))
        assertEquals(5L, scalarLong("SELECT COUNT(*) FROM memory_search_document"))
        assertEquals(VERSION_ID, scalarString("SELECT chapter_version_id FROM chapter_summary"))
        assertEquals(VERSION_ID, scalarString("SELECT source_chapter_version_id FROM entity_event LIMIT 1"))
        assertEquals(VERSION_ID, scalarString("SELECT source_chapter_version_id FROM canon_fact LIMIT 1"))
        assertEquals("VALID", scalarString("SELECT status FROM chapter_summary"))
        assertEquals("SUCCEEDED", scalarString("SELECT status FROM generation_stage WHERE stage_id = '${runtime.stageId}'"))
        assertEquals("COMPLETED", scalarString("SELECT status FROM generation_job WHERE job_id = '${runtime.jobId}'"))
        assertEquals("FINAL", scalarString("SELECT status FROM usage_ledger WHERE attempt_id = 'attempt.memory.valid'"))
        assertEquals(200L, scalarLong("SELECT total_tokens FROM usage_ledger WHERE attempt_id = 'attempt.memory.valid'"))
        assertTrue(requireNotNull(scalarString("SELECT summary_json FROM chapter_summary")).contains("衣袖仍有裂口"))
    }

    @Test
    fun completedBoundMemoryStageAuthorizesTheFirstTrackingStage() = runBlocking {
        val editedSource = replaceCurrentChapterVersionForRebuild()
        val plan = ChapterEditRebuildPlanRepository(database).plan(
            ChapterEditRebuildPlanRequest(
                bookId = BOOK_ID,
                editedChapterId = CHAPTER_ID,
                editedVersionId = editedSource.chapterVersionId,
            ),
        )
        val execution = ChapterEditRebuildExecutionRepository(database).prepare(
            ChapterEditRebuildExecutionPrepareCommand(
                plan = plan,
                rewindId = "rewind.memory.e2e",
                preparedAt = 120L,
            ),
        )
        val rebuild = ChapterEditRebuildStageRepository(database)
        val memoryStage = rebuild.createEditedMemoryStage(
            ChapterEditRebuildEditedMemoryStageCommand(
                executionId = execution.executionId,
                userIntentJson = "{\"mode\":\"automatic\"}",
                budgetSnapshotJson = "{\"mode\":\"fixture\"}",
                createdAt = 130L,
            ),
        )
        states.transitionJob(
            memoryStage.jobId,
            GenerationJobStatus.CREATED,
            JobEvent.VALIDATION_PASSED,
            131L,
        )
        states.transitionStage(
            memoryStage.stageId,
            GenerationStageStatus.PENDING,
            StageEvent.DEPENDENCIES_SATISFIED,
            131L,
        )
        states.acquireJobLease(memoryStage.jobId, "worker.rebuild.memory", 132L)
        states.acquireStageLease(memoryStage.stageId, "worker.rebuild.memory.stage", 132L)
        val inputVersionHash = requireNotNull(
            scalarString(
                "SELECT input_version_hash FROM generation_stage WHERE stage_id = '${memoryStage.stageId}'",
            ),
        )
        val runtime = ExtractionRuntime(
            jobId = memoryStage.jobId,
            stageId = memoryStage.stageId,
            source = editedSource,
            inputVersionHash = inputVersionHash,
        )
        val prepared = prepare(runtime, "attempt.memory.rebuild", "ledger.memory.rebuild", 133L)
        val accepted = coordinator(140L).execute(
            persistedRequest = prepared,
            adapter = MemoryFakeAdapter(
                events = successfulEvents(
                    validMemoryJson(editedSource.chapterVersionId, editedSource.chapterContentHash),
                ),
            ),
            profile = profile(),
            boundRequest = request(runtime, "attempt.memory.rebuild", EDITED_CHAPTER_CONTENT),
        )
        assertTrue(accepted is ChapterMemoryExtractionResult.Accepted)
        accepted as ChapterMemoryExtractionResult.Accepted
        val derived = ChapterMemoryExtractionPersistenceMapper.map(
            memory = accepted.memory,
            spec = ChapterMemoryExtractionMappingSpec(
                bookId = BOOK_ID,
                generationStageId = runtime.stageId,
                modelSnapshotJson = MODEL_SNAPSHOT,
                createdAt = 160L,
            ),
        )
        ChapterMemoryExtractionCommitRepository(database, artifactStore).commit(
            accepted.commitPermit,
            ChapterMemoryExtractionCommitDraft(
                source = editedSource,
                extractionContentHash = derived.extractionContentHash,
                summary = derived.summary,
                entityEvents = derived.entityEvents,
                canonFacts = derived.canonFacts,
                usage = FinalUsageCommit(
                    source = UsageSource.PROVIDER_REPORTED,
                    inputTokens = 120L,
                    outputTokens = 80L,
                    cachedTokens = null,
                    reasoningTokens = null,
                    totalTokens = 200L,
                ),
                committedAt = 160L,
            ),
        )

        val tracking = rebuild.createFirstTrackingStage(
            ChapterEditRebuildTrackingStageCommand(
                executionId = execution.executionId,
                userIntentJson = "{\"mode\":\"automatic\"}",
                budgetSnapshotJson = "{\"mode\":\"fixture\"}",
                createdAt = 170L,
            ),
        )
        val trackingSources = requireNotNull(
            scalarString(
                "SELECT input_sources_json FROM generation_stage WHERE stage_id = '${tracking.stageId}'",
            ),
        )

        assertEquals(2, tracking.stepOrdinal)
        assertTrue(trackingSources.contains("\"stepType\":\"TRACKING\""))
        assertTrue(trackingSources.contains("\"sourceChapterVersionId\":\"${editedSource.chapterVersionId}\""))
        assertEquals(2L, scalarLong("SELECT COUNT(*) FROM generation_job"))
        assertEquals(2L, scalarLong("SELECT COUNT(*) FROM generation_stage"))
        assertEquals("COMPLETED", scalarString("SELECT status FROM generation_job WHERE job_id = '${runtime.jobId}'"))
        assertEquals("SUCCEEDED", scalarString("SELECT status FROM generation_stage WHERE stage_id = '${runtime.stageId}'"))
        assertEquals("FINAL", scalarString("SELECT status FROM usage_ledger WHERE attempt_id = 'attempt.memory.rebuild'"))
    }

    @Test
    fun sourceVersionChangedAfterIntentBlocksBeforeProviderOpen() = runBlocking {
        val runtime = createRunningExtractionJob("job.memory.stale", "stage.memory.stale", 100L)
        val prepared = prepare(runtime, "attempt.memory.stale", "ledger.memory.stale", 103L)
        val bound = request(runtime, "attempt.memory.stale")
        replaceCurrentChapterVersion()
        val providerCalls = AtomicInteger(0)

        val failure = expectFailure {
            coordinator(110L).execute(
                persistedRequest = prepared,
                adapter = MemoryFakeAdapter(
                    onGenerate = { providerCalls.incrementAndGet() },
                    events = successfulEvents(validMemoryJson()),
                ),
                profile = profile(),
                boundRequest = bound,
            )
        }

        assertTrue(failure.message.orEmpty().contains("source version", ignoreCase = true))
        assertEquals(0, providerCalls.get())
        assertEquals("INTENT_RECORDED", scalarString(
            "SELECT status FROM request_attempt WHERE attempt_id = 'attempt.memory.stale'",
        ))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM chapter_summary"))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM entity_event"))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM canon_fact"))
    }

    @Test
    fun unknownEntityInProviderOutputRequiresRepairAndCannotCreateMemory() = runBlocking {
        val runtime = createRunningExtractionJob("job.memory.invalid", "stage.memory.invalid", 200L)
        val prepared = prepare(runtime, "attempt.memory.invalid", "ledger.memory.invalid", 203L)
        val bound = request(runtime, "attempt.memory.invalid")
        val invalid = validMemoryJson().replace("char.lin", "char.unknown")

        val result = coordinator(210L).execute(
            persistedRequest = prepared,
            adapter = MemoryFakeAdapter(events = successfulEvents(invalid)),
            profile = profile(),
            boundRequest = bound,
        )

        assertTrue(result is ChapterMemoryExtractionResult.RepairRequired)
        result as ChapterMemoryExtractionResult.RepairRequired
        assertTrue(result.report.issues.any { it.path.contains("entityEvents") || it.path.contains("facts") })
        assertEquals("RETRY_WAIT", scalarString(
            "SELECT status FROM generation_stage WHERE stage_id = '${runtime.stageId}'",
        ))
        assertEquals("RUNNING", scalarString(
            "SELECT status FROM generation_job WHERE job_id = '${runtime.jobId}'",
        ))
        assertEquals("FINAL", scalarString(
            "SELECT status FROM usage_ledger WHERE attempt_id = 'attempt.memory.invalid'",
        ))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM chapter_summary"))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM entity_event"))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM canon_fact"))
    }

    private suspend fun createRunningExtractionJob(
        jobId: String,
        stageId: String,
        createdAt: Long,
    ): ExtractionRuntime {
        val source = ChapterMemoryExtractionSourceV1(
            chapterVersionId = VERSION_ID,
            chapterContentHash = CONTENT_HASH,
            chapterId = CHAPTER_ID,
            chapterIndex = 1,
        )
        val setup = ChapterMemoryExtractionJobFactory.create(
            ChapterMemoryExtractionJobSpec(
                jobId = jobId,
                stageId = stageId,
                bookId = BOOK_ID,
                userIntentJson = "{\"mode\":\"automatic\"}",
                budgetSnapshotJson = "{\"mode\":\"fixture\"}",
                source = source,
                createdAt = createdAt,
            ),
        )
        GenerationJobSetupRepository(database).create(setup)
        states.transitionJob(jobId, GenerationJobStatus.CREATED, JobEvent.VALIDATION_PASSED, createdAt + 1L)
        states.transitionStage(
            stageId,
            GenerationStageStatus.PENDING,
            StageEvent.DEPENDENCIES_SATISFIED,
            createdAt + 1L,
        )
        states.acquireJobLease(jobId, "worker.$jobId", createdAt + 2L)
        states.acquireStageLease(stageId, "worker.$stageId", createdAt + 2L)
        return ExtractionRuntime(jobId, stageId, source, setup.stages.single().inputVersionHash)
    }

    private suspend fun prepare(
        runtime: ExtractionRuntime,
        attemptId: String,
        ledgerId: String,
        createdAt: Long,
    ) = drafts.prepareBeforeSend(
        RequestIntentDraft(
            attemptId = attemptId,
            usageLedgerId = ledgerId,
            stageId = runtime.stageId,
            retryParentAttemptId = null,
            connectionSnapshotJson = "{\"referenceId\":\"fixture-connection\"}",
            modelSnapshotJson = MODEL_SNAPSHOT,
            protocolSnapshotJson = "{\"protocol\":\"fixture\"}",
            inputHash = runtime.inputVersionHash,
            streamDraftRef = null,
            createdAt = createdAt,
        ),
        BudgetedGenerationTestSupport.budgetedDraft(
            attemptId = attemptId,
            connectionId = "connection.memory",
        ),
        requireNotNull(states.findStage(runtime.stageId)?.leaseToken),
    )

    private fun request(
        runtime: ExtractionRuntime,
        attemptId: String,
        chapterContent: String = CHAPTER_CONTENT,
    ) =
        ChapterMemoryExtractionRequestFactory.create(
            ChapterMemoryExtractionRequestSpec(
                requestId = "request.$attemptId",
                generationId = runtime.jobId,
                stageId = runtime.stageId,
                attemptId = attemptId,
                modelId = ProviderModelId.from("local-fake"),
                sourceChapterVersionId = runtime.source.chapterVersionId,
                sourceChapterContentHash = runtime.source.chapterContentHash,
                chapterId = runtime.source.chapterId,
                chapterIndex = runtime.source.chapterIndex,
                chapterContent = chapterContent,
                knownEntities = listOf(
                    ChapterMemoryKnownEntity(
                        entityId = "char.lin",
                        canonicalName = "林澜",
                        entityType = StoryEntityType.CHARACTER,
                        adultStatus = AdultStatus.CONFIRMED_ADULT,
                    ),
                ),
                maximumOutputTokens = 2_048,
                timeouts = ProviderTimeoutPolicy(1_000, 1_000, 1_000, 2_000),
                idempotencyKey = "provider.$attemptId",
            ),
        )

    private fun coordinator(clockStart: Long) = ChapterMemoryExtractionCoordinator(
        executor = AuditedStreamingProviderExecutor(drafts, outputs, MemoryClock(clockStart)),
        validation = StructuredOutputValidationCoordinator(outputs),
        clock = MemoryClock(clockStart + 20L),
    )

    private fun successfulEvents(json: String) = listOf(
        ProviderStreamEvent.Started(),
        ProviderStreamEvent.StructuredDelta(SensitiveProviderText.from(json)),
        ProviderStreamEvent.UsageUpdate(
            ProviderUsage(
                inputTokens = 120L,
                outputTokens = 80L,
                cachedInputTokens = null,
                cachedWriteTokens = null,
                reasoningTokens = null,
                totalTokens = 200L,
                quality = ProviderUsageQuality.PROVIDER_REPORTED,
            ),
        ),
        ProviderStreamEvent.Completed(ProviderFinishReason.STOP),
    )

    private fun validMemoryJson(
        sourceChapterVersionId: String = VERSION_ID,
        sourceChapterContentHash: String = CONTENT_HASH,
    ): String = """
        {
          "schemaVersion":1,
          "sourceChapterVersionId":"$sourceChapterVersionId",
          "sourceChapterContentHash":"$sourceChapterContentHash",
          "chapterId":"$CHAPTER_ID",
          "chapterIndex":1,
          "summary":{
            "objectiveOutcome":"林澜保住了关键记录，但暴露了调查意图。",
            "keyEvents":["她从档案夹层取出原始纸页","追赶中手臂受伤仍带走证据"],
            "decisions":["不把唯一证据留在档案馆"],
            "relationshipChanges":[],
            "endingState":"林澜已回到临时住处，右臂擦伤，衣袖仍有裂口，并知道有人正在追查证据。",
            "unresolvedQuestions":["谁提前调换了档案"],
            "importance":88
          },
          "entityEvents":[
            {"entityId":"char.lin","attribute":"PHYSICAL_STATE","relatedEntityId":null,"oldValue":null,"newValue":"右臂擦伤且衣袖破裂","storyTimeExpression":"当夜","confidenceMicros":1000000,"canonLevel":"STORY_CANON","evidence":"正文结尾明确描写伤势与破损衣袖"},
            {"entityId":"char.lin","attribute":"KNOWLEDGE","relatedEntityId":null,"oldValue":null,"newValue":"确认有人正在追查原始纸页","storyTimeExpression":"当夜","confidenceMicros":1000000,"canonLevel":"STORY_CANON","evidence":"她收到只提及原始纸页的匿名警告"}
          ],
          "facts":[
            {"factKind":"POSSESSION","entityId":"char.lin","text":"林澜持有从档案夹层取出的原始纸页","canonLevel":"STORY_CANON","confidenceMicros":1000000,"conflictGroupId":null},
            {"factKind":"CHARACTER_STATE","entityId":"char.lin","text":"第一章结束时林澜右臂擦伤","canonLevel":"STORY_CANON","confidenceMicros":1000000,"conflictGroupId":null}
          ]
        }
    """.trimIndent()

    private fun seedBookChapterAndEntity() {
        val sql = database.openHelper.writableDatabase
        sql.execSQL(
            """
            INSERT INTO book_creation_snapshot VALUES (
                'snapshot.memory', '{}', '{}', '{}', '{}', '{}', '{}',
                1, 'prompt-memory', 1, 'snapshot-memory-hash', 1
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
                '$BOOK_ID', 'snapshot.memory', '记忆测试', 'USER', 'DRAFT', 'SHORT',
                80000, 80, 80, 1, NULL, NULL, 1, 'extracting', NULL, NULL, 1, 1
            )
            """.trimIndent(),
        )
        sql.execSQL(
            """
            INSERT INTO story_bible_revision (
                bible_revision_id, book_id, revision_no, parent_revision_id, source, schema_version,
                content_control_schema_version, payload_json, content_hash, generation_stage_id, created_at
            ) VALUES (
                'bible.memory.1', '$BOOK_ID', 1, NULL, 'USER', 1, 1, '{}',
                'bible-memory-hash', NULL, 2
            )
            """.trimIndent(),
        )
        sql.execSQL(
            """
            INSERT INTO book_memory_head (
                book_id, current_bible_revision_id, current_outline_revision_id, updated_at
            ) VALUES ('$BOOK_ID', 'bible.memory.1', NULL, 2)
            """.trimIndent(),
        )
        sql.execSQL(
            """
            INSERT INTO chapter (
                chapter_id, book_id, chapter_index, planned_title, display_title, status,
                current_version_id, consistency_status, created_at, updated_at
            ) VALUES ('$CHAPTER_ID', '$BOOK_ID', 1, '第一章', '第一章', 'READY', NULL, 'VALID', 2, 2)
            """.trimIndent(),
        )
        sql.execSQL(
            """
            INSERT INTO chapter_version (
                chapter_version_id, chapter_id, version_no, content, character_count, content_hash,
                source, parent_version_id, generation_stage_id, model_snapshot_json, created_at
            ) VALUES ('$VERSION_ID', '$CHAPTER_ID', 1, ?, ?, '$CONTENT_HASH', 'AI_GENERATED', NULL, NULL, '$MODEL_SNAPSHOT', 3)
            """.trimIndent(),
            arrayOf<Any>(CHAPTER_CONTENT, CHAPTER_CONTENT.length),
        )
        sql.execSQL("UPDATE chapter SET current_version_id = '$VERSION_ID' WHERE chapter_id = '$CHAPTER_ID'")
        sql.execSQL(
            """
            INSERT INTO story_entity (
                entity_id, book_id, entity_type, canonical_name, aliases_json, stable_definition_json,
                adult_status, age_years, source_bible_revision_id, created_at, updated_at, archived_at
            ) VALUES (
                'char.lin', '$BOOK_ID', 'CHARACTER', '林澜', '[]',
                '{"ageYears":22,"adultStatus":"CONFIRMED_ADULT","realIdentifiablePerson":false}',
                'CONFIRMED_ADULT', 22, 'bible.memory.1', 4, 4, NULL
            )
            """.trimIndent(),
        )
    }

    private fun replaceCurrentChapterVersionForRebuild(): ChapterMemoryExtractionSourceV1 {
        val editedHash = sha256(EDITED_CHAPTER_CONTENT)
        database.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO chapter_version (
                chapter_version_id, chapter_id, version_no, content, character_count, content_hash,
                source, parent_version_id, generation_stage_id, model_snapshot_json, created_at
            ) VALUES (
                '$EDITED_VERSION_ID', '$CHAPTER_ID', 2, ?, ?, '$editedHash',
                'USER_EDIT', '$VERSION_ID', NULL, NULL, 100
            )
            """.trimIndent(),
            arrayOf<Any>(EDITED_CHAPTER_CONTENT, EDITED_CHAPTER_CONTENT.length),
        )
        database.openHelper.writableDatabase.execSQL(
            """
            UPDATE chapter
            SET current_version_id = '$EDITED_VERSION_ID', status = 'EDITED',
                consistency_status = 'UNKNOWN', updated_at = 100
            WHERE chapter_id = '$CHAPTER_ID'
            """.trimIndent(),
        )
        return ChapterMemoryExtractionSourceV1(
            chapterVersionId = EDITED_VERSION_ID,
            chapterContentHash = editedHash,
            chapterId = CHAPTER_ID,
            chapterIndex = 1,
        )
    }

    private fun replaceCurrentChapterVersion() {
        val replacement = "章节已被用户改写，旧版本不得再提取。"
        val replacementHash = sha256(replacement)
        database.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO chapter_version (
                chapter_version_id, chapter_id, version_no, content, character_count, content_hash,
                source, parent_version_id, generation_stage_id, model_snapshot_json, created_at
            ) VALUES ('version.chapter.1.2', '$CHAPTER_ID', 2, ?, ?, '$replacementHash',
                'USER_EDIT', '$VERSION_ID', NULL, NULL, 105)
            """.trimIndent(),
            arrayOf<Any>(replacement, replacement.length),
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE chapter SET current_version_id = 'version.chapter.1.2', updated_at = 105 WHERE chapter_id = '$CHAPTER_ID'",
        )
    }

    private fun profile() = ProviderConnectionProfile.create(
        connectionId = "connection.memory",
        protocol = ProviderProtocol.OPENAI_CHAT_COMPAT,
        baseUrl = "https://example.invalid",
    )

    private fun scalarString(query: String): String? =
        database.openHelper.readableDatabase.query(query).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }

    private fun scalarLong(query: String): Long? =
        database.openHelper.readableDatabase.query(query).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }

    private fun cleanArtifacts() {
        artifactStore.unlockAfterAuthentication()
        artifactStore.listArtifactReferenceIds().forEach(artifactStore::delete)
    }

    private suspend fun expectFailure(block: suspend () -> Unit): Throwable = try {
        block()
        throw AssertionError("Expected operation to fail.")
    } catch (error: Throwable) {
        if (error is AssertionError && error.message == "Expected operation to fail.") throw error
        error
    }

    private data class ExtractionRuntime(
        val jobId: String,
        val stageId: String,
        val source: ChapterMemoryExtractionSourceV1,
        val inputVersionHash: String,
    )

    private companion object {
        const val BOOK_ID = "book.memory"
        const val CHAPTER_ID = "chapter.memory.1"
        const val VERSION_ID = "version.chapter.1.1"
        const val EDITED_VERSION_ID = "version.chapter.1.2.rebuild"
        const val CHAPTER_CONTENT = "林澜从档案夹层取出原始纸页。追赶中她的右臂擦伤，衣袖被划破。回到临时住处后，她收到一条只提及原始纸页的匿名警告，确认有人正在追查这份证据。"
        const val EDITED_CHAPTER_CONTENT = "林澜从档案夹层取出原始纸页。追赶中她的右臂擦伤，衣袖被划破。回到临时住处后，她锁好证据并确认匿名警告来自追查者。"
        const val MODEL_SNAPSHOT = "{\"model\":\"local-fake\"}"
        val CONTENT_HASH: String = sha256(CHAPTER_CONTENT)

        fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}

private class MemoryClock(startAt: Long) : GenerationExecutionClock {
    private val next = AtomicLong(startAt)
    override fun nowMillis(): Long = next.getAndIncrement()
}

private class MemoryFakeAdapter(
    private val events: List<ProviderStreamEvent>,
    private val onGenerate: () -> Unit = {},
) : ProviderAdapter {
    override val protocol = ProviderProtocol.OPENAI_CHAT_COMPAT
    override val adapterVersion = "memory-test-1"

    override suspend fun testConnection(profile: ProviderConnectionProfile): ConnectionTestResult =
        error("Not used by chapter-memory extraction tests.")

    override suspend fun listModels(profile: ProviderConnectionProfile): ModelListResult =
        error("Not used by chapter-memory extraction tests.")

    override suspend fun getCapabilities(
        profile: ProviderConnectionProfile,
        modelId: ProviderModelId,
    ): CapabilityResult = error("Not used by chapter-memory extraction tests.")

    override fun generate(
        profile: ProviderConnectionProfile,
        request: GenerationRequest,
    ): Flow<ProviderStreamEvent> = flow {
        onGenerate()
        events.forEach { emit(it) }
    }

    override suspend fun cancel(
        profile: ProviderConnectionProfile,
        requestId: String,
    ): ProviderCancellationResult = ProviderCancellationResult.ALREADY_TERMINAL
}
