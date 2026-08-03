package app.zhijuan.core.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SecretRecordCodecTest {
    @Test
    fun `active record round trip preserves descriptor and envelope`() {
        val record = activeRecord()

        assertEquals(record, SecretRecordCodec.decode(SecretRecordCodec.encode(record)))
    }

    @Test
    fun `revoked tombstone contains no encrypted envelope`() {
        val descriptor = activeRecord().descriptor.copy(
            lastFour = "",
            state = SecretRecordState.REVOKED,
            updatedAt = 20,
            lastUsedAt = null,
        )
        val decoded = SecretRecordCodec.decode(
            SecretRecordCodec.encode(StoredSecretRecord(descriptor, envelope = null)),
        )

        assertEquals(SecretRecordState.REVOKED, decoded.descriptor.state)
        assertNull(decoded.envelope)
    }

    @Test
    fun `active descriptor without envelope is rejected`() {
        assertThrows<IllegalArgumentException> {
            SecretRecordCodec.encode(StoredSecretRecord(activeRecord().descriptor, envelope = null))
        }
    }

    @Test
    fun `trailing bytes and malformed identity are rejected`() {
        val encoded = SecretRecordCodec.encode(activeRecord())
        assertThrows<IllegalArgumentException> { SecretRecordCodec.decode(encoded + byteArrayOf(1)) }
        assertThrows<IllegalArgumentException> {
            SecretRecordCodec.encode(
                activeRecord().copy(
                    descriptor = activeRecord().descriptor.copy(secretRefId = "not-a-random-reference"),
                ),
            )
        }
    }

    private fun activeRecord() = StoredSecretRecord(
        descriptor = SecretDescriptor(
            secretRefId = "123e4567-e89b-42d3-a456-426614174000",
            purpose = SecretPurpose.API_KEY,
            lastFour = "wxyz",
            state = SecretRecordState.ACTIVE,
            keyVersion = 1,
            createdAt = 10,
            updatedAt = 10,
            lastUsedAt = null,
        ),
        envelope = EncryptedEnvelope(
            version = 1,
            initializationVector = ByteArray(12) { it.toByte() },
            ciphertext = ByteArray(32) { (it * 7).toByte() },
        ),
    )
}
