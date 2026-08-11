package app.zhijuan.core.database

import app.zhijuan.core.database.connection.ConnectionProfileEntity
import app.zhijuan.core.database.generation.PersistentBudgetPolicyRepository
import app.zhijuan.core.database.generation.RequestBudgetReservationDraft
import app.zhijuan.core.model.BudgetLimit
import app.zhijuan.core.model.ProviderOpenDestinationEvidence

/**
 * Shared androidTest fixture for the public v1 budgeted RequestIntent path.
 *
 * After a test has created its Book, [seedBudgetedRequestEnvironment] activates
 * one finite BOOK policy, one finite UTC DAILY policy and one fixed connection
 * with accepted disclosure, all predating every public prepare call in the
 * GenerationDatabaseTest / ChapterFinalCandidateCommitDatabaseTest fixtures.
 * No default policy, default quota or default price is created anywhere.
 *
 * [budgetedDraft] returns one per-attempt [RequestBudgetReservationDraft] with
 * a finite per-request token limit and a token-only estimate; no monetary
 * limits and no fabricated prices.
 */
object BudgetedRequestTestSupport {
    const val BUDGETED_CONNECTION_ID = "budgeted-fixture-connection"
    const val BUDGETED_BOOK_POLICY_ID = "budgeted-fixture-book-policy"
    const val BUDGETED_DAILY_POLICY_ID = "budgeted-fixture-daily-policy"
    const val BUDGETED_DAILY_ZONE = "UTC"
    const val BUDGETED_BASE_URL = "https://api.deepseek.com"
    const val BUDGETED_PROTOCOL_ID = "OPENAI_CHAT_COMPAT"
    const val BUDGETED_REQUEST_MAX_TOKENS = 1_000_000L
    const val BUDGETED_ESTIMATED_TOKENS = 1L

    suspend fun seedBudgetedRequestEnvironment(database: ZhijuanDatabase, bookId: String) {
        PersistentBudgetPolicyRepository(database).activateBookPolicy(
            policyId = BUDGETED_BOOK_POLICY_ID,
            bookId = bookId,
            limit = BudgetLimit(maxTokens = 1_000_000_000L),
            activatedAt = 1L,
        )
        PersistentBudgetPolicyRepository(database).activateDailyPolicy(
            policyId = BUDGETED_DAILY_POLICY_ID,
            zoneId = BUDGETED_DAILY_ZONE,
            limit = BudgetLimit(maxTokens = 1_000_000_000L),
            activatedAt = 2L,
        )
        database.connectionDao().insertConnection(
            ConnectionProfileEntity(
                connectionId = BUDGETED_CONNECTION_ID,
                displayName = "Budgeted request test connection",
                serviceId = "DEEPSEEK",
                protocolId = BUDGETED_PROTOCOL_ID,
                baseUrl = BUDGETED_BASE_URL,
                normalizedDestination = "https://api.deepseek.com:443",
                secretRefId = "secret-ref-budgeted-fixture",
                secretLastFour = "1234",
                selectedModelId = "deepseek-chat",
                availableModelsJson = "[\"deepseek-chat\"]",
                modelVerification = "DISCOVERED",
                basicVerifiedAt = 2L,
                fullVerifiedAt = null,
                dataDisclosureVersion = null,
                dataDisclosureAcceptedAt = null,
                dataDisclosureBindingHash = null,
                createdAt = 2L,
                updatedAt = 2L,
            ),
        )
        database.connectionDao().acceptDataDisclosureForCurrentDestination(
            BUDGETED_CONNECTION_ID,
            acceptedAt = 2L,
        )
    }

    fun budgetedDraft(
        attemptId: String,
        requestMaxTokens: Long = BUDGETED_REQUEST_MAX_TOKENS,
        estimatedTokens: Long = BUDGETED_ESTIMATED_TOKENS,
    ) = RequestBudgetReservationDraft(
        reservationId = "reservation-$attemptId",
        requestMaxTokens = requestMaxTokens,
        estimatedTokens = estimatedTokens,
        estimateSourceVersion = "zhijuan.estimate.v1",
        connectionId = BUDGETED_CONNECTION_ID,
    )

    fun budgetedDestinationEvidence(): ProviderOpenDestinationEvidence =
        ProviderOpenDestinationEvidence.create(
            connectionId = BUDGETED_CONNECTION_ID,
            baseUrl = BUDGETED_BASE_URL,
            protocolId = BUDGETED_PROTOCOL_ID,
        )
}
