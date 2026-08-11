package app.zhijuan.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BudgetDailyPeriodKeyV1Test {
    @Test
    fun `utc midnight boundary splits the calendar day deterministically`() {
        // 2026-08-09T00:00:00.000Z and one millisecond before it.
        assertEquals("2026-08-09|UTC", BudgetDailyPeriodKeyV1.create(1786233600000L, "UTC"))
        assertEquals("2026-08-08|UTC", BudgetDailyPeriodKeyV1.create(1786233599999L, "UTC"))
    }

    @Test
    fun `asia shanghai midnight boundary splits on the local calendar day`() {
        // 2026-08-09T16:00:00.000Z is 2026-08-10 00:00 in Asia/Shanghai.
        assertEquals("2026-08-09|Asia/Shanghai", BudgetDailyPeriodKeyV1.create(1786291199999L, "Asia/Shanghai"))
        assertEquals("2026-08-10|Asia/Shanghai", BudgetDailyPeriodKeyV1.create(1786291200000L, "Asia/Shanghai"))
    }

    @Test
    fun `same input always produces the same key`() {
        val first = BudgetDailyPeriodKeyV1.create(1786291200000L, "Asia/Shanghai")
        val second = BudgetDailyPeriodKeyV1.create(1786291200000L, "Asia/Shanghai")
        assertEquals(first, second)
        assertEquals("2026-08-10|Asia/Shanghai", first)
    }

    @Test
    fun `key contains only the derived date and the explicit zone`() {
        val key = BudgetDailyPeriodKeyV1.create(1786291200000L, "Asia/Shanghai")
        assertTrue(key.matches(Regex("\\d{4}-\\d{2}-\\d{2}\\|Asia/Shanghai")))
    }

    @Test
    fun `invalid zone ids are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            BudgetDailyPeriodKeyV1.create(1786291200000L, "Not/AZone")
        }
        assertThrows(IllegalArgumentException::class.java) {
            BudgetDailyPeriodKeyV1.create(1786291200000L, "UTC-8")
        }
        assertThrows(IllegalArgumentException::class.java) {
            BudgetDailyPeriodKeyV1.create(1786291200000L, "   ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            BudgetDailyPeriodKeyV1.create(1786291200000L, "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            BudgetDailyPeriodKeyV1.create(1786291200000L, "a".repeat(65))
        }
    }

    @Test
    fun `negative epoch millis are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            BudgetDailyPeriodKeyV1.create(-1L, "UTC")
        }
    }
}
