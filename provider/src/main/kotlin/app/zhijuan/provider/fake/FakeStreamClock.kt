package app.zhijuan.provider.fake

import kotlinx.coroutines.yield

/**
 * Injectable wait/clock abstraction used by [FakeProviderAdapter] to advance time
 * deterministically without touching the wall clock.
 *
 * The adapter only ever reads [nowMillis] and suspends on [await]; it never sleeps.
 * This keeps the abstraction small enough that a later integration can map it onto
 * `core:diagnostics` `GenerationTimingClock` (capture) without changing the adapter.
 */
interface FakeStreamClock {
    /** Current virtual time in milliseconds. Must be monotonic and non-negative. */
    fun nowMillis(): Long

    /**
     * Advances virtual time by [millis] and yields the coroutine scheduler at least
     * once, so the wait is cancellable and never busy-waits.
     */
    suspend fun await(millis: Long)
}

/**
 * Thread-safe virtual clock for tests. Time only moves forward; every [await]
 * advances [elapsedMillis] by the exact requested duration and calls [yield].
 *
 * Negative durations and `Long` overflow are rejected with [IllegalArgumentException].
 */
class VirtualFakeStreamClock(
    private val startMillis: Long = 0L,
) : FakeStreamClock {
    init {
        require(startMillis >= 0L) { "Virtual fake clock start time cannot be negative." }
    }

    private val lock = Any()
    private var now = startMillis
    private var yieldCount = 0L

    override fun nowMillis(): Long = synchronized(lock) { now }

    override suspend fun await(millis: Long) {
        require(millis >= 0L) { "Virtual fake clock cannot move backwards or stay unset." }
        yield()
        advance(millis)
        synchronized(lock) { yieldCount++ }
    }

    /**
     * Advances virtual time by [millis] without yielding. Returns the new current
     * time. Rejects negative durations and overflow.
     */
    fun advance(millis: Long): Long {
        require(millis >= 0L) { "Virtual fake clock cannot move backwards or stay unset." }
        synchronized(lock) {
            now = try {
                Math.addExact(now, millis)
            } catch (overflow: ArithmeticException) {
                throw IllegalArgumentException("Virtual fake clock time overflow.", overflow)
            }
            return now
        }
    }

    /** Total virtual time elapsed since construction. Never decreases. */
    val elapsedMillis: Long
        get() = synchronized(lock) { now - startMillis }

    /** Number of completed [await] calls (each yields exactly once). */
    val yields: Long
        get() = synchronized(lock) { yieldCount }
}
