package app.zhijuan.core.task

import app.zhijuan.core.model.BudgetStatus
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationStageStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BackgroundRecoveryPolicyTest {
    private val eligible = BackgroundRecoveryContext(
        jobStatus = GenerationJobStatus.PAUSED,
        stageStatus = GenerationStageStatus.READY,
        autoResumePaidGenerationEnabled = true,
        manuallyPaused = false,
        networkAvailable = true,
        retryWindowReached = true,
        leaseExpiredOrMissing = true,
        budgetStatus = BudgetStatus.AVAILABLE,
    )

    @Test
    fun `eligible recovery resumes only one stage`() {
        assertEquals(BackgroundRecoveryDecision.ResumeOneStage, BackgroundRecoveryPolicy.evaluate(eligible))
    }

    @Test
    fun `paid recovery requires explicit opt in`() {
        assertDeferred(
            eligible.copy(autoResumePaidGenerationEnabled = false),
            RecoveryDeferralReason.USER_OPT_IN_REQUIRED,
        )
    }

    @Test
    fun `manual pause is never overridden`() {
        assertDeferred(eligible.copy(manuallyPaused = true), RecoveryDeferralReason.MANUALLY_PAUSED)
    }

    @Test
    fun `active lease prevents duplicate execution`() {
        assertDeferred(eligible.copy(leaseExpiredOrMissing = false), RecoveryDeferralReason.ACTIVE_LEASE)
    }

    @Test
    fun `exhausted budget prevents remote request`() {
        assertDeferred(
            eligible.copy(budgetStatus = BudgetStatus.EXHAUSTED),
            RecoveryDeferralReason.BUDGET_EXHAUSTED,
        )
    }

    @Test
    fun `warning budget remains eligible because hard limit is not exhausted`() {
        assertEquals(
            BackgroundRecoveryDecision.ResumeOneStage,
            BackgroundRecoveryPolicy.evaluate(eligible.copy(budgetStatus = BudgetStatus.WARNING)),
        )
    }

    @Test
    fun `retry window must be reached`() {
        assertDeferred(
            eligible.copy(retryWindowReached = false),
            RecoveryDeferralReason.RETRY_WINDOW_NOT_REACHED,
        )
    }

    @Test
    fun `network must be available`() {
        assertDeferred(eligible.copy(networkAvailable = false), RecoveryDeferralReason.NETWORK_UNAVAILABLE)
    }

    @Test
    fun `blocked stage remains blocked`() {
        assertDeferred(
            eligible.copy(stageStatus = GenerationStageStatus.BLOCKED),
            RecoveryDeferralReason.STAGE_BLOCKED,
        )
    }

    @Test
    fun `unknown or interrupted request is reconciled without replacement request`() {
        val ambiguousStages = listOf(
            GenerationStageStatus.REQUEST_INTENT_RECORDED,
            GenerationStageStatus.STREAMING,
            GenerationStageStatus.UNKNOWN_RESULT,
            GenerationStageStatus.RECOVERY_REQUIRED,
        )
        ambiguousStages.forEach { stage ->
            assertEquals(
                BackgroundRecoveryDecision.ReconcileWithoutNewRequest,
                BackgroundRecoveryPolicy.evaluate(
                    eligible.copy(
                        stageStatus = stage,
                        budgetStatus = BudgetStatus.EXHAUSTED,
                        autoResumePaidGenerationEnabled = false,
                    ),
                ),
            )
        }
    }

    @Test
    fun `reconciliation waits for network instead of issuing replacement`() {
        assertDeferred(
            eligible.copy(
                stageStatus = GenerationStageStatus.UNKNOWN_RESULT,
                networkAvailable = false,
            ),
            RecoveryDeferralReason.NETWORK_UNAVAILABLE,
        )
    }

    @Test
    fun `job requiring action is not auto resumed`() {
        assertDeferred(
            eligible.copy(jobStatus = GenerationJobStatus.NEEDS_ACTION),
            RecoveryDeferralReason.USER_ACTION_REQUIRED,
        )
    }

    @Test
    fun `stopping transition is not reversed by scheduler`() {
        assertDeferred(
            eligible.copy(jobStatus = GenerationJobStatus.STOPPING),
            RecoveryDeferralReason.TRANSITION_IN_PROGRESS,
        )
    }

    @Test
    fun `terminal jobs and stages have no work`() {
        val jobs = listOf(
            GenerationJobStatus.COMPLETED,
            GenerationJobStatus.STOPPED,
        )
        jobs.forEach { status ->
            assertEquals(
                BackgroundRecoveryDecision.NoWork,
                BackgroundRecoveryPolicy.evaluate(eligible.copy(jobStatus = status)),
            )
        }
        val stages = listOf(
            GenerationStageStatus.SUCCEEDED,
            GenerationStageStatus.CANCELLED,
        )
        stages.forEach { status ->
            assertEquals(
                BackgroundRecoveryDecision.NoWork,
                BackgroundRecoveryPolicy.evaluate(eligible.copy(stageStatus = status)),
            )
        }
    }

    private fun assertDeferred(
        context: BackgroundRecoveryContext,
        reason: RecoveryDeferralReason,
    ) {
        assertEquals(
            BackgroundRecoveryDecision.Defer(reason),
            BackgroundRecoveryPolicy.evaluate(context),
        )
    }
}
