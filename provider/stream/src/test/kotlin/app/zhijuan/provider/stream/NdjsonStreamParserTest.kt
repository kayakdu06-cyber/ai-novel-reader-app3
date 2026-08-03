package app.zhijuan.provider.stream

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.charset.StandardCharsets

class NdjsonStreamParserTest {
    @Test
    fun `every byte split preserves Chinese UTF-8 and record boundaries`() {
        val payload = "{\"text\":\"织卷\"}\n{\"done\":true}\n".utf8()
        val expected = listOf("{\"text\":\"织卷\"}", "{\"done\":true}")

        for (splitAt in 1 until payload.size) {
            val parser = NdjsonStreamParser()
            val output = buildList {
                addAll(parser.feed(payload.copyOfRange(0, splitAt)))
                addAll(parser.feed(payload.copyOfRange(splitAt, payload.size)))
                addAll(parser.finish())
            }
            assertEquals(expected, output.map(NdjsonRecord::json), "splitAt=$splitAt")
        }
    }

    @Test
    fun `final record does not require newline`() {
        val parser = NdjsonStreamParser()

        val output = buildList {
            addAll(parser.feed("{\"done\":true}".utf8()))
            addAll(parser.finish())
        }

        assertEquals(listOf("{\"done\":true}"), output.map(NdjsonRecord::json))
    }

    @Test
    fun `blank lines are ignored across all supported line endings`() {
        val parser = NdjsonStreamParser()

        val output = parser.feed("\r\n{\"a\":1}\r\r\n \n{\"b\":2}\n".utf8())

        assertEquals(listOf("{\"a\":1}", "{\"b\":2}"), output.map(NdjsonRecord::json))
    }

    @Test
    fun `UTF-8 BOM is ignored at stream start`() {
        val parser = NdjsonStreamParser()

        val output = parser.feed("\uFEFF{\"ok\":true}\n".utf8())

        assertEquals("{\"ok\":true}", output.single().json)
    }

    @Test
    fun `malformed UTF-8 fails explicitly`() {
        val parser = NdjsonStreamParser()

        assertThrows<MalformedStreamException> {
            parser.feed(byteArrayOf(0xC3.toByte(), 0x28, 0x0A))
        }
    }

    @Test
    fun `oversized line is rejected before unbounded buffering`() {
        val parser = NdjsonStreamParser(maxLineBytes = 4)

        assertThrows<MalformedStreamException> { parser.feed("12345".utf8()) }
    }

    @Test
    fun `parser cannot be reused after finish`() {
        val parser = NdjsonStreamParser()
        parser.finish()

        assertThrows<IllegalStateException> { parser.feed("{}\n".utf8()) }
        assertThrows<IllegalStateException> { parser.finish() }
    }

    private fun String.utf8(): ByteArray = toByteArray(StandardCharsets.UTF_8)
}
