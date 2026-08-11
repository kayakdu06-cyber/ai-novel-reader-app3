package app.zhijuan.feature.generation

import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.database.connection.ConnectionProfileEntity
import app.zhijuan.core.database.generation.PersistentBudgetPolicyRepository
import app.zhijuan.core.database.generation.RequestBudgetReservationDraft
import app.zhijuan.core.model.BudgetLimit
import app.zhijuan.core.model.ProviderOpenDestinationEvidence

/**
 * Shared feature androidTest fixture for the Phase 3B public v1 budgeted
 * RequestIntent path.
 *
 * After a test has created its Book, [seedBudgetedRequestEnvironment] activates
 * one finite BOOK policy, one finite UTC DAILY policy and inserts and confirms
 * one fixed test connection that matches the test's existing Provider profile
 * (same connection id, same protocol, same canonical destination). Everything
 * predates every public prepare call in the feature end-to-end fixtures. No
 * default policy, default quota or default price is created anywhere.
 *
 * [budgetedDraft] returns one per-attempt [RequestBudgetReservationDraft] with
 * a finite per-request token limit and a token-only estimate; no monetary
 * limits and no fabricated prices.
 */
object BudgetedGenerationTestSupport {
    const val BUDGETED_BOOK_POLICY_ID = "budgeted-feature-book-policy"
    const val BUDGETED_DAILY_POLICY_ID = "budgeted-feature-daily-policy"
    const val BUDGETED_DAILY_ZONE = "UTC"
    const val BUDGETED_BASE_URL = "https://example.invalid"
    const val BUDGETED_PROTOCOL_ID = "OPENAI_CHAT_COMPAT"
    const val BUDGETED_REQUEST_MAX_TOKENS = 1_000_000L
    const val BUDGETED_ESTIMATED_TOKENS = 1L

    suspend fun seedBudgetedRequestEnvironment(
        database: ZhijuanDatabase,
        bookId: String,
        connectionId: String,
    ) {
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
                connectionId = connectionId,
                displayName = "Budgeted feature test connection",
                serviceId = "DEEPSEEK",
                protocolId = BUDGETED_PROTOCOL_ID,
                baseUrl = BUDGETED_BASE_URL,
                normalizedDestination = "https://example.invalid:443",
                secretRefId = "secret-ref-budgeted-feature",
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
            connectionId,
            acceptedAt = 2L,
        )
    }

    fun budgetedDraft(
        attemptId: String,
        connectionId: String,
        requestMaxTokens: Long = BUDGETED_REQUEST_MAX_TOKENS,
        estimatedTokens: Long = BUDGETED_ESTIMATED_TOKENS,
    ) = RequestBudgetReservationDraft(
        reservationId = "reservation-$attemptId",
        requestMaxTokens = requestMaxTokens,
        estimatedTokens = estimatedTokens,
        estimateSourceVersion = "zhijuan.estimate.v1",
        connectionId = connectionId,
    )

    fun budgetedDestinationEvidence(connectionId: String): ProviderOpenDestinationEvidence =
        ProviderOpenDestinationEvidence.create(
            connectionId = connectionId,
            baseUrl = BUDGETED_BASE_URL,
            protocolId = BUDGETED_PROTOCOL_ID,
        )
}
