package app.zhijuan.core.backup

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

data class BackupCryptoPolicy(
    val minimumPbkdf2Iterations: Int = DEFAULT_PBKDF2_ITERATIONS,
    val maximumPbkdf2Iterations: Int = MAXIMUM_PBKDF2_ITERATIONS,
    val maximumChunkBytes: Int = MAXIMUM_CHUNK_BYTES,
    val maximumPlaintextBytes: Long = MAXIMUM_PLAINTEXT_BYTES,
) {
    init {
        require(minimumPbkdf2Iterations > 0)
        require(maximumPbkdf2Iterations >= minimumPbkdf2Iterations)
        require(maximumChunkBytes >= MINIMUM_CHUNK_BYTES)
        require(maximumPlaintextBytes >= 0)
    }
}

data class BackupWriteParameters(
    val pbkdf2Iterations: Int = DEFAULT_PBKDF2_ITERATIONS,
    val chunkBytes: Int = DEFAULT_CHUNK_BYTES,
)

data class BackupTransferSummary(
    val plaintextBytes: Long,
    val plaintextSha256: String,
    val chunkCount: Int,
    val pbkdf2Iterations: Int,
)

class ChunkedEncryptedBackupCodec(
    private val policy: BackupCryptoPolicy = BackupCryptoPolicy(),
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun encrypt(
        input: InputStream,
        plaintextLength: Long,
        output: OutputStream,
        passphrase: CharArray,
        parameters: BackupWriteParameters = BackupWriteParameters(),
    ): BackupTransferSummary {
        validateParameters(plaintextLength, parameters.pbkdf2Iterations, parameters.chunkBytes)
        val salt = ByteArray(SALT_BYTES).also(secureRandom::nextBytes)
        val noncePrefix = ByteArray(NONCE_PREFIX_BYTES).also(secureRandom::nextBytes)
        val header = encodeHeader(
            Header(
                pbkdf2Iterations = parameters.pbkdf2Iterations,
                chunkBytes = parameters.chunkBytes,
                plaintextBytes = plaintextLength,
                salt = salt,
                noncePrefix = noncePrefix,
            ),
        )
        val key = deriveKey(passphrase, salt, parameters.pbkdf2Iterations)
        val digest = MessageDigest.getInstance(SHA_256)
        val dataOutput = DataOutputStream(output)
        dataOutput.write(header)

        var remaining = plaintextLength
        val chunkCount = expectedChunkCount(plaintextLength, parameters.chunkBytes)
        repeat(chunkCount) { chunkIndex ->
            val plaintextBytes = expectedPlaintextBytes(remaining, parameters.chunkBytes, plaintextLength == 0L)
            val plaintext = ByteArray(plaintextBytes)
            try {
                readExactly(input, plaintext)
                digest.update(plaintext)
                val ciphertext = encryptChunk(
                    key = key,
                    nonce = nonce(noncePrefix, chunkIndex),
                    aad = aad(header, chunkIndex, plaintextBytes),
                    plaintext = plaintext,
                )
                dataOutput.writeInt(ciphertext.size)
                dataOutput.write(ciphertext)
                ciphertext.fill(0)
                remaining -= plaintextBytes
            } finally {
                plaintext.fill(0)
            }
        }

        if (remaining != 0L || input.read() != -1) {
            throw BackupFormatException("Input length did not match the declared plaintext length.")
        }
        dataOutput.flush()
        return BackupTransferSummary(
            plaintextBytes = plaintextLength,
            plaintextSha256 = digest.digest().toHex(),
            chunkCount = chunkCount,
            pbkdf2Iterations = parameters.pbkdf2Iterations,
        )
    }

    fun decrypt(
        input: InputStream,
        output: OutputStream,
        passphrase: CharArray,
    ): BackupTransferSummary {
        val headerBytes = ByteArray(HEADER_BYTES)
        readExactly(input, headerBytes)
        val header = decodeAndValidateHeader(headerBytes)
        val key = deriveKey(passphrase, header.salt, header.pbkdf2Iterations)
        val digest = MessageDigest.getInstance(SHA_256)
        val dataInput = DataInputStream(input)
        var remaining = header.plaintextBytes
        val chunkCount = expectedChunkCount(header.plaintextBytes, header.chunkBytes)

        repeat(chunkCount) { chunkIndex ->
            val plaintextBytes = expectedPlaintextBytes(remaining, header.chunkBytes, header.plaintextBytes == 0L)
            val expectedCiphertextBytes = plaintextBytes + GCM_TAG_BYTES
            val declaredCiphertextBytes = try {
                dataInput.readInt()
            } catch (error: EOFException) {
                throw BackupFormatException("Backup ended before chunk $chunkIndex.", error)
            }
            if (declaredCiphertextBytes != expectedCiphertextBytes) {
                throw BackupFormatException(
                    "Chunk $chunkIndex has invalid ciphertext length $declaredCiphertextBytes.",
                )
            }
            val ciphertext = ByteArray(declaredCiphertextBytes)
            readExactly(input, ciphertext)
            val plaintext = try {
                decryptChunk(
                    key = key,
                    nonce = nonce(header.noncePrefix, chunkIndex),
                    aad = aad(headerBytes, chunkIndex, plaintextBytes),
                    ciphertext = ciphertext,
                )
            } catch (error: AEADBadTagException) {
                throw BackupAuthenticationException(
                    "Backup authentication failed. The password or backup contents are invalid.",
                    error,
                )
            } finally {
                ciphertext.fill(0)
            }
            try {
                if (plaintext.size != plaintextBytes) {
                    throw BackupFormatException("Chunk $chunkIndex decrypted to an invalid length.")
                }
                output.write(plaintext)
                digest.update(plaintext)
                remaining -= plaintextBytes
            } finally {
                plaintext.fill(0)
            }
        }

        if (remaining != 0L) {
            throw BackupFormatException("Backup plaintext length is incomplete.")
        }
        if (input.read() != -1) {
            throw BackupFormatException("Backup contains trailing bytes.")
        }
        output.flush()
        return BackupTransferSummary(
            plaintextBytes = header.plaintextBytes,
            plaintextSha256 = digest.digest().toHex(),
            chunkCount = chunkCount,
            pbkdf2Iterations = header.pbkdf2Iterations,
        )
    }

    private fun validateParameters(plaintextBytes: Long, iterations: Int, chunkBytes: Int) {
        if (plaintextBytes !in 0..policy.maximumPlaintextBytes) {
            throw BackupFormatException("Plaintext length is outside the supported range.")
        }
        if (iterations !in policy.minimumPbkdf2Iterations..policy.maximumPbkdf2Iterations) {
            throw BackupFormatException("PBKDF2 work factor is outside the supported range.")
        }
        if (chunkBytes !in MINIMUM_CHUNK_BYTES..policy.maximumChunkBytes) {
            throw BackupFormatException("Chunk size is outside the supported range.")
        }
        expectedChunkCount(plaintextBytes, chunkBytes)
    }

    private fun decodeAndValidateHeader(bytes: ByteArray): Header {
        val buffer = ByteBuffer.wrap(bytes)
        val magic = ByteArray(MAGIC.size).also(buffer::get)
        if (!magic.contentEquals(MAGIC)) {
            throw BackupFormatException("Backup magic is invalid.")
        }
        val version = buffer.short.toInt()
        if (version != FORMAT_VERSION) {
            throw BackupFormatException("Unsupported backup format version $version.")
        }
        if (buffer.get().toInt() != KDF_PBKDF2_SHA256) {
            throw BackupFormatException("Unsupported backup KDF.")
        }
        if (buffer.get().toInt() != CIPHER_AES_256_GCM) {
            throw BackupFormatException("Unsupported backup cipher.")
        }
        val header = Header(
            pbkdf2Iterations = buffer.int,
            chunkBytes = buffer.int,
            plaintextBytes = buffer.long,
            salt = ByteArray(SALT_BYTES).also(buffer::get),
            noncePrefix = ByteArray(NONCE_PREFIX_BYTES).also(buffer::get),
        )
        validateParameters(header.plaintextBytes, header.pbkdf2Iterations, header.chunkBytes)
        return header
    }

    private fun encodeHeader(header: Header): ByteArray = ByteBuffer.allocate(HEADER_BYTES)
        .put(MAGIC)
        .putShort(FORMAT_VERSION.toShort())
        .put(KDF_PBKDF2_SHA256.toByte())
        .put(CIPHER_AES_256_GCM.toByte())
        .putInt(header.pbkdf2Iterations)
        .putInt(header.chunkBytes)
        .putLong(header.plaintextBytes)
        .put(header.salt)
        .put(header.noncePrefix)
        .array()

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val specification = PBEKeySpec(passphrase, salt, iterations, AES_KEY_BITS)
        return try {
            val encoded = SecretKeyFactory.getInstance(PBKDF2_SHA256)
                .generateSecret(specification)
                .encoded
            try {
                SecretKeySpec(encoded, AES)
            } finally {
                encoded.fill(0)
            }
        } finally {
            specification.clearPassword()
        }
    }

    private fun encryptChunk(
        key: SecretKeySpec,
        nonce: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray,
    ): ByteArray = Cipher.getInstance(AES_GCM).run {
        init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        updateAAD(aad)
        doFinal(plaintext)
    }

    private fun decryptChunk(
        key: SecretKeySpec,
        nonce: ByteArray,
        aad: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray = Cipher.getInstance(AES_GCM).run {
        init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        updateAAD(aad)
        doFinal(ciphertext)
    }

    private fun expectedChunkCount(plaintextBytes: Long, chunkBytes: Int): Int {
        val count = if (plaintextBytes == 0L) 1L else ((plaintextBytes - 1L) / chunkBytes) + 1L
        if (count > Int.MAX_VALUE) {
            throw BackupFormatException("Backup contains too many chunks.")
        }
        return count.toInt()
    }

    private fun expectedPlaintextBytes(remaining: Long, chunkBytes: Int, emptyPayload: Boolean): Int =
        if (emptyPayload) 0 else min(remaining, chunkBytes.toLong()).toInt()

    private fun nonce(prefix: ByteArray, chunkIndex: Int): ByteArray = ByteBuffer.allocate(NONCE_BYTES)
        .put(prefix)
        .putInt(chunkIndex)
        .array()

    private fun aad(header: ByteArray, chunkIndex: Int, plaintextBytes: Int): ByteArray =
        ByteBuffer.allocate(header.size + Int.SIZE_BYTES * 2)
            .put(header)
            .putInt(chunkIndex)
            .putInt(plaintextBytes)
            .array()

    private fun readExactly(input: InputStream, destination: ByteArray) {
        var offset = 0
        while (offset < destination.size) {
            val read = input.read(destination, offset, destination.size - offset)
            if (read < 0) {
                throw BackupFormatException("Backup ended unexpectedly.")
            }
            if (read == 0) {
                val one = input.read()
                if (one < 0) {
                    throw BackupFormatException("Backup ended unexpectedly.")
                }
                destination[offset] = one.toByte()
                offset += 1
            } else {
                offset += read
            }
        }
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }

    private data class Header(
        val pbkdf2Iterations: Int,
        val chunkBytes: Int,
        val plaintextBytes: Long,
        val salt: ByteArray,
        val noncePrefix: ByteArray,
    )

    private companion object {
        val MAGIC = "ZHJBKP01".encodeToByteArray()
        const val FORMAT_VERSION = 1
        const val KDF_PBKDF2_SHA256 = 1
        const val CIPHER_AES_256_GCM = 1
        const val SALT_BYTES = 16
        const val NONCE_PREFIX_BYTES = 8
        const val NONCE_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = GCM_TAG_BITS / Byte.SIZE_BITS
        const val AES_KEY_BITS = 256
        const val HEADER_BYTES = 52
        const val PBKDF2_SHA256 = "PBKDF2WithHmacSHA256"
        const val AES_GCM = "AES/GCM/NoPadding"
        const val AES = "AES"
        const val SHA_256 = "SHA-256"
    }
}

const val DEFAULT_PBKDF2_ITERATIONS = 600_000
const val DEFAULT_CHUNK_BYTES = 1024 * 1024
const val MINIMUM_CHUNK_BYTES = 4 * 1024
const val MAXIMUM_CHUNK_BYTES = 4 * 1024 * 1024
const val MAXIMUM_PBKDF2_ITERATIONS = 5_000_000
const val MAXIMUM_PLAINTEXT_BYTES = 1024L * 1024L * 1024L * 1024L
