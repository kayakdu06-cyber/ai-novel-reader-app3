package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.ChapterCandidateArtifactRoleV1
import app.zhijuan.core.database.generation.ChapterFinalCandidateArtifactEvidenceV1
import app.zhijuan.core.model.ConsistencyCriterionV1
import app.zhijuan.core.security.ProtectedArtifactDescriptor
import app.zhijuan.core.security.ProtectedArtifactType
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChapterFinalCandidateArtifactRecoveryCoordinatorTest {

    private val body: String = "Chapter one. $CANARY\n\nThe hero returned home, wrist still bruised."

    @Test
    fun recoversAllRolesInFixedOrderRegardlessOfEvidenceOrder() {
        val fake = FakeReader()
        val coordinator = ChapterFinalCandidateArtifactRecoveryCoordinator(fake)
        val bodyBytes = body.encodeToByteArray()
        val memoryBytes = memoryDocument().toString().encodeToByteArray()
        val trackingBytes = trackingDocument().toString().encodeToByteArray()
        val consistencyBytes = consistencyDocument().toString().encodeToByteArray()
        fake.install("artifact.body", bodyBytes)
        fake.install("artifact.memory", memoryBytes)
        fake.install("artifact.tracking", trackingBytes)
        fake.install("artifact.consistency", consistencyBytes)

        val result = coordinator.recover(
            listOf(
                evidence(ChapterCandidateArtifactRoleV1.TRACKING, trackingBytes),
                evidence(ChapterCandidateArtifactRoleV1.BODY, bodyBytes),
                evidence(ChapterCandidateArtifactRoleV1.CONSISTENCY, consistencyBytes),
                evidence(ChapterCandidateArtifactRoleV1.MEMORY, memoryBytes),
            ),
        )

        assertEquals(body, result.candidateContent)
        assertTrue(result.candidateContent.contains(CANARY))
        assertEquals(1, result.memory.chapterIndex)
        assertEquals(2, result.tracking.chapterIndex)
        assertEquals(2, result.consistency.chapterIndex)
        assertEquals(
            listOf(
                "artifact.body" to MAX_BODY_BYTES,
                "artifact.memory" to MAX_STRUCTURED_BYTES,
                "artifact.tracking" to MAX_STRUCTURED_BYTES,
                "artifact.consistency" to MAX_STRUCTURED_BYTES,
            ),
            fake.readOrder,
        )
        assertEquals(
            listOf("artifact.body", "artifact.memory", "artifact.tracking", "artifact.consistency"),
            fake.closedRefIds,
        )
        assertTrue(result.toString().contains("content=redacted"))
        assertFalse(result.toString().contains(CANARY))
    }

    @Test
    fun duplicateOrMissingRoleFailsBeforeAnyRead() {
        val fake = FakeReader()
        val coordinator = ChapterFinalCandidateArtifactRecoveryCoordinator(fake)
        val bodyBytes = body.encodeToByteArray()
        val memoryBytes = memoryDocument().toString().encodeToByteArray()
        val consistencyBytes = consistencyDocument().toString().encodeToByteArray()
        fake.install("artifact.body", bodyBytes)
        fake.install("artifact.memory", memoryBytes)
        fake.install("artifact.consistency", consistencyBytes)

        val exception = assertThrows(IllegalArgumentException::class.java) {
            coordinator.recover(
                listOf(
                    evidence(ChapterCandidateArtifactRoleV1.BODY, bodyBytes),
                    evidence(ChapterCandidateArtifactRoleV1.MEMORY, memoryBytes),
                    evidence(ChapterCandidateArtifactRoleV1.MEMORY, memoryBytes),
                    evidence(ChapterCandidateArtifactRoleV1.CONSISTENCY, consistencyBytes),
                ),
            )
        }

        assertEquals("Final candidate evidence must contain exactly one artifact per role.", exception.message)
        assertTrue(fake.readOrder.isEmpty())
    }

    @Test
    fun descriptorArtifactRefIdTypeOrRevisionMismatchFails() {
        val fake = FakeReader()
        val coordinator = ChapterFinalCandidateArtifactRecoveryCoordinator(fake)
        val bodyBytes = body.encodeToByteArray()
        val memoryBytes = memoryDocument().toString().encodeToByteArray()
        val trackingBytes = trackingDocument().toString().encodeToByteArray()
        val consistencyBytes = consistencyDocument().toString().encodeToByteArray()
        fake.install("artifact.body", bodyBytes)
        fake.install("artifact.memory", memoryBytes, descriptorRefId = "artifact.memory.other")
        fake.install("artifact.tracking", trackingBytes)
        fake.install("artifact.consistency", consistencyBytes)

        val refIdException = assertThrows(IllegalArgumentException::class.java) {
            coordinator.recover(fullEvidence(bodyBytes, memoryBytes, trackingBytes, consistencyBytes))
        }
        assertEquals(
            "Persisted artifact descriptor does not match the final candidate evidence.",
            refIdException.message,
        )
        assertFalse(refIdException.toString().contains(CANARY))

        fake.install("artifact.memory", memoryBytes)
        fake.install("artifact.tracking", trackingBytes, revision = 2)
        val revisionException = assertThrows(IllegalArgumentException::class.java) {
            coordinator.recover(fullEvidence(bodyBytes, memoryBytes, trackingBytes, consistencyBytes))
        }
        assertEquals(
            "Persisted artifact revision does not match the final candidate evidence.",
            revisionException.message,
        )

        fake.install("artifact.tracking", trackingBytes)
        fake.install("artifact.consistency", consistencyBytes, type = ProtectedArtifactType.DIAGNOSTIC_LOG)
        val typeException = assertThrows(IllegalArgumentException::class.java) {
            coordinator.recover(fullEvidence(bodyBytes, memoryBytes, trackingBytes, consistencyBytes))
        }
        assertEquals(
            "Persisted artifact descriptor does not match the final candidate evidence.",
            typeException.message,
        )
    }

    @Test
    fun replacedPayloadFailsRawHashGate() {
        val fake = FakeReader()
        val coordinator = ChapterFinalCandidateArtifactRecoveryCoordinator(fake)
        val bodyBytes = body.encodeToByteArray()
        val memoryBytes = memoryDocument().toString().encodeToByteArray()
        val trackingBytes = trackingDocument().toString().encodeToByteArray()
        val consistencyBytes = consistencyDocument().toString().encodeToByteArray()
        fake.install("artifact.body", (body + " tampered").encodeToByteArray())
        fake.install("artifact.memory", memoryBytes)
        fake.install("artifact.tracking", trackingBytes)
        fake.install("artifact.consistency", consistencyBytes)

        val bodyException = assertThrows(IllegalArgumentException::class.java) {
            coordinator.recover(fullEvidence(bodyBytes, memoryBytes, trackingBytes, consistencyBytes))
        }
        assertEquals("Persisted candidate body has been modified.", bodyException.message)
        assertFalse(bodyException.toString().contains(CANARY))
        assertFalse(bodyException.toString().contains("tampered"))

        fake.install("artifact.body", bodyBytes)
        fake.install("artifact.memory", memoryBytes + "x".encodeToByteArray())
        val structuredException = assertThrows(IllegalArgumentException::class.java) {
            coordinator.recover(fullEvidence(bodyBytes, memoryBytes, trackingBytes, consistencyBytes))
        }
        assertEquals("Persisted structured artifact has been modified.", structuredException.message)
    }

    @Test
    fun invalidSchemaOrCanonicalHashMismatchFailsWithoutLeaking() {
        val fake = FakeReader()
        val coordinator = ChapterFinalCandidateArtifactRecoveryCoordinator(fake)
        val bodyBytes = body.encodeToByteArray()
        val memoryBytes = memoryDocument().toString().encodeToByteArray()
        val trackingBytes = trackingDocument().toString().encodeToByteArray()
        val consistencyBytes = consistencyDocument().toString().encodeToByteArray()
        val invalidMemoryBytes = JsonObject(
            memoryDocument() + ("extra" to JsonPrimitive(true)),
        ).toString().encodeToByteArray()
        fake.install("artifact.body", bodyBytes)
        fake.install("artifact.memory", invalidMemoryBytes)
        fake.install("artifact.tracking", trackingBytes)
        fake.install("artifact.consistency", consistencyBytes)

        val schemaException = assertThrows(IllegalArgumentException::class.java) {
            coordinator.recover(
                listOf(
                    evidence(ChapterCandidateArtifactRoleV1.BODY, bodyBytes),
                    evidence(ChapterCandidateArtifactRoleV1.MEMORY, invalidMemoryBytes),
                    evidence(ChapterCandidateArtifactRoleV1.TRACKING, trackingBytes),
                    evidence(ChapterCandidateArtifactRoleV1.CONSISTENCY, consistencyBytes),
                ),
            )
        }
        assertEquals("Persisted structured artifact schema is invalid.", schemaException.message)
        assertFalse(schemaException.toString().contains(CANARY))
        assertFalse(schemaException.toString().contains("objective"))

        fake.install("artifact.memory", memoryBytes)
        val canonicalException = assertThrows(IllegalArgumentException::class.java) {
            coordinator.recover(
                fullEvidence(
                    bodyBytes,
                    memoryBytes,
                    trackingBytes,
                    consistencyBytes,
                    memoryCanonicalHash = "d".repeat(64),
                ),
            )
        }
        assertEquals(
            "Persisted structured artifact canonical hash does not match the final candidate evidence.",
            canonicalException.message,
        )
        assertFalse(canonicalException.toString().contains(CANARY))
    }

    private fun evidence(
        role: ChapterCandidateArtifactRoleV1,
        bytes: ByteArray,
        artifactRefId: String = "artifact.${role.name.lowercase()}",
        artifactRevision: Int = 1,
        rawOutputHash: String = sha256(bytes),
        canonicalOutputHash: String = sha256(bytes),
    ): ChapterFinalCandidateArtifactEvidenceV1 = ChapterFinalCandidateArtifactEvidenceV1(
        role = role,
        stageId = "stage.${role.name.lowercase()}",
        attemptId = "attempt.${role.name.lowercase()}",
        artifactRefId = artifactRefId,
        artifactRevision = artifactRevision,
        rawOutputHash = rawOutputHash,
        canonicalOutputHash = canonicalOutputHash,
        sourceBindingHash = "f".repeat(64),
    )

    private fun fullEvidence(
        bodyBytes: ByteArray,
        memoryBytes: ByteArray,
        trackingBytes: ByteArray,
        consistencyBytes: ByteArray,
        memoryCanonicalHash: String = sha256(memoryBytes),
    ): List<ChapterFinalCandidateArtifactEvidenceV1> = listOf(
        evidence(ChapterCandidateArtifactRoleV1.BODY, bodyBytes),
        evidence(ChapterCandidateArtifactRoleV1.MEMORY, memoryBytes, canonicalOutputHash = memoryCanonicalHash),
        evidence(ChapterCandidateArtifactRoleV1.TRACKING, trackingBytes),
        evidence(ChapterCandidateArtifactRoleV1.CONSISTENCY, consistencyBytes),
    )

    private fun memoryDocument(): JsonObject = JsonObject(
        linkedMapOf(
            "schemaVersion" to JsonPrimitive(1),
            "sourceChapterVersionId" to JsonPrimitive("memory.$CANARY"),
            "sourceChapterContentHash" to JsonPrimitive("a".repeat(64)),
            "chapterId" to JsonPrimitive("chapter.1"),
            "chapterIndex" to JsonPrimitive(1),
            "summary" to JsonObject(
                linkedMapOf(
                    "objectiveOutcome" to JsonPrimitive("objective-$CANARY"),
                    "keyEvents" to JsonArray(listOf(JsonPrimitive("obtained the sealed record"))),
                    "decisions" to JsonArray(listOf(JsonPrimitive("continue investigating"))),
                    "relationshipChanges" to JsonArray(emptyList()),
                    "endingState" to JsonPrimitive("hero returns home"),
                    "unresolvedQuestions" to JsonArray(listOf(JsonPrimitive("who edited the record"))),
                    "importance" to JsonPrimitive(80),
                ),
            ),
            "entityEvents" to JsonArray(
                listOf(
                    JsonObject(
                        linkedMapOf(
                            "entityId" to JsonPrimitive("char.lin"),
                            "attribute" to JsonPrimitive("PHYSICAL_STATE"),
                            "relatedEntityId" to JsonNull,
                            "oldValue" to JsonNull,
                            "newValue" to JsonPrimitive("wrist bruised"),
                            "storyTimeExpression" to JsonPrimitive("evening"),
                            "confidenceMicros" to JsonPrimitive(980000),
                            "canonLevel" to JsonPrimitive("STORY_CANON"),
                            "evidence" to JsonPrimitive("chapter ending states so"),
                        ),
                    ),
                ),
            ),
            "facts" to JsonArray(
                listOf(
                    JsonObject(
                        linkedMapOf(
                            "factKind" to JsonPrimitive("DISCOVERY"),
                            "entityId" to JsonPrimitive("char.lin"),
                            "text" to JsonPrimitive("Lin discovered the record was edited"),
                            "canonLevel" to JsonPrimitive("STORY_CANON"),
                            "confidenceMicros" to JsonPrimitive(990000),
                            "conflictGroupId" to JsonNull,
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun trackingDocument(): JsonObject = JsonObject(
        linkedMapOf(
            "schemaVersion" to JsonPrimitive(1),
            "sourceChapterVersionId" to JsonPrimitive("tracking.$CANARY"),
            "sourceChapterContentHash" to JsonPrimitive("a".repeat(64)),
            "chapterId" to JsonPrimitive("chapter.one"),
            "chapterIndex" to JsonPrimitive(2),
            "memorySnapshotHash" to JsonPrimitive("b".repeat(64)),
            "priorForeshadowSnapshotHash" to JsonPrimitive("c".repeat(64)),
            "knownEntitySnapshotHash" to JsonPrimitive("e".repeat(64)),
            "timelineEvents" to JsonArray(
                listOf(
                    JsonObject(
                        linkedMapOf(
                            "name" to JsonPrimitive("hero enters the old hall"),
                            "participantEntityIds" to JsonArray(listOf(JsonPrimitive("char.hero"))),
                            "locationEntityId" to JsonPrimitive("loc.hall"),
                            "storyTimeExpression" to JsonPrimitive("evening"),
                            "constraints" to JsonArray(listOf(JsonPrimitive("the hall is sealed"))),
                            "evidence" to JsonPrimitive("hero walks in"),
                        ),
                    ),
                ),
            ),
            "foreshadowOperations" to JsonArray(
                listOf(
                    JsonObject(
                        linkedMapOf(
                            "operation" to JsonPrimitive("DEVELOP"),
                            "foreshadowItemId" to JsonPrimitive("clue.old"),
                            "description" to JsonPrimitive("silver bell behind the door"),
                            "targetStartChapterIndex" to JsonNull,
                            "targetEndChapterIndex" to JsonNull,
                            "visibleEntityIds" to JsonArray(listOf(JsonPrimitive("char.hero"))),
                            "importance" to JsonPrimitive(80),
                            "fromStatus" to JsonPrimitive("PLANTED"),
                            "confidenceMicros" to JsonPrimitive(900000),
                            "evidence" to JsonPrimitive("bell rings again"),
                        ),
                    ),
                    JsonObject(
                        linkedMapOf(
                            "operation" to JsonPrimitive("PLANT"),
                            "foreshadowItemId" to JsonNull,
                            "description" to JsonPrimitive("double wax seal on the ledger"),
                            "targetStartChapterIndex" to JsonPrimitive(3),
                            "targetEndChapterIndex" to JsonPrimitive(5),
                            "visibleEntityIds" to JsonArray(listOf(JsonPrimitive("char.hero"))),
                            "importance" to JsonPrimitive(70),
                            "fromStatus" to JsonNull,
                            "confidenceMicros" to JsonPrimitive(850000),
                            "evidence" to JsonPrimitive("seal observed"),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun consistencyDocument(): JsonObject = JsonObject(
        linkedMapOf(
            "schemaVersion" to JsonPrimitive(1),
            "sourceChapterVersionId" to JsonPrimitive("consistency.$CANARY"),
            "sourceChapterContentHash" to JsonPrimitive("a".repeat(64)),
            "chapterId" to JsonPrimitive("chapter.one"),
            "chapterIndex" to JsonPrimitive(2),
            "checkSourceSnapshotHash" to JsonPrimitive("b".repeat(64)),
            "sceneContractHash" to JsonPrimitive("c".repeat(64)),
            "criterionResults" to JsonArray(
                ConsistencyCriterionV1.entries.map { criterion ->
                    JsonObject(
                        linkedMapOf(
                            "criterion" to JsonPrimitive(criterion.name),
                            "status" to JsonPrimitive("PASS"),
                            "issueIds" to JsonArray(emptyList()),
                        ),
                    )
                },
            ),
            "requiredProcessResults" to JsonArray(emptyList()),
            "issues" to JsonArray(emptyList()),
        ),
    )

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        const val CANARY = "canary-recovery-secret-1"
        const val MAX_BODY_BYTES = 4 * 1_024 * 1_024
        const val MAX_STRUCTURED_BYTES = 512 * 1_024
    }
}

private class FakeReader : ChapterFinalCandidateArtifactBytesReader {
    val readOrder = mutableListOf<Pair<String, Int>>()
    val closedRefIds = mutableListOf<String>()

    private val payloads = mutableMapOf<String, ByteArray>()
    private val descriptors = mutableMapOf<String, ProtectedArtifactDescriptor>()

    fun install(
        artifactRefId: String,
        bytes: ByteArray,
        revision: Int = 1,
        descriptorRefId: String = artifactRefId,
        type: ProtectedArtifactType = ProtectedArtifactType.STREAM_DRAFT,
    ) {
        payloads[artifactRefId] = bytes.copyOf()
        descriptors[artifactRefId] = ProtectedArtifactDescriptor(
            artifactRefId = descriptorRefId,
            type = type,
            revision = revision,
            keyVersion = 1,
            createdAt = 1L,
            updatedAt = 2L,
        )
    }

    override fun read(artifactRefId: String, maximumBytes: Int): ChapterFinalCandidateArtifactReadLease {
        readOrder.add(artifactRefId to maximumBytes)
        val payload = requireNotNull(payloads[artifactRefId]) { "Fake reader has no payload for $artifactRefId" }
        val descriptor = requireNotNull(descriptors[artifactRefId]) { "Fake reader has no descriptor for $artifactRefId" }
        return FakeLease(descriptor, payload.copyOf()) { closedRefIds.add(it) }
    }
}

private class FakeLease(
    override val descriptor: ProtectedArtifactDescriptor,
    private val bytes: ByteArray,
    private val onClose: (String) -> Unit,
) : ChapterFinalCandidateArtifactReadLease {
    override fun <T> withBytes(block: (ByteArray) -> T): T = block(bytes)

    override fun close() {
        onClose(descriptor.artifactRefId)
        bytes.fill(0)
    }
}
