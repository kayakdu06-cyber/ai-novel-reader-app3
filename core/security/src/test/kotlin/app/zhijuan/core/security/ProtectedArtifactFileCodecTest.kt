package app.zhijuan.core.security

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class ProtectedArtifactFileCodecTest {
    private val cipher = TestAead()

    @Test
    fun chunkedRoundTripUsesBoundedInputReads() {
        val plaintext = ByteArray(ProtectedArtifactFileCodec.DEFAULT_CHUNK_BYTES * 3 + 17) { index ->
            (index * 31).toByte()
        }
        val source = TrackingInputStream(plaintext)
        val encrypted = ByteArrayOutputStream()

        val write = ProtectedArtifactFileCodec.write(header(), source, encrypted, cipher)
        val restored = ByteArrayOutputStream()
        val read = ProtectedArtifactFileCodec.read(
            ByteArrayInputStream(encrypted.toByteArray()),
            restored,
            cipher,
        )

        assertEquals(plaintext.size.toLong(), write.plaintextBytes)
        assertEquals(plaintext.size.toLong(), read.plaintextBytes)
        assertEquals(ProtectedArtifactFileCodec.DEFAULT_CHUNK_BYTES, source.maximumRequestedBytes)
        assertArrayEquals(plaintext, restored.toByteArray())
    }

    @Test
    fun emptyArtifactRoundTripsWithAuthenticatedTrailer() {
        val encrypted = ByteArrayOutputStream()
        ProtectedArtifactFileCodec.write(
            header(),
            ByteArrayInputStream(ByteArray(0)),
            encrypted,
            cipher,
        )

        val restored = ByteArrayOutputStream()
        val summary = ProtectedArtifactFileCodec.read(
            ByteArrayInputStream(encrypted.toByteArray()),
            restored,
            cipher,
        )

        assertEquals(0L, summary.plaintextBytes)
        assertEquals(0, restored.size())
    }

    @Test
    fun ciphertextTamperingAndTruncationFailAuthentication() {
        val encoded = encode(ByteArray(150_000) { index -> (index xor 0x5a).toByte() })
        val tampered = encoded.copyOf().also { bytes ->
            bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
        }
        val truncated = encoded.copyOf(encoded.size - 1)

        assertThrows(Exception::class.java) { decode(tampered) }
        assertThrows(Exception::class.java) { decode(truncated) }
    }

    @Test
    fun metadataTamperingAndTrailingBytesAreRejected() {
        val encoded = encode("受保护的草稿".toByteArray())
        val headerTampered = encoded.copyOf().also { bytes ->
            bytes[6] = 2 // Valid recovery-point type, but no longer matches header authentication.
        }
        val withTrailingBytes = encoded + byteArrayOf(1, 2, 3)

        assertThrows(Exception::class.java) { decode(headerTampered) }
        assertThrows(Exception::class.java) { decode(withTrailingBytes) }
    }

    private fun encode(plaintext: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
        ProtectedArtifactFileCodec.write(
            header(),
            ByteArrayInputStream(plaintext),
            output,
            cipher,
        )
        output.toByteArray()
    }

    private fun decode(encrypted: ByteArray) {
        ProtectedArtifactFileCodec.read(
            ByteArrayInputStream(encrypted),
            ByteArrayOutputStream(),
            cipher,
        )
    }

    private fun header() = ProtectedArtifactHeader(
        ProtectedArtifactDescriptor(
            artifactRefId = "12345678-1234-4234-9234-123456789abc",
            type = ProtectedArtifactType.STREAM_DRAFT,
            revision = 1,
            keyVersion = 1,
            createdAt = 10,
            updatedAt = 10,
        ),
    )
}

private class TrackingInputStream(
    bytes: ByteArray,
) : ByteArrayInputStream(bytes) {
    var maximumRequestedBytes: Int = 0
        private set

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        maximumRequestedBytes = maxOf(maximumRequestedBytes, length)
        return super.read(target, offset, length)
    }
}

private class TestAead : ArtifactAead {
    private val key = SecretKeySpec(ByteArray(32) { index -> (index + 1).toByte() }, "AES")
    private val random = SecureRandom()

    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): EncryptedEnvelope {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(associatedData)
        return EncryptedEnvelope(1, iv, cipher.doFinal(plaintext))
    }

    override fun decrypt(envelope: EncryptedEnvelope, associatedData: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, envelope.initializationVector))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(envelope.ciphertext)
    }
}
