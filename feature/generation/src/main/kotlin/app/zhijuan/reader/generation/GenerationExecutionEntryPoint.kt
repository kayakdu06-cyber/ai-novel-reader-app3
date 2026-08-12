package app.zhijuan.reader.generation

import app.zhijuan.feature.generation.GenerationPersistentRunResult
import app.zhijuan.feature.generation.GenerationTotalRunnerPort

/** Thin caller identity wrapper; all durable behavior remains in the single total runner port. */
internal class GenerationExecutionEntryPointV1(
    private val runner: GenerationTotalRunnerPort,
    private val ownerPrefix: String,
) {
    init {
        require(ownerPrefix.matches(Regex("[A-Za-z0-9._-]{1,24}")))
    }

    suspend fun run(jobId: String, ownerSuffix: String): GenerationPersistentRunResult =
        runner.runJob(jobId, "$ownerPrefix:$ownerSuffix")
}
