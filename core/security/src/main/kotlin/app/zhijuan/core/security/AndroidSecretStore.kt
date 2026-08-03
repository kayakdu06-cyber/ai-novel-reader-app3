package app.zhijuan.core.security

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID

class AndroidSecretStore(
    context: Context,
    initiallyUnlocked: Boolean = true,
) {
    private val directory = File(context.noBackupFilesDir, SECRET_DIRECTORY)
    private val activeLeases = mutableMapOf<SecretLease, String>()

    @Volatile
    private var unlocked = initiallyUnlocked

    fun createAndClear(
        purpose: SecretPurpose,
        secret: ByteArray,
        now: Long,
    ): SecretDescriptor = synchronized(STORE_LOCK) {
        requireUnlocked()
        require(now >= 0)
        try {
            validateSecret(secret, purpose)
            ensureDirectory()
            repeat(MAX_ID_ATTEMPTS) {
                val secretRefId = UUID.randomUUID().toString()
                val recordFile = recordFile(secretRefId)
                if (recordFile.baseFile.exists()) return@repeat
                val keyVersion = 1
                val cipher = AndroidKeystoreAesGcm(keyAliasFor(secretRefId, keyVersion))
                val descriptor = SecretDescriptor(
                    secretRefId = secretRefId,
                    purpose = purpose,
                    lastFour = lastFour(secret),
                    state = SecretRecordState.ACTIVE,
                    keyVersion = keyVersion,
                    createdAt = now,
                    updatedAt = now,
                    lastUsedAt = null,
                )
                val envelope = cipher.encrypt(secret, associatedData(descriptor))
                try {
                    writeRecord(recordFile, StoredSecretRecord(descriptor, envelope))
                } catch (error: Exception) {
                    cipher.deleteKey()
                    throw error
                }
                return@synchronized descriptor
            }
            error("Unable to allocate a unique secret reference.")
        } finally {
            secret.fill(0)
        }
    }

    fun read(
        secretRefId: String,
        expectedPurpose: SecretPurpose,
        now: Long,
    ): SecretLease = synchronized(STORE_LOCK) {
        requireUnlocked()
        require(now >= 0)
        val recordFile = recordFile(secretRefId)
        val record = readRecord(recordFile, secretRefId)
        requireActive(record)
        if (record.descriptor.purpose != expectedPurpose) throw SecretPurposeMismatchException()
        val plaintext = try {
            AndroidKeystoreAesGcm(keyAliasFor(secretRefId, record.descriptor.keyVersion)).decrypt(
                requireNotNull(record.envelope),
                associatedData(record.descriptor),
            )
        } catch (error: Exception) {
            throw SecretUnavailableException("Secret key is missing, invalidated, or the record was changed.", error)
        }
        try {
            validateSecret(plaintext, record.descriptor.purpose)
            val effectiveUsedAt = maxOf(record.descriptor.createdAt, now)
            val touched = record.copy(
                descriptor = record.descriptor.copy(
                    updatedAt = maxOf(record.descriptor.updatedAt, effectiveUsedAt),
                    lastUsedAt = effectiveUsedAt,
                ),
            )
            writeRecord(recordFile, touched)
            SecretLease(plaintext) { lease ->
                synchronized(STORE_LOCK) { activeLeases.remove(lease) }
            }.also { lease -> activeLeases[lease] = secretRefId }
        } catch (error: Exception) {
            plaintext.fill(0)
            throw error
        }
    }

    fun rotateAndClear(
        secretRefId: String,
        expectedPurpose: SecretPurpose,
        replacement: ByteArray,
        now: Long,
    ): SecretDescriptor = synchronized(STORE_LOCK) {
        requireUnlocked()
        require(now >= 0)
        try {
            validateSecret(replacement, expectedPurpose)
            val recordFile = recordFile(secretRefId)
            val current = readRecord(recordFile, secretRefId)
            requireActive(current)
            if (current.descriptor.purpose != expectedPurpose) throw SecretPurposeMismatchException()
            val nextVersion = Math.addExact(current.descriptor.keyVersion, 1)
            val nextDescriptor = current.descriptor.copy(
                lastFour = lastFour(replacement),
                keyVersion = nextVersion,
                updatedAt = maxOf(current.descriptor.updatedAt, now),
                lastUsedAt = null,
            )
            val nextCipher = AndroidKeystoreAesGcm(keyAliasFor(secretRefId, nextVersion))
            val nextEnvelope = nextCipher.encrypt(replacement, associatedData(nextDescriptor))
            try {
                writeRecord(recordFile, StoredSecretRecord(nextDescriptor, nextEnvelope))
            } catch (error: Exception) {
                nextCipher.deleteKey()
                throw error
            }
            closeLeases(secretRefId)
            AndroidKeystoreAesGcm(keyAliasFor(secretRefId, current.descriptor.keyVersion)).deleteKey()
            nextDescriptor
        } finally {
            replacement.fill(0)
        }
    }

    fun revoke(secretRefId: String, now: Long): SecretDescriptor = synchronized(STORE_LOCK) {
        requireUnlocked()
        require(now >= 0)
        val recordFile = recordFile(secretRefId)
        val current = readRecord(recordFile, secretRefId)
        if (current.descriptor.state == SecretRecordState.REVOKED) return@synchronized current.descriptor
        val revoked = current.descriptor.copy(
            lastFour = "",
            state = SecretRecordState.REVOKED,
            updatedAt = maxOf(current.descriptor.updatedAt, now),
            lastUsedAt = null,
        )
        writeRecord(recordFile, StoredSecretRecord(revoked, envelope = null))
        closeLeases(secretRefId)
        AndroidKeystoreAesGcm(keyAliasFor(secretRefId, current.descriptor.keyVersion)).deleteKey()
        revoked
    }

    fun descriptor(secretRefId: String): SecretDescriptor = synchronized(STORE_LOCK) {
        requireUnlocked()
        readRecord(recordFile(secretRefId), secretRefId).descriptor
    }

    fun listDescriptors(): List<SecretDescriptor> = synchronized(STORE_LOCK) {
        requireUnlocked()
        if (!directory.exists()) return@synchronized emptyList()
        directory.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.name.matches(RECORD_FILE_PATTERN) }
            ?.map { file ->
                val secretRefId = file.name.removePrefix(RECORD_PREFIX).removeSuffix(RECORD_SUFFIX)
                readRecord(AtomicFile(file), secretRefId).descriptor
            }
            ?.sortedBy { it.createdAt }
            ?.toList()
            .orEmpty()
    }

    fun lock() = synchronized(STORE_LOCK) {
        unlocked = false
        activeLeases.keys.toList().forEach(SecretLease::close)
    }

    fun unlockAfterAuthentication() = synchronized(STORE_LOCK) {
        unlocked = true
    }

    private fun closeLeases(secretRefId: String) {
        activeLeases.filterValues { it == secretRefId }.keys.toList().forEach(SecretLease::close)
    }

    private fun requireUnlocked() {
        if (!unlocked) throw SecretStoreLockedException()
    }

    private fun readRecord(file: AtomicFile, expectedSecretRefId: String): StoredSecretRecord {
        if (!file.baseFile.exists()) throw SecretUnavailableException("Secret reference does not exist.")
        if (file.baseFile.length() > SecretRecordCodec.MAX_RECORD_BYTES) {
            throw SecretUnavailableException("Secret record is too large or corrupted.")
        }
        val record = try {
            file.openRead().use { input -> SecretRecordCodec.decode(input.readBytes()) }
        } catch (error: Exception) {
            throw SecretUnavailableException("Secret record is corrupted.", error)
        }
        if (record.descriptor.secretRefId != expectedSecretRefId) {
            throw SecretUnavailableException("Secret record identity does not match its reference.")
        }
        return record
    }

    private fun requireActive(record: StoredSecretRecord) {
        if (record.descriptor.state != SecretRecordState.ACTIVE || record.envelope == null) {
            throw SecretUnavailableException("Secret reference was revoked.")
        }
    }

    private fun writeRecord(file: AtomicFile, record: StoredSecretRecord) {
        val encoded = SecretRecordCodec.encode(record)
        val stream = file.startWrite()
        try {
            stream.write(encoded)
            stream.fd.sync()
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    private fun recordFile(secretRefId: String): AtomicFile {
        require(secretRefId.matches(SecretRecordCodec.SECRET_REF_PATTERN)) { "Secret reference format is invalid." }
        return AtomicFile(File(directory, "$RECORD_PREFIX$secretRefId$RECORD_SUFFIX"))
    }

    private fun ensureDirectory() {
        check(directory.exists() || directory.mkdirs()) { "Unable to create protected secret directory." }
    }

    private fun validateSecret(secret: ByteArray, purpose: SecretPurpose) {
        require(secret.size in MIN_SECRET_BYTES..MAX_SECRET_BYTES) { "Secret length is outside the allowed range." }
        val minimumByte = if (purpose == SecretPurpose.API_KEY) 0x21 else 0x20
        require(secret.all { it.toInt() and 0xff in minimumByte..0x7e }) {
            "Secrets must contain printable ASCII; API keys cannot contain spaces."
        }
    }

    private fun lastFour(secret: ByteArray): String {
        val count = minOf(4, secret.size)
        return String(CharArray(count) { index -> (secret[secret.size - count + index].toInt() and 0xff).toChar() })
    }

    private fun associatedData(descriptor: SecretDescriptor): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.write(AAD_DOMAIN.toByteArray(StandardCharsets.UTF_8))
            output.writeUTF(descriptor.secretRefId)
            output.writeUTF(descriptor.purpose.name)
            output.writeUTF(descriptor.lastFour)
            output.writeUTF(descriptor.state.name)
            output.writeInt(descriptor.keyVersion)
            output.writeLong(descriptor.createdAt)
        }
        bytes.toByteArray()
    }

    companion object {
        private const val SECRET_DIRECTORY = "security/api-secrets"
        private const val RECORD_PREFIX = "secret-"
        private const val RECORD_SUFFIX = ".zjsr"
        private const val MIN_SECRET_BYTES = 8
        private const val MAX_SECRET_BYTES = 16_384
        private const val MAX_ID_ATTEMPTS = 8
        private const val AAD_DOMAIN = "app.zhijuan.reader.secret-record.v1"
        private val RECORD_FILE_PATTERN = Regex("secret-[0-9a-f-]{36}\\.zjsr")
        private val STORE_LOCK = Any()

        internal fun keyAliasFor(secretRefId: String, keyVersion: Int): String =
            "app.zhijuan.reader.api-secret.$secretRefId.v$keyVersion"
    }
}
