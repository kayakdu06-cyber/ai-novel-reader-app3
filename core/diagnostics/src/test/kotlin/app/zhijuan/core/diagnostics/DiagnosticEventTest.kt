package app.zhijuan.core.diagnostics

import app.zhijuan.core.model.StandardErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiagnosticEventTest {
    private val factory = DiagnosticEventFactory { EVENT_ID }

    @Test
    fun arbitraryCorrelationValuesAreHashedAndNeverEncodedVerbatim() {
        val event = factory.create(
            timestampEpochMillis = 10,
            severity = DiagnosticSeverity.ERROR,
            category = DiagnosticCategory.NETWORK,
            code = DiagnosticCode.REQUEST_FAILED,
            operation = DiagnosticOperation.GENERATION_REQUEST,
            standardErrorCode = StandardErrorCode.AUTH_FAILED,
            protocol = DiagnosticProtocol.OPENAI_CHAT_COMPATIBLE,
            httpStatus = 401,
            retryable = false,
            correlations = mapOf(
                DiagnosticCorrelationKind.CONNECTION to SECRET_CANARY,
                DiagnosticCorrelationKind.BOOK to NOVEL_CANARY,
            ),
        )

        val encoded = DiagnosticEventCodec.encode(listOf(event))
        assertFalse(encoded.containsSubsequence(SECRET_CANARY.toByteArray()))
        assertFalse(encoded.containsSubsequence(NOVEL_CANARY.toByteArray()))
        assertEquals(2, event.correlationHashes.size)
        assertTrue(event.correlationHashes.values.all { it.matches(Regex("[0-9a-f]{24}")) })
        assertNotEquals(
            event.correlationHashes[DiagnosticCorrelationKind.CONNECTION],
            event.correlationHashes[DiagnosticCorrelationKind.BOOK],
        )
    }

    @Test
    fun throwableMessagesAndStackTextAreNotPartOfTheEvent() {
        val cause = IllegalArgumentException("cause $SECRET_CANARY")
        val error = IllegalStateException("top $NOVEL_CANARY", cause)
        error.addSuppressed(UnsupportedOperationException("suppressed $SECRET_CANARY"))

        val event = factory.create(
            timestampEpochMillis = 20,
            severity = DiagnosticSeverity.ERROR,
            category = DiagnosticCategory.APPLICATION,
            code = DiagnosticCode.UNEXPECTED_FAILURE,
            operation = DiagnosticOperation.STARTUP,
            error = error,
        )
        val encoded = DiagnosticEventCodec.encode(listOf(event))

        assertEquals(
            listOf(
                IllegalStateException::class.java.name,
                IllegalArgumentException::class.java.name,
                UnsupportedOperationException::class.java.name,
            ),
            event.errorTypes,
        )
        assertFalse(encoded.containsSubsequence(SECRET_CANARY.toByteArray()))
        assertFalse(encoded.containsSubsequence(NOVEL_CANARY.toByteArray()))
    }

    @Test
    fun codecRoundTripsOnlyTheStructuredContract() {
        val event = factory.create(
            timestampEpochMillis = 30,
            severity = DiagnosticSeverity.WARNING,
            category = DiagnosticCategory.STORAGE,
            code = DiagnosticCode.DATABASE_OPEN_FAILED,
            operation = DiagnosticOperation.DATABASE_OPEN,
            elapsedMillis = 125,
            androidApiLevel = 35,
            retryable = true,
            correlations = mapOf(DiagnosticCorrelationKind.JOB to "job-1"),
        )

        assertEquals(listOf(event), DiagnosticEventCodec.decode(DiagnosticEventCodec.encode(listOf(event))))
    }

    @Test
    fun codecRejectsTrailingOrMalformedData() {
        val event = factory.create(
            timestampEpochMillis = 40,
            severity = DiagnosticSeverity.INFO,
            category = DiagnosticCategory.APPLICATION,
            code = DiagnosticCode.APP_STARTED,
            operation = DiagnosticOperation.STARTUP,
        )
        val valid = DiagnosticEventCodec.encode(listOf(event))
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticEventCodec.decode(valid + byteArrayOf(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticEventCodec.decode(byteArrayOf(1, 2, 3))
        }
    }

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        return indices.any { start ->
            start + needle.size <= size && needle.indices.all { offset ->
                this[start + offset] == needle[offset]
            }
        }
    }

    private companion object {
        const val EVENT_ID = "123e4567-e89b-42d3-a456-426614174000"
        const val SECRET_CANARY = "ZHIJUAN_SENSITIVE_VALUE_CANARY_018"
        const val NOVEL_CANARY = "ZHIJUAN_DIAGNOSTIC_NOVEL_TEXT_CANARY_018"
    }
}
