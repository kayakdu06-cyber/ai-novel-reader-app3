package app.zhijuan.core.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

object EncryptedEnvelopeCodec {
    private const val MAGIC = 0x5A4A4553 // ZJES
    private const val CURRENT_VERSION = 1
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BYTES = 16
    private const val MAX_CIPHERTEXT_BYTES = 1_048_576

    fun encode(envelope: EncryptedEnvelope): ByteArray {
        require(envelope.version == CURRENT_VERSION) { "Unsupported envelope version." }
        require(envelope.initializationVector.size == GCM_IV_BYTES) {
            "AES-GCM initialization vector must contain $GCM_IV_BYTES bytes."
        }
        require(envelope.ciphertext.size in GCM_TAG_BYTES..MAX_CIPHERTEXT_BYTES) {
            "Ciphertext length is outside the allowed envelope range."
        }
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(MAGIC)
                output.writeByte(envelope.version)
                output.writeByte(envelope.initializationVector.size)
                output.writeInt(envelope.ciphertext.size)
                output.write(envelope.initializationVector)
                output.write(envelope.ciphertext)
            }
            bytes.toByteArray()
        }
    }

    fun decode(encoded: ByteArray): EncryptedEnvelope {
        require(encoded.size <= MAX_CIPHERTEXT_BYTES + 64) { "Encrypted envelope is too large." }
        return try {
            DataInputStream(ByteArrayInputStream(encoded)).use { input ->
                require(input.readInt() == MAGIC) { "Encrypted envelope magic is invalid." }
                val version = input.readUnsignedByte()
                require(version == CURRENT_VERSION) { "Unsupported envelope version: $version." }
                val ivLength = input.readUnsignedByte()
                require(ivLength == GCM_IV_BYTES) { "Encrypted envelope IV length is invalid." }
                val ciphertextLength = input.readInt()
                require(ciphertextLength in GCM_TAG_BYTES..MAX_CIPHERTEXT_BYTES) {
                    "Encrypted envelope ciphertext length is invalid."
                }
                val expectedRemaining = ivLength + ciphertextLength
                require(input.available() == expectedRemaining) {
                    "Encrypted envelope is truncated or contains trailing data."
                }
                val iv = ByteArray(ivLength).also(input::readFully)
                val ciphertext = ByteArray(ciphertextLength).also(input::readFully)
                EncryptedEnvelope(version, iv, ciphertext)
            }
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("Encrypted envelope is malformed.", error)
        }
    }
}
