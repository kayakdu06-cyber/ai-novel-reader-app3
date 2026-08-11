package app.zhijuan.feature.generation

import app.zhijuan.core.model.AdultStatus
import app.zhijuan.core.model.ConsistencyIssueCode
import app.zhijuan.core.model.ConsistencyIssueSeverity
import app.zhijuan.core.model.FadePolicy
import app.zhijuan.core.model.StoryEntityType
import app.zhijuan.core.task.PromptInstruction
import app.zhijuan.core.task.SceneExecutionContract
import app.zhijuan.core.task.ChapterDeterministicConsistencyFactsV1
import app.zhijuan.core.task.ConsistencyEvidenceRange
import app.zhijuan.core.task.DeterministicEntityFactV1
import app.zhijuan.core.task.DeterministicEntityReferenceV1
import app.zhijuan.core.task.ChapterRevisionNeedsActionReasonV1
import app.zhijuan.core.task.ChapterRevisionPolicyDecisionV1
import app.zhijuan.provider.common.ProviderModelId
import app.zhijuan.provider.common.ProviderTimeoutPolicy
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChapterConsistencyAcceptanceGateTest {
    @Test
    fun productionRoutingPlannerKeepsMinorCandidateOnCommitPathWithoutBuildingRevisionRequest() {
        val bound = proportionalBound()
        val report = parseValid(
            bound,
            document(
                bound.expectation,
                listOf(issue("model.voice", ConsistencyIssueCode.VOICE_CONTINUITY_BREAK, ConsistencyIssueSeverity.MINOR)),
            ),
        )

        val plan = ChapterCandidateConsistencyRoutingPlannerV1.plan(
            report,
            bound,
            routingSpec(bound, revisionIndex = 0, revisionRequest = revisionSeed(strict = false)),
        )

        assertTrue(plan.policyDecision is ChapterRevisionPolicyDecisionV1.AcceptCandidate)
        assertEquals(ChapterConsistencyGateDecisionV1.ACCEPT_CANDIDATE, plan.gate.decision)
        assertEquals(null, plan.revisionRequest)
    }

    @Test
    fun productionRoutingPlannerBuildsRevisionRequestFromSameMajorIssueSet() {
        val bound = proportionalBound()
        val report = parseValid(
            bound,
            document(
                bound.expectation,
                listOf(issue("model.action", ConsistencyIssueCode.ACTION_REACTION_GAP, ConsistencyIssueSeverity.MAJOR)),
            ),
        )

        val plan = ChapterCandidateConsistencyRoutingPlannerV1.plan(
            report,
            bound,
            routingSpec(bound, revisionIndex = 0, revisionRequest = revisionSeed(strict = false)),
        )
        val decision = plan.policyDecision as ChapterRevisionPolicyDecisionV1.ReviseAutomatically

        assertEquals(ChapterConsistencyGateDecisionV1.REVISE_CANDIDATE, plan.gate.decision)
        assertEquals(listOf("model.action"), plan.policyInput.issues.map { it.issueId })
        assertEquals("stage.route.next.0", requireNotNull(plan.revisionRequest).request.stageId)
        assertEquals(decision.repairPlanHash, plan.revisionRequest.plan.repairPlanHash)
    }

    @Test
    fun productionRoutingPlannerExhaustsBeforeBuildingAnotherRevisionRequest() {
        val bound = proportionalBound()
        val report = parseValid(
            bound,
            document(
                bound.expectation,
                listOf(issue("model.action", ConsistencyIssueCode.ACTION_REACTION_GAP, ConsistencyIssueSeverity.MAJOR)),
            ),
        )

        val plan = ChapterCandidateConsistencyRoutingPlannerV1.plan(
            report,
            bound,
            routingSpec(bound, revisionIndex = 1, revisionRequest = revisionSeed(strict = false)),
        )
        val decision = plan.policyDecision as ChapterRevisionPolicyDecisionV1.NeedsAction

        assertEquals(ChapterRevisionNeedsActionReasonV1.AUTOMATIC_REVISION_LIMIT_REACHED, decision.reason)
        assertEquals(null, plan.revisionRequest)
    }

    @Test
    fun productionRoutingPlannerRejectsCandidateContentThatDiffersFromFrozenCheck() {
        val bound = proportionalBound()
        val report = parseValid(bound, document(bound.expectation, emptyList()))
        val wrongCandidate = routingSpec(bound, revisionIndex = 0, revisionRequest = null).copy(
            candidate = ChapterCandidatePipelineIdentityV1(
                chapterVersionId = bound.expectation.sourceChapterVersionId,
                chapterId = bound.expectation.chapterId,
                chapterIndex = bound.expectation.chapterIndex,
                contentHash = sha256("different body"),
                revisionIndex = 0,
                routeBindingHash = null,
            ),
            candidateContent = "different body",
            candidateContentHashHistory = listOf(sha256("different body")),
        )

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            ChapterCandidateConsistencyRoutingPlannerV1.plan(report, bound, wrongCandidate)
        }
    }

    @Test
    fun productionRoutingPlannerRejectsRevisionSeedFromDifferentJobBeforeBuildingRequest() {
        val bound = proportionalBound()
        val report = parseValid(
            bound,
            document(
                bound.expectation,
                listOf(issue("model.action", ConsistencyIssueCode.ACTION_REACTION_GAP, ConsistencyIssueSeverity.MAJOR)),
            ),
        )
        val wrongJobSeed = revisionSeed(strict = false).copy(generationId = "job.other.9")

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            ChapterCandidateConsistencyRoutingPlannerV1.plan(
                report,
                bound,
                routingSpec(bound, revisionIndex = 0, revisionRequest = wrongJobSeed),
            )
        }
    }

    @Test
    fun test039FixedContinuityNegativeSetForcesRevisionAtFrozenSeverities() {
        val bound = strictBound()
        val issues = listOf(
            issue("model.fade", ConsistencyIssueCode.FADE_SUBSTITUTION, ConsistencyIssueSeverity.BLOCKER),
            issue("model.action", ConsistencyIssueCode.ACTION_REACTION_GAP, ConsistencyIssueSeverity.MAJOR),
            issue("model.space", ConsistencyIssueCode.SPATIAL_CONTINUITY_BREAK, ConsistencyIssueSeverity.BLOCKER),
            issue("model.body", ConsistencyIssueCode.BODY_STATE_CONTINUITY_BREAK, ConsistencyIssueSeverity.BLOCKER),
            issue("model.aftermath", ConsistencyIssueCode.RELEVANT_AFTERMATH_MISSING, ConsistencyIssueSeverity.MAJOR),
            issue(
                "model.process",
                ConsistencyIssueCode.REQUIRED_PROCESS_MISSING,
                ConsistencyIssueSeverity.BLOCKER,
                processIds = listOf("process.1"),
            ),
            issue("model.sensory", ConsistencyIssueCode.SENSORY_CONTINUITY_BREAK, ConsistencyIssueSeverity.MAJOR),
            issue("model.mechanical", ConsistencyIssueCode.MECHANICAL_DETAIL_LIST, ConsistencyIssueSeverity.MINOR),
        )
        val report = parseValid(bound, document(bound.expectation, issues))

        val result = ChapterConsistencyAcceptanceGateV1.evaluate(
            bound.localReport,
            report,
            bound.expectation,
            bound.sceneContract,
        )

        assertEquals(ChapterConsistencyGateDecisionV1.REVISE_CANDIDATE, result.decision)
        assertEquals(4, result.blockerCount)
        assertEquals(3, result.majorCount)
        assertEquals(1, result.minorCount)
        assertEquals(issues.map { (it.getValue("code") as JsonPrimitive).content }.toSet(), result.issues.map { it.code.name }.toSet())
    }

    @Test
    fun ordinaryVoiceDifferenceIsMinorAndDoesNotBecomeAFalseBlocker() {
        val bound = proportionalBound()
        val report = parseValid(
            bound,
            document(
                bound.expectation,
                listOf(
                    issue(
                        "model.voice",
                        ConsistencyIssueCode.VOICE_CONTINUITY_BREAK,
                        ConsistencyIssueSeverity.MINOR,
                    ),
                ),
            ),
        )

        val result = ChapterConsistencyAcceptanceGateV1.evaluate(
            bound.localReport,
            report,
            bound.expectation,
            bound.sceneContract,
        )

        assertEquals(ChapterConsistencyGateDecisionV1.ACCEPT_CANDIDATE, result.decision)
        assertEquals(0, result.blockerCount)
        assertEquals(0, result.majorCount)
        assertEquals(1, result.minorCount)
    }

    @Test
    fun persistenceDraftContainsOnlyBoundedCodesAndPositionsNotChapterProse() {
        val bound = strictBound()
        val report = parseValid(
            bound,
            document(
                bound.expectation,
                listOf(issue("model.fade", ConsistencyIssueCode.FADE_SUBSTITUTION, ConsistencyIssueSeverity.BLOCKER)),
            ),
        )
        val first = ChapterConsistencyPersistenceMapperV1.map(
            bound.localReport,
            report,
            bound.expectation,
            bound.sceneContract,
            ChapterConsistencyMappingSpecV1(
                bookId = "book.1",
                generationStageId = "stage.check.1",
                modelSnapshotJson = "{\"model\":\"local-fake\"}",
                createdAt = 100L,
            ),
        )
        val second = ChapterConsistencyPersistenceMapperV1.map(
            bound.localReport,
            report,
            bound.expectation,
            bound.sceneContract,
            ChapterConsistencyMappingSpecV1(
                bookId = "book.1",
                generationStageId = "stage.check.1",
                modelSnapshotJson = "{\"model\":\"local-fake\"}",
                createdAt = 100L,
            ),
        )

        assertEquals(first.report, second.report)
        assertEquals(first.reportContentHash, second.reportContentHash)
        assertTrue(first.report.issuesJson.contains("FADE_SUBSTITUTION"))
        assertTrue(first.report.issuesJson.contains("REVISE_CANDIDATE"))
        assertFalse(first.report.issuesJson.contains(BODY.take(20)))
        assertFalse(first.report.issuesJson.contains("evidenceText"))
    }

    private fun strictBound() = ready(strict = true)

    private fun proportionalBound() = ready(strict = false)

    private fun ready(strict: Boolean): BoundChapterConsistencyCheckRequest {
        val scene = sceneExecution(strict)
        val spec = ChapterConsistencyCheckRequestSpec(
            requestId = "request.check.1",
            generationId = "job.check.1",
            stageId = "stage.check.1",
            attemptId = "attempt.check.1",
            modelId = ProviderModelId.from("local-fake"),
            sourceChapterVersionId = "version.candidate.1",
            sourceChapterContentHash = sha256(BODY),
            chapterId = "chapter.1",
            chapterIndex = 1,
            chapterContent = BODY,
            minimumBodyCodePoints = 100,
            deterministicFacts = ChapterDeterministicConsistencyFactsV1(
                currentChapterIndex = 1,
                expectedChapterIndex = 1,
                entities = listOf(
                    DeterministicEntityFactV1(
                        "char.hero",
                        StoryEntityType.CHARACTER,
                        AdultStatus.CONFIRMED_ADULT,
                        24,
                    ),
                ),
                references = listOf(
                    DeterministicEntityReferenceV1(
                        "char.hero",
                        true,
                        ConsistencyEvidenceRange(0, 4),
                    ),
                ),
                characterReturns = emptyList(),
                locationConstraints = emptyList(),
                itemOwnershipConstraints = emptyList(),
                timelineConstraints = emptyList(),
                requiredEvents = emptyList(),
            ),
            sceneExecutionContract = scene,
            sceneParticipantEntityIds = setOf("char.hero"),
            requiredProcessNodeIds = if (strict) setOf("process.1", "process.2") else emptySet(),
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
            evidenceItems = listOf(
                ChapterConsistencyEvidenceItemV1(
                    "foreshadow.1",
                    ChapterConsistencyEvidenceKindV1.FORESHADOW_STATE,
                    "{\"status\":\"PLANTED\"}",
                ),
            ),
            maximumOutputTokens = 2_048,
            timeouts = ProviderTimeoutPolicy(1_000, 1_000, 1_000, 2_000),
        )
        return (ChapterConsistencyCheckRequestFactoryV1.prepare(spec) as ChapterConsistencyRequestPreparationV1.Ready)
            .boundRequest
    }

    private fun sceneExecution(strict: Boolean) = SceneExecutionContract.Allowed(
        automatic = true,
        intimacyDetailLevel = if (strict) 4 else 2,
        fadePolicy = if (strict) FadePolicy.AVOID else FadePolicy.ALLOW,
        strictBodyAndSensoryContinuity = strict,
        requiredKeyProcessCoveragePercent = if (strict) 100 else null,
        fadeSubstitutionAllowed = !strict,
        requiresStateContinuity = true,
        requiresRelevantAftermath = true,
        instructions = listOf(PromptInstruction("scene.fixture", "fixture")),
    )

    private fun routingSpec(
        bound: BoundChapterConsistencyCheckRequest,
        revisionIndex: Int,
        revisionRequest: ChapterCandidateRevisionRequestSeedV1?,
    ): ChapterCandidateConsistencyRoutingSpecV1 {
        val currentHash = bound.expectation.sourceChapterContentHash
        return ChapterCandidateConsistencyRoutingSpecV1(
            candidate = ChapterCandidatePipelineIdentityV1(
                chapterVersionId = bound.expectation.sourceChapterVersionId,
                chapterId = bound.expectation.chapterId,
                chapterIndex = bound.expectation.chapterIndex,
                contentHash = currentHash,
                revisionIndex = revisionIndex,
                routeBindingHash = sha256("revision-result-$revisionIndex").takeIf { revisionIndex > 0 },
            ),
            candidateContent = BODY,
            candidateContentHashHistory = if (revisionIndex == 0) {
                listOf(currentHash)
            } else {
                listOf(sha256("prior-candidate"), currentHash)
            },
            minimumBodyCodePoints = 100,
            totalRevisionAttemptsUsed = revisionIndex,
            revisionStageMaximumAttempts = 2,
            nextStageId = "stage.route.next.$revisionIndex",
            expectedCurrentVersionId = null,
            revisionRequest = revisionRequest,
            routedAt = 200L,
        )
    }

    private fun revisionSeed(strict: Boolean) = ChapterCandidateRevisionRequestSeedV1(
        requestId = "request.revision.1",
        generationId = "job.check.1",
        attemptId = "attempt.revision.1",
        modelId = ProviderModelId.from("local-fake"),
        sceneExecutionContract = sceneExecution(strict),
        sceneParticipantEntityIds = setOf("char.hero"),
        requiredProcessNodeIds = if (strict) setOf("process.1", "process.2") else emptySet(),
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
        maximumOutputTokens = 2_048,
        timeouts = ProviderTimeoutPolicy(1_000, 1_000, 1_000, 2_000),
    )

    private fun parseValid(
        bound: BoundChapterConsistencyCheckRequest,
        document: JsonObject,
    ): ChapterConsistencyReportV1 {
        val validated = StructuredOutputValidator().validate(
            document.toString().encodeToByteArray(),
            bound.outputContract,
        )
        assertTrue(validated is StructuredOutputValidationResult.Valid, validated.toString())
        return ChapterConsistencyOutputParser().fromDocument(document)
    }

    private fun document(
        expected: ChapterConsistencyExpectation,
        issues: List<JsonObject>,
    ): JsonObject {
        val byCriterion = issues.groupBy { issue ->
            ConsistencyIssuePolicyV1.criterionFor(
                ConsistencyIssueCode.valueOf((issue.getValue("code") as JsonPrimitive).content),
            )
        }
        val missingNodeIssues = issues.flatMap { issue ->
            val code = ConsistencyIssueCode.valueOf((issue.getValue("code") as JsonPrimitive).content)
            if (code != ConsistencyIssueCode.REQUIRED_PROCESS_MISSING) return@flatMap emptyList()
            val issueId = (issue.getValue("issueId") as JsonPrimitive).content
            (issue.getValue("relatedRequiredProcessNodeIds") as JsonArray).map { node ->
                (node as JsonPrimitive).content to issueId
            }
        }.toMap()
        return JsonObject(
            linkedMapOf(
                "schemaVersion" to JsonPrimitive(1),
                "sourceChapterVersionId" to JsonPrimitive(expected.sourceChapterVersionId),
                "sourceChapterContentHash" to JsonPrimitive(expected.sourceChapterContentHash),
                "chapterId" to JsonPrimitive(expected.chapterId),
                "chapterIndex" to JsonPrimitive(expected.chapterIndex),
                "checkSourceSnapshotHash" to JsonPrimitive(expected.checkSourceSnapshotHash),
                "sceneContractHash" to JsonPrimitive(expected.sceneContractHash),
                "criterionResults" to JsonArray(expected.expectedCriteria.map { criterion ->
                    val ids = byCriterion[criterion].orEmpty().map { (it.getValue("issueId") as JsonPrimitive).content }
                    JsonObject(
                        linkedMapOf(
                            "criterion" to JsonPrimitive(criterion.name),
                            "status" to JsonPrimitive(if (ids.isEmpty()) "PASS" else "ISSUE"),
                            "issueIds" to JsonArray(ids.map(::JsonPrimitive)),
                        ),
                    )
                }),
                "requiredProcessResults" to JsonArray(expected.requiredProcessNodeIds.sorted().map { nodeId ->
                    val issueId = missingNodeIssues[nodeId]
                    JsonObject(
                        linkedMapOf(
                            "requiredProcessNodeId" to JsonPrimitive(nodeId),
                            "status" to JsonPrimitive(if (issueId == null) "COVERED" else "MISSING"),
                            "issueId" to (issueId?.let(::JsonPrimitive) ?: JsonNull),
                        ),
                    )
                }),
                "issues" to JsonArray(issues),
            ),
        )
    }

    private fun issue(
        id: String,
        code: ConsistencyIssueCode,
        severity: ConsistencyIssueSeverity,
        processIds: List<String> = emptyList(),
    ) = JsonObject(
        linkedMapOf(
            "issueId" to JsonPrimitive(id),
            "code" to JsonPrimitive(code.name),
            "severity" to JsonPrimitive(severity.name),
            "startCodePointInclusive" to JsonPrimitive(10),
            "endCodePointExclusive" to JsonPrimitive(30),
            "relatedEntityIds" to JsonArray(listOf(JsonPrimitive("char.hero"))),
            "relatedForeshadowItemIds" to JsonArray(emptyList()),
            "relatedRequiredProcessNodeIds" to JsonArray(processIds.map(::JsonPrimitive)),
            "repairAction" to JsonPrimitive(ConsistencyIssuePolicyV1.requiredRepairAction(code).name),
        ),
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        val BODY = "这是一段仅用于检查契约边界的普通候选正文。".repeat(50)
    }
}
