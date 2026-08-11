package app.zhijuan.core.security

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.SecureRandom

class DatabasePassphraseStore(
    context: Context,
    private val cipher: AndroidKeystoreAesGcm = AndroidKeystoreAesGcm(DATABASE_KEY_ALIAS),
    private val random: SecureRandom = SecureRandom(),
) {
    private val envelopeFile = AtomicFile(
        File(context.noBackupFilesDir, ENVELOPE_RELATIVE_PATH),
    )

    fun getOrCreate(): ByteArray = synchronized(FILE_LOCK) {
        if (envelopeFile.baseFile.exists()) {
            val envelope = envelopeFile.openRead().use { input ->
                EncryptedEnvelopeCodec.decode(input.readBytes())
            }
            return@synchronized cipher.decrypt(envelope).also(::validatePassphrase)
        }

        envelopeFile.baseFile.parentFile?.let { directory ->
            check(directory.exists() || directory.mkdirs()) {
                "Unable to create the protected secret directory."
            }
        }
        val passphrase = ByteArray(PASSPHRASE_BYTES).also(random::nextBytes)
        val encoded = EncryptedEnvelopeCodec.encode(cipher.encrypt(passphrase))
        val stream = envelopeFile.startWrite()
        try {
            stream.write(encoded)
            stream.fd.sync()
            envelopeFile.finishWrite(stream)
        } catch (error: Exception) {
            envelopeFile.failWrite(stream)
            passphrase.fill(0)
            throw error
        }
        passphrase
    }

    private fun validatePassphrase(passphrase: ByteArray) {
        require(passphrase.size == PASSPHRASE_BYTES) { "Stored database passphrase has invalid length." }
    }

    companion object {
        const val DATABASE_KEY_ALIAS = "app.zhijuan.reader.database.passphrase.v1"
        const val PASSPHRASE_BYTES = 32
        private const val ENVELOPE_RELATIVE_PATH = "security/database-passphrase.zjes"
        private val FILE_LOCK = Any()
    }
}
