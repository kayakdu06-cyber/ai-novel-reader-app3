package app.zhijuan.provider.fake

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class VirtualFakeStreamClockTest {

    @Test
    fun `start time is validated`() {
        assertThrows<IllegalArgumentException> { VirtualFakeStreamClock(-1L) }
    }

    @Test
    fun `await advances monotonically and yields exactly once per call`() = runBlocking {
        val clock = VirtualFakeStreamClock(1_000L)
        assertEquals(1_000L, clock.nowMillis())
        clock.await(250L)
        assertEquals(1_250L, clock.nowMillis())
        assertEquals(250L, clock.elapsedMillis)
        assertEquals(1L, clock.yields)
        clock.await(0L)
        assertEquals(1_250L, clock.nowMillis())
        assertEquals(2L, clock.yields)
        assertEquals(1_300L, clock.advance(50L))
        assertEquals(300L, clock.elapsedMillis)
    }

    @Test
    fun `negative and overflowing advances are rejected`() = runBlocking {
        val clock = VirtualFakeStreamClock()
        assertThrows<IllegalArgumentException> { clock.advance(-1L) }
        assertThrows<IllegalArgumentException> { clock.await(-1L) }
        clock.advance(Long.MAX_VALUE)
        assertThrows<IllegalArgumentException> { clock.advance(1L) }
    }

    @Test
    fun `concurrent awaits are thread safe and exactly additive`() = runBlocking {
        val clock = VirtualFakeStreamClock()
        coroutineScope {
            repeat(20) {
                launch { clock.await(10L) }
            }
        }
        assertEquals(200L, clock.elapsedMillis)
        assertEquals(20L, clock.yields)
        assertTrue(clock.nowMillis() >= 200L)
    }
}
