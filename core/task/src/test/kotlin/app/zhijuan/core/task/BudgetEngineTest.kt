package app.zhijuan.core.task

import app.zhijuan.core.model.BudgetCounter
import app.zhijuan.core.model.BudgetLimit
import app.zhijuan.core.model.BudgetReservationResult
import app.zhijuan.core.model.BudgetScope
import app.zhijuan.core.model.BudgetScopeSnapshot
import app.zhijuan.core.model.RequestEstimate
import app.zhijuan.core.model.UsageActual
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BudgetEngineTest {
    @Test
    fun `reservation updates all scopes together`() {
        val result = BudgetEngine.reserve(scopes(), RequestEstimate(100, 250_000, "CNY"))
        val granted = assertInstanceOf(BudgetReservationResult.Granted::class.java, result)
        assertTrue(granted.updatedScopes.all { it.counter.reservedTokens == 100L })
        assertTrue(granted.updatedScopes.all { it.counter.reservedCostMicros == 250_000L })
    }

    @Test
    fun `one exhausted scope denies the whole reservation`() {
        val scopes = scopes().map { snapshot ->
            if (snapshot.scope == BudgetScope.DAILY) {
                snapshot.copy(counter = BudgetCounter(usedTokens = 9_950))
            } else {
                snapshot
            }
        }
        val result = BudgetEngine.reserve(scopes, RequestEstimate(tokens = 100))
        val denied = assertInstanceOf(BudgetReservationResult.Denied::class.java, result)
        assertEquals(listOf(BudgetScope.DAILY), denied.violations.map { it.scope })
    }

    @Test
    fun `unknown price still obeys token hard limit`() {
        val requestLimit = BudgetLimit(maxTokens = 50, maxCostMicros = 1_000_000, currency = "CNY")
        val scopes = scopes().map { snapshot ->
            if (snapshot.scope == BudgetScope.REQUEST) snapshot.copy(limit = requestLimit) else snapshot
        }
        val result = BudgetEngine.reserve(scopes, RequestEstimate(tokens = 51))
        val denied = assertInstanceOf(BudgetReservationResult.Denied::class.java, result)
        assertTrue(denied.violations.single().tokenLimitExceeded)
        assertFalse(denied.violations.single().monetaryLimitExceeded)
    }

    @Test
    fun `counter overflow is treated as exceeded`() {
        val scopes = scopes().map { snapshot ->
            if (snapshot.scope == BudgetScope.DAILY) {
                snapshot.copy(
                    limit = BudgetLimit(Long.MAX_VALUE),
                    counter = BudgetCounter(usedTokens = Long.MAX_VALUE - 2),
                )
            } else {
                snapshot.copy(limit = BudgetLimit(Long.MAX_VALUE))
            }
        }
        val result = BudgetEngine.reserve(scopes, RequestEstimate(tokens = 10))
        assertInstanceOf(BudgetReservationResult.Denied::class.java, result)
    }

    @Test
    fun `settlement releases estimate and records actual usage`() {
        val granted = BudgetEngine.reserve(
            scopes(),
            RequestEstimate(100, 250_000, "CNY"),
        ) as BudgetReservationResult.Granted
        val settlement = BudgetEngine.settle(
            granted,
            UsageActual(80, 200_000, "CNY"),
        )
        settlement.updatedScopes.forEach { snapshot ->
            assertEquals(0, snapshot.counter.reservedTokens)
            assertEquals(80, snapshot.counter.usedTokens)
            assertEquals(0, snapshot.counter.reservedCostMicros)
            assertEquals(200_000, snapshot.counter.usedCostMicros)
        }
    }

    @Test
    fun `all three hard limit scopes are required`() {
        assertThrows(IllegalArgumentException::class.java) {
            BudgetEngine.reserve(
                scopes().filterNot { it.scope == BudgetScope.DAILY },
                RequestEstimate(10),
            )
        }
    }

    private fun scopes(): List<BudgetScopeSnapshot> = listOf(
        BudgetScopeSnapshot(
            BudgetScope.REQUEST,
            BudgetLimit(1_000, 5_000_000, "CNY"),
            BudgetCounter(),
        ),
        BudgetScopeSnapshot(
            BudgetScope.BOOK,
            BudgetLimit(100_000, 500_000_000, "CNY"),
            BudgetCounter(),
        ),
        BudgetScopeSnapshot(
            BudgetScope.DAILY,
            BudgetLimit(10_000, 50_000_000, "CNY"),
            BudgetCounter(),
        ),
    )
}

