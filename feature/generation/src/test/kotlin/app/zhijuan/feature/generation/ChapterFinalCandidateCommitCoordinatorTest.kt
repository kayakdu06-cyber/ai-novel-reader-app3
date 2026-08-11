package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.ChapterCandidateArtifactRoleV1
import app.zhijuan.core.database.generation.ChapterFinalCandidateArtifactEvidenceV1
import app.zhijuan.core.database.generation.ChapterFinalCandidateCommitDraftV1
import app.zhijuan.core.database.generation.ChapterFinalCandidateCommitResultV1
import app.zhijuan.core.database.generation.ChapterFinalCandidateRecoveryV1
import app.zhijuan.core.database.generation.ChapterFinalCommitStageSourceV1
import app.zhijuan.core.database.generation.GenerationLeaseToken
import app.zhijuan.core.database.generation.StoredGenerationStageState
import app.zhijuan.core.model.AdultStatus
import app.zhijuan.core.model.FadePolicy
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.StoryEntityType
import app.zhijuan.core.task.ChapterDeterministicConsistencyFactsV1
import app.zhijuan.core.task.ChapterRevisionPolicyInputV1
import app.zhijuan.core.task.ChapterRevisionPolicyV1
import app.zhijuan.core.task.ConsistencyEvidenceRange
import app.zhijuan.core.task.DeterministicEntityFactV1
import app.zhijuan.core.task.DeterministicEntityReferenceV1
import app.zhijuan.core.task.PromptInstruction
import app.zhijuan.core.task.SceneExecutionContract
import app.zhijuan.provider.common.ProviderModelId
import app.zhijuan.provider.common.ProviderTimeoutPolicy
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChapterFinalCandidateCommitCoordinatorTest {
    @Test
    fun preparingStageValidatesMapsTransitionsThenCommits() = runBlocking {
        val fixture = fixture(GenerationStageStatus.PREPARING)
        val lease = GenerationLeaseToken("worker.final.1", 150L)
        val trace = mutableListOf<String>()
        var committedDraft: ChapterFinalCandidateCommitDraftV1? = null
        val coordinator = ChapterFinalCandidateCommitCoordinatorV1(
            ChapterFinalCandidateCommitCoordinatorDependenciesV1(
                loadRecovery = {
                    trace += "load"
                    fixture.recovery
                },
                recoverArtifacts = {
                    trace += "recover"
                    assertEquals(fixture.recovery.artifacts, it)
                    fixture.artifacts
                },
                transitionToCommitting = { stageId, token, at ->
                    trace += "transition"
                    assertEquals(FINAL_STAGE_ID, stageId)
                    assertEquals(lease, token)
                    assertEquals(REQUESTED_AT, at)
                    storedStage(GenerationStageStatus.COMMITTING, token, at)
                },
                commitDraft = { stageId, token, draft ->
                    trace += "commit"
                    assertEquals(FINAL_STAGE_ID, stageId)
                    assertEquals(lease, token)
                    committedDraft = draft
                    fixture.result
                },
            ),
        )

        val result = coordinator.commit(FINAL_STAGE_ID, lease, REQUESTED_AT)

        assertEquals(fixture.result, result)
        assertEquals(listOf("load", "recover", "transition", "commit"), trace)
        assertEquals(REQUESTED_AT, requireNotNull(committedDraft).committedAt)
        assertEquals(fixture.recovery.source.routeBindingHash, finalRouteBinding(fixture))
    }

    @Test
    fun committingStageRebuildsAtPersistedTimeWithoutRepeatingTransition() = runBlocking {
        val fixture = fixture(GenerationStageStatus.COMMITTING)
        val lease = GenerationLeaseToken("worker.final.2", 150L)
        val trace = mutableListOf<String>()
        var committedDraft: ChapterFinalCandidateCommitDraftV1? = null
        val coordinator = ChapterFinalCandidateCommitCoordinatorV1(
            ChapterFinalCandidateCommitCoordinatorDependenciesV1(
                loadRecovery = {
                    trace += "load"
                    fixture.recovery
                },
                recoverArtifacts = {
                    trace += "recover"
                    fixture.artifacts
                },
                transitionToCommitting = { _, _, _ ->
                    throw AssertionError("COMMITTING recovery must not repeat the transition.")
                },
                commitDraft = { _, _, draft ->
                    trace += "commit"
                    committedDraft = draft
                    fixture.result
                },
            ),
        )

        coordinator.commit(FINAL_STAGE_ID, lease, REQUESTED_AT)

        assertEquals(listOf("load", "recover", "commit"), trace)
        assertEquals(FINAL_STAGE_UPDATED_AT, requireNotNull(committedDraft).committedAt)
    }

    @Test
    fun revisedCandidateKeepsRevisionSourceBindingSeparateFromFinalAcceptBinding() = runBlocking {
        val fixture = fixture(GenerationStageStatus.COMMITTING, revisionIndex = 1)
        assertTrue(fixture.recovery.candidateRouteBindingHash != fixture.recovery.source.routeBindingHash)
        var committedDraft: ChapterFinalCandidateCommitDraftV1? = null
        val coordinator = ChapterFinalCandidateCommitCoordinatorV1(
            ChapterFinalCandidateCommitCoordinatorDependenciesV1(
                loadRecovery = { fixture.recovery },
                recoverArtifacts = { fixture.artifacts },
                transitionToCommitting = { _, _, _ ->
                    throw AssertionError("COMMITTING recovery must not repeat the transition.")
                },
                commitDraft = { _, _, draft ->
                    committedDraft = draft
                    fixture.result
                },
            ),
        )

        coordinator.commit(
            FINAL_STAGE_ID,
            GenerationLeaseToken("worker.final.revision", 150L),
            REQUESTED_AT,
        )

        val draft = requireNotNull(committedDraft)
        assertEquals(1, draft.revisionIndex)
        assertEquals(fixture.recovery.source.candidateContentHashHistory, draft.candidateContentHashHistory)
    }

    @Test
    fun readyAndSucceededStagesFailBeforeArtifactRecovery() = runBlocking {
        listOf(GenerationStageStatus.READY, GenerationStageStatus.SUCCEEDED).forEach { status ->
            val fixture = fixture(status)
            val trace = mutableListOf<String>()
            val coordinator = ChapterFinalCandidateCommitCoordinatorV1(
                ChapterFinalCandidateCommitCoordinatorDependenciesV1(
                    loadRecovery = {
                        trace += "load"
                        fixture.recovery
                    },
                    recoverArtifacts = {
                        trace += "recover"
                        fixture.artifacts
                    },
                    transitionToCommitting = { _, _, _ ->
                        throw AssertionError("An ineligible stage must not transition.")
                    },
                    commitDraft = { _, _, _ ->
                        throw AssertionError("An ineligible stage must not commit.")
                    },
                ),
            )

            val failure = expectFailure {
                coordinator.commit(FINAL_STAGE_ID, GenerationLeaseToken("worker.invalid", 150L), REQUESTED_AT)
            }

            assertTrue(failure is IllegalArgumentException)
            assertEquals(listOf("load"), trace)
        }
    }

    @Test
    fun changedFinalPolicyBindingFailsBeforeStageTransitionOrCommit() = runBlocking {
        val fixture = fixture(GenerationStageStatus.PREPARING).let { valid ->
            valid.copy(
                recovery = valid.recovery.copy(
                    source = valid.recovery.source.copy(routeBindingHash = sha256("different-final-route")),
                ),
            )
        }
        val trace = mutableListOf<String>()
        val coordinator = ChapterFinalCandidateCommitCoordinatorV1(
            ChapterFinalCandidateCommitCoordinatorDependenciesV1(
                loadRecovery = {
                    trace += "load"
                    fixture.recovery
                },
                recoverArtifacts = {
                    trace += "recover"
                    fixture.artifacts
                },
                transitionToCommitting = { _, _, _ ->
                    trace += "transition"
                    storedStage(GenerationStageStatus.COMMITTING, null, REQUESTED_AT)
                },
                commitDraft = { _, _, _ ->
                    trace += "commit"
                    fixture.result
                },
            ),
        )

        val failure = expectFailure {
            coordinator.commit(FINAL_STAGE_ID, GenerationLeaseToken("worker.final.3", 150L), REQUESTED_AT)
        }

        assertTrue(failure is IllegalArgumentException)
        assertEquals(listOf("load", "recover"), trace)
    }

    @Test
    fun failedPreparingTransitionNeverReachesFinalRepository() = runBlocking {
        val fixture = fixture(GenerationStageStatus.PREPARING)
        val trace = mutableListOf<String>()
        val coordinator = ChapterFinalCandidateCommitCoordinatorV1(
            ChapterFinalCandidateCommitCoordinatorDependenciesV1(
                loadRecovery = {
                    trace += "load"
                    fixture.recovery
                },
                recoverArtifacts = {
                    trace += "recover"
                    fixture.artifacts
                },
                transitionToCommitting = { _, _, at ->
                    trace += "transition"
                    storedStage(GenerationStageStatus.PREPARING, null, at)
                },
                commitDraft = { _, _, _ ->
                    trace += "commit"
                    fixture.result
                },
            ),
        )

        val failure = expectFailure {
            coordinator.commit(FINAL_STAGE_ID, GenerationLeaseToken("worker.final.4", 150L), REQUESTED_AT)
        }

        assertTrue(failure is IllegalArgumentException)
        assertEquals(listOf("load", "recover", "transition"), trace)
    }

    private data class Fixture(
        val recovery: ChapterFinalCandidateRecoveryV1,
        val artifacts: ChapterFinalCandidateArtifactRecoveryResultV1,
        val result: ChapterFinalCandidateCommitResultV1,
        val policyInput: ChapterRevisionPolicyInputV1,
    )

    private fun fixture(
        status: GenerationStageStatus,
        revisionIndex: Int = 0,
    ): Fixture {
        val bound = boundRequest()
        val candidateRouteBindingHash = sha256("candidate-revision-route").takeIf { revisionIndex > 0 }
        val candidateHistory = if (revisionIndex == 0) {
            listOf(BODY_HASH)
        } else {
            listOf(sha256("prior-candidate-body"), BODY_HASH)
        }
        val routingSpec = ChapterCandidateConsistencyRoutingSpecV1(
            candidate = ChapterCandidatePipelineIdentityV1(
                chapterVersionId = VERSION_ID,
                chapterId = CHAPTER_ID,
                chapterIndex = 1,
                contentHash = BODY_HASH,
                revisionIndex = revisionIndex,
                routeBindingHash = candidateRouteBindingHash,
            ),
            candidateContent = BODY,
            candidateContentHashHistory = candidateHistory,
            minimumBodyCodePoints = 100,
            totalRevisionAttemptsUsed = revisionIndex,
            revisionStageMaximumAttempts = 2,
            nextStageId = FINAL_STAGE_ID,
            expectedCurrentVersionId = null,
            revisionRequest = null,
            routedAt = FINAL_STAGE_UPDATED_AT,
        )
        val snapshotJson = ChapterFinalConsistencyMappingSnapshotCodecV1.capture(bound, routingSpec)
        val consistency = acceptedConsistencyReport(bound.expectation)
        val gate = ChapterConsistencyAcceptanceGateV1.evaluate(
            bound.localReport,
            consistency,
            bound.expectation,
            bound.sceneContract,
        )
        val policyInput = ChapterRevisionPolicyInputV1(
            currentCandidateContentHash = BODY_HASH,
            candidateContentHashHistory = candidateHistory,
            bodyCodePointCount = bound.expectation.bodyCodePointCount,
            minimumBodyCodePoints = 100,
            completedAutomaticRevisions = revisionIndex,
            totalRevisionAttemptsUsed = revisionIndex,
            stageMaximumAttempts = 2,
            sceneContract = bound.sceneContract,
            issues = ChapterRevisionRequestFactoryV1.issuesFrom(gate),
        )
        val source = ChapterFinalCommitStageSourceV1(
            candidateChapterVersionId = VERSION_ID,
            candidateContentHash = BODY_HASH,
            chapterId = CHAPTER_ID,
            chapterIndex = 1,
            revisionIndex = revisionIndex,
            predecessorStageId = CONSISTENCY_STAGE_ID,
            routeBindingHash = ChapterRevisionPolicyV1.routingBindingHash(policyInput),
            expectedCurrentVersionId = null,
            maximumAutomaticRevisions = 1,
            candidateContentHashHistory = candidateHistory,
            consistencyRequestSourceBindingHash = bound.sourceBindingHash,
            consistencyMappingSnapshotJson = snapshotJson,
            consistencyMappingSnapshotContentHash =
                ChapterFinalConsistencyMappingSnapshotCodecV1.contentHash(snapshotJson),
        )
        val memory = ChapterMemoryV1(
            sourceChapterVersionId = VERSION_ID,
            sourceChapterContentHash = BODY_HASH,
            chapterId = CHAPTER_ID,
            chapterIndex = 1,
            summary = ChapterMemorySummaryV1(
                objectiveOutcome = "done",
                keyEvents = emptyList(),
                decisions = emptyList(),
                relationshipChanges = emptyList(),
                endingState = "stable",
                unresolvedQuestions = emptyList(),
                importance = 50,
            ),
            entityEvents = emptyList(),
            facts = emptyList(),
            canonicalJson = "{}",
            contentHash = sha256("memory-output"),
        )
        val tracking = ChapterStoryTrackingV1(
            sourceChapterVersionId = VERSION_ID,
            sourceChapterContentHash = BODY_HASH,
            chapterId = CHAPTER_ID,
            chapterIndex = 1,
            memorySnapshotHash = sha256("memory-snapshot"),
            priorForeshadowSnapshotHash = sha256("foreshadow-snapshot"),
            knownEntitySnapshotHash = sha256("entity-snapshot"),
            timelineEvents = emptyList(),
            foreshadowOperations = emptyList(),
            canonicalJson = "{}",
            contentHash = sha256("tracking-output"),
        )
        val evidence = listOf(
            evidence(ChapterCandidateArtifactRoleV1.BODY, BODY_HASH),
            evidence(ChapterCandidateArtifactRoleV1.MEMORY, memory.contentHash),
            evidence(ChapterCandidateArtifactRoleV1.TRACKING, tracking.contentHash),
            evidence(ChapterCandidateArtifactRoleV1.CONSISTENCY, consistency.contentHash).copy(
                sourceBindingHash = bound.sourceBindingHash,
            ),
        )
        return Fixture(
            recovery = ChapterFinalCandidateRecoveryV1(
                finalStageId = FINAL_STAGE_ID,
                jobId = JOB_ID,
                bookId = BOOK_ID,
                finalStageStatus = status,
                finalStageUpdatedAt = FINAL_STAGE_UPDATED_AT,
                source = source,
                candidateRouteBindingHash = candidateRouteBindingHash,
                artifacts = evidence,
                memoryModelSnapshotJson = MODEL_SNAPSHOT,
                trackingModelSnapshotJson = MODEL_SNAPSHOT,
                consistencyModelSnapshotJson = MODEL_SNAPSHOT,
            ),
            artifacts = ChapterFinalCandidateArtifactRecoveryResultV1(BODY, memory, tracking, consistency),
            result = ChapterFinalCandidateCommitResultV1(
                chapterVersionId = VERSION_ID,
                chapterId = CHAPTER_ID,
                stageId = FINAL_STAGE_ID,
                revisionIndex = revisionIndex,
                replayed = false,
                isCurrentVersion = true,
                staleCascade = null,
            ),
            policyInput = policyInput,
        )
    }

    private fun boundRequest(): BoundChapterConsistencyCheckRequest {
        val spec = ChapterConsistencyCheckRequestSpec(
            requestId = "request.check.1",
            generationId = JOB_ID,
            stageId = CONSISTENCY_STAGE_ID,
            attemptId = "attempt.consistency.1",
            modelId = ProviderModelId.from("local-fake"),
            sourceChapterVersionId = VERSION_ID,
            sourceChapterContentHash = BODY_HASH,
            chapterId = CHAPTER_ID,
            chapterIndex = 1,
            chapterContent = BODY,
            minimumBodyCodePoints = 100,
            deterministicFacts = ChapterDeterministicConsistencyFactsV1(
                currentChapterIndex = 1,
                expectedChapterIndex = 1,
                entities = listOf(
                    DeterministicEntityFactV1(
                        entityId = "char.hero",
                        entityType = StoryEntityType.CHARACTER,
                        adultStatus = AdultStatus.CONFIRMED_ADULT,
                        ageYears = 24,
                    ),
                ),
                references = listOf(
                    DeterministicEntityReferenceV1(
                        entityId = "char.hero",
                        adultRelevant = true,
                        evidenceRange = ConsistencyEvidenceRange(0, 4),
                    ),
                ),
                characterReturns = emptyList(),
                locationConstraints = emptyList(),
                itemOwnershipConstraints = emptyList(),
                timelineConstraints = emptyList(),
                requiredEvents = emptyList(),
            ),
            sceneExecutionContract = SceneExecutionContract.Allowed(
                automatic = true,
                intimacyDetailLevel = 2,
                fadePolicy = FadePolicy.ALLOW,
                strictBodyAndSensoryContinuity = false,
                requiredKeyProcessCoveragePercent = null,
                fadeSubstitutionAllowed = true,
                requiresStateContinuity = true,
                requiresRelevantAftermath = true,
                instructions = listOf(PromptInstruction("scene.fixture", "fixture")),
            ),
            sceneParticipantEntityIds = setOf("char.hero"),
            requiredProcessNodeIds = emptySet(),
            knownEntities = listOf(
                ChapterConsistencyKnownEntityV1(
                    entityId = "char.hero",
                    canonicalName = "主角",
                    entityType = StoryEntityType.CHARACTER,
                    adultStatus = AdultStatus.CONFIRMED_ADULT,
                    ageYears = 24,
                    realIdentifiablePerson = false,
                ),
            ),
            evidenceItems = emptyList(),
            maximumOutputTokens = 2_048,
            timeouts = ProviderTimeoutPolicy(1_000, 1_000, 1_000, 2_000),
        )
        return (ChapterConsistencyCheckRequestFactoryV1.prepare(spec) as ChapterConsistencyRequestPreparationV1.Ready)
            .boundRequest
    }

    private fun acceptedConsistencyReport(expectation: ChapterConsistencyExpectation): ChapterConsistencyReportV1 {
        val document = JsonObject(
            linkedMapOf(
                "schemaVersion" to JsonPrimitive(1),
                "sourceChapterVersionId" to JsonPrimitive(expectation.sourceChapterVersionId),
                "sourceChapterContentHash" to JsonPrimitive(expectation.sourceChapterContentHash),
                "chapterId" to JsonPrimitive(expectation.chapterId),
                "chapterIndex" to JsonPrimitive(expectation.chapterIndex),
                "checkSourceSnapshotHash" to JsonPrimitive(expectation.checkSourceSnapshotHash),
                "sceneContractHash" to JsonPrimitive(expectation.sceneContractHash),
                "criterionResults" to JsonArray(expectation.expectedCriteria.map { criterion ->
                    JsonObject(
                        linkedMapOf(
                            "criterion" to JsonPrimitive(criterion.name),
                            "status" to JsonPrimitive("PASS"),
                            "issueIds" to JsonArray(emptyList()),
                        ),
                    )
                }),
                "requiredProcessResults" to JsonArray(expectation.requiredProcessNodeIds.sorted().map { nodeId ->
                    JsonObject(
                        linkedMapOf(
                            "requiredProcessNodeId" to JsonPrimitive(nodeId),
                            "status" to JsonPrimitive("COVERED"),
                            "issueId" to JsonNull,
                        ),
                    )
                }),
                "issues" to JsonArray(emptyList()),
            ),
        )
        return ChapterConsistencyOutputParser().fromDocument(document)
    }

    private fun evidence(
        role: ChapterCandidateArtifactRoleV1,
        canonicalOutputHash: String,
    ) = ChapterFinalCandidateArtifactEvidenceV1(
        role = role,
        stageId = when (role) {
            ChapterCandidateArtifactRoleV1.BODY -> "stage.body.1"
            ChapterCandidateArtifactRoleV1.MEMORY -> "stage.memory.1"
            ChapterCandidateArtifactRoleV1.TRACKING -> "stage.tracking.1"
            ChapterCandidateArtifactRoleV1.CONSISTENCY -> CONSISTENCY_STAGE_ID
        },
        attemptId = "attempt.${role.name.lowercase()}.1",
        artifactRefId = "artifact.${role.name.lowercase()}.1",
        artifactRevision = 1,
        rawOutputHash = sha256("raw-${role.name}"),
        canonicalOutputHash = canonicalOutputHash,
        sourceBindingHash = sha256("source-${role.name}"),
    )

    private fun storedStage(
        status: GenerationStageStatus,
        leaseToken: GenerationLeaseToken?,
        updatedAt: Long,
    ) = StoredGenerationStageState(
        stageId = FINAL_STAGE_ID,
        jobId = JOB_ID,
        status = status,
        attemptCount = 0,
        maxAttempts = 1,
        standardErrorCode = null,
        nextRetryAt = null,
        leaseToken = leaseToken,
        leaseHeartbeatAt = updatedAt,
        updatedAt = updatedAt,
    )

    private fun finalRouteBinding(fixture: Fixture): String =
        ChapterRevisionPolicyV1.routingBindingHash(fixture.policyInput)

    private suspend fun expectFailure(block: suspend () -> Unit): Throwable = try {
        block()
        throw AssertionError("Expected failure")
    } catch (error: Throwable) {
        if (error is AssertionError && error.message == "Expected failure") throw error
        error
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        const val BOOK_ID = "book.final.1"
        const val CHAPTER_ID = "chapter.final.1"
        const val VERSION_ID = "chapter.version.final.1"
        const val JOB_ID = "job.final.1"
        const val CONSISTENCY_STAGE_ID = "stage.consistency.1"
        const val FINAL_STAGE_ID = "stage.commit.1"
        const val MODEL_SNAPSHOT = "{\"model\":\"local-fake\"}"
        const val FINAL_STAGE_UPDATED_AT = 200L
        const val REQUESTED_AT = 300L
        val BODY = "这是一段仅用于最终本地提交协调器测试的普通候选正文。".repeat(50)
        val BODY_HASH = sha256Static(BODY)

        private fun sha256Static(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
