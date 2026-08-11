package app.zhijuan.core.database.generation

import androidx.room.withTransaction
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.model.BudgetDailyPeriodKeyV1
import app.zhijuan.core.model.BudgetLimit
import app.zhijuan.core.model.BudgetScope

internal const val BUDGET_POLICY_VERSION = "zhijuan.budget-policy.v1"
internal const val DAILY_POLICY_SCOPE_KEY = "GLOBAL"

/**
 * Redacted activation result. Never expands policy ids, book ids, zones or
 * monetary amounts in [toString].
 */
class BudgetPolicyActivation internal constructor(
    val scope: BudgetScope,
    val revisionNo: Int,
) {
    override fun toString(): String = "BudgetPolicyActivation(scope=$scope, revisionNo=$revisionNo)"
}

/**
 * Redacted current-policy read result. [toString] never expands limits,
 * policy ids, book ids or zones.
 */
class CurrentBudgetPolicy internal constructor(
    val revisionNo: Int,
    val maxTokens: Long,
    val hasMonetaryLimit: Boolean,
) {
    override fun toString(): String = "CurrentBudgetPolicy(revisionNo=$revisionNo)"
}

/**
 * Atomically activates one policy revision and advances the (scope, scope_key)
 * head. Every activation runs in a single Room transaction: identifier, time,
 * zone and book existence are validated first, then the chain is read, the
 * next revision is appended with a contiguous parent, and the head is either
 * inserted (revision 1) or CAS-advanced. Any concurrent or stale change aborts
 * the whole transaction. No default policy is ever created and limits are
 * never guessed from job JSON.
 */
class PersistentBudgetPolicyRepository(
    private val database: ZhijuanDatabase,
) {
    suspend fun activateBookPolicy(
        policyId: String,
        bookId: String,
        limit: BudgetLimit,
        activatedAt: Long,
    ): BudgetPolicyActivation = activate(
        scope = BudgetScope.BOOK,
        scopeKey = bookId,
        policyId = policyId,
        bookId = bookId,
        dailyZoneId = null,
        limit = limit,
        activatedAt = activatedAt,
    )

    suspend fun activateDailyPolicy(
        policyId: String,
        zoneId: String,
        limit: BudgetLimit,
        activatedAt: Long,
    ): BudgetPolicyActivation = activate(
        scope = BudgetScope.DAILY,
        scopeKey = DAILY_POLICY_SCOPE_KEY,
        policyId = policyId,
        bookId = null,
        dailyZoneId = zoneId,
        limit = limit,
        activatedAt = activatedAt,
    )

    suspend fun currentBookPolicy(bookId: String): CurrentBudgetPolicy? {
        require(bookId.isNotBlank()) { "Book id must not be blank." }
        val revision = currentRevision(BudgetScope.BOOK, bookId) ?: return null
        return CurrentBudgetPolicy(
            revisionNo = revision.revisionNo,
            maxTokens = revision.maxTokens,
            hasMonetaryLimit = revision.maxCostMicros != null,
        )
    }

    suspend fun currentDailyPolicy(zoneId: String): CurrentBudgetPolicy? {
        require(zoneId.isNotBlank()) { "Zone id must not be blank." }
        val revision = currentRevision(BudgetScope.DAILY, DAILY_POLICY_SCOPE_KEY) ?: return null
        if (revision.dailyZoneId != zoneId) return null
        return CurrentBudgetPolicy(
            revisionNo = revision.revisionNo,
            maxTokens = revision.maxTokens,
            hasMonetaryLimit = revision.maxCostMicros != null,
        )
    }

    private suspend fun currentRevision(
        scope: BudgetScope,
        scopeKey: String,
    ): BudgetPolicyRevisionEntity? {
        val dao = database.budgetDao()
        val head = dao.findHead(scope, scopeKey) ?: return null
        return dao.findRevision(head.currentBudgetPolicyId)
    }

    private suspend fun activate(
        scope: BudgetScope,
        scopeKey: String,
        policyId: String,
        bookId: String?,
        dailyZoneId: String?,
        limit: BudgetLimit,
        activatedAt: Long,
    ): BudgetPolicyActivation = database.withTransaction {
        require(policyId.isNotBlank()) { "Policy id must not be blank." }
        require(activatedAt >= 0) { "Activation time must be non-negative." }
        require(scope == BudgetScope.BOOK || scope == BudgetScope.DAILY) {
            "Unsupported budget scope $scope."
        }
        when (scope) {
            BudgetScope.BOOK -> {
                require(bookId != null && bookId.isNotBlank()) { "Book id must not be blank." }
                require(database.libraryDao().findBook(bookId) != null) {
                    "Book does not exist: $bookId"
                }
            }
            BudgetScope.DAILY -> {
                require(dailyZoneId != null && BudgetDailyPeriodKeyV1.isSupportedZoneId(dailyZoneId)) {
                    "Unsupported IANA zone id."
                }
            }
            else -> error("Unsupported budget scope $scope")
        }

        val dao = database.budgetDao()
        val head = dao.findHead(scope, scopeKey)
        val parent = head?.let { dao.findRevision(it.currentBudgetPolicyId) }
        if (parent != null) {
            require(parent.bookId == bookId && parent.dailyZoneId == dailyZoneId) {
                "Budget policy chain identity mismatch."
            }
        }
        val revisionNo = if (parent == null) 1 else parent.revisionNo + 1
        val newRevision = BudgetPolicyRevisionEntity(
            budgetPolicyId = policyId,
            scope = scope,
            scopeKey = scopeKey,
            revisionNo = revisionNo,
            parentBudgetPolicyId = parent?.budgetPolicyId,
            bookId = bookId,
            dailyZoneId = dailyZoneId,
            maxTokens = limit.maxTokens,
            maxCostMicros = limit.maxCostMicros,
            currency = limit.currency,
            policyVersion = BUDGET_POLICY_VERSION,
            createdAt = activatedAt,
        )
        dao.insertRevision(newRevision)
        if (head == null) {
            dao.insertHead(
                BudgetPolicyHeadEntity(
                    scope = scope,
                    scopeKey = scopeKey,
                    currentBudgetPolicyId = policyId,
                    updatedAt = activatedAt,
                ),
            )
        } else {
            val advanced = dao.casAdvanceHead(
                scope = scope,
                scopeKey = scopeKey,
                expectedCurrentPolicyId = head.currentBudgetPolicyId,
                newCurrentPolicyId = policyId,
                updatedAt = activatedAt,
            )
            check(advanced == 1) {
                "Budget policy head changed concurrently; activation rolled back."
            }
        }
        BudgetPolicyActivation(scope = scope, revisionNo = revisionNo)
    }
}
