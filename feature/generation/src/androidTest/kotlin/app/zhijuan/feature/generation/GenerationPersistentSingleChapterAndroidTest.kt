@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package app.zhijuan.feature.generation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zhijuan.core.database.LibraryDatabaseGuards
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.database.connection.ConnectionProfileEntity
import app.zhijuan.core.database.generation.ChapterContextAssemblyJobFactory
import app.zhijuan.core.database.generation.ChapterContextAssemblyJobSpec
import app.zhijuan.core.database.generation.ChapterContextAssemblyRepository
import app.zhijuan.core.database.generation.ChapterContextAssemblyStageIds
import app.zhijuan.core.database.generation.ChapterPlanV2FrozenSources
import app.zhijuan.core.database.generation.ChapterPlanV2PromptSourcesRepository
import app.zhijuan.core.database.generation.ChapterPlanV2StageBinding
import app.zhijuan.core.database.generation.ChapterProgressionAuthorization
import app.zhijuan.core.database.generation.ChapterProgressionGateRepository
import app.zhijuan.core.database.generation.GenerationControlRepository
import app.zhijuan.core.database.generation.GenerationJobSetup
import app.zhijuan.core.database.generation.GenerationJobSetupRepository
import app.zhijuan.core.database.generation.GenerationStageSetup
import app.zhijuan.core.database.generation.GenerationStateRepository
import app.zhijuan.core.database.generation.PersistentBudgetPolicyRepository
import app.zhijuan.core.database.generation.PromptBundleBindingRepository
import app.zhijuan.core.database.generation.PersistedChapterContextAssemblyResult
import app.zhijuan.core.database.generation.ReadyChapterContext
import app.zhijuan.core.database.library.BookCreationRepository
import app.zhijuan.core.database.library.BookCreationSnapshotEntity
import app.zhijuan.core.database.library.BookEntity
import app.zhijuan.core.database.library.ChapterEntity
import app.zhijuan.core.database.library.ChapterVersionEntity
import app.zhijuan.core.database.memory.CanonFactEntity
import app.zhijuan.core.database.memory.OutlineNodeEntity
import app.zhijuan.core.database.memory.OutlineRevisionEntity
import app.zhijuan.core.database.memory.StoryBibleRevisionEntity
import app.zhijuan.core.database.memory.StoryEntity
import app.zhijuan.core.model.AdultStatus
import app.zhijuan.core.model.BookLengthMode
import app.zhijuan.core.model.BookStatus
import app.zhijuan.core.model.BudgetLimit
import app.zhijuan.core.model.ChapterStatus
import app.zhijuan.core.model.ChapterVersionSource
import app.zhijuan.core.model.CanonLevel
import app.zhijuan.core.model.ConsistencyCriterionV1
import app.zhijuan.core.model.ConsistencyStatus
import app.zhijuan.core.model.DerivedDataStatus
import app.zhijuan.core.model.FadePolicy
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationJobType
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.model.OutlineNodeType
import app.zhijuan.core.model.RelevantCharacterAdultGate
import app.zhijuan.core.model.RevisionSource
import app.zhijuan.core.model.StoryEntityType
import app.zhijuan.core.model.TitleSource
import app.zhijuan.core.security.AndroidProtectedArtifactStore
import app.zhijuan.core.task.ChapterConsistencyPolicyDecisionV1
import app.zhijuan.core.task.ChapterConsistencyPolicyV1
import app.zhijuan.core.task.ChapterContextBudgetSpec
import app.zhijuan.core.task.ChapterContextLimitSource
import app.zhijuan.core.task.FirstChapterGenerationMode
import app.zhijuan.core.task.JobEvent
import app.zhijuan.core.task.PromptBundleCatalogV1
import app.zhijuan.core.task.PromptInstruction
import app.zhijuan.core.task.SceneExecutionContract
import app.zhijuan.core.task.StageEvent
import app.zhijuan.provider.common.ProviderConnectionProfile
import app.zhijuan.provider.common.ProviderProtocol
import app.zhijuan.provider.common.ProviderTimeoutPolicy
import app.zhijuan.provider.fake.FakeProviderAdapter
import app.zhijuan.provider.fake.fakeStreamScript
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GenerationPersistentChapterSequenceAndroidTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: ZhijuanDatabase
    private lateinit var artifacts: AndroidProtectedArtifactStore
    private var artifactRefsBefore = emptySet<String>()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, ZhijuanDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(LibraryDatabaseGuards.callback)
            .build()
            .also { it.openHelper.writableDatabase }
        artifacts = AndroidProtectedArtifactStore(context)
        artifactRefsBefore = artifacts.listArtifactReferenceIds().toSet()
    }

    @After
    fun tearDown() {
        (artifacts.listArtifactReferenceIds().toSet() - artifactRefsBefore).forEach(artifacts::delete)
        database.close()
    }

    @Test
    fun fourChaptersPauseAndRestartWithoutLosingReadableStateOrOpeningProviderTwice() = runBlocking {
        seedBookPlanning()
        val routing = strictMixedPolicy()
        val firstChapter = prepareChapter(1, routing)
        val firstRemote = FakeRemote(database, artifacts)
        val firstRuntime = GenerationPersistentRuntimeFactoryV1.create(
            database = database,
            artifactStore = artifacts,
            remote = firstRemote,
            clock = IncrementingClock(30_000L),
        )
        var pauseInjected = false
        val firstSequence = GenerationPersistentChapterSequenceV1(firstRuntime.runner) { _, expected ->
            val prepared = prepareChapter(expected, routing)
            if (!pauseInjected && expected == 2) {
                GenerationControlRepository(database).requestPause(prepared.jobId, chapterBaseTime(expected) + 100L)
                pauseInjected = true
            }
            GenerationNextChapterPreparationResult.Prepared(prepared)
        }

        val paused = firstSequence.run(firstChapter, 4, "runner.task129.first")

        assertEquals(GenerationChapterSequenceDisposition.RUNNER_HALTED, paused.disposition)
        assertEquals(listOf(1), paused.completedChapters.map { it.chapterOrdinal })
        assertEquals(2, paused.currentChapter.chapterOrdinal)
        assertEquals(3, firstRemote.generateCalls())
        assertEquals(GenerationJobStatus.PAUSED, GenerationStateRepository(database).findJob(jobId(2))?.status)
        assertReadableChapter(1)

        GenerationControlRepository(database).resume(jobId(2), chapterBaseTime(2) + 101L)
        val restartedRemote = FakeRemote(database, artifacts)
        val restartedRuntime = GenerationPersistentRuntimeFactoryV1.create(
            database = database,
            artifactStore = artifacts,
            remote = restartedRemote,
            clock = IncrementingClock(50_000L),
        )
        val restartedSequence = GenerationPersistentChapterSequenceV1(restartedRuntime.runner) { _, expected ->
            GenerationNextChapterPreparationResult.Prepared(prepareChapter(expected, routing))
        }

        val completed = restartedSequence.run(
            initialChapter = paused.currentChapter,
            requestedChapterCount = 4,
            runnerOwnerPrefix = "runner.task129.restarted",
            alreadyCompletedChapterCount = 1,
        )

        assertEquals(GenerationChapterSequenceDisposition.TARGET_COMPLETED, completed.disposition)
        assertEquals(listOf(2, 3, 4), completed.completedChapters.map { it.chapterOrdinal })
        assertEquals(9, restartedRemote.generateCalls())
        assertEquals(12, firstRemote.generateCalls() + restartedRemote.generateCalls())
        assertEquals((1..4).toList(), database.libraryDao().chaptersForBook(BOOK_ID).map { it.chapterIndex })
        (1..4).forEach { index ->
            assertEquals(GenerationJobStatus.COMPLETED, GenerationStateRepository(database).findJob(jobId(index))?.status)
            val stages = database.generationDao().stagesForJob(jobId(index))
            assertEquals(5, stages.size)
            assertTrue(stages.all { it.status == GenerationStageStatus.SUCCEEDED })
            assertReadableChapter(index)
        }
        assertEquals(12, scalarInt("SELECT COUNT(*) FROM usage_ledger WHERE status = 'FINAL'"))
        val systemLevels = (1..4).map { index ->
            val version = requireNotNull(database.libraryDao().findChapter(chapterId(index))?.currentVersionId)
            assertTrue(database.memoryDao().canonFactsForVersion(version).any {
                it.factPayloadJson.contains(OBLIGATION_ID) && it.factPayloadJson.contains("CARRY_FORWARD")
            })
            database.memoryDao().entityEventsForVersion(version)
                .single { it.attributeKey == "system.level" }.newValueJson
        }
        assertEquals(listOf("2", "3", "4", "5"), systemLevels)
    }

    private suspend fun seedBookPlanning() {
        BookCreationRepository(database).create(
            BookCreationSnapshotEntity(
                snapshotId = SNAPSHOT_ID,
                rawInputJson = INTENT_JSON,
                normalizedInputJson = INTENT_JSON,
                inferenceProvenanceJson = "{\"schemaVersion\":1}",
                genrePayloadJson = GENRE_JSON,
                presentationProfileJson = PRESENTATION_JSON,
                modelPreferenceJson = "{\"connectionId\":\"$CONNECTION_ID\",\"modelId\":\"fake-model\"}",
                schemaVersion = 1,
                promptBundleVersion = PromptBundleCatalogV1.UNASSIGNED_CREATION_BUNDLE_VERSION,
                contentControlSchemaVersion = 1,
                contentHash = CREATION_HASH,
                createdAt = 1L,
            ),
            BookEntity(
                bookId = BOOK_ID,
                creationSnapshotId = SNAPSHOT_ID,
                title = "闭环测试",
                titleSource = TitleSource.USER,
                status = BookStatus.GENERATING,
                lengthMode = BookLengthMode.SHORT,
                targetCharacters = null,
                targetChapters = 80,
                minimumChapters = 80,
                lengthPolicySchemaVersion = 1,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        createEvidenceStage(BIBLE_JOB, BIBLE_STAGE, GenerationPhase.BUILD_BIBLE)
        createEvidenceStage(MASTER_JOB, MASTER_STAGE, GenerationPhase.BUILD_MASTER_OUTLINE)
        createEvidenceStage(WINDOW_JOB, WINDOW_STAGE, GenerationPhase.BUILD_ARC_PLAN)
        val bible = BIBLE_JSON
        database.memoryDao().createBibleRevision(StoryBibleRevisionEntity(
            BIBLE_REVISION, BOOK_ID, 1, null, RevisionSource.AI_GENERATED, 1, 1,
            bible, sha256(bible), BIBLE_STAGE, 2L,
        ))
        database.memoryDao().insertStoryEntity(entity(HERO_ID, "林岚"))
        database.memoryDao().insertStoryEntity(entity(PARTNER_ID, "苏晚"))
        database.memoryDao().insertCanonFacts(listOf(CanonFactEntity(
            canonFactId = "fact.task128.adults",
            bookId = BOOK_ID,
            entityId = null,
            factText = "两名角色均为二十二岁的虚构成年人",
            factPayloadJson = "{\"confirmedAdultCharacterIds\":[\"$HERO_ID\",\"$PARTNER_ID\"]}",
            canonLevel = CanonLevel.HARD_CANON,
            scopeJson = "{\"scope\":\"BOOK\"}",
            sourceChapterVersionId = null,
            sourceBibleRevisionId = BIBLE_REVISION,
            validFromStoryOrder = null,
            validToStoryOrder = null,
            conflictGroupId = "adult-identity",
            status = DerivedDataStatus.VALID,
            createdAt = 2L,
        )))
        markEvidenceStage(BIBLE_STAGE, "story-bible.v1", BIBLE_REVISION, sha256(bible))
        val master = "{\"title\":\"总纲\"}"
        database.memoryDao().createOutlineRevision(
            OutlineRevisionEntity(MASTER_REVISION, BOOK_ID, 1, null, RevisionSource.AI_GENERATED, 1,
                master, sha256(master), MASTER_STAGE, 3L),
            listOf(outlineNode("node.master", MASTER_REVISION, null, OutlineNodeType.BOOK, 0L, null, master)),
        )
        markEvidenceStage(MASTER_STAGE, "master-outline.v1", MASTER_REVISION, sha256(master))
        val window = "{\"window\":\"1-8\"}"
        database.memoryDao().createOutlineRevision(
            OutlineRevisionEntity(WINDOW_REVISION, BOOK_ID, 2, MASTER_REVISION, RevisionSource.AI_GENERATED, 1,
                window, sha256(window), WINDOW_STAGE, 4L),
            buildList {
                add(outlineNode("node.window", WINDOW_REVISION, null, OutlineNodeType.BOOK, 0L, null, window))
                add(outlineNode("node.arc", WINDOW_REVISION, "node.window", OutlineNodeType.ARC, 1L, null,
                    "{\"goal\":\"任务升级并推动关系\"}"))
                (1..4).forEach { index ->
                    add(outlineNode("node.chapter.$index", WINDOW_REVISION, "node.arc", OutlineNodeType.CHAPTER,
                        index + 1L, index, "{\"goal\":\"第${index}次任务推动人物、关系、系统与道具状态\"}"))
                }
            },
        )
        markEvidenceStage(WINDOW_STAGE, "arc-plan.v1", WINDOW_REVISION, sha256(window))
        seedBudget()
    }

    private suspend fun prepareChapter(
        chapterIndex: Int,
        routing: ChapterPromptPolicySelectionV1,
    ): GenerationChapterRun {
        val chapterId = chapterId(chapterIndex)
        val jobId = jobId(chapterIndex)
        val contextStage = contextStageId(chapterIndex)
        val planStage = planStageId(chapterIndex)
        database.libraryDao().insertChapter(chapter(chapterIndex))
        val permit = ChapterProgressionGateRepository(database).authorize(
            BOOK_ID, chapterId, FirstChapterGenerationMode.FULL_PLANNING,
        ) as ChapterProgressionAuthorization.Ready
        val binding = PromptBundleBindingRepository(database).bindForBook(BOOK_ID)
        GenerationJobSetupRepository(database).create(ChapterContextAssemblyJobFactory.create(
            ChapterContextAssemblyJobSpec(
                jobId = jobId,
                bookId = BOOK_ID,
                chapterId = chapterId,
                chapterIndex = chapterIndex,
                userIntentJson = INTENT_JSON,
                budgetSnapshotJson = "{\"fixture\":true}",
                promptBindingHash = binding.bindingHash,
                contextBudget = ChapterContextBudgetSpec(
                    contextLimitTokens = 32_768,
                    maximumOutputTokens = 8_192,
                    requestedOutputTokens = 4_096,
                    limitSource = ChapterContextLimitSource.OFFICIAL_METADATA,
                    unknownLimitConfirmed = false,
                    tokenizerFamily = "conservative-utf8-v1",
                ),
                progressionPermit = permit.permit,
                stageIds = ChapterContextAssemblyStageIds(contextStage, planStage),
                createdAt = chapterBaseTime(chapterIndex),
            ),
        ))
        val states = GenerationStateRepository(database)
        val base = chapterBaseTime(chapterIndex)
        states.transitionJob(jobId, GenerationJobStatus.CREATED, JobEvent.VALIDATION_PASSED, base + 1L)
        states.transitionStage(contextStage, GenerationStageStatus.PENDING, StageEvent.DEPENDENCIES_SATISFIED, base + 1L)
        states.acquireJobLease(jobId, "fixture.context.$chapterIndex", base + 2L)
        states.acquireStageLease(contextStage, "fixture.context.$chapterIndex", base + 3L)
        val token = requireNotNull(states.findStage(contextStage)?.leaseToken)
        val ready = when (val result = ChapterContextAssemblyRepository(database).assemble(contextStage, token, base + 4L)) {
            is PersistedChapterContextAssemblyResult.Ready -> result.context
            is PersistedChapterContextAssemblyResult.Blocked -> error("Context assembly blocked: $result")
        }
        val policyHash = ChapterPlanV2RequestFactory.policyCompilationHash(routing)
        val expectation = ChapterPlanExpectationV2(
            base = ChapterPlanExpectationV1(
                chapterId = chapterId,
                chapterIndex = chapterIndex,
                contextContentHash = ready.contentHash,
                contextSourceManifestHash = ready.sourceManifestHash,
                knownCharacterIds = setOf(HERO_ID, PARTNER_ID),
                confirmedAdultFictionalCharacterIds = setOf(HERO_ID, PARTNER_ID),
                sceneExecutionContract = strictSceneContract(),
            ),
            activationHash = routing.activation.activationHash,
            policyCompilationHash = policyHash,
            contextEvidenceHash = sha256(ready.providerPayloadJson),
            activeCapabilityIds = routing.activation.activeCapabilityIds,
            activeStateNamespaces = routing.activation.expectedStateNamespaceIds,
            priorObligationIds = setOf(OBLIGATION_ID),
        )
        val authority = ChapterPlanV2RequestFactory.create(ChapterPlanV2RequestSpec(
            requestId = "request.authority.$chapterIndex",
            generationId = jobId,
            stageId = planStage,
            attemptId = "attempt.authority.$chapterIndex",
            modelId = FakeProviderAdapter.DEFAULT_MODEL_ID,
            contextPayloadJson = ready.providerPayloadJson,
            contextContentHash = ready.contentHash,
            contextSourceManifestHash = ready.sourceManifestHash,
            contextEvidenceHash = expectation.contextEvidenceHash,
            expectation = expectation,
            policySelection = routing,
            maximumOutputTokens = 4_096,
            timeouts = TIMEOUTS,
        ))
        freezePlanStage(jobId, planStage, base, authority)
        GenerationControlRepository(database).requestPause(jobId, base + 5L)
        GenerationControlRepository(database).resume(jobId, base + 6L)
        return GenerationChapterRun(BOOK_ID, jobId, chapterIndex)
    }

    private suspend fun freezePlanStage(
        jobId: String,
        planStageId: String,
        createdAt: Long,
        authority: BoundChapterPlanV2Request,
    ) {
        val original = requireNotNull(database.generationDao().findStage(planStageId))
        val setup = GenerationJobSetup(
            jobId = jobId,
            bookId = BOOK_ID,
            jobType = GenerationJobType.CONTINUE_BOOK,
            userIntentJson = INTENT_JSON,
            budgetSnapshotJson = "{\"fixture\":true}",
            promptBundleVersion = PromptBundleCatalogV1.BUNDLE_VERSION,
            stages = listOf(GenerationStageSetup(
                original.stageId, original.phase, original.targetType, original.targetId,
                original.inputVersionHash, original.idempotencyKey, original.maxAttempts, original.inputSourcesJson,
            )),
            createdAt = createdAt,
        )
        val frozen = ChapterPlanV2FrozenSources.freeze(
            authority.expectationJson,
            authority.activationManifestJson,
            authority.activationHash,
            authority.policyManifestJson,
            authority.policyCompilationHash,
            authority.contextEvidenceHash,
        )
        val upgraded = ChapterPlanV2StageBinding.bind(setup, frozen).stages.single()
        database.openHelper.writableDatabase.execSQL(
            "UPDATE generation_stage SET input_version_hash = ?, idempotency_key = ?, input_sources_json = ? WHERE stage_id = ?",
            arrayOf(upgraded.inputVersionHash, upgraded.idempotencyKey, upgraded.inputSourcesJson, planStageId),
        )
    }

    private fun strictMixedPolicy(): ChapterPromptPolicySelectionV1 {
        val book = BookCapabilityRouterV1.derive(CreationSnapshotIntentSourceV1(
            CREATION_HASH, INTENT_JSON, INTENT_JSON,
        ))
        return when (val result = ChapterCapabilityRouterV1.activate(book, ChapterCapabilityRequestV1(
            phase = GenerationPhase.BUILD_CHAPTER_PLAN,
            chapterTaskText = "系统、道具、关系、亲密与人物连续性同时推进",
            explicitlyRequiredCapabilityIds = setOf(
                "progression-system", "item-progression", "relationship-progression", "intimacy-continuity",
            ),
            intimacyRelevant = true,
            adultGate = RelevantCharacterAdultGate.CONFIRMED_ADULTS,
            availablePolicyPromptChars = 20_000,
        ))) {
            is ChapterCapabilityRoutingDecisionV1.Ready -> result.selection
            is ChapterCapabilityRoutingDecisionV1.Blocked -> error(
                "Capability routing blocked: $result, manifest=${book.manifest.capabilityIds}",
            )
        }
    }

    private fun strictSceneContract() = SceneExecutionContract.Allowed(
        automatic = true,
        intimacyDetailLevel = 4,
        fadePolicy = FadePolicy.AVOID,
        strictBodyAndSensoryContinuity = true,
        requiredKeyProcessCoveragePercent = 100,
        fadeSubstitutionAllowed = false,
        requiresStateContinuity = true,
        requiresRelevantAftermath = true,
        instructions = listOf(PromptInstruction("scene.task128", "保持身体、感官、位置、物件、状态与剧情余波连续。")),
    )

    private fun planOutput(authority: BoundChapterPlanV2Request, ready: app.zhijuan.core.database.generation.ReadyChapterContext): String =
        """{"schemaVersion":2,"policyVersion":"zhijuan.chapter-plan-output-policy.v2","chapterId":"$CHAPTER_ID","chapterIndex":1,"contextContentHash":"${ready.contentHash}","contextSourceManifestHash":"${ready.sourceManifestHash}","openingState":"林岚与苏晚共同面对系统任务","chapterGoal":"完成系统任务并使关系与道具状态产生后果","closingState":"任务完成且双方承担可见余波","finalHook":"升级后的系统发出新警告","continuityConstraints":["林岚与苏晚均为虚构成年人"],"scenes":[{"sceneId":"scene-1","sequence":1,"purpose":"通过完整互动完成任务并推进剧情","location":"封闭档案室","pointOfViewCharacterId":"$HERO_ID","participantCharacterIds":["$HERO_ID","$PARTNER_ID"],"openingState":"两人确认任务条件","turn":"互动使系统判定任务完成","closingState":"双方关系、身体状态与系统等级改变","continuityCarry":["系统升至二级","钥匙仍由林岚持有"],"intimacyRelevant":true,"requiredProcessNodes":[{"nodeId":"process-1","sequence":1,"action":"双方确认边界并靠近","reaction":"苏晚明确回应","spatialStateAfter":"两人位于档案柜旁","bodyStateAfter":"呼吸加快但动作协调","clothingAndObjectStateAfter":"钥匙仍在林岚手中","sensoryChange":"触感与呼吸变化清晰"},{"nodeId":"process-2","sequence":2,"action":"双方持续完成任务要求","reaction":"彼此根据反应调整动作","spatialStateAfter":"位置连续且没有跳切","bodyStateAfter":"疲劳与兴奋逐步累积","clothingAndObjectStateAfter":"衣物与钥匙位置变化有记录","sensoryChange":"温度与触觉连续变化"},{"nodeId":"process-3","sequence":3,"action":"任务完成并停止","reaction":"双方确认结果与感受","spatialStateAfter":"两人仍在档案柜旁","bodyStateAfter":"呼吸逐渐平稳并出现疲劳","clothingAndObjectStateAfter":"整理衣物后林岚收起钥匙","sensoryChange":"余温与疲劳保留"}],"aftermath":"双方明确结盟，系统升级，身体与情绪余波继续影响后续决定"}],"activationHash":"${authority.activationHash}","policyCompilationHash":"${authority.policyCompilationHash}","chapterObjective":"完成任务、系统升级并让亲密关系产生可追踪后果","activeCapabilityIds":${stringArray(authority.expectation.activeCapabilityIds.sorted())},"obligationActions":[],"expectedStateDeltas":[{"namespace":"system","entityId":"$HERO_ID","relatedEntityId":null,"attribute":"level","oldValueJson":"1","newValueJson":"2","plannedEvidence":"系统明确显示升级"},{"namespace":"item","entityId":"$HERO_ID","relatedEntityId":null,"attribute":"owner","oldValueJson":"null","newValueJson":"\"$HERO_ID\"","plannedEvidence":"林岚收起钥匙"},{"namespace":"relationship","entityId":"$HERO_ID","relatedEntityId":"$PARTNER_ID","attribute":"trust","oldValueJson":"0","newValueJson":"1","plannedEvidence":"双方明确结盟"}],"prohibitedRepetitions":["不得复述既有任务说明"],"requiredCallbacks":["回应系统任务条件"],"sceneCauseEffect":[{"sceneId":"scene-1","cause":"系统任务要求双方合作","effect":"互动完成任务并改变关系与系统状态"}],"endHook":"系统升级后出现新的代价","contextEvidenceHash":"${authority.contextEvidenceHash}"}"""

    private fun candidateBody(): String {
        val paragraphs = (1..28).map { index ->
            "第${index}段里，林岚与苏晚按照已经确认的边界继续配合。动作、呼吸、距离、触感、衣物和钥匙的位置都随过程连续变化，没有跳时或转场。每一次选择都会带来可见反应，两人也据此调整下一步，因此这段互动推动了任务、关系和人物决定。"
        }
        return (listOf(
            "林岚和苏晚都是二十二岁的虚构成年人。封闭档案室里，两人确认系统任务条件，也明确表达同意与边界。",
        ) + paragraphs + listOf(
            "过程结束后，两人的呼吸逐渐平稳，疲劳和余温仍然存在。苏晚明确与林岚结盟，林岚把钥匙收进口袋。系统显示等级从一级升到二级，同时提示新的代价。",
        )).joinToString("\n")
    }

    private fun sequencePlanOutput(
        source: app.zhijuan.core.database.generation.ChapterPlanV2PromptSources,
        chapterIndex: Int,
    ): String {
        val authority = ChapterPlanV2RequestFactory.restore(FrozenChapterPlanV2RequestSpec(
            requestId = "request.sequence.$chapterIndex",
            generationId = jobId(chapterIndex),
            stageId = planStageId(chapterIndex),
            attemptId = "attempt.sequence.$chapterIndex",
            modelId = FakeProviderAdapter.DEFAULT_MODEL_ID,
            context = source.context,
            frozen = source.frozen,
            maximumOutputTokens = 4_096,
            timeouts = TIMEOUTS,
            idempotencyKey = source.stageIdempotencyKey,
        ))
        return planOutput(authority, source.context)
            .replace("\"chapterId\":\"$CHAPTER_ID\"", "\"chapterId\":\"${chapterId(chapterIndex)}\"")
            .replace("\"chapterIndex\":1", "\"chapterIndex\":$chapterIndex")
            .replace(
                "\"obligationActions\":[]",
                "\"obligationActions\":[{\"obligationId\":\"$OBLIGATION_ID\",\"action\":\"CARRY_FORWARD\",\"plannedEvidence\":\"系统警告仍待后续兑现\",\"nextDueChapterIndex\":null}]",
            )
            .replace(
                "\"oldValueJson\":\"1\",\"newValueJson\":\"2\"",
                "\"oldValueJson\":\"$chapterIndex\",\"newValueJson\":\"${chapterIndex + 1}\"",
            )
            .replace(
                "\"oldValueJson\":\"0\",\"newValueJson\":\"1\"",
                "\"oldValueJson\":\"${chapterIndex - 1}\",\"newValueJson\":\"$chapterIndex\"",
            )
    }

    private fun sequenceCandidateBody(chapterIndex: Int): String =
        "第${chapterIndex}章的新任务承接上一章正式结果。\n" + candidateBody() +
            "\n系统在第${chapterIndex}章确认等级升至${chapterIndex + 1}级。"

    private inner class FakeRemote(
        private val database: ZhijuanDatabase,
        private val artifacts: AndroidProtectedArtifactStore,
    ) : GenerationBoundRemoteExecutionProvider {
        private val adapters = mutableListOf<FakeProviderAdapter>()

        override suspend fun resolve(
            snapshot: app.zhijuan.core.database.generation.GenerationRunnerCurrentStageRouteSnapshot,
            requestedAt: Long,
        ): GenerationBoundRemoteExecution {
            val chapterIndex = snapshot.executionLease.jobId.substringAfterLast('.').toInt()
            val output = when (snapshot.route) {
                app.zhijuan.core.database.generation.GenerationRunnerStageRoute.CHAPTER_PLAN_V2 -> {
                    val source = ChapterPlanV2PromptSourcesRepository(database).loadBound(snapshot, requestedAt)
                    sequencePlanOutput(source, chapterIndex)
                }
                app.zhijuan.core.database.generation.GenerationRunnerStageRoute.INITIAL_CHAPTER_DRAFT_V1 ->
                    JsonObject(mapOf("body" to JsonPrimitive(sequenceCandidateBody(chapterIndex)))).toString()
                app.zhijuan.core.database.generation.GenerationRunnerStageRoute.CANDIDATE_CHAPTER_POST_ANALYSIS_V1 ->
                    postAnalysisOutput(snapshot, requestedAt).replace(
                        "\"evidenceBindings\":[",
                        "\"evidenceBindings\":[{\"bindingId\":\"bind-obligation\",\"subject\":\"OBLIGATION\"," +
                            "\"subjectIndex\":0,\"startCodePointInclusive\":37,\"endCodePointExclusive\":48},",
                    )
                else -> error("Fake Provider was requested for a local route: ${snapshot.route}")
            }
            val adapter = FakeProviderAdapter(fakeStreamScript {
                started("fake-${snapshot.route.name.lowercase()}")
                structured(output)
                usage(inputTokens = 120, outputTokens = 80)
                completed()
            }).also(adapters::add)
            return GenerationBoundRemoteExecution(
                adapter = adapter,
                profile = ProviderConnectionProfile.create(CONNECTION_ID, ProviderProtocol.OPENAI_CHAT_COMPAT, BASE_URL),
                modelId = FakeProviderAdapter.DEFAULT_MODEL_ID,
                connectionSnapshotJson = "{\"connectionId\":\"$CONNECTION_ID\"}",
                modelSnapshotJson = "{\"modelId\":\"fake-model\"}",
                protocolSnapshotJson = "{\"protocol\":\"OPENAI_CHAT_COMPAT\"}",
                maximumOutputTokens = 4_096,
                timeouts = TIMEOUTS,
                requestMaximumTokens = 1_000_000,
                estimatedTokens = 1,
            )
        }

        fun generateCalls(): Int = adapters.sumOf { it.stats.snapshot().generateCalls.toInt() }

        private suspend fun postAnalysisOutput(
            snapshot: app.zhijuan.core.database.generation.GenerationRunnerCurrentStageRouteSnapshot,
            requestedAt: Long,
        ): String {
            val source = app.zhijuan.core.database.generation.ChapterPostAnalysisPromptSourcesRepository(
                database, artifacts,
            ).loadBound(snapshot, requestedAt)
            val assembly = ChapterPostAnalysisBoundRequestAssemblerV1.assemble(
                source = source,
                requestId = "request.analysis.fixture",
                generationId = snapshot.executionLease.jobId,
                attemptId = "attempt.analysis.fixture",
                modelId = FakeProviderAdapter.DEFAULT_MODEL_ID,
                maximumOutputTokens = 4_096,
                timeouts = TIMEOUTS,
                minimumBodyCodePoints = 2_500,
            )
            val expected = (ChapterPostAnalysisRequestFactoryV1.prepare(assembly.requestSpec)
                as ChapterPostAnalysisRequestPreparationV1.Ready).boundRequest.expectation
            val criteria = expected.consistency.expectedCriteria.joinToString(",") { criterion ->
                "{\"criterion\":\"${criterion.name}\",\"status\":\"PASS\",\"issueIds\":[]}"
            }
            val process = expected.consistency.requiredProcessNodeIds.sorted().joinToString(",") { node ->
                "{\"requiredProcessNodeId\":\"$node\",\"status\":\"COVERED\",\"issueId\":null}"
            }
            val chapterIndex = expected.memory.chapterIndex
            return """{"schemaVersion":1,"sourceChapterVersionId":"${expected.memory.sourceChapterVersionId}","sourceChapterContentHash":"${expected.memory.sourceChapterContentHash}","chapterId":"${expected.memory.chapterId}","chapterIndex":${expected.memory.chapterIndex},"memorySnapshotHash":"${expected.tracking.memorySnapshotHash}","priorForeshadowSnapshotHash":"${expected.tracking.priorForeshadowSnapshotHash}","knownEntitySnapshotHash":"${expected.tracking.knownEntitySnapshotHash}","checkSourceSnapshotHash":"${expected.consistency.checkSourceSnapshotHash}","sceneContractHash":"${expected.consistency.sceneContractHash}","summary":{"objectiveOutcome":"系统任务完成并产生关系与升级后果","keyEvents":["系统升级"],"decisions":["双方结盟"],"relationshipChanges":["信任提升"],"endingState":"系统升级并出现新代价","unresolvedQuestions":["新代价是什么"],"importance":90},"entityEvents":[{"entityId":"$HERO_ID","attribute":"RELATIONSHIP","relatedEntityId":"$PARTNER_ID","oldValue":"前一状态","newValue":"继续结盟","storyTimeExpression":"当夜","confidenceMicros":1000000,"canonLevel":"STORY_CANON","evidence":"双方继续结盟"}],"canonFacts":[{"factKind":"DISCOVERY","entityId":"$HERO_ID","text":"系统完成第${chapterIndex}次任务后升级","canonLevel":"STORY_CANON","confidenceMicros":1000000,"conflictGroupId":"system-level-$chapterIndex"}],"timelineEvents":[{"name":"完成第${chapterIndex}次系统任务","participantEntityIds":["$HERO_ID","$PARTNER_ID"],"locationEntityId":null,"storyTimeExpression":"当夜","constraints":["升级发生在任务完成后"],"evidence":"正文先完成任务再显示升级"}],"foreshadowTransitions":[],"completedAndOpenObligations":[{"obligationId":"$OBLIGATION_ID","action":"CARRY_FORWARD","evidence":"系统警告仍待后续兑现","nextDueChapterIndex":null}],"storyStateDeltas":[{"namespace":"SYSTEM","entityId":"$HERO_ID","attribute":"level","relatedEntityId":null,"oldValueJson":"$chapterIndex","newValueJson":"${chapterIndex + 1}","evidence":"系统明确显示本次升级"},{"namespace":"ITEM","entityId":"$HERO_ID","attribute":"owner","relatedEntityId":null,"oldValueJson":"null","newValueJson":"\"$HERO_ID\"","evidence":"林岚继续持有钥匙"},{"namespace":"RELATIONSHIP","entityId":"$HERO_ID","attribute":"trust","relatedEntityId":"$PARTNER_ID","oldValueJson":"${chapterIndex - 1}","newValueJson":"$chapterIndex","evidence":"双方信任继续提升"}],"repetitionFindings":[],"consistencyFindings":[],"presentationFindings":[],"criterionResults":[$criteria],"requiredProcessResults":[$process],"severeRevisionRequired":false,"evidenceBindings":[{"bindingId":"bind-system","subject":"STORY_STATE_DELTA","subjectIndex":0,"startCodePointInclusive":1,"endCodePointExclusive":12},{"bindingId":"bind-item","subject":"STORY_STATE_DELTA","subjectIndex":1,"startCodePointInclusive":13,"endCodePointExclusive":24},{"bindingId":"bind-relationship","subject":"STORY_STATE_DELTA","subjectIndex":2,"startCodePointInclusive":25,"endCodePointExclusive":36}]}"""
        }
    }

    private suspend fun seedBudget() {
        PersistentBudgetPolicyRepository(database).activateBookPolicy(
            "policy.book.task128", BOOK_ID, BudgetLimit(maxTokens = 1_000_000_000), 1L,
        )
        PersistentBudgetPolicyRepository(database).activateDailyPolicy(
            "policy.daily.task128", "UTC", BudgetLimit(maxTokens = 1_000_000_000), 2L,
        )
        database.connectionDao().insertConnection(ConnectionProfileEntity(
            CONNECTION_ID, "Fake", "DEEPSEEK", "OPENAI_CHAT_COMPAT", BASE_URL,
            "https://example.invalid:443", "secret-ref-task128", "0000", "fake-model",
            "[\"fake-model\"]", "DISCOVERED", 2L, null, null, null, null, 2L, 2L,
        ))
        database.connectionDao().acceptDataDisclosureForCurrentDestination(CONNECTION_ID, 2L)
    }

    private suspend fun createEvidenceStage(jobId: String, stageId: String, phase: GenerationPhase) {
        GenerationJobSetupRepository(database).create(GenerationJobSetup(
            jobId, BOOK_ID, GenerationJobType.CREATE_BOOK, "{}", "{}", PromptBundleCatalogV1.BUNDLE_VERSION,
            listOf(GenerationStageSetup(
                stageId, phase, if (phase == GenerationPhase.BUILD_BIBLE) GenerationTargetType.STORY_BIBLE else GenerationTargetType.OUTLINE,
                BOOK_ID, sha256(stageId), "idem.$stageId", 1, "{}",
            )), 1L,
        ))
    }

    private fun markEvidenceStage(stageId: String, schema: String, objectId: String, hash: String) {
        val idField = if (schema == "arc-plan.v1") "outlineRevisionId" else "committedObjectId"
        val output = "{\"outputSchemaId\":\"$schema\",\"$idField\":\"$objectId\",\"contentHash\":\"$hash\"}"
        database.openHelper.writableDatabase.execSQL(
            "UPDATE generation_stage SET status = 'SUCCEEDED', output_reference_json = ?, updated_at = 5 WHERE stage_id = ?",
            arrayOf(output, stageId),
        )
    }

    private fun entity(id: String, name: String) = StoryEntity(
        id, BOOK_ID, StoryEntityType.CHARACTER, name, "[]", "{\"role\":\"fictional adult\"}",
        AdultStatus.CONFIRMED_ADULT, 22, BIBLE_REVISION, 2L, 2L,
    )

    private fun chapter(chapterIndex: Int) = ChapterEntity(
        chapterId(chapterIndex), BOOK_ID, chapterIndex, "第${chapterIndex}章", "第${chapterIndex}章",
        ChapterStatus.PLANNED, null,
        ConsistencyStatus.UNKNOWN, 1L, 1L,
    )

    private suspend fun assertReadableChapter(chapterIndex: Int) {
        val chapter = requireNotNull(database.libraryDao().findChapter(chapterId(chapterIndex)))
        val version = requireNotNull(chapter.currentVersionId?.let { database.libraryDao().findChapterVersion(it) })
        assertTrue(version.content.startsWith("第${chapterIndex}章的新任务"))
        assertEquals(1, database.libraryDao().versionsForChapter(chapter.chapterId).size)
        assertNotNull(database.memoryDao().findSummaryForVersion(version.chapterVersionId))
    }

    private fun outlineNode(
        id: String,
        revision: String,
        parent: String?,
        type: OutlineNodeType,
        order: Long,
        index: Int?,
        plan: String,
    ) = OutlineNodeEntity(id, revision, parent, type, order, index, id, plan, sha256(plan), 3L)

    private fun scalarInt(sql: String): Int = database.openHelper.readableDatabase.query(sql).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getInt(0)
    }

    private fun stringArray(values: List<String>) =
        JsonArray(values.map(::JsonPrimitive)).toString()

    private class IncrementingClock(start: Long) : GenerationExecutionClock {
        private val value = AtomicLong(start)
        override fun nowMillis(): Long = value.getAndIncrement()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val SNAPSHOT_ID = "snapshot.task128"
        const val BOOK_ID = "book.task128"
        const val CHAPTER_ID = "chapter.task128.1"
        const val HERO_ID = "character.lin"
        const val PARTNER_ID = "character.su"
        const val OBLIGATION_ID = "obligation.system-warning"
        const val BIBLE_JOB = "job.task128.bible"
        const val BIBLE_STAGE = "stage.task128.bible"
        const val BIBLE_REVISION = "bible.task128.1"
        const val MASTER_JOB = "job.task128.master"
        const val MASTER_STAGE = "stage.task128.master"
        const val MASTER_REVISION = "outline.task128.master"
        const val WINDOW_JOB = "job.task128.window"
        const val WINDOW_STAGE = "stage.task128.window"
        const val WINDOW_REVISION = "outline.task128.window"
        const val CONNECTION_ID = "connection.task128"
        const val BASE_URL = "https://example.invalid"
        val TIMEOUTS = ProviderTimeoutPolicy(1_000, 2_000, 2_000, 10_000)
        val CREATION_HASH = "a".repeat(64)
        val INTENT_JSON = """{"storyIdea":"两名虚构成年人的亲密关系、系统和道具状态在任务中共同推进","requestedGenreId":null,"advancedDetails":{}}"""
        val GENRE_JSON = """{"contentDimensionBaseline":{"conflictDetailLevel":1,"graphicInjuryLevel":0,"languageIntensityLevel":2,"emotionalPressureLevel":3}}"""
        val PRESENTATION_JSON = """{"directive":{"preset":"DETAILED","narrativeDetailLevel":4,"intimacyDetailLevel":4,"fadePolicy":"AVOID","conflictDetailOverride":null,"graphicInjuryOverride":null,"languageIntensityOverride":null,"emotionalPressureOverride":null,"presentationMappingSchemaVersion":1,"contentControlSchemaVersion":1},"resolvedProfile":{"preset":"DETAILED","narrativeDetailLevel":4,"intimacyDetailLevel":4,"conflictDetailLevel":1,"graphicInjuryLevel":0,"languageIntensityLevel":2,"emotionalPressureLevel":3,"fadePolicy":"AVOID","presentationMappingSchemaVersion":1,"contentControlSchemaVersion":1}}"""
        val BIBLE_JSON = """{"schemaVersion":1,"characters":[{"entityId":"$HERO_ID"},{"entityId":"$PARTNER_ID"}],"worldRules":[{"ruleId":"rule.system","text":"系统只在完成任务后升级"}],"hardFacts":[{"factId":"fact.adult","entityId":"$HERO_ID","text":"两名角色均为二十二岁的虚构成年人"}],"themes":["选择与后果"],"writingStyle":["有限视角"],"forbiddenChanges":["不得跳过状态变化的因果"]}"""

        fun chapterId(chapterIndex: Int) = "chapter.task128.$chapterIndex"
        fun jobId(chapterIndex: Int) = "job.task128.chapter.$chapterIndex"
        fun contextStageId(chapterIndex: Int) = "stage.task128.context.$chapterIndex"
        fun planStageId(chapterIndex: Int) = "stage.task128.plan.$chapterIndex"
        fun chapterBaseTime(chapterIndex: Int) = 100L + chapterIndex * 1_000L
    }
}
