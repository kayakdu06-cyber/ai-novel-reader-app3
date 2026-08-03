package app.zhijuan.reader.generation

import app.zhijuan.core.database.generation.GenerationControlResult
import app.zhijuan.core.model.GenerationJobStatus

internal enum class ForegroundGenerationCommand {
    START,
    PAUSE,
    STOP,
    RECHECK,
    SYSTEM_TIMEOUT,
}

internal enum class ForegroundGenerationDirective {
    KEEP_RUNNING,
    STOP_SERVICE,
}

internal data class ForegroundGenerationSnapshot(
    val status: GenerationJobStatus,
    val updatedAt: Long,
)

internal data class ForegroundGenerationCommandResult(
    val directive: ForegroundGenerationDirective,
    val status: GenerationJobStatus?,
)

internal interface ForegroundGenerationControlPort {
    suspend fun findJob(jobId: String): ForegroundGenerationSnapshot?

    suspend fun requestUserPause(jobId: String, requestedAt: Long): GenerationControlResult

    suspend fun requestStop(jobId: String, requestedAt: Long): GenerationControlResult

    suspend fun requestSystemTimeoutPause(jobId: String, requestedAt: Long): GenerationControlResult
}

internal class ForegroundGenerationCommandProcessor(
    private val controls: ForegroundGenerationControlPort,
) {
    suspend fun handle(
        jobId: String,
        command: ForegroundGenerationCommand,
        requestedAt: Long,
    ): ForegroundGenerationCommandResult {
        require(JOB_ID.matches(jobId)) { "Generation job id is invalid." }
        require(requestedAt >= 0L) { "Generation command time is invalid." }
        val status = when (command) {
            ForegroundGenerationCommand.START,
            ForegroundGenerationCommand.RECHECK,
            -> controls.findJob(jobId)?.status
            ForegroundGenerationCommand.PAUSE ->
                controls.requestUserPause(jobId, requestedAt).jobStatus
            ForegroundGenerationCommand.STOP ->
                controls.requestStop(jobId, requestedAt).jobStatus
            ForegroundGenerationCommand.SYSTEM_TIMEOUT ->
                controls.requestSystemTimeoutPause(jobId, requestedAt).jobStatus
        }
        return ForegroundGenerationCommandResult(
            directive = if (command == ForegroundGenerationCommand.SYSTEM_TIMEOUT) {
                ForegroundGenerationDirective.STOP_SERVICE
            } else if (status in ACTIVE_JOB_STATUSES) {
                ForegroundGenerationDirective.KEEP_RUNNING
            } else {
                ForegroundGenerationDirective.STOP_SERVICE
            },
            status = status,
        )
    }

    private companion object {
        val JOB_ID = Regex("[A-Za-z0-9._:-]{1,128}")
        val ACTIVE_JOB_STATUSES = setOf(
            GenerationJobStatus.READY,
            GenerationJobStatus.RUNNING,
            GenerationJobStatus.PAUSING,
            GenerationJobStatus.STOPPING,
        )
    }
}
