package app.zhijuan.core.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

internal object SecretRecordCodec {
    const val MAX_RECORD_BYTES = 65_536
    private const val MAGIC = 0x5A4A5352 // ZJSR
    private const val VERSION = 1
    private const val MAX_TEXT_BYTES = 256
    private const val MAX_ENVELOPE_BYTES = 32_768

    fun encode(record: StoredSecretRecord): ByteArray {
        validate(record)
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(MAGIC)
                output.writeByte(VERSION)
                writeText(output, record.descriptor.secretRefId)
                writeText(output, record.descriptor.purpose.name)
                writeText(output, record.descriptor.lastFour)
                writeText(output, record.descriptor.state.name)
                output.writeInt(record.descriptor.keyVersion)
                output.writeLong(record.descriptor.createdAt)
                output.writeLong(record.descriptor.updatedAt)
                output.writeLong(record.descriptor.lastUsedAt ?: -1L)
                val envelopeBytes = record.envelope?.let(EncryptedEnvelopeCodec::encode) ?: ByteArray(0)
                output.writeInt(envelopeBytes.size)
                output.write(envelopeBytes)
            }
            bytes.toByteArray().also { require(it.size <= MAX_RECORD_BYTES) }
        }
    }

    fun decode(encoded: ByteArray): StoredSecretRecord {
        require(encoded.size <= MAX_RECORD_BYTES) { "Secret record is too large." }
        return try {
            DataInputStream(ByteArrayInputStream(encoded)).use { input ->
                require(input.readInt() == MAGIC) { "Secret record magic is invalid." }
                require(input.readUnsignedByte() == VERSION) { "Secret record version is unsupported." }
                val descriptor = SecretDescriptor(
                    secretRefId = readText(input),
                    purpose = enumValueOf(readText(input)),
                    lastFour = readText(input),
                    state = enumValueOf(readText(input)),
                    keyVersion = input.readInt(),
                    createdAt = input.readLong(),
                    updatedAt = input.readLong(),
                    lastUsedAt = input.readLong().takeIf { it >= 0L },
                )
                val envelopeLength = input.readInt()
                require(envelopeLength in 0..MAX_ENVELOPE_BYTES)
                require(input.available() == envelopeLength) { "Secret record is truncated or has trailing data." }
                val envelope = if (envelopeLength == 0) {
                    null
                } else {
                    EncryptedEnvelopeCodec.decode(ByteArray(envelopeLength).also(input::readFully))
                }
                StoredSecretRecord(descriptor, envelope).also(::validate)
            }
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("Secret record is malformed.", error)
        }
    }

    private fun validate(record: StoredSecretRecord) {
        val descriptor = record.descriptor
        require(descriptor.secretRefId.matches(SECRET_REF_PATTERN))
        require(descriptor.lastFour.length <= 4)
        require(descriptor.keyVersion > 0)
        require(descriptor.createdAt >= 0 && descriptor.updatedAt >= descriptor.createdAt)
        require(descriptor.lastUsedAt == null || descriptor.lastUsedAt >= descriptor.createdAt)
        require((descriptor.state == SecretRecordState.ACTIVE) == (record.envelope != null))
    }

    private fun writeText(output: DataOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_TEXT_BYTES)
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readText(input: DataInputStream): String {
        val length = input.readInt()
        require(length in 0..MAX_TEXT_BYTES)
        require(input.available() >= length)
        return String(ByteArray(length).also(input::readFully), StandardCharsets.UTF_8)
    }

    internal val SECRET_REF_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
}
