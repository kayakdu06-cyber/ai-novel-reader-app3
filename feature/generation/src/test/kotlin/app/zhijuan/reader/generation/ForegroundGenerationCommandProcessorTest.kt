package app.zhijuan.reader.generation

import app.zhijuan.core.database.generation.GenerationControlDisposition
import app.zhijuan.core.database.generation.GenerationControlResult
import app.zhijuan.core.database.generation.GenerationExecutionControl
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationStageStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ForegroundGenerationCommandProcessorTest {
    @Test
    fun startKeepsOnlyRunnableJobsInTheForeground() = runBlocking {
        val controls = FakeControls(GenerationJobStatus.READY)
        val processor = ForegroundGenerationCommandProcessor(controls)

        val result = processor.handle("job-1", ForegroundGenerationCommand.START, 1L)

        assertEquals(ForegroundGenerationDirective.KEEP_RUNNING, result.directive)
        assertEquals(GenerationJobStatus.READY, result.status)
        assertEquals(listOf("find"), controls.calls)
    }

    @Test
    fun missingOrTerminalJobStopsInsteadOfShowingAStaleNotification() = runBlocking {
        val missing = ForegroundGenerationCommandProcessor(FakeControls(null))
        val completed = ForegroundGenerationCommandProcessor(FakeControls(GenerationJobStatus.COMPLETED))

        assertEquals(
            ForegroundGenerationDirective.STOP_SERVICE,
            missing.handle("job-1", ForegroundGenerationCommand.START, 1L).directive,
        )
        assertEquals(
            ForegroundGenerationDirective.STOP_SERVICE,
            completed.handle("job-1", ForegroundGenerationCommand.RECHECK, 1L).directive,
        )
    }

    @Test
    fun pausePersistsBeforeTheServiceDecidesWhetherToWaitForASafePoint() = runBlocking {
        val controls = FakeControls(GenerationJobStatus.RUNNING).apply {
            pauseStatus = GenerationJobStatus.PAUSING
        }
        val processor = ForegroundGenerationCommandProcessor(controls)

        val result = processor.handle("job-1", ForegroundGenerationCommand.PAUSE, 2L)

        assertEquals(ForegroundGenerationDirective.KEEP_RUNNING, result.directive)
        assertEquals(GenerationJobStatus.PAUSING, result.status)
        assertEquals(listOf("pause"), controls.calls)
    }

    @Test
    fun completedPauseStopsTheForegroundService() = runBlocking {
        val controls = FakeControls(GenerationJobStatus.READY).apply {
            pauseStatus = GenerationJobStatus.PAUSED
        }

        val result = ForegroundGenerationCommandProcessor(controls)
            .handle("job-1", ForegroundGenerationCommand.PAUSE, 2L)

        assertEquals(ForegroundGenerationDirective.STOP_SERVICE, result.directive)
    }

    @Test
    fun stopWaitsOnlyWhileThePersistentJobIsStopping() = runBlocking {
        val controls = FakeControls(GenerationJobStatus.RUNNING).apply {
            stopStatus = GenerationJobStatus.STOPPING
        }

        val result = ForegroundGenerationCommandProcessor(controls)
            .handle("job-1", ForegroundGenerationCommand.STOP, 3L)

        assertEquals(ForegroundGenerationDirective.KEEP_RUNNING, result.directive)
        assertEquals(listOf("stop"), controls.calls)
    }

    @Test
    fun systemTimeoutAlwaysStopsAfterPersistingItsOwnPauseReason() = runBlocking {
        val controls = FakeControls(GenerationJobStatus.RUNNING).apply {
            systemPauseStatus = GenerationJobStatus.PAUSING
        }

        val result = ForegroundGenerationCommandProcessor(controls)
            .handle("job-1", ForegroundGenerationCommand.SYSTEM_TIMEOUT, 4L)

        assertEquals(ForegroundGenerationDirective.STOP_SERVICE, result.directive)
        assertEquals(GenerationJobStatus.PAUSING, result.status)
        assertEquals(listOf("system-timeout"), controls.calls)
    }

    @Test
    fun invalidIdentifierFailsBeforeTouchingPersistentState() {
        val controls = FakeControls(GenerationJobStatus.READY)
        val processor = ForegroundGenerationCommandProcessor(controls)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                processor.handle("job id with spaces", ForegroundGenerationCommand.START, 1L)
            }
        }
        assertEquals(emptyList<String>(), controls.calls)
    }

    private class FakeControls(
        private val foundStatus: GenerationJobStatus?,
    ) : ForegroundGenerationControlPort {
        val calls = mutableListOf<String>()
        var pauseStatus = GenerationJobStatus.PAUSED
        var stopStatus = GenerationJobStatus.STOPPED
        var systemPauseStatus = GenerationJobStatus.PAUSED

        override suspend fun findJob(jobId: String): ForegroundGenerationSnapshot? {
            calls += "find"
            return foundStatus?.let { ForegroundGenerationSnapshot(it, 1L) }
        }

        override suspend fun requestUserPause(
            jobId: String,
            requestedAt: Long,
        ): GenerationControlResult {
            calls += "pause"
            return result(GenerationExecutionControl.PAUSE, pauseStatus)
        }

        override suspend fun requestStop(
            jobId: String,
            requestedAt: Long,
        ): GenerationControlResult {
            calls += "stop"
            return result(GenerationExecutionControl.STOP, stopStatus)
        }

        override suspend fun requestSystemTimeoutPause(
            jobId: String,
            requestedAt: Long,
        ): GenerationControlResult {
            calls += "system-timeout"
            return result(GenerationExecutionControl.PAUSE, systemPauseStatus)
        }

        private fun result(
            action: GenerationExecutionControl,
            status: GenerationJobStatus,
        ) = GenerationControlResult(
            action = action,
            disposition = if (status == GenerationJobStatus.PAUSING ||
                status == GenerationJobStatus.STOPPING
            ) {
                GenerationControlDisposition.SAFE_POINT_REQUIRED
            } else {
                GenerationControlDisposition.APPLIED
            },
            jobStatus = status,
            stageStatus = GenerationStageStatus.READY,
        )
    }
}
