package com.example.capture.security.domain

/** Thrown by [KeyBackupRepository.importFromCurrentFile]/[KeyBackupRepository.changePassphrase] when the supplied passphrase does not decrypt the key file. */
class IncorrectPassphraseException : Exception()

data class PublicKeyInfo(val publicKeyBase64: String, val fingerprintSha256: String)

/**
 * Reads and writes the app's single `.kkey` (encrypted private-key backup) file. Format is
 * byte-for-byte compatible with the existing .NET/MAUI app: PBKDF2-SHA256-derived AES-256-GCM
 * encryption of a PKCS8 RSA private key, integrity-bound to its own header fields via the
 * encryption's associated data.
 */
interface KeyBackupRepository {

    suspend fun hasKeyFile(): Boolean

    /** Generates a new RSA-3072 key pair and writes it, passphrase-protected, to the key file (backing up any existing file first). */
    suspend fun createKeyFile(passphrase: String): EncryptionKeyPair

    /** Reads the public key and fingerprint without needing the passphrase. Returns null if no key file exists. */
    suspend fun readPublicKeyInfo(): PublicKeyInfo?

    /** Decrypts the current key file's private key. @throws IncorrectPassphraseException if [passphrase] is wrong. */
    suspend fun importFromCurrentFile(passphrase: String): ImportedKeyPair

    /** Overwrites the key file with [bytes] (the contents of a picked `.kkey` file), backing up any existing file first. */
    suspend fun importKeyFileBytes(bytes: ByteArray)

    /** Reads the current key file's raw bytes, for export/sharing. */
    suspend fun exportKeyFileBytes(): ByteArray

    /** Re-encrypts the key file's private key under [newPassphrase]. @throws IncorrectPassphraseException if [currentPassphrase] is wrong. */
    suspend fun changePassphrase(currentPassphrase: String, newPassphrase: String)

    /** The key file's absolute path, for building a share intent. */
    fun keyFilePath(): String
}
