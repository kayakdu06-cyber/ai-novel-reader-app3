package app.zhijuan.feature.generation

import app.zhijuan.core.model.AdultStatus
import app.zhijuan.core.model.FadePolicy
import app.zhijuan.core.model.StoryEntityType
import app.zhijuan.core.task.ChapterDeterministicConsistencyFactsV1
import app.zhijuan.core.task.ConsistencyEvidenceRange
import app.zhijuan.core.task.DeterministicEntityFactV1
import app.zhijuan.core.task.DeterministicEntityReferenceV1
import app.zhijuan.core.task.PromptInstruction
import app.zhijuan.core.task.SceneExecutionContract
import app.zhijuan.provider.common.ProviderModelId
import app.zhijuan.provider.common.ProviderTimeoutPolicy
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChapterFinalConsistencyMappingSnapshotTest {
    @Test
    fun captureRoundTripsDeterministicallyAcrossInputSetOrder() {
        val firstBound = ready(reverseEntities = false)
        val secondBound = ready(reverseEntities = true)

        val first = ChapterFinalConsistencyMappingSnapshotCodecV1.capture(firstBound, routingSpec(firstBound))
        val second = ChapterFinalConsistencyMappingSnapshotCodecV1.capture(secondBound, routingSpec(secondBound))
        val parsed = ChapterFinalConsistencyMappingSnapshotCodecV1.parseAndVerify(first)

        assertEquals(first, second)
        assertEquals(firstBound.expectation, parsed.expectation)
        assertEquals(firstBound.localReport, parsed.localReport)
        assertEquals(firstBound.sceneContract, parsed.sceneContract)
        assertEquals(0, parsed.totalRevisionAttemptsUsed)
    }

    @Test
    fun contentHashUsesCanonicalReencodingInsteadOfInputKeyOrder() {
        val bound = ready()
        val canonical = ChapterFinalConsistencyMappingSnapshotCodecV1.capture(bound, routingSpec(bound))
        val root = parseRoot(canonical)
        val reordered = JsonObject(root.entries.reversed().associateTo(linkedMapOf()) { it.toPair() }).toString()

        assertEquals(
            ChapterFinalConsistencyMappingSnapshotCodecV1.contentHash(canonical),
            ChapterFinalConsistencyMappingSnapshotCodecV1.contentHash(reordered),
        )
    }

    @Test
    fun parserRejectsUnknownNestedKeysWithoutEchoingValues() {
        val bound = ready()
        val canonical = ChapterFinalConsistencyMappingSnapshotCodecV1.capture(bound, routingSpec(bound))
        val root = parseRoot(canonical)
        val local = root.objectValue("localReport")
        val tampered = root.withObject(
            "localReport",
            JsonObject(local + ("secretPayload" to JsonPrimitive(SENSITIVE_MARKER))),
        )

        val failure = assertThrows(IllegalArgumentException::class.java) {
            ChapterFinalConsistencyMappingSnapshotCodecV1.parseAndVerify(tampered.toString())
        }

        assertFalse(requireNotNull(failure.message).contains(SENSITIVE_MARKER))
    }

    @Test
    fun parserRejectsStringNullAndStringNumbers() {
        val bound = ready()
        val canonical = ChapterFinalConsistencyMappingSnapshotCodecV1.capture(bound, routingSpec(bound))
        val root = parseRoot(canonical)
        val scene = root.objectValue("sceneContract")
        val stringNull = root.withObject(
            "sceneContract",
            JsonObject(scene + ("intimacyDetailLevel" to JsonPrimitive("null"))),
        )
        val stringNumber = JsonObject(root + ("minimumBodyCodePoints" to JsonPrimitive("100")))

        assertThrows(IllegalArgumentException::class.java) {
            ChapterFinalConsistencyMappingSnapshotCodecV1.parseAndVerify(stringNull.toString())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChapterFinalConsistencyMappingSnapshotCodecV1.parseAndVerify(stringNumber.toString())
        }
    }

    @Test
    fun parserRejectsUnsortedAndDuplicateIdentifierSets() {
        val bound = ready()
        val canonical = ChapterFinalConsistencyMappingSnapshotCodecV1.capture(bound, routingSpec(bound))
        val root = parseRoot(canonical)
        val expectation = root.objectValue("expectation")
        val entityIds = expectation.getValue("knownEntityIds") as JsonArray
        assertEquals(2, entityIds.size)
        val unsorted = root.withObject(
            "expectation",
            JsonObject(expectation + ("knownEntityIds" to JsonArray(entityIds.reversed()))),
        )
        val duplicate = root.withObject(
            "expectation",
            JsonObject(expectation + ("knownEntityIds" to JsonArray(listOf(entityIds.first(), entityIds.first())))),
        )

        assertThrows(IllegalArgumentException::class.java) {
            ChapterFinalConsistencyMappingSnapshotCodecV1.parseAndVerify(unsorted.toString())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChapterFinalConsistencyMappingSnapshotCodecV1.parseAndVerify(duplicate.toString())
        }
    }

    @Test
    fun parserRejectsCrossObjectHashTampering() {
        val bound = ready()
        val canonical = ChapterFinalConsistencyMappingSnapshotCodecV1.capture(bound, routingSpec(bound))
        val root = parseRoot(canonical)
        val expectation = root.objectValue("expectation")
        val tampered = root.withObject(
            "expectation",
            JsonObject(expectation + ("sceneContractHash" to JsonPrimitive(sha256("different-contract")))),
        )

        assertThrows(IllegalArgumentException::class.java) {
            ChapterFinalConsistencyMappingSnapshotCodecV1.parseAndVerify(tampered.toString())
        }
    }

    @Test
    fun captureRejectsCandidateMismatchAndAllDiagnosticsStayRedacted() {
        val bound = ready()
        val mismatched = routingSpec(bound).copy(
            candidate = routingSpec(bound).candidate.copy(chapterVersionId = "version.other"),
        )

        val failure = assertThrows(IllegalArgumentException::class.java) {
            ChapterFinalConsistencyMappingSnapshotCodecV1.capture(bound, mismatched)
        }
        val valid = ChapterFinalConsistencyMappingSnapshotCodecV1.parseAndVerify(
            ChapterFinalConsistencyMappingSnapshotCodecV1.capture(bound, routingSpec(bound)),
        )

        assertFalse(requireNotNull(failure.message).contains("version.candidate.1"))
        assertFalse(valid.toString().contains("version.candidate.1"))
        assertFalse(valid.toString().contains(bound.sourceBindingHash))
        assertFalse(valid.toString().contains(BODY.take(20)))
        assertTrue(valid.toString().contains("content=redacted"))
    }

    private fun ready(reverseEntities: Boolean = false): BoundChapterConsistencyCheckRequest {
        val knownEntities = listOf(
            ChapterConsistencyKnownEntityV1(
                entityId = "char.hero",
                canonicalName = "主角",
                entityType = StoryEntityType.CHARACTER,
                adultStatus = AdultStatus.CONFIRMED_ADULT,
                ageYears = 24,
                realIdentifiablePerson = false,
            ),
            ChapterConsistencyKnownEntityV1(
                entityId = "char.partner",
                canonicalName = "同伴",
                entityType = StoryEntityType.CHARACTER,
                adultStatus = AdultStatus.CONFIRMED_ADULT,
                ageYears = 25,
                realIdentifiablePerson = false,
            ),
        ).let { if (reverseEntities) it.reversed() else it }
        val requestSpec = ChapterConsistencyCheckRequestSpec(
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
                        entityId = "char.hero",
                        entityType = StoryEntityType.CHARACTER,
                        adultStatus = AdultStatus.CONFIRMED_ADULT,
                        ageYears = 24,
                    ),
                    DeterministicEntityFactV1(
                        entityId = "char.partner",
                        entityType = StoryEntityType.CHARACTER,
                        adultStatus = AdultStatus.CONFIRMED_ADULT,
                        ageYears = 25,
                    ),
                ),
                references = listOf(
                    DeterministicEntityReferenceV1(
                        entityId = "char.hero",
                        adultRelevant = true,
                        evidenceRange = ConsistencyEvidenceRange(0, 4),
                    ),
                    DeterministicEntityReferenceV1(
                        entityId = "char.partner",
                        adultRelevant = true,
                        evidenceRange = ConsistencyEvidenceRange(4, 8),
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
                intimacyDetailLevel = 4,
                fadePolicy = FadePolicy.AVOID,
                strictBodyAndSensoryContinuity = true,
                requiredKeyProcessCoveragePercent = 100,
                fadeSubstitutionAllowed = false,
                requiresStateContinuity = true,
                requiresRelevantAftermath = true,
                instructions = listOf(PromptInstruction("scene.fixture", "fixture")),
            ),
            sceneParticipantEntityIds = setOf("char.hero", "char.partner"),
            requiredProcessNodeIds = linkedSetOf("process.2", "process.1"),
            knownEntities = knownEntities,
            evidenceItems = listOf(
                ChapterConsistencyEvidenceItemV1(
                    evidenceId = "foreshadow.1",
                    kind = ChapterConsistencyEvidenceKindV1.FORESHADOW_STATE,
                    payloadJson = "{\"status\":\"PLANTED\"}",
                ),
            ),
            maximumOutputTokens = 2_048,
            timeouts = ProviderTimeoutPolicy(1_000, 1_000, 1_000, 2_000),
        )
        return (ChapterConsistencyCheckRequestFactoryV1.prepare(requestSpec) as ChapterConsistencyRequestPreparationV1.Ready)
            .boundRequest
    }

    private fun routingSpec(bound: BoundChapterConsistencyCheckRequest) =
        ChapterCandidateConsistencyRoutingSpecV1(
            candidate = ChapterCandidatePipelineIdentityV1(
                chapterVersionId = bound.expectation.sourceChapterVersionId,
                chapterId = bound.expectation.chapterId,
                chapterIndex = bound.expectation.chapterIndex,
                contentHash = bound.expectation.sourceChapterContentHash,
                revisionIndex = 0,
                routeBindingHash = null,
            ),
            candidateContent = BODY,
            candidateContentHashHistory = listOf(bound.expectation.sourceChapterContentHash),
            minimumBodyCodePoints = 100,
            totalRevisionAttemptsUsed = 0,
            revisionStageMaximumAttempts = 2,
            nextStageId = "stage.commit.1",
            expectedCurrentVersionId = null,
            revisionRequest = null,
            routedAt = 200L,
        )

    private fun parseRoot(value: String): JsonObject = Json.parseToJsonElement(value) as JsonObject

    private fun JsonObject.objectValue(key: String): JsonObject = getValue(key) as JsonObject

    private fun JsonObject.withObject(key: String, value: JsonObject): JsonObject = JsonObject(this + (key to value))

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        const val SENSITIVE_MARKER = "must-not-leak"
        val BODY = "这是一段仅用于快照契约检查的普通候选正文。".repeat(50)
    }
}
