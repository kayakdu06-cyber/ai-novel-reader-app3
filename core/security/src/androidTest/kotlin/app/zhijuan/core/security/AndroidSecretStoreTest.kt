package app.zhijuan.core.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidSecretStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory: File get() = File(context.noBackupFilesDir, "security/api-secrets")
    private lateinit var store: AndroidSecretStore

    @Before
    fun setUp() {
        cleanRecordsAndKeys()
        store = AndroidSecretStore(context)
    }

    @After
    fun tearDown() {
        runCatching { store.unlockAfterAuthentication() }
        cleanRecordsAndKeys()
    }

    @Test
    fun createConsumesInputAndOnlyReturnsPlaintextThroughClosableLease() {
        val input = secretBytes(1)
        val expected = input.copyOf()
        val descriptor = store.createAndClear(SecretPurpose.API_KEY, input, 10)

        assertTrue(input.all { it == 0.toByte() })
        assertEquals(lastFour(expected), descriptor.lastFour)
        assertEquals(listOf(descriptor), store.listDescriptors())
        assertTrue(directory.listFiles().orEmpty().all { !it.readBytes().containsSubsequence(expected) })

        val resolved = store.read(descriptor.secretRefId, SecretPurpose.API_KEY, 11).use { lease ->
            lease.withBytes(ByteArray::copyOf)
        }
        assertArrayEquals(expected, resolved)
        assertEquals(11L, store.descriptor(descriptor.secretRefId).lastUsedAt)
        expected.fill(0)
        resolved.fill(0)
    }

    @Test
    fun lockingClosesOutstandingLeasesAndBlocksEveryStoreOperation() {
        val descriptor = store.createAndClear(SecretPurpose.API_KEY, secretBytes(2), 10)
        val lease = store.read(descriptor.secretRefId, SecretPurpose.API_KEY, 11)
        val borrowedForVerification = lease.withBytes { it }
        store.lock()

        assertTrue(borrowedForVerification.all { it == 0.toByte() })
        assertThrows(IllegalStateException::class.java) { lease.withBytes { it.size } }
        assertThrows(SecretStoreLockedException::class.java) {
            store.read(descriptor.secretRefId, SecretPurpose.API_KEY, 12)
        }
        assertThrows(SecretStoreLockedException::class.java) { store.listDescriptors() }

        store.unlockAfterAuthentication()
        store.read(descriptor.secretRefId, SecretPurpose.API_KEY, 13).use { leaseAfterUnlock ->
            assertTrue(leaseAfterUnlock.withBytes { it.isNotEmpty() })
        }
    }

    @Test
    fun rotationKeepsReferenceAndAtomicallyAdvancesKeyVersion() {
        val original = secretBytes(3)
        val originalCopy = original.copyOf()
        val first = store.createAndClear(SecretPurpose.API_KEY, original, 10)
        val oldAlias = AndroidSecretStore.keyAliasFor(first.secretRefId, 1)
        assertTrue(AndroidKeystoreAesGcm(oldAlias).keyExists())

        val replacement = secretBytes(8)
        val replacementCopy = replacement.copyOf()
        val rotated = store.rotateAndClear(first.secretRefId, SecretPurpose.API_KEY, replacement, 20)

        assertTrue(replacement.all { it == 0.toByte() })
        assertEquals(first.secretRefId, rotated.secretRefId)
        assertEquals(2, rotated.keyVersion)
        assertFalse(AndroidKeystoreAesGcm(oldAlias).keyExists())
        assertTrue(AndroidKeystoreAesGcm(AndroidSecretStore.keyAliasFor(first.secretRefId, 2)).keyExists())
        val recordBytes = directory.listFiles().orEmpty().single().readBytes()
        assertFalse(recordBytes.containsSubsequence(originalCopy))
        assertFalse(recordBytes.containsSubsequence(replacementCopy))
        val resolved = store.read(first.secretRefId, SecretPurpose.API_KEY, 21).use { lease ->
            lease.withBytes { it.copyOf() }
        }
        assertArrayEquals(replacementCopy, resolved)
        originalCopy.fill(0)
        replacementCopy.fill(0)
        resolved.fill(0)
    }

    @Test
    fun revokeWritesTombstoneClosesLeaseAndDeletesUsableKey() {
        val descriptor = store.createAndClear(SecretPurpose.API_KEY, secretBytes(4), 10)
        val alias = AndroidSecretStore.keyAliasFor(descriptor.secretRefId, 1)
        val lease = store.read(descriptor.secretRefId, SecretPurpose.API_KEY, 11)
        val revoked = store.revoke(descriptor.secretRefId, 12)

        assertEquals(SecretRecordState.REVOKED, revoked.state)
        assertEquals("", revoked.lastFour)
        assertFalse(AndroidKeystoreAesGcm(alias).keyExists())
        assertThrows(IllegalStateException::class.java) { lease.withBytes { it.size } }
        assertThrows(SecretUnavailableException::class.java) {
            store.read(descriptor.secretRefId, SecretPurpose.API_KEY, 13)
        }
        assertEquals(revoked, store.revoke(descriptor.secretRefId, 14))
        val stored = decodeOnlyRecord()
        assertEquals(SecretRecordState.REVOKED, stored.descriptor.state)
        assertEquals(null, stored.envelope)
    }

    @Test
    fun purposeMismatchAndMissingKeystoreKeyFailClosedWithoutChangingRecord() {
        val descriptor = store.createAndClear(SecretPurpose.API_KEY, secretBytes(5), 10)
        val recordFile = directory.listFiles().orEmpty().single()
        val before = recordFile.readBytes()

        assertThrows(SecretPurposeMismatchException::class.java) {
            store.read(descriptor.secretRefId, SecretPurpose.SENSITIVE_HEADER, 11)
        }
        assertArrayEquals(before, recordFile.readBytes())

        AndroidKeystoreAesGcm(AndroidSecretStore.keyAliasFor(descriptor.secretRefId, 1)).deleteKey()
        assertThrows(SecretUnavailableException::class.java) {
            store.read(descriptor.secretRefId, SecretPurpose.API_KEY, 12)
        }
        assertArrayEquals(before, recordFile.readBytes())
        assertFalse(AndroidKeystoreAesGcm(AndroidSecretStore.keyAliasFor(descriptor.secretRefId, 1)).keyExists())
    }

    @Test
    fun metadataTamperingBreaksAadAuthenticationAndDescriptorsNeverExposeSecrets() {
        val firstSecret = secretBytes(6)
        val firstCopy = firstSecret.copyOf()
        val first = store.createAndClear(SecretPurpose.API_KEY, firstSecret, 10)
        val second = store.createAndClear(SecretPurpose.SENSITIVE_HEADER, secretBytes(7, includeSpace = true), 11)
        val descriptors = store.listDescriptors()

        assertEquals(setOf(first.secretRefId, second.secretRefId), descriptors.map { it.secretRefId }.toSet())
        assertTrue(descriptors.none { it.toString().contains(String(firstCopy)) })

        val file = File(directory, "secret-${first.secretRefId}.zjsr")
        val record = SecretRecordCodec.decode(file.readBytes())
        file.writeBytes(
            SecretRecordCodec.encode(
                record.copy(descriptor = record.descriptor.copy(lastFour = "xxxx")),
            ),
        )
        assertThrows(SecretUnavailableException::class.java) {
            store.read(first.secretRefId, SecretPurpose.API_KEY, 12)
        }
        firstCopy.fill(0)
    }

    private fun secretBytes(seed: Int, includeSpace: Boolean = false): ByteArray = ByteArray(32) { index ->
        if (includeSpace && index == 8) {
            0x20
        } else {
            (0x41 + (seed + index) % 26).toByte()
        }
    }

    private fun lastFour(value: ByteArray): String = String(
        CharArray(4) { index -> value[value.size - 4 + index].toInt().toChar() },
    )

    private fun decodeOnlyRecord(): StoredSecretRecord =
        SecretRecordCodec.decode(directory.listFiles().orEmpty().single().readBytes())

    private fun cleanRecordsAndKeys() {
        directory.listFiles().orEmpty().forEach { file ->
            runCatching {
                val record = SecretRecordCodec.decode(file.readBytes())
                (1..record.descriptor.keyVersion).forEach { version ->
                    AndroidKeystoreAesGcm(
                        AndroidSecretStore.keyAliasFor(record.descriptor.secretRefId, version),
                    ).deleteKey()
                }
            }
        }
        directory.deleteRecursively()
    }

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        return indices.any { start ->
            start + needle.size <= size && needle.indices.all { offset -> this[start + offset] == needle[offset] }
        }
    }
}
