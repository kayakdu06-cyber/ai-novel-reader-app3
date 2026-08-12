package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.ChapterCandidateArtifactRoleV1
import app.zhijuan.core.database.generation.ChapterFinalCandidateArtifactEvidenceV1
import app.zhijuan.core.security.AndroidProtectedArtifactStore
import app.zhijuan.core.security.ProtectedArtifactDescriptor
import app.zhijuan.core.security.ProtectedArtifactLease
import app.zhijuan.core.security.ProtectedArtifactType
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Reads one persisted final-candidate artifact as plaintext bytes.
 *
 * Implementations own the returned lease and the underlying byte array; the
 * coordinator never clears arrays provided by the reader.
 */
interface ChapterFinalCandidateArtifactBytesReader {
    fun read(artifactRefId: String, maximumBytes: Int): ChapterFinalCandidateArtifactReadLease
}

/** A lease over one decrypted artifact. Closing it releases and clears the bytes. */
interface ChapterFinalCandidateArtifactReadLease : AutoCloseable {
    val descriptor: ProtectedArtifactDescriptor

    fun <T> withBytes(block: (ByteArray) -> T): T
}

/** Recovered final-candidate models. Holds no artifact byte arrays. */
data class ChapterFinalCandidateArtifactRecoveryResultV1(
    val candidateContent: String,
    val memory: ChapterMemoryV1,
    val tracking: ChapterStoryTrackingV1,
    val consistency: ChapterConsistencyReportV1,
) {
    override fun toString(): String =
        "ChapterFinalCandidateArtifactRecoveryResultV1(" +
            "memoryChapterIndex=${memory.chapterIndex}, " +
            "trackingChapterIndex=${tracking.chapterIndex}, " +
            "consistencyChapterIndex=${consistency.chapterIndex}, content=redacted)"
}

/**
 * Rebuilds a final candidate from the four persisted artifacts after a process
 * restart. Reads BODY -> MEMORY -> TRACKING -> CONSISTENCY in a fixed order,
 * re-verifies identity, revision, raw hash and canonical hash, and only keeps
 * the body string plus the strictly parsed structured models.
 */
class ChapterFinalCandidateArtifactRecoveryCoordinator(
    private val reader: ChapterFinalCandidateArtifactBytesReader,
) {
    fun recover(
        evidence: List<ChapterFinalCandidateArtifactEvidenceV1>,
    ): ChapterFinalCandidateArtifactRecoveryResultV1 {
        val byRole = evidence.groupBy { it.role }
        val roles = byRole.keys
        require(roles in setOf(LEGACY_ROLES, MERGED_ROLES) && roles.all { byRole[it]?.size == 1 }) {
            "Final candidate evidence must contain one supported artifact chain."
        }
        val body = readBody(byRole.getValue(ChapterCandidateArtifactRoleV1.BODY).single())
        if (roles == MERGED_ROLES) {
            val post = readStructured(
                byRole.getValue(ChapterCandidateArtifactRoleV1.POST_ANALYSIS).single(),
                { bytes -> ChapterPostAnalysisOutputParser().parse(bytes) },
                { it.contentHash },
            )
            return ChapterFinalCandidateArtifactRecoveryResultV1(
                candidateContent = body,
                memory = post.asMemory(),
                tracking = post.asTracking(),
                consistency = post.asConsistency(),
            )
        }
        val memory = readStructured(
            byRole.getValue(ChapterCandidateArtifactRoleV1.MEMORY).single(),
            { bytes -> ChapterMemoryOutputParser().parse(bytes) },
            { it.contentHash },
        )
        val tracking = readStructured(
            byRole.getValue(ChapterCandidateArtifactRoleV1.TRACKING).single(),
            { bytes -> ChapterTrackingOutputParser().parse(bytes) },
            { it.contentHash },
        )
        val consistency = readStructured(
            byRole.getValue(ChapterCandidateArtifactRoleV1.CONSISTENCY).single(),
            { bytes -> ChapterConsistencyOutputParser().parse(bytes) },
            { it.contentHash },
        )
        return ChapterFinalCandidateArtifactRecoveryResultV1(body, memory, tracking, consistency)
    }

    private fun readBody(evidence: ChapterFinalCandidateArtifactEvidenceV1): String =
        withLease(evidence, MAX_CANDIDATE_BODY_BYTES) { bytes ->
            require(sha256(bytes) == evidence.rawOutputHash) {
                "Persisted candidate body has been modified."
            }
            val content = decodeUtf8Strict(bytes)
                ?: throw IllegalArgumentException("Persisted candidate body is not valid UTF-8 text.")
            require(content.isNotBlank()) { "Persisted candidate body is empty." }
            require(evidence.canonicalOutputHash == evidence.rawOutputHash) {
                "Persisted candidate body canonical hash does not match the final candidate evidence."
            }
            content
        }

    private fun <T : Any> readStructured(
        evidence: ChapterFinalCandidateArtifactEvidenceV1,
        parse: (ByteArray) -> PlanningOutputValidationResult<T>,
        contentHashOf: (T) -> String,
    ): T = withLease(evidence, MAX_CANDIDATE_STRUCTURED_BYTES) { bytes ->
        require(sha256(bytes) == evidence.rawOutputHash) {
            "Persisted structured artifact has been modified."
        }
        val value = when (val result = parse(bytes)) {
            is PlanningOutputValidationResult.Valid -> result.value
            is PlanningOutputValidationResult.Invalid ->
                throw IllegalArgumentException("Persisted structured artifact schema is invalid.")
        }
        require(contentHashOf(value) == evidence.canonicalOutputHash) {
            "Persisted structured artifact canonical hash does not match the final candidate evidence."
        }
        value
    }

    private fun <T> withLease(
        evidence: ChapterFinalCandidateArtifactEvidenceV1,
        maximumBytes: Int,
        block: (ByteArray) -> T,
    ): T {
        reader.read(evidence.artifactRefId, maximumBytes).use { lease ->
            require(lease.descriptor.artifactRefId == evidence.artifactRefId) {
                "Persisted artifact descriptor does not match the final candidate evidence."
            }
            require(lease.descriptor.type == ProtectedArtifactType.STREAM_DRAFT) {
                "Persisted artifact descriptor does not match the final candidate evidence."
            }
            require(lease.descriptor.revision == evidence.artifactRevision) {
                "Persisted artifact revision does not match the final candidate evidence."
            }
            return lease.withBytes(block)
        }
    }

    companion object {
        private const val MAX_CANDIDATE_BODY_BYTES = 4 * 1_024 * 1_024
        private const val MAX_CANDIDATE_STRUCTURED_BYTES = 512 * 1_024

        /** Production reader backed by the protected artifact store. */
        fun forProtectedStore(
            store: AndroidProtectedArtifactStore,
        ): ChapterFinalCandidateArtifactRecoveryCoordinator =
            ChapterFinalCandidateArtifactRecoveryCoordinator(StoreBackedReader(store))
    }

    private fun ChapterPostAnalysisV1.asMemory() = ChapterMemoryV1(
        sourceChapterVersionId, sourceChapterContentHash, chapterId, chapterIndex,
        summary, entityEvents, canonFacts, canonicalJson, contentHash,
    )

    private fun ChapterPostAnalysisV1.asTracking() = ChapterStoryTrackingV1(
        sourceChapterVersionId, sourceChapterContentHash, chapterId, chapterIndex,
        memorySnapshotHash, priorForeshadowSnapshotHash, knownEntitySnapshotHash,
        timelineEvents, foreshadowTransitions, canonicalJson, contentHash,
    )

    private fun ChapterPostAnalysisV1.asConsistency() = ChapterConsistencyReportV1(
        sourceChapterVersionId, sourceChapterContentHash, chapterId, chapterIndex,
        checkSourceSnapshotHash, sceneContractHash, criterionResults, requiredProcessResults,
        consistencyFindings, canonicalJson, contentHash,
    )
}

private val LEGACY_ROLES = setOf(
    ChapterCandidateArtifactRoleV1.BODY,
    ChapterCandidateArtifactRoleV1.MEMORY,
    ChapterCandidateArtifactRoleV1.TRACKING,
    ChapterCandidateArtifactRoleV1.CONSISTENCY,
)
private val MERGED_ROLES = setOf(
    ChapterCandidateArtifactRoleV1.BODY,
    ChapterCandidateArtifactRoleV1.POST_ANALYSIS,
)

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun decodeUtf8Strict(bytes: ByteArray): String? = try {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (_: Exception) {
    null
}

private class StoreBackedReader(
    private val store: AndroidProtectedArtifactStore,
) : ChapterFinalCandidateArtifactBytesReader {
    override fun read(artifactRefId: String, maximumBytes: Int): ChapterFinalCandidateArtifactReadLease =
        StoreBackedLease(
            store.readBytes(artifactRefId, ProtectedArtifactType.STREAM_DRAFT, maximumBytes),
        )

    private class StoreBackedLease(
        private val delegate: ProtectedArtifactLease,
    ) : ChapterFinalCandidateArtifactReadLease {
        override val descriptor: ProtectedArtifactDescriptor
            get() = delegate.descriptor

        override fun <T> withBytes(block: (ByteArray) -> T): T = delegate.withBytes(block)

        override fun close() {
            delegate.close()
        }
    }
}
