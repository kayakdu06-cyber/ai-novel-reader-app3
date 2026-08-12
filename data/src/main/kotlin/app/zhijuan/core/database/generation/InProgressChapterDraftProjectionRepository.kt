package app.zhijuan.core.database.generation

import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.RequestAttemptStatus
import app.zhijuan.core.security.AndroidProtectedArtifactStore
import app.zhijuan.core.security.ProtectedArtifactType
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class InProgressChapterDraftProjection(
    val stageId: String,
    val chapterId: String,
    val text: String,
    val revision: Int,
    val formal: Boolean = false,
) {
    override fun toString(): String =
        "InProgressChapterDraftProjection(characters=${text.length}, revision=$revision, formal=$formal, content=redacted)"
}

/** Read-only protected-artifact projection; it never creates a formal ChapterVersion. */
class InProgressChapterDraftProjectionRepository(
    private val database: ZhijuanDatabase,
    private val artifactStore: AndroidProtectedArtifactStore,
) {
    suspend fun current(stageId: String): InProgressChapterDraftProjection? {
        val dao = database.generationDao()
        val stage = dao.findStage(stageId) ?: return null
        if (stage.phase != GenerationPhase.DRAFT_CHAPTER) return null
        val attempt = dao.attemptsForStage(stageId).lastOrNull() ?: return null
        if (attempt.status !in VISIBLE_ATTEMPT_STATUSES) return null
        val artifactRef = attempt.streamDraftRef ?: return null
        return artifactStore.readBytes(
            artifactRef, ProtectedArtifactType.STREAM_DRAFT, MAXIMUM_BYTES,
        ).use { lease ->
            val text = lease.withBytes { bytes ->
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            }
            InProgressChapterDraftProjection(stage.stageId, stage.targetId, text, lease.descriptor.revision)
        }
    }

    private companion object {
        const val MAXIMUM_BYTES = 4 * 1_024 * 1_024
        val VISIBLE_ATTEMPT_STATUSES = setOf(
            RequestAttemptStatus.STREAMING,
            RequestAttemptStatus.SUCCEEDED,
            RequestAttemptStatus.UNKNOWN_RESULT,
        )
    }
}
