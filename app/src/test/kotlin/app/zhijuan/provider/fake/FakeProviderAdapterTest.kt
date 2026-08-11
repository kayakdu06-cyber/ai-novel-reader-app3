package app.zhijuan.provider.fake

import app.zhijuan.core.model.FailureRequestState
import app.zhijuan.core.model.StandardErrorCode
import app.zhijuan.provider.common.CapabilitySupport
import app.zhijuan.provider.common.GenerationParameters
import app.zhijuan.provider.common.GenerationRequest
import app.zhijuan.provider.common.ProviderCancellationResult
import app.zhijuan.provider.common.ProviderConnectionProfile
import app.zhijuan.provider.common.ProviderModelId
import app.zhijuan.provider.common.ProviderProtocol
import app.zhijuan.provider.common.ProviderPrompt
import app.zhijuan.provider.common.ProviderRemoteRequestId
import app.zhijuan.provider.common.ProviderStreamEvent
import app.zhijuan.provider.common.ProviderStreamFormat
import app.zhijuan.provider.common.ProviderTimeoutPolicy
import app.zhijuan.provider.common.ProviderUsageQuality
import app.zhijuan.provider.common.PromptLayer
import app.zhijuan.provider.common.PromptPart
import app.zhijuan.provider.common.SensitiveProviderText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FakeProviderAdapterTest {

    private val protocol = ProviderProtocol.OPENAI_CHAT_COMPAT

    private fun profile(baseUrl: String = "https://fake.example/v1"): ProviderConnectionProfile =
        ProviderConnectionProfile.create(
            connectionId = "fake-conn-1",
            protocol = protocol,
            baseUrl = baseUrl,
        )

    private fun request(
        requestId: String = "fake-req-0001",
        stream: Boolean = true,
        modelId: ProviderModelId = ProviderModelId.from("fake-model"),
    ): GenerationRequest = GenerationRequest(
        requestId = requestId,
        generationId = "gen-0001",
        stageId = "stage-0001",
        attemptId = "attempt-0001",
        modelId = modelId,
        prompt = ProviderPrompt(
            listOf(
                PromptPart(PromptLayer.STAGE_CONTRACT, SensitiveProviderText.from("stage contract")),
            ),
        ),
        parameters = GenerationParameters(),
        structuredOutputSchema = null,
        stream = stream,
        timeouts = ProviderTimeoutPolicy(
            connectMillis = 10_000,
            firstByteMillis = 60_000,
            streamIdleMillis = 60_000,
            totalStageMillis = 300_000,
        ),
        idempotencyKey = null,
    )

    @Test
    fun `fixed script emits deterministic event order and virtual time`() = runBlocking {
        val script = fakeStreamScript {
            wait(100)
            started("remote-1")
            text("hello")
            wait(250)
            usage(inputTokens = 12, outputTokens = 3)
            heartbeat()
            completed()
        }
        val clock = VirtualFakeStreamClock()
        val adapter = FakeProviderAdapter(script, protocol, clock = clock)

        val events = mutableListOf<ProviderStreamEvent>()
        adapter.generate(profile(), request()).collect { events += it }

        assertEquals(350L, clock.elapsedMillis)
        assertEquals(
            listOf(
                ProviderStreamEvent.Started::class,
                ProviderStreamEvent.TextDelta::class,
                ProviderStreamEvent.UsageUpdate::class,
                ProviderStreamEvent.Heartbeat::class,
                ProviderStreamEvent.Completed::class,
            ),
            events.map { it::class },
        )
        val started = events[0] as ProviderStreamEvent.Started
        assertEquals("remote-1", started.remoteRequestId?.withValue { it })
        val text = events[1] as ProviderStreamEvent.TextDelta
        assertEquals("hello", text.text.withValue { it })
        val usage = events[2] as ProviderStreamEvent.UsageUpdate
        assertEquals(12L, usage.usage.inputTokens)
        assertEquals(3L, usage.usage.outputTokens)
        assertEquals(15L, usage.usage.totalTokens)
        assertEquals(ProviderUsageQuality.PROVIDER_REPORTED, usage.usage.quality)

        val stats = adapter.stats.snapshot()
        assertEquals(1L, stats.generateCalls)
        assertEquals(1L, stats.eventCounts[FakeProviderEventKind.STARTED])
        assertEquals(1L, stats.eventCounts[FakeProviderEventKind.TEXT])
        assertEquals(1L, stats.eventCounts[FakeProviderEventKind.USAGE])
        assertEquals(1L, stats.eventCounts[FakeProviderEventKind.HEARTBEAT])
        assertEquals(1L, stats.eventCounts[FakeProviderEventKind.COMPLETED])
        assertEquals(0L, stats.eventCounts[FakeProviderEventKind.FAILED])
        assertEquals(5L, stats.textCharacters)
        assertEquals(12L, stats.inputTokens)
        assertEquals(3L, stats.outputTokens)
        assertEquals(350L, stats.virtualMillis)
    }

    @Test
    fun `slow flow simulates five virtual minutes in wall milliseconds`() = runBlocking {
        val script = fakeStreamScript {
            started()
            repeat(301) { wait(1_000) }
            text("done")
            completed()
        }
        assertEquals(301_000L, script.totalVirtualMillis)
        val clock = VirtualFakeStreamClock()
        val adapter = FakeProviderAdapter(script, protocol, clock = clock)

        val wallStart = System.nanoTime()
        val events = mutableListOf<ProviderStreamEvent>()
        adapter.generate(profile(), request()).collect { events += it }
        val wallMillis = (System.nanoTime() - wallStart) / 1_000_000

        assertTrue(
            wallMillis < 5_000L,
            "Wall time $wallMillis ms must not depend on the 5 virtual minutes.",
        )
        assertEquals(301_000L, clock.elapsedMillis)
        assertEquals(301L, clock.yields)
        assertEquals(301_000L, adapter.stats.snapshot().virtualMillis)
        assertTrue(events.last() is ProviderStreamEvent.Completed)
    }

    @Test
    fun `no-terminal script stalls without a synthesized terminal`() = runBlocking {
        val script = fakeStreamScript {
            started()
            text("partial")
            heartbeat()
        }
        assertFalse(script.hasTerminal)
        val adapter = FakeProviderAdapter(script, protocol)

        val events = mutableListOf<ProviderStreamEvent>()
        adapter.generate(profile(), request()).collect { events += it }

        assertEquals(
            listOf(
                ProviderStreamEvent.Started::class,
                ProviderStreamEvent.TextDelta::class,
                ProviderStreamEvent.Heartbeat::class,
            ),
            events.map { it::class },
        )
        val stats = adapter.stats.snapshot()
        assertEquals(0L, stats.eventCounts[FakeProviderEventKind.COMPLETED])
        assertEquals(0L, stats.eventCounts[FakeProviderEventKind.FAILED])
        assertEquals(0L, stats.eventCounts[FakeProviderEventKind.REFUSED])
    }

    @Test
    fun `explicit unknown failure is preserved without adapter repair`() = runBlocking {
        val script = fakeStreamScript {
            started()
            failed(
                code = StandardErrorCode.UNKNOWN_RESULT,
                requestState = FailureRequestState.RESULT_UNKNOWN,
            )
        }
        val adapter = FakeProviderAdapter(script, protocol)

        val events = mutableListOf<ProviderStreamEvent>()
        adapter.generate(profile(), request()).collect { events += it }

        assertEquals(
            listOf(ProviderStreamEvent.Started::class, ProviderStreamEvent.Failed::class),
            events.map { it::class },
        )
        val failed = events.last() as ProviderStreamEvent.Failed
        assertEquals(StandardErrorCode.UNKNOWN_RESULT, failed.code)
        assertEquals(FailureRequestState.RESULT_UNKNOWN, failed.requestState)
        assertNull(failed.httpStatus)
        assertEquals(1L, adapter.stats.snapshot().eventCounts[FakeProviderEventKind.FAILED])
    }

    @Test
    fun `usage step without tokens reports unknown quality`() = runBlocking {
        val adapter = FakeProviderAdapter(
            fakeStreamScript { started(); usage(); completed() },
            protocol,
        )
        val events = mutableListOf<ProviderStreamEvent>()
        adapter.generate(profile(), request()).collect { events += it }
        val usage = events[1] as ProviderStreamEvent.UsageUpdate
        assertEquals(ProviderUsageQuality.UNKNOWN, usage.usage.quality)
        assertNull(usage.usage.inputTokens)
        assertNull(usage.usage.outputTokens)
    }

    private class GateClock : FakeStreamClock {
        private val gate = CompletableDeferred<Unit>()

        override fun nowMillis(): Long = 0L

        override suspend fun await(millis: Long) {
            require(millis >= 0L)
            gate.await()
        }
    }

    @Test
    fun `collection cancellation and repeated cancel are observed separately and deterministically`() =
        runBlocking {
            val clock = GateClock()
            val adapter = FakeProviderAdapter(
                fakeStreamScript { started(); wait(1); text("x"); completed() },
                protocol,
                clock = clock,
            )
            val job = launch {
                adapter.generate(profile(), request()).collect { }
            }
            repeat(10) { yield() }
            job.cancel()
            job.join()

            val afterCancellation = adapter.stats.snapshot()
            assertEquals(1L, afterCancellation.cancelledCollections)
            assertEquals(1L, afterCancellation.generateCalls)
            assertEquals(0L, afterCancellation.cancelCalls)

            val first = adapter.cancel(profile(), "fake-req-0001")
            val second = adapter.cancel(profile(), "fake-req-0001")
            assertEquals(ProviderCancellationResult.CANCELLED_LOCALLY, first)
            assertEquals(ProviderCancellationResult.CANCELLED_LOCALLY, second)

            val afterCancel = adapter.stats.snapshot()
            assertEquals(2L, afterCancel.cancelCalls)
            assertEquals(2L, afterCancel.cancelResults[ProviderCancellationResult.CANCELLED_LOCALLY])
            assertEquals(1L, afterCancel.cancelledCollections)
        }

    @Test
    fun `rejected requests are not recorded as generate calls`() {
        val adapter = FakeProviderAdapter(fakeStreamScript { started(); completed() }, protocol)
        assertThrows<IllegalArgumentException> {
            adapter.generate(profile(), request(modelId = ProviderModelId.from("other-model")))
        }
        assertThrows<IllegalArgumentException> {
            adapter.generate(profile(), request(stream = false))
        }
        assertEquals(0L, adapter.stats.snapshot().generateCalls)
    }

    @Test
    fun `capability and connection methods return fixed offline results`() = runBlocking {
        val clock = VirtualFakeStreamClock(500L)
        val adapter = FakeProviderAdapter(
            fakeStreamScript { started(); completed() },
            protocol,
            clock = clock,
        )
        val profile = profile()
        val modelId = ProviderModelId.from("fake-model")

        val connection = adapter.testConnection(profile)
        assertTrue(connection is app.zhijuan.provider.common.ConnectionTestResult.Success)
        val list = adapter.listModels(profile)
        assertTrue(list is app.zhijuan.provider.common.ModelListResult.Success)
        val capabilities = adapter.getCapabilities(profile, modelId)
        assertTrue(capabilities is app.zhijuan.provider.common.CapabilityResult.Success)
        val snapshot = (capabilities as app.zhijuan.provider.common.CapabilityResult.Success).snapshot
        assertEquals(CapabilitySupport.SUPPORTED, snapshot.streaming)
        assertEquals(ProviderStreamFormat.SSE, snapshot.streamFormat)
        assertEquals(modelId, snapshot.modelId)
        assertEquals(
            ProviderCancellationResult.CANCELLED_LOCALLY,
            adapter.cancel(profile, "fake-req-0001"),
        )
        val recovery = adapter.queryRequestRecovery(
            profile,
            ProviderRemoteRequestId.from("remote-1"),
        )
        assertTrue(recovery is app.zhijuan.provider.common.ProviderRequestRecoveryResult.NotSupported)

        val stats = adapter.stats.snapshot()
        assertEquals(1L, stats.testConnectionCalls)
        assertEquals(1L, stats.listModelsCalls)
        assertEquals(1L, stats.getCapabilitiesCalls)
        assertEquals(1L, stats.recoveryQueryCalls)
        assertEquals(1L, stats.cancelCalls)
    }

    @Test
    fun `default strings and stats never expose canary values`() = runBlocking {
        val body = "CANARY_BODY_7f3a9c2e"
        val fragment = "CANARY_STRUCTURED_9b2c4d6e"
        val remote = "remote-CANARY-REQ-1a2b3c4d"
        val script = fakeStreamScript {
            started(remote)
            text(body)
            structured(fragment)
            completed()
        }
        val adapter = FakeProviderAdapter(script, protocol)
        adapter.generate(profile(baseUrl = "https://CANARY-ENDPOINT.example/v1"), request()).collect { }

        val printed = listOf(
            adapter.toString(),
            adapter.stats.toString(),
            adapter.stats.snapshot().toString(),
            script.toString(),
        )
        printed.forEach { assertFalse(it.contains("CANARY"), "Leaked in: $it") }
        assertFalse(adapter.stats.snapshot().eventCounts.toString().contains("CANARY"))
    }

    @Test
    fun `identical script runs produce identical stats and events`() = runBlocking {
        suspend fun runOnce(): Pair<List<String>, FakeProviderCallStatsSnapshot> {
            val script = fakeStreamScript {
                wait(50)
                started("remote-1")
                text("deterministic")
                completed()
            }
            val adapter = FakeProviderAdapter(script, protocol, clock = VirtualFakeStreamClock())
            val events = mutableListOf<ProviderStreamEvent>()
            adapter.generate(profile(), request()).collect { events += it }
            return events.map { it::class.simpleName!! } to adapter.stats.snapshot()
        }

        val first = runOnce()
        val second = runOnce()
        assertEquals(first.first, second.first)
        assertEquals(first.second, second.second)
    }

    @Test
    fun `concurrent collections record thread safe aggregates`() = runBlocking {
        val stats = FakeProviderCallStats()
        val adapter = FakeProviderAdapter(
            fakeStreamScript { started(); text("a"); completed() },
            protocol,
            stats = stats,
        )
        coroutineScope {
            repeat(8) {
                launch {
                    adapter.generate(profile(), request()).collect { }
                }
            }
        }
        val snapshot = stats.snapshot()
        assertEquals(8L, snapshot.generateCalls)
        assertEquals(8L, snapshot.eventCounts[FakeProviderEventKind.STARTED])
        assertEquals(8L, snapshot.eventCounts[FakeProviderEventKind.TEXT])
        assertEquals(8L, snapshot.eventCounts[FakeProviderEventKind.COMPLETED])
        assertEquals(8L, snapshot.textCharacters)
    }
}
