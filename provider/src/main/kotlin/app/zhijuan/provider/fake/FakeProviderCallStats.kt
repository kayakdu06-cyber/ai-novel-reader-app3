package app.zhijuan.provider.fake

import app.zhijuan.provider.common.ProviderCancellationResult
import java.util.Collections
import java.util.EnumMap

/** Finite event kinds counted by [FakeProviderCallStats]. */
enum class FakeProviderEventKind {
    STARTED,
    TEXT,
    STRUCTURED,
    USAGE,
    HEARTBEAT,
    COMPLETED,
    REFUSED,
    FAILED,
}

/**
 * Immutable snapshot of [FakeProviderCallStats]. Contains only finite enums,
 * counts, character/token totals and virtual milliseconds. No text fragments,
 * prompts, endpoints, secrets or request ids are ever retained or rendered.
 */
data class FakeProviderCallStatsSnapshot(
    val generateCalls: Long,
    val cancelCalls: Long,
    val testConnectionCalls: Long,
    val listModelsCalls: Long,
    val getCapabilitiesCalls: Long,
    val recoveryQueryCalls: Long,
    val cancelledCollections: Long,
    val eventCounts: Map<FakeProviderEventKind, Long>,
    val textCharacters: Long,
    val structuredCharacters: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val virtualMillis: Long,
    val cancelResults: Map<ProviderCancellationResult, Long>,
)

/**
 * Thread-safe, redacted call statistics for a [FakeProviderAdapter]. Every mutator
 * and [snapshot] is synchronized, so concurrent collections can be recorded and
 * read safely.
 */
class FakeProviderCallStats {
    private val lock = Any()
    private var generateCalls = 0L
    private var cancelCalls = 0L
    private var testConnectionCalls = 0L
    private var listModelsCalls = 0L
    private var getCapabilitiesCalls = 0L
    private var recoveryQueryCalls = 0L
    private var cancelledCollections = 0L
    private var textCharacters = 0L
    private var structuredCharacters = 0L
    private var inputTokens = 0L
    private var outputTokens = 0L
    private var virtualMillis = 0L
    private val eventCounts = EnumMap<FakeProviderEventKind, Long>(FakeProviderEventKind::class.java)
    private val cancelResults =
        EnumMap<ProviderCancellationResult, Long>(ProviderCancellationResult::class.java)

    init {
        FakeProviderEventKind.entries.forEach { eventCounts[it] = 0L }
        ProviderCancellationResult.entries.forEach { cancelResults[it] = 0L }
    }

    fun recordGenerate(): Unit = synchronized(lock) { generateCalls++ }

    fun recordCancel(result: ProviderCancellationResult): Unit = synchronized(lock) {
        cancelCalls++
        cancelResults[result] = cancelResults.getValue(result) + 1
    }

    fun recordTestConnection(): Unit = synchronized(lock) { testConnectionCalls++ }

    fun recordListModels(): Unit = synchronized(lock) { listModelsCalls++ }

    fun recordGetCapabilities(): Unit = synchronized(lock) { getCapabilitiesCalls++ }

    fun recordRecoveryQuery(): Unit = synchronized(lock) { recoveryQueryCalls++ }

    /** Records that a flow collection was cancelled by coroutine cancellation. */
    fun recordCollectionCancelled(): Unit = synchronized(lock) { cancelledCollections++ }

    fun recordEvent(kind: FakeProviderEventKind): Unit = synchronized(lock) {
        eventCounts[kind] = eventCounts.getValue(kind) + 1
    }

    fun recordTextCharacters(count: Long): Unit = synchronized(lock) { textCharacters += count }

    fun recordStructuredCharacters(count: Long): Unit =
        synchronized(lock) { structuredCharacters += count }

    fun recordInputTokens(count: Long): Unit = synchronized(lock) { inputTokens += count }

    fun recordOutputTokens(count: Long): Unit = synchronized(lock) { outputTokens += count }

    fun recordVirtualMillis(millis: Long): Unit = synchronized(lock) {
        require(millis >= 0L) { "Recorded virtual time cannot be negative." }
        virtualMillis += millis
    }

    fun snapshot(): FakeProviderCallStatsSnapshot = synchronized(lock) {
        FakeProviderCallStatsSnapshot(
            generateCalls = generateCalls,
            cancelCalls = cancelCalls,
            testConnectionCalls = testConnectionCalls,
            listModelsCalls = listModelsCalls,
            getCapabilitiesCalls = getCapabilitiesCalls,
            recoveryQueryCalls = recoveryQueryCalls,
            cancelledCollections = cancelledCollections,
            eventCounts = unmodifiableCopy(eventCounts),
            textCharacters = textCharacters,
            structuredCharacters = structuredCharacters,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            virtualMillis = virtualMillis,
            cancelResults = unmodifiableCopy(cancelResults),
        )
    }

    override fun toString(): String = snapshot().toString()

    private fun <K : Enum<K>> unmodifiableCopy(source: EnumMap<K, Long>): Map<K, Long> =
        Collections.unmodifiableMap(EnumMap(source))
}
