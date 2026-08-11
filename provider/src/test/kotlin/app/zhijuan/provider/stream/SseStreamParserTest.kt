package app.zhijuan.provider.stream

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.charset.StandardCharsets

class SseStreamParserTest {
    @Test
    fun `every byte split preserves Chinese UTF-8 text`() {
        val payload = "data: {\"delta\":\"织卷开始\"}\n\n".toByteArray(StandardCharsets.UTF_8)

        for (splitAt in 1 until payload.size) {
            val parser = SseStreamParser()
            val output = buildList {
                addAll(parser.feed(payload.copyOfRange(0, splitAt)))
                addAll(parser.feed(payload.copyOfRange(splitAt, payload.size)))
                addAll(parser.finish())
            }
            assertEquals(
                listOf(
                    SseItem.Event(
                        SseEvent(
                            event = "message",
                            data = "{\"delta\":\"织卷开始\"}",
                            id = null,
                            retryMillis = null,
                        ),
                    ),
                ),
                output,
                "splitAt=$splitAt",
            )
        }
    }

    @Test
    fun `one chunk may contain multiple events`() {
        val parser = SseStreamParser()

        val output = parser.feed("data: first\n\ndata: second\n\n".utf8())

        assertEquals(
            listOf("first", "second"),
            output.filterIsInstance<SseItem.Event>().map { it.value.data },
        )
    }

    @Test
    fun `CRLF fields join multiple data lines and retain controls`() {
        val parser = SseStreamParser()

        val output = parser.feed(
            "id: chapter-8\r\nevent: delta\r\nretry: 2500\r\ndata: 第一行\r\ndata: 第二行\r\n\r\n".utf8(),
        )

        assertEquals(
            listOf(
                SseItem.Event(
                    SseEvent(
                        event = "delta",
                        data = "第一行\n第二行",
                        id = "chapter-8",
                        retryMillis = 2500,
                    ),
                ),
            ),
            output,
        )
    }

    @Test
    fun `comments are emitted as heartbeats while unknown fields are ignored`() {
        val parser = SseStreamParser()

        val output = parser.feed(": keep-alive\nunknown: value\ndata: ok\n\n".utf8())

        assertEquals(SseItem.Comment("keep-alive"), output.first())
        assertEquals("ok", (output.last() as SseItem.Event).value.data)
    }

    @Test
    fun `event id and retry persist across dispatched events`() {
        val parser = SseStreamParser()

        val output = parser.feed("id: 7\nretry: 1000\ndata: a\n\ndata: b\n\n".utf8())
            .filterIsInstance<SseItem.Event>()

        assertTrue(output.all { it.value.id == "7" })
        assertTrue(output.all { it.value.retryMillis == 1000L })
    }

    @Test
    fun `isolated carriage return is a valid line ending`() {
        val parser = SseStreamParser()

        val output = parser.feed("data: one\r\rdata: two\r\r".utf8())

        assertEquals(
            listOf("one", "two"),
            output.filterIsInstance<SseItem.Event>().map { it.value.data },
        )
    }

    @Test
    fun `UTF-8 BOM is ignored at stream start`() {
        val parser = SseStreamParser()

        val output = parser.feed("\uFEFFdata: ok\n\n".utf8())

        assertEquals("ok", (output.single() as SseItem.Event).value.data)
    }

    @Test
    fun `unfinished SSE event is discarded at EOF`() {
        val parser = SseStreamParser()

        val output = buildList {
            addAll(parser.feed("data: incomplete".utf8()))
            addAll(parser.finish())
        }

        assertTrue(output.isEmpty())
    }

    @Test
    fun `malformed UTF-8 fails explicitly`() {
        val parser = SseStreamParser()
        val invalid = byteArrayOf(
            'd'.code.toByte(),
            'a'.code.toByte(),
            't'.code.toByte(),
            'a'.code.toByte(),
            ':'.code.toByte(),
            ' '.code.toByte(),
            0xC3.toByte(),
            0x28,
            0x0A,
        )

        assertThrows<MalformedStreamException> { parser.feed(invalid) }
    }

    @Test
    fun `oversized line is rejected before unbounded buffering`() {
        val parser = SseStreamParser(maxLineBytes = 4)

        assertThrows<MalformedStreamException> { parser.feed("12345".utf8()) }
    }

    @Test
    fun `parser cannot be reused after finish`() {
        val parser = SseStreamParser()
        parser.finish()

        assertThrows<IllegalStateException> { parser.feed("data: late\n\n".utf8()) }
        assertThrows<IllegalStateException> { parser.finish() }
    }

    private fun String.utf8(): ByteArray = toByteArray(StandardCharsets.UTF_8)
}
