package app.zhijuan.core.task

import app.zhijuan.core.model.BudgetCounter
import app.zhijuan.core.model.BudgetReservationResult
import app.zhijuan.core.model.BudgetScope
import app.zhijuan.core.model.BudgetScopeSnapshot
import app.zhijuan.core.model.BudgetSettlement
import app.zhijuan.core.model.BudgetViolation
import app.zhijuan.core.model.RequestEstimate
import app.zhijuan.core.model.UsageActual

object BudgetEngine {
    fun reserve(
        scopes: List<BudgetScopeSnapshot>,
        estimate: RequestEstimate,
    ): BudgetReservationResult {
        require(scopes.map { it.scope }.distinct().size == scopes.size) {
            "A budget scope can appear only once."
        }
        require(scopes.any { it.scope == BudgetScope.REQUEST }) {
            "A request hard limit is required."
        }
        require(scopes.any { it.scope == BudgetScope.BOOK }) {
            "A book hard limit is required."
        }
        require(scopes.any { it.scope == BudgetScope.DAILY }) {
            "A daily hard limit is required."
        }

        val violations = scopes.mapNotNull { snapshot ->
            val tokenExceeded = exceeds(
                snapshot.counter.usedTokens,
                snapshot.counter.reservedTokens,
                estimate.tokens,
                snapshot.limit.maxTokens,
            )
            val monetaryExceeded = monetaryExceeded(snapshot, estimate)
            if (tokenExceeded || monetaryExceeded) {
                BudgetViolation(snapshot.scope, tokenExceeded, monetaryExceeded)
            } else {
                null
            }
        }
        if (violations.isNotEmpty()) {
            return BudgetReservationResult.Denied(violations)
        }

        val updated = scopes.map { snapshot ->
            val estimatedCostMicros = estimate.costMicros
            snapshot.copy(
                counter = snapshot.counter.copy(
                    reservedTokens = Math.addExact(snapshot.counter.reservedTokens, estimate.tokens),
                    reservedCostMicros = if (estimatedCostMicros == null) {
                        snapshot.counter.reservedCostMicros
                    } else {
                        Math.addExact(snapshot.counter.reservedCostMicros, estimatedCostMicros)
                    },
                ),
            )
        }
        return BudgetReservationResult.Granted(estimate, updated)
    }

    fun settle(
        reservation: BudgetReservationResult.Granted,
        actual: UsageActual,
    ): BudgetSettlement {
        val updated = reservation.updatedScopes.map { snapshot ->
            val reservedCost = reservation.estimate.costMicros ?: 0
            val actualCost = actual.costMicros ?: 0
            snapshot.copy(
                counter = BudgetCounter(
                    usedTokens = Math.addExact(snapshot.counter.usedTokens, actual.tokens),
                    reservedTokens = subtractExactNonNegative(
                        snapshot.counter.reservedTokens,
                        reservation.estimate.tokens,
                    ),
                    usedCostMicros = Math.addExact(snapshot.counter.usedCostMicros, actualCost),
                    reservedCostMicros = subtractExactNonNegative(
                        snapshot.counter.reservedCostMicros,
                        reservedCost,
                    ),
                ),
            )
        }
        val exhausted = updated.filter { snapshot ->
            val maxCostMicros = snapshot.limit.maxCostMicros
            val actualCostMicros = actual.costMicros
            snapshot.counter.usedTokens >= snapshot.limit.maxTokens ||
                (maxCostMicros != null &&
                    actualCostMicros != null &&
                    snapshot.limit.currency == actual.currency &&
                    snapshot.counter.usedCostMicros >= maxCostMicros)
        }.mapTo(mutableSetOf()) { it.scope }
        return BudgetSettlement(updated, exhausted)
    }

    private fun monetaryExceeded(
        snapshot: BudgetScopeSnapshot,
        estimate: RequestEstimate,
    ): Boolean {
        val limit = snapshot.limit.maxCostMicros ?: return false
        val estimatedCost = estimate.costMicros ?: return false
        require(snapshot.limit.currency == estimate.currency) {
            "Cannot compare ${snapshot.limit.currency} limit with ${estimate.currency} estimate."
        }
        return exceeds(
            snapshot.counter.usedCostMicros,
            snapshot.counter.reservedCostMicros,
            estimatedCost,
            limit,
        )
    }

    private fun exceeds(
        used: Long,
        reserved: Long,
        requested: Long,
        limit: Long,
    ): Boolean = try {
        Math.addExact(Math.addExact(used, reserved), requested) > limit
    } catch (_: ArithmeticException) {
        true
    }

    private fun subtractExactNonNegative(
        current: Long,
        decrement: Long,
    ): Long {
        val result = Math.subtractExact(current, decrement)
        check(result >= 0) { "Reservation counters cannot become negative." }
        return result
    }
}
