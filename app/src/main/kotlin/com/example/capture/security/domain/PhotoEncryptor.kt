package com.example.capture.security.domain

/**
 * Hybrid RSA-OAEP(AES key) + AES-256-GCM(payload) encryption of arbitrary bytes, keyed off the
 * current public key - signing in (having the private key) is not required to encrypt, only to
 * decrypt.
 */
interface PhotoEncryptor {

    /** Encrypts [plain] using the current public key. @throws IllegalStateException if no public key is set. */
    fun encrypt(plain: ByteArray): ByteArray

    /** Decrypts data previously produced by [encrypt]. @throws PrivateKeyException if the private key is missing or invalid. */
    fun decrypt(data: ByteArray): ByteArray

    /**
     * Encrypts [imageBytes] into a genuine standalone `.kenc` FILE: magic header, then the image
     * as one encrypted block, then [metadataJson] (UTF-8) as a second encrypted block - see
     * `KencCodec`'s file format kdoc. Cross-app compatible with the .NET/MAUI and keibler Android
     * apps, unlike [encrypt]'s interleaved standalone-block format. @throws IllegalStateException
     * if no public key is set.
     */
    fun encryptToKencFile(imageBytes: ByteArray, metadataJson: String): ByteArray
}
