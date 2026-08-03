package app.zhijuan.core.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EncryptedEnvelopeCodecTest {
    private val sample = EncryptedEnvelope(
        version = 1,
        initializationVector = ByteArray(12) { it.toByte() },
        ciphertext = ByteArray(48) { (it * 3).toByte() },
    )

    @Test
    fun `round trip preserves envelope bytes`() {
        assertEquals(sample, EncryptedEnvelopeCodec.decode(EncryptedEnvelopeCodec.encode(sample)))
    }

    @Test
    fun `wrong magic is rejected`() {
        val encoded = EncryptedEnvelopeCodec.encode(sample)
        encoded[0] = 0

        assertThrows<IllegalArgumentException> { EncryptedEnvelopeCodec.decode(encoded) }
    }

    @Test
    fun `truncation is rejected`() {
        val encoded = EncryptedEnvelopeCodec.encode(sample)

        assertThrows<IllegalArgumentException> {
            EncryptedEnvelopeCodec.decode(encoded.copyOf(encoded.size - 1))
        }
    }

    @Test
    fun `trailing bytes are rejected`() {
        val encoded = EncryptedEnvelopeCodec.encode(sample) + byteArrayOf(1)

        assertThrows<IllegalArgumentException> { EncryptedEnvelopeCodec.decode(encoded) }
    }

    @Test
    fun `decoded arrays are independent from encoded bytes`() {
        val encoded = EncryptedEnvelopeCodec.encode(sample)
        val decoded = EncryptedEnvelopeCodec.decode(encoded)
        encoded.fill(0)

        assertTrue(decoded.initializationVector.any { it.toInt() != 0 })
        assertTrue(decoded.ciphertext.any { it.toInt() != 0 })
    }
}
