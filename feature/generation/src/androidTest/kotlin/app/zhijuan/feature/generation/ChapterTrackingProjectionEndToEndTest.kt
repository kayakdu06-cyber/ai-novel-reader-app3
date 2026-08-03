package app.zhijuan.feature.generation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.database.generation.ChapterTrackingProjectionCommitDraft
import app.zhijuan.core.database.generation.ChapterTrackingProjectionCommitRepository
import app.zhijuan.core.database.generation.ChapterTrackingProjectionInputs
import app.zhijuan.core.database.generation.ChapterTrackingProjectionJobFactory
import app.zhijuan.core.database.generation.ChapterTrackingProjectionJobSpec
import app.zhijuan.core.database.generation.ChapterTrackingProjectionSourceRepository
import app.zhijuan.core.database.generation.ChapterTrackingProjectionSourceV1
import app.zhijuan.core.database.generation.FinalUsageCommit
import app.zhijuan.core.database.generation.GenerationJobSetupRepository
import app.zhijuan.core.database.generation.GenerationOutputValidationRepository
import app.zhijuan.core.database.generation.GenerationStateRepository
import app.zhijuan.core.database.generation.GenerationStreamingDraftRepository
import app.zhijuan.core.database.generation.RequestIntentDraft
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationStageStatus
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
class ChapterTrackingProjectionEndToEndTest {
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
            .build()
            .also { it.openHelper.writableDatabase }
        states = GenerationStateRepository(database)
        drafts = GenerationStreamingDraftRepository(database, artifactStore)
        outputs = GenerationOutputValidationRepository(database, artifactStore)
        seedBookMemoryAndPriorForeshadow()
    }

    @After
    fun tearDown() {
        runCatching { cleanArtifacts() }
        database.close()
    }

    @Test
    fun validProjectionCommitsTimelineAndAppendOnlyForeshadowTransitionsThenReplays() = runBlocking {
        val runtime = createRunningJob("job.tracking.valid", "stage.tracking.valid", 100L)
        val prepared = prepare(runtime, "attempt.tracking.valid", "ledger.tracking.valid", 103L)
        val result = coordinator(110L).execute(
            persistedRequest = prepared,
            adapter = TrackingFakeAdapter(successfulEvents(validTrackingJson(runtime.inputs.source))),
            profile = profile(),
            boundRequest = request(runtime, "attempt.tracking.valid"),
        )

        assertTrue(result is ChapterTrackingProjectionResult.Accepted)
        result as ChapterTrackingProjectionResult.Accepted
        val mapped = ChapterTrackingProjectionPersistenceMapper.map(
            result.tracking,
            ChapterTrackingProjectionMappingSpec(
                bookId = BOOK_ID,
                generationStageId = runtime.stageId,
                modelSnapshotJson = MODEL_SNAPSHOT,
                createdAt = 160L,
            ),
        )
        val draft = ChapterTrackingProjectionCommitDraft(
            source = runtime.inputs.source,
            trackingContentHash = mapped.trackingContentHash,
            projection = mapped.projection,
            timelineEvents = mapped.timelineEvents,
            newForeshadows = mapped.newForeshadows,
            existingForeshadowUpdates = mapped.existingForeshadowUpdates,
            foreshadowTransitions = mapped.foreshadowTransitions,
            usage = FinalUsageCommit(
                source = UsageSource.PROVIDER_REPORTED,
                inputTokens = 140L,
                outputTokens = 90L,
                cachedTokens = null,
                reasoningTokens = null,
                totalTokens = 230L,
            ),
            committedAt = 160L,
        )
        val repository = ChapterTrackingProjectionCommitRepository(database, artifactStore)
        val committed = repository.commit(result.commitPermit, draft)
        val replayed = repository.commit(result.commitPermit, draft)

        assertEquals(false, committed.replayed)
        assertEquals(true, replayed.replayed)
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM chapter_tracking_projection"))
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM timeline_event"))
        assertEquals(2L, scalarLong("SELECT COUNT(*) FROM foreshadow_transition"))
        assertEquals(2L, scalarLong("SELECT COUNT(*) FROM foreshadow_item"))
        assertEquals("DEVELOPING", scalarString("SELECT foreshadow_status FROM foreshadow_item WHERE foreshadow_item_id = '$PRIOR_FORESHADOW_ID'"))
        assertEquals(VERSION_2_ID, scalarString("SELECT source_chapter_version_id FROM foreshadow_item WHERE foreshadow_item_id = '$PRIOR_FORESHADOW_ID'"))
        assertEquals("PLANTED", scalarString("SELECT foreshadow_status FROM foreshadow_item WHERE foreshadow_item_id != '$PRIOR_FORESHADOW_ID'"))
        assertEquals("SUCCEEDED", scalarString("SELECT status FROM generation_stage WHERE stage_id = '${runtime.stageId}'"))
        assertEquals("COMPLETED", scalarString("SELECT status FROM generation_job WHERE job_id = '${runtime.jobId}'"))
        assertEquals("FINAL", scalarString("SELECT status FROM usage_ledger WHERE attempt_id = 'attempt.tracking.valid'"))
        assertEquals(230L, scalarLong("SELECT total_tokens FROM usage_ledger WHERE attempt_id = 'attempt.tracking.valid'"))
    }

    @Test
    fun priorForeshadowChangeAfterIntentBlocksBeforeProviderOpen() = runBlocking {
        val runtime = createRunningJob("job.tracking.stale", "stage.tracking.stale", 200L)
        val prepared = prepare(runtime, "attempt.tracking.stale", "ledger.tracking.stale", 203L)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE foreshadow_item SET importance = 79, updated_at = 205 WHERE foreshadow_item_id = '$PRIOR_FORESHADOW_ID'",
        )
        val calls = AtomicInteger(0)

        val failure = expectFailure {
            coordinator(210L).execute(
                persistedRequest = prepared,
                adapter = TrackingFakeAdapter(
                    successfulEvents(validTrackingJson(runtime.inputs.source)),
                    onGenerate = { calls.incrementAndGet() },
                ),
                profile = profile(),
                boundRequest = request(runtime, "attempt.tracking.stale"),
            )
        }

        assertTrue(failure.message.orEmpty().contains("source", ignoreCase = true) || failure.message.orEmpty().contains("snapshot", ignoreCase = true))
        assertEquals(0, calls.get())
        assertEquals("INTENT_RECORDED", scalarString("SELECT status FROM request_attempt WHERE attempt_id = 'attempt.tracking.stale'"))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM chapter_tracking_projection"))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM timeline_event"))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM foreshadow_transition"))
    }

    @Test
    fun unknownForeshadowTransitionRequiresRepairAndCannotMutateLedger() = runBlocking {
        val runtime = createRunningJob("job.tracking.invalid", "stage.tracking.invalid", 300L)
        val prepared = prepare(runtime, "attempt.tracking.invalid", "ledger.tracking.invalid", 303L)
        val invalid = validTrackingJson(runtime.inputs.source).replace(PRIOR_FORESHADOW_ID, "clue.unknown")

        val result = coordinator(310L).execute(
            persistedRequest = prepared,
            adapter = TrackingFakeAdapter(successfulEvents(invalid)),
            profile = profile(),
            boundRequest = request(runtime, "attempt.tracking.invalid"),
        )

        assertTrue(result is ChapterTrackingProjectionResult.RepairRequired)
        result as ChapterTrackingProjectionResult.RepairRequired
        assertTrue(result.report.issues.any { it.path.contains("foreshadowItemId") })
        assertEquals("RETRY_WAIT", scalarString("SELECT status FROM generation_stage WHERE stage_id = '${runtime.stageId}'"))
        assertEquals("FINAL", scalarString("SELECT status FROM usage_ledger WHERE attempt_id = 'attempt.tracking.invalid'"))
        assertEquals("PLANTED", scalarString("SELECT foreshadow_status FROM foreshadow_item WHERE foreshadow_item_id = '$PRIOR_FORESHADOW_ID'"))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM chapter_tracking_projection"))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM timeline_event"))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM foreshadow_transition"))
    }

    private suspend fun createRunningJob(jobId: String, stageId: String, createdAt: Long): TrackingRuntime {
        val inputs = ChapterTrackingProjectionSourceRepository(database).loadCurrentVersion(CHAPTER_2_ID)
        val setup = ChapterTrackingProjectionJobFactory.create(
            ChapterTrackingProjectionJobSpec(
                jobId = jobId,
                stageId = stageId,
                bookId = BOOK_ID,
                userIntentJson = "{\"mode\":\"automatic\"}",
                budgetSnapshotJson = "{\"mode\":\"fixture\"}",
                source = inputs.source,
                createdAt = createdAt,
            ),
        )
        GenerationJobSetupRepository(database).create(setup)
        states.transitionJob(jobId, GenerationJobStatus.CREATED, JobEvent.VALIDATION_PASSED, createdAt + 1L)
        states.transitionStage(stageId, GenerationStageStatus.PENDING, StageEvent.DEPENDENCIES_SATISFIED, createdAt + 1L)
        states.acquireJobLease(jobId, "worker.$jobId", createdAt + 2L)
        states.acquireStageLease(stageId, "worker.$stageId", createdAt + 2L)
        return TrackingRuntime(jobId, stageId, inputs, setup.stages.single().inputVersionHash)
    }

    private suspend fun prepare(runtime: TrackingRuntime, attemptId: String, ledgerId: String, createdAt: Long) =
        drafts.prepareBeforeSend(
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
                dailyPeriodKey = "2026-08-03|Asia/Shanghai",
                createdAt = createdAt,
            ),
            requireNotNull(states.findStage(runtime.stageId)?.leaseToken),
        )

    private fun request(runtime: TrackingRuntime, attemptId: String) =
        ChapterTrackingProjectionRequestFactory.create(
            ChapterTrackingProjectionRequestSpec(
                requestId = "request.$attemptId",
                generationId = runtime.jobId,
                stageId = runtime.stageId,
                attemptId = attemptId,
                modelId = ProviderModelId.from("local-fake"),
                inputs = runtime.inputs,
                maximumOutputTokens = 2_048,
                timeouts = ProviderTimeoutPolicy(1_000, 1_000, 1_000, 2_000),
                idempotencyKey = "provider.$attemptId",
            ),
        )

    private fun coordinator(startAt: Long) = ChapterTrackingProjectionCoordinator(
        executor = AuditedStreamingProviderExecutor(drafts, outputs, TrackingClock(startAt)),
        validation = StructuredOutputValidationCoordinator(outputs),
        clock = TrackingClock(startAt + 20L),
    )

    private fun successfulEvents(json: String) = listOf(
        ProviderStreamEvent.Started(),
        ProviderStreamEvent.StructuredDelta(SensitiveProviderText.from(json)),
        ProviderStreamEvent.UsageUpdate(
            ProviderUsage(
                inputTokens = 140L,
                outputTokens = 90L,
                cachedInputTokens = null,
                cachedWriteTokens = null,
                reasoningTokens = null,
                totalTokens = 230L,
                quality = ProviderUsageQuality.PROVIDER_REPORTED,
            ),
        ),
        ProviderStreamEvent.Completed(ProviderFinishReason.STOP),
    )

    private fun validTrackingJson(source: ChapterTrackingProjectionSourceV1): String = """
        {
          "schemaVersion":1,
          "sourceChapterVersionId":"${source.chapterVersionId}",
          "sourceChapterContentHash":"${source.chapterContentHash}",
          "chapterId":"${source.chapterId}",
          "chapterIndex":${source.chapterIndex},
          "memorySnapshotHash":"${source.memorySnapshotHash}",
          "priorForeshadowSnapshotHash":"${source.priorForeshadowSnapshotHash}",
          "knownEntitySnapshotHash":"${source.knownEntitySnapshotHash}",
          "timelineEvents":[{
            "name":"林澜在旧厅打开夹层门",
            "participantEntityIds":["char.lin"],
            "locationEntityId":"loc.hall",
            "storyTimeExpression":"第二夜子时前",
            "constraints":["开门发生在银铃第二次响起之后"],
            "evidence":"正文明确写出听铃后开门的顺序"
          }],
          "foreshadowOperations":[{
            "operation":"DEVELOP",
            "foreshadowItemId":"$PRIOR_FORESHADOW_ID",
            "description":"旧厅夹层门后的银铃声",
            "targetStartChapterIndex":null,
            "targetEndChapterIndex":null,
            "visibleEntityIds":["char.lin"],
            "importance":85,
            "fromStatus":"PLANTED",
            "confidenceMicros":960000,
            "evidence":"银铃与夹层门的机关形成直接关联"
          },{
            "operation":"PLANT",
            "foreshadowItemId":null,
            "description":"信封上的两层不同封蜡",
            "targetStartChapterIndex":3,
            "targetEndChapterIndex":8,
            "visibleEntityIds":["char.lin"],
            "importance":72,
            "fromStatus":null,
            "confidenceMicros":930000,
            "evidence":"正文用近距离观察明确写出两层印记"
          }]
        }
    """.trimIndent()

    private fun seedBookMemoryAndPriorForeshadow() {
        val sql = database.openHelper.writableDatabase
        sql.execSQL("INSERT INTO book_creation_snapshot VALUES ('snapshot.tracking','{}','{}','{}','{}','{}','{}',1,'prompt-tracking',1,'snapshot-hash',1)")
        sql.execSQL(
            """
            INSERT INTO book (
              book_id,creation_snapshot_id,title,title_source,status,length_mode,target_characters,target_chapters,
              minimum_chapters,length_policy_schema_version,branched_from_book_id,branched_from_chapter_version_id,
              completed_chapter_count,generation_status_summary,archived_at,deleted_at,created_at,updated_at
            ) VALUES ('$BOOK_ID','snapshot.tracking','投影测试','USER','DRAFT','SHORT',80000,80,80,1,NULL,NULL,2,'tracking',NULL,NULL,1,1)
            """.trimIndent(),
        )
        insertChapter(sql, CHAPTER_1_ID, 1, VERSION_1_ID, CHAPTER_1_CONTENT, 2L)
        insertChapter(sql, CHAPTER_2_ID, 2, VERSION_2_ID, CHAPTER_2_CONTENT, 3L)
        sql.execSQL(
            """
            INSERT INTO story_bible_revision (
              bible_revision_id,book_id,revision_no,parent_revision_id,source,schema_version,
              content_control_schema_version,payload_json,content_hash,generation_stage_id,created_at
            ) VALUES ('$BIBLE_ID','$BOOK_ID',1,NULL,'AI_GENERATED',1,1,'{}','bible-hash',NULL,4)
            """.trimIndent(),
        )
        sql.execSQL("INSERT INTO book_memory_head VALUES ('$BOOK_ID','$BIBLE_ID',NULL,4)")
        sql.execSQL(
            """
            INSERT INTO story_entity VALUES (
              'char.lin','$BOOK_ID','CHARACTER','林澜','[]','{"ageYears":22,"adultStatus":"CONFIRMED_ADULT","realIdentifiablePerson":false}',
              'CONFIRMED_ADULT',22,'$BIBLE_ID',5,5,NULL
            )
            """.trimIndent(),
        )
        sql.execSQL(
            """
            INSERT INTO story_entity VALUES (
              'loc.hall','$BOOK_ID','LOCATION','旧厅','[]','{}','NOT_APPLICABLE',NULL,'$BIBLE_ID',5,5,NULL
            )
            """.trimIndent(),
        )
        sql.execSQL(
            """
            INSERT INTO chapter_summary VALUES (
              'summary.tracking','$BOOK_ID','$VERSION_2_ID',2,1,
              '{"schemaVersion":1,"sourceChapterContentHash":"${sha256(CHAPTER_2_CONTENT)}","objectiveOutcome":"打开夹层门","keyEvents":["听见银铃","打开夹层门"],"decisions":[],"relationshipChanges":[],"endingState":"林澜持有双层封蜡信封","unresolvedQuestions":["第二层封蜡来自谁"]}',
              88,'VALID','$MODEL_SNAPSHOT',6,6
            )
            """.trimIndent(),
        )
        sql.execSQL(
            """
            INSERT INTO entity_event VALUES (
              'event.tracking','$BOOK_ID','char.lin','$VERSION_2_ID',2000001,'knowledge',NULL,
              '{"value":"银铃与夹层门机关相关","relatedEntityId":null}',
              '第二夜',1000000,'STORY_CANON','{"source":"chapter-memory.v1","evidence":"开门顺序"}','VALID',6
            )
            """.trimIndent(),
        )
        sql.execSQL(
            """
            INSERT INTO canon_fact VALUES (
              'fact.tracking','$BOOK_ID','char.lin','林澜持有双层封蜡信封',
              '{"schemaVersion":1,"kind":"POSSESSION","confidenceMicros":1000000}',
              'STORY_CANON','{"fromChapter":2,"throughChapter":null}','$VERSION_2_ID',NULL,2000002,NULL,NULL,'VALID',6
            )
            """.trimIndent(),
        )
        sql.execSQL(
            """
            INSERT INTO foreshadow_item VALUES (
              '$PRIOR_FORESHADOW_ID','$BOOK_ID','旧厅夹层门后的银铃声','PLANTED','VALID',2,6,
              '$VERSION_1_ID','$VERSION_1_ID',NULL,'["char.lin"]',85,'CHAPTER_EXTRACTION',5,5
            )
            """.trimIndent(),
        )
    }

    private fun insertChapter(
        sql: androidx.sqlite.db.SupportSQLiteDatabase,
        chapterId: String,
        chapterIndex: Int,
        versionId: String,
        content: String,
        createdAt: Long,
    ) {
        val hash = sha256(content)
        sql.execSQL(
            "INSERT INTO chapter VALUES ('$chapterId','$BOOK_ID',$chapterIndex,'第${chapterIndex}章','第${chapterIndex}章','READY',NULL,'VALID',$createdAt,$createdAt)",
        )
        sql.execSQL(
            """
            INSERT INTO chapter_version VALUES (
              '$versionId','$chapterId',1,?,?, '$hash','AI_GENERATED',NULL,NULL,'$MODEL_SNAPSHOT',$createdAt
            )
            """.trimIndent(),
            arrayOf<Any>(content, content.length),
        )
        sql.execSQL("UPDATE chapter SET current_version_id = '$versionId' WHERE chapter_id = '$chapterId'")
    }

    private fun profile() = ProviderConnectionProfile.create(
        connectionId = "connection.tracking",
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

    private data class TrackingRuntime(
        val jobId: String,
        val stageId: String,
        val inputs: ChapterTrackingProjectionInputs,
        val inputVersionHash: String,
    )

    private companion object {
        const val BOOK_ID = "book.tracking"
        const val BIBLE_ID = "bible.tracking"
        const val CHAPTER_1_ID = "chapter.tracking.1"
        const val CHAPTER_2_ID = "chapter.tracking.2"
        const val VERSION_1_ID = "version.tracking.1"
        const val VERSION_2_ID = "version.tracking.2"
        const val PRIOR_FORESHADOW_ID = "clue.prior"
        const val CHAPTER_1_CONTENT = "林澜在旧厅外第一次听见夹层后的银铃声。"
        const val CHAPTER_2_CONTENT = "第二夜，银铃再次响起。林澜确认响声来自夹层门的机关，随后打开门并发现一封带有两层不同封蜡的信。"
        const val MODEL_SNAPSHOT = "{\"model\":\"local-fake\"}"

        fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}

private class TrackingClock(startAt: Long) : GenerationExecutionClock {
    private val next = AtomicLong(startAt)
    override fun nowMillis(): Long = next.getAndIncrement()
}

private class TrackingFakeAdapter(
    private val events: List<ProviderStreamEvent>,
    private val onGenerate: () -> Unit = {},
) : ProviderAdapter {
    override val protocol = ProviderProtocol.OPENAI_CHAT_COMPAT
    override val adapterVersion = "tracking-test-1"

    override suspend fun testConnection(profile: ProviderConnectionProfile): ConnectionTestResult = error("Not used")
    override suspend fun listModels(profile: ProviderConnectionProfile): ModelListResult = error("Not used")
    override suspend fun getCapabilities(profile: ProviderConnectionProfile, modelId: ProviderModelId): CapabilityResult = error("Not used")
    override fun generate(profile: ProviderConnectionProfile, request: GenerationRequest): Flow<ProviderStreamEvent> = flow {
        onGenerate()
        events.forEach { emit(it) }
    }
    override suspend fun cancel(profile: ProviderConnectionProfile, requestId: String): ProviderCancellationResult =
        ProviderCancellationResult.ALREADY_TERMINAL
}
