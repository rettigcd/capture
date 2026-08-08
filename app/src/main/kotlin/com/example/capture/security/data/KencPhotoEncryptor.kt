package com.example.capture.security.data

import com.example.capture.security.domain.PhotoEncryptor
import com.example.capture.security.domain.PrivateKeyException
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [PhotoEncryptor] impl: a random AES-256 key wrapped with RSA-OAEP-SHA256, payload encrypted
 * with AES-256-GCM. Also implements [KeySessionKeyStore], the in-memory key-pair state that
 * [DefaultKeySessionRepository] drives - one instance, two interfaces, so the key state behind
 * `encrypt`/`decrypt` is exactly the state sign-in/out mutates. `@Singleton`: this state must not
 * fork across injection sites.
 */
@Singleton
class KencPhotoEncryptor @Inject constructor() : PhotoEncryptor, KeySessionKeyStore {

    @Volatile private var publicKey: PublicKey? = null
    @Volatile private var privateKey: PrivateKey? = null

    override val hasPublicKey: Boolean get() = publicKey != null
    override val hasDecryptionKey: Boolean get() = privateKey != null

    override fun setPublicKey(publicKeyBase64: String) {
        val newKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)))

        privateKey = null
        publicKey = newKey
    }

    override fun clearKeys() {
        privateKey = null
        publicKey = null
    }

    // Validates the candidate via a functional RSA-OAEP round trip against the already-known
    // publicKey, rather than deriving a public key from the candidate's own CRT parameters: some
    // providers (e.g. Conscrypt, Android's default) don't retain CRT parameters on a private key
    // reconstructed via KeyFactory.generatePrivate(PKCS8EncodedKeySpec(...)) - see
    // FileKeyBackupRepository's kdoc for the same issue on the import side - so this only needs
    // the candidate to work as a private key, not to expose its internal fields.
    override fun setPrivateKey(candidate: PrivateKey?): Boolean {
        if (candidate === privateKey) return true

        if (candidate != null) {
            val pub = publicKey ?: return false
            val matches = try {
                val probe = ByteArray(32).also(secureRandom::nextBytes)
                rsaDecrypt(candidate, rsaEncrypt(pub, probe)).contentEquals(probe)
            } catch (e: GeneralSecurityException) {
                false
            }
            if (!matches) return false
        }

        privateKey = candidate
        return true
    }

    override fun encrypt(plain: ByteArray): ByteArray = KencCodec.encodeStandaloneBlock(encryptBytes(plain))

    override fun decrypt(data: ByteArray): ByteArray = decryptBlock(KencCodec.decodeStandaloneBlock(data))

    override fun encryptToKencFile(imageBytes: ByteArray, metadataJson: String): ByteArray {
        val imageBlock = encryptBytes(imageBytes)
        val metadataBlock = encryptBytes(metadataJson.toByteArray(Charsets.UTF_8))
        return KencCodec.writeHeader(imageBlock.fileFormatLength) + KencCodec.writeBlock(imageBlock) + KencCodec.writeBlock(metadataBlock)
    }

    // ---- crypto primitives ----
    // internal, not private: lets this module's own tests build realistic .kenc-format fixtures
    // without duplicating the crypto logic.

    internal fun encryptBytes(plain: ByteArray): EncryptedBlock {
        val pub = publicKey ?: error("Public key is not set.")
        val aesKey = ByteArray(32).also(secureRandom::nextBytes) // 32 bytes = AES-256
        val (nonce, tag, cipherText) = symmetricEncrypt(plain, aesKey)
        val encryptedAesKey = rsaEncrypt(pub, aesKey)
        return EncryptedBlock(encryptedAesKey, nonce, tag, cipherText)
    }

    internal fun decryptBlock(block: EncryptedBlock): ByteArray {
        val priv = privateKey ?: throw PrivateKeyException(PrivateKeyException.Reason.MISSING_KEY)
        try {
            val aesKey = rsaDecrypt(priv, block.encryptedAesKey)
            return symmetricDecrypt(block.cipher, block.nonce, block.tag, aesKey)
        } catch (e: javax.crypto.BadPaddingException) {
            // RSA-OAEP unwrap failed against this private key - BadPaddingException is the
            // portable, typed signal for "this key doesn't match this ciphertext".
            throw PrivateKeyException(PrivateKeyException.Reason.INVALID_KEY, e)
        }
    }

    private fun symmetricEncrypt(plain: ByteArray, aesKey: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        val nonce = ByteArray(GCM_NONCE_SIZE_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(GCM_TAG_SIZE_BYTES * 8, nonce))
        val output = cipher.doFinal(plain) // ciphertext || tag (Java's GCM Cipher appends the tag)
        val cipherText = output.copyOfRange(0, output.size - GCM_TAG_SIZE_BYTES)
        val tag = output.copyOfRange(output.size - GCM_TAG_SIZE_BYTES, output.size)
        return Triple(nonce, tag, cipherText)
    }

    private fun symmetricDecrypt(cipherText: ByteArray, nonce: ByteArray, tag: ByteArray, aesKey: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(tag.size * 8, nonce))
        return cipher.doFinal(cipherText + tag)
    }

    private fun rsaEncrypt(key: PublicKey, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key, oaepParams())
        return cipher.doFinal(data)
    }

    private fun rsaDecrypt(key: PrivateKey, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(Cipher.DECRYPT_MODE, key, oaepParams())
        return cipher.doFinal(data)
    }

    // Explicit params, not just the transformation string: guarantees SHA-256 for both the OAEP
    // hash and the MGF1 mask function, matching .NET's RSAEncryptionPadding.OaepSHA256 exactly.
    // Relying on the transformation-string shorthand risks a provider defaulting MGF1 to SHA-1.
    private fun oaepParams(): OAEPParameterSpec =
        OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)

    private companion object {
        const val GCM_NONCE_SIZE_BYTES = 12
        const val GCM_TAG_SIZE_BYTES = 16

        val secureRandom = SecureRandom()
    }
}
