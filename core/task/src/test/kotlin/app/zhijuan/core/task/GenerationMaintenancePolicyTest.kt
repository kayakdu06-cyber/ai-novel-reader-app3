package app.zhijuan.core.task

import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationStageStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GenerationMaintenancePolicyTest {
    @Test
    fun `expired pre-request work is the only state blindly requeued`() {
        assertEquals(
            GenerationMaintenanceAction.REQUEUE_BEFORE_REQUEST,
            decide(GenerationJobStatus.RUNNING, GenerationStageStatus.PREPARING, false),
        )
    }

    @Test
    fun `request and local pipeline states require an evidence audit without a provider`() {
        val stages = listOf(
            GenerationStageStatus.REQUEST_INTENT_RECORDED,
            GenerationStageStatus.STREAMING,
            GenerationStageStatus.VALIDATING,
            GenerationStageStatus.COMMITTING,
        )
        assertEquals(
            List(stages.size) { GenerationMaintenanceAction.AUDIT_WITHOUT_PROVIDER },
            stages.map { decide(GenerationJobStatus.RUNNING, it, true) },
        )
    }

    @Test
    fun `pending pause or stop settles only an expired network attempt`() {
        val jobs = listOf(GenerationJobStatus.PAUSING, GenerationJobStatus.STOPPING)
        val stages = listOf(
            GenerationStageStatus.REQUEST_INTENT_RECORDED,
            GenerationStageStatus.STREAMING,
        )
        val actual = jobs.flatMap { job -> stages.map { stage -> decide(job, stage, true) } }
        assertEquals(
            List(actual.size) { GenerationMaintenanceAction.SETTLE_EXPIRED_NETWORK_CONTROL },
            actual,
        )
    }

    @Test
    fun `missing attempt and crashed local control are deferred instead of guessed`() {
        assertEquals(
            GenerationMaintenanceAction.DEFER_UNSAFE_OR_INCOMPLETE,
            decide(GenerationJobStatus.RUNNING, GenerationStageStatus.STREAMING, false),
        )
        assertEquals(
            GenerationMaintenanceAction.DEFER_UNSAFE_OR_INCOMPLETE,
            decide(GenerationJobStatus.PAUSING, GenerationStageStatus.COMMITTING, true),
        )
    }

    private fun decide(
        job: GenerationJobStatus,
        stage: GenerationStageStatus,
        hasAttempt: Boolean,
    ) = GenerationMaintenancePolicy.decide(
        GenerationMaintenanceContext(job, stage, hasAttempt),
    )
}
