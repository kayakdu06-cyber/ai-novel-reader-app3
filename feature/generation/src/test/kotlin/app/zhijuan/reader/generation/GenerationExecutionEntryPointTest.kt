package app.zhijuan.reader.generation

import app.zhijuan.feature.generation.GenerationPersistentRunDisposition
import app.zhijuan.feature.generation.GenerationPersistentRunResult
import app.zhijuan.feature.generation.GenerationTotalRunnerPort
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GenerationExecutionEntryPointTest {
    @Test
    fun `foreground and worker callers share the same runner port instance`() = runBlocking {
        val runner = RecordingRunner()
        val foreground = GenerationExecutionEntryPointV1(runner, "fgs")
        val worker = GenerationExecutionEntryPointV1(runner, "work")

        foreground.run("job-1", "owner-1")
        worker.run("job-2", "owner-2")

        assertEquals(listOf("fgs:job-1:owner-1", "work:job-2:owner-2"), runner.calls)
    }

    private class RecordingRunner : GenerationTotalRunnerPort {
        val calls = mutableListOf<String>()

        override suspend fun runJob(jobId: String, runnerOwnerId: String): GenerationPersistentRunResult {
            calls += "${runnerOwnerId.substringBefore(':')}:$jobId:${runnerOwnerId.substringAfter(':')}"
            return GenerationPersistentRunResult(GenerationPersistentRunDisposition.NOT_READY, 0)
        }
    }
}
