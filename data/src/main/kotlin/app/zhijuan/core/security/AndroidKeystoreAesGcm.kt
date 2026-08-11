package app.zhijuan.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidKeystoreAesGcm(
    private val keyAlias: String,
) {
    init {
        require(keyAlias.isNotBlank()) { "Keystore alias must not be blank." }
    }

    fun encrypt(
        plaintext: ByteArray,
        associatedData: ByteArray = EMPTY_ASSOCIATED_DATA,
    ): EncryptedEnvelope {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        if (associatedData.isNotEmpty()) cipher.updateAAD(associatedData)
        return EncryptedEnvelope(
            version = 1,
            initializationVector = cipher.iv,
            ciphertext = cipher.doFinal(plaintext),
        )
    }

    fun decrypt(
        envelope: EncryptedEnvelope,
        associatedData: ByteArray = EMPTY_ASSOCIATED_DATA,
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            requireKey(),
            GCMParameterSpec(GCM_TAG_BITS, envelope.initializationVector),
        )
        if (associatedData.isNotEmpty()) cipher.updateAAD(associatedData)
        return cipher.doFinal(envelope.ciphertext)
    }

    fun keyExists(): Boolean = synchronized(KEYSTORE_LOCK) {
        loadKeyStore().containsAlias(keyAlias)
    }

    fun deleteKey() {
        synchronized(KEYSTORE_LOCK) {
            loadKeyStore().deleteEntry(keyAlias)
        }
    }

    private fun getOrCreateKey(): SecretKey = synchronized(KEYSTORE_LOCK) {
        val keyStore = loadKeyStore()
        (keyStore.getKey(keyAlias, null) as? SecretKey) ?: generateKey()
    }

    private fun requireKey(): SecretKey = synchronized(KEYSTORE_LOCK) {
        val key = loadKeyStore().getKey(keyAlias, null) as? SecretKey
        requireNotNull(key) { "Keystore key is missing or was invalidated." }
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        val EMPTY_ASSOCIATED_DATA = ByteArray(0)
        val KEYSTORE_LOCK = Any()
    }
}
