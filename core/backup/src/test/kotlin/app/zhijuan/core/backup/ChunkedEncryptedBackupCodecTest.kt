package app.zhijuan.core.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChunkedEncryptedBackupCodecTest {
    private val codec = testCodec()
    private val parameters = BackupWriteParameters(pbkdf2Iterations = 1_000, chunkBytes = 64 * 1024)

    @Test
    fun `round trip is authenticated randomized and preserves unicode bytes`() {
        val plaintext = ("长安风雪与章节正文。".repeat(20_000)).encodeToByteArray()
        val first = encrypt(plaintext)
        val second = encrypt(plaintext)

        assertFalse(first.contentEquals(second))
        assertFalse(first.toString(Charsets.UTF_8).contains("长安风雪"))
        assertArrayEquals(plaintext, decrypt(first))
        assertArrayEquals(plaintext, decrypt(second))
    }

    @Test
    fun `wrong password is rejected`() {
        val encrypted = encrypt("private library".encodeToByteArray())

        assertThrows(BackupAuthenticationException::class.java) {
            codec.decrypt(
                ByteArrayInputStream(encrypted),
                ByteArrayOutputStream(),
                "wrong password".toCharArray(),
            )
        }
    }

    @Test
    fun `header and ciphertext tampering are rejected`() {
        val encrypted = encrypt(ByteArray(200_000) { (it % 251).toByte() })
        val tamperedHeader = encrypted.copyOf().also { bytes -> bytes[51] = (bytes[51].toInt() xor 1).toByte() }
        val tamperedCiphertext = encrypted.copyOf().also { bytes -> bytes[bytes.lastIndex - 3] = (bytes[bytes.lastIndex - 3].toInt() xor 1).toByte() }

        assertThrows(BackupAuthenticationException::class.java) { decrypt(tamperedHeader) }
        assertThrows(BackupAuthenticationException::class.java) { decrypt(tamperedCiphertext) }
    }

    @Test
    fun `truncation and trailing bytes are rejected`() {
        val encrypted = encrypt(ByteArray(150_000) { 7 })

        assertThrows(BackupFormatException::class.java) { decrypt(encrypted.copyOf(encrypted.size - 5)) }
        assertThrows(BackupFormatException::class.java) { decrypt(encrypted + byteArrayOf(1)) }
    }

    @Test
    fun `unsafe header limits are rejected before allocation`() {
        val encrypted = encrypt("small".encodeToByteArray())
        ByteBuffer.wrap(encrypted).putInt(16, MAXIMUM_CHUNK_BYTES + 1)

        assertThrows(BackupFormatException::class.java) { decrypt(encrypted) }
    }

    @Test
    fun `zero length backup has an authenticated frame`() {
        val encrypted = encrypt(byteArrayOf())

        assertArrayEquals(byteArrayOf(), decrypt(encrypted))
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertThrows(BackupAuthenticationException::class.java) { decrypt(encrypted) }
    }

    @Test
    fun `declared source length must match the stream`() {
        assertThrows(BackupFormatException::class.java) {
            codec.encrypt(
                ByteArrayInputStream(byteArrayOf(1, 2, 3)),
                2,
                ByteArrayOutputStream(),
                PASSWORD,
                parameters,
            )
        }
    }

    @Test
    fun `large payload is consumed in bounded chunks`() {
        val source = CountingPatternInputStream(64L * 1024L * 1024L)
        val summary = codec.encrypt(
            source,
            source.remaining,
            DiscardOutputStream,
            PASSWORD,
            parameters,
        )

        assertEquals(64L * 1024L * 1024L, summary.plaintextBytes)
        assertTrue(source.maximumRequestedBytes <= parameters.chunkBytes)
    }

    private fun encrypt(plaintext: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
        codec.encrypt(ByteArrayInputStream(plaintext), plaintext.size.toLong(), output, PASSWORD, parameters)
        output.toByteArray()
    }

    private fun decrypt(encrypted: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
        codec.decrypt(ByteArrayInputStream(encrypted), output, PASSWORD)
        output.toByteArray()
    }

    private class CountingPatternInputStream(
        val remaining: Long,
    ) : InputStream() {
        private var bytesLeft = remaining
        var maximumRequestedBytes: Int = 0
            private set

        override fun read(): Int = if (bytesLeft-- > 0) 0x5A else -1

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            maximumRequestedBytes = maxOf(maximumRequestedBytes, length)
            if (bytesLeft <= 0) return -1
            val count = minOf(bytesLeft, length.toLong()).toInt()
            bytes.fill(0x5A, offset, offset + count)
            bytesLeft -= count
            return count
        }
    }

    private object DiscardOutputStream : OutputStream() {
        override fun write(value: Int) = Unit

        override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
    }

    private companion object {
        val PASSWORD = "correct horse battery staple".toCharArray()

        fun testCodec(): ChunkedEncryptedBackupCodec = ChunkedEncryptedBackupCodec(
            policy = BackupCryptoPolicy(minimumPbkdf2Iterations = 1_000),
            secureRandom = SecureRandom(),
        )
    }
}
