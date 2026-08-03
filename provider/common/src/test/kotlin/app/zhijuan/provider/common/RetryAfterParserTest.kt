package app.zhijuan.provider.common

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RetryAfterParserTest {
    @Test
    fun `delta seconds are parsed and capped to one day`() {
        assertEquals(9_000, RetryAfterParser.parse("9", NOW))
        assertEquals(RetryAfterParser.MAXIMUM_RETRY_AFTER_MILLIS, RetryAfterParser.parse("999999", NOW))
    }

    @Test
    fun `http date is converted to a delay using the supplied clock`() {
        assertEquals(
            120_000,
            RetryAfterParser.parse("Wed, 21 Oct 2015 07:30:00 GMT", NOW),
        )
    }

    @Test
    fun `past dates request an immediate retry`() {
        assertEquals(0, RetryAfterParser.parse("Wed, 21 Oct 2015 07:27:00 GMT", NOW))
    }

    @Test
    fun `invalid negative and control values are ignored`() {
        assertNull(RetryAfterParser.parse("-1", NOW))
        assertNull(RetryAfterParser.parse("soon", NOW))
        assertNull(RetryAfterParser.parse("5\nX-Test: value", NOW))
    }

    private companion object {
        val NOW = Instant.parse("2015-10-21T07:28:00Z").toEpochMilli()
    }
}
