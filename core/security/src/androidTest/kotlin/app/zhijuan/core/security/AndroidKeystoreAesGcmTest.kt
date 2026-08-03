package app.zhijuan.core.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreAesGcmTest {
    private val alias = "app.zhijuan.reader.test.${System.nanoTime()}"
    private val cipher = AndroidKeystoreAesGcm(alias)

    @After
    fun tearDown() {
        cipher.deleteKey()
    }

    @Test
    fun keystoreRoundTripUsesRandomizedCiphertext() {
        val plaintext = ByteArray(32).also(SecureRandom()::nextBytes)

        val first = cipher.encrypt(plaintext)
        val second = cipher.encrypt(plaintext)

        assertArrayEquals(plaintext, cipher.decrypt(first))
        assertArrayEquals(plaintext, cipher.decrypt(second))
        assertFalse(first.initializationVector.contentEquals(second.initializationVector))
        assertFalse(first.ciphertext.contentEquals(second.ciphertext))
    }

    @Test
    fun missingKeystoreKeyDoesNotSilentlyReplaceStoredDatabasePassphrase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val envelopeFile = File(context.noBackupFilesDir, "security/database-passphrase.zjes")
        envelopeFile.parentFile?.deleteRecursively()
        val store = DatabasePassphraseStore(context, cipher)
        try {
            store.getOrCreate().fill(0)
            val originalEnvelope = envelopeFile.readBytes()
            cipher.deleteKey()

            assertThrows(IllegalArgumentException::class.java) { store.getOrCreate() }
            assertArrayEquals(originalEnvelope, envelopeFile.readBytes())
        } finally {
            envelopeFile.parentFile?.deleteRecursively()
        }
    }
}
