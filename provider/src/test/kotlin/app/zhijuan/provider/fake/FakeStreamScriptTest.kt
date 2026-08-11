package app.zhijuan.provider.fake

import app.zhijuan.provider.common.ProviderFinishReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FakeStreamScriptTest {

    @Test
    fun `builder produces expected steps and metadata`() {
        val script = fakeStreamScript {
            wait(100)
            started("remote-1")
            text("hi")
            structured("{}")
            usage(inputTokens = 3, outputTokens = 2)
            heartbeat()
            completed(ProviderFinishReason.STOP)
        }
        assertEquals(7, script.steps.size)
        assertEquals(100L, script.totalVirtualMillis)
        assertTrue(script.hasTerminal)
        assertEquals(FakeStreamStep.Completed::class, script.steps.last()::class)
        assertEquals(script.steps, FakeStreamScript.from(script.steps).steps)
    }

    @Test
    fun `no-terminal script is explicit and allowed`() {
        val script = fakeStreamScript {
            started()
            text("partial")
            heartbeat()
        }
        assertFalse(script.hasTerminal)
        assertEquals(0L, script.totalVirtualMillis)
    }

    @Test
    fun `negative wait is rejected at step construction and by the builder`() {
        assertThrows<IllegalArgumentException> { FakeStreamStep.Wait(-1L) }
        assertThrows<IllegalArgumentException> { fakeStreamScript { wait(-1L) } }
    }

    @Test
    fun `overflowing virtual time is rejected`() {
        assertThrows<IllegalArgumentException> {
            FakeStreamScript.of(
                FakeStreamStep.Wait(Long.MAX_VALUE),
                FakeStreamStep.Wait(1L),
            )
        }
    }

    @Test
    fun `overflowing provider usage total is rejected before execution`() {
        assertThrows<IllegalArgumentException> {
            FakeStreamStep.Usage(inputTokens = Long.MAX_VALUE, outputTokens = 1L)
        }
    }

    @Test
    fun `a second terminal step is rejected`() {
        assertThrows<IllegalArgumentException> {
            FakeStreamScript.of(FakeStreamStep.Completed(), FakeStreamStep.Refused())
        }
    }

    @Test
    fun `steps after the terminal step are rejected at construction`() {
        assertThrows<IllegalArgumentException> {
            FakeStreamScript.of(FakeStreamStep.Completed(), FakeStreamStep.Text("late"))
        }
    }

    @Test
    fun `content before Started is rejected`() {
        assertThrows<IllegalArgumentException> {
            FakeStreamScript.of(FakeStreamStep.Text("early"))
        }
        assertThrows<IllegalArgumentException> {
            FakeStreamScript.of(FakeStreamStep.Heartbeat)
        }
    }

    @Test
    fun `duplicate Started is rejected`() {
        assertThrows<IllegalArgumentException> {
            FakeStreamScript.of(FakeStreamStep.Started(), FakeStreamStep.Started())
        }
    }

    @Test
    fun `default strings redact text fragments and remote request ids`() {
        val body = "CANARY_BODY_7f3a9c2e"
        val fragment = "CANARY_STRUCTURED_9b2c4d6e"
        val remote = "remote-CANARY-REQ-1a2b3c4d"
        val script = fakeStreamScript {
            started(remote)
            text(body)
            structured(fragment)
            completed()
        }
        assertFalse(script.toString().contains("CANARY"))
        assertFalse(FakeStreamStep.Text(body).toString().contains(body))
        assertFalse(FakeStreamStep.Structured(fragment).toString().contains(fragment))
        assertFalse(FakeStreamStep.Started(remote).toString().contains(remote))
    }
}
