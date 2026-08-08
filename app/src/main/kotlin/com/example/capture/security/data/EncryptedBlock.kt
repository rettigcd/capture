package com.example.capture.security.data

/** One hybrid-encrypted payload: an AES-256 key wrapped with RSA-OAEP-SHA256, plus the AES-256-GCM-encrypted data itself. */
internal data class EncryptedBlock(
    val encryptedAesKey: ByteArray,
    val nonce: ByteArray,
    val tag: ByteArray,
    val cipher: ByteArray,
) {
    /** Total size once written via [KencCodec.writeBlock]: the 20-byte length header plus all four fields. */
    val fileFormatLength: Long get() = 4L + 4 + 4 + 8 + encryptedAesKey.size + nonce.size + tag.size + cipher.size

    override fun equals(other: Any?): Boolean =
        other is EncryptedBlock &&
            encryptedAesKey.contentEquals(other.encryptedAesKey) &&
            nonce.contentEquals(other.nonce) &&
            tag.contentEquals(other.tag) &&
            cipher.contentEquals(other.cipher)

    override fun hashCode(): Int = encryptedAesKey.contentHashCode() * 31 + cipher.contentHashCode()
}
