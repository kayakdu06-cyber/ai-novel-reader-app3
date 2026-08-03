package app.zhijuan.core.security

data class EncryptedEnvelope(
    val version: Int,
    val initializationVector: ByteArray,
    val ciphertext: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is EncryptedEnvelope &&
            version == other.version &&
            initializationVector.contentEquals(other.initializationVector) &&
            ciphertext.contentEquals(other.ciphertext)

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + initializationVector.contentHashCode()
        result = 31 * result + ciphertext.contentHashCode()
        return result
    }
}
