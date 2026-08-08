package com.example.capture.security.domain

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.spec.RSAPrivateCrtKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/**
 * An RSA-3072 key pair, exported as Base64 SubjectPublicKeyInfo (X.509) and PKCS8 - both
 * standard, canonical DER encodings (there is exactly one valid DER encoding of a given key under
 * each), so a key pair generated here exports bytes the existing .NET apps can import unchanged,
 * and vice versa. This isn't an app-specific format; it's the same interop guarantee any two
 * correct RSA implementations get from using X.509/PKCS8.
 */
class EncryptionKeyPair private constructor(val privateKey: PrivateKey, publicKeyDer: ByteArray, privateKeyDer: ByteArray) {

    /** Base64 SubjectPublicKeyInfo (X.509) - enables encrypting. */
    val public64: String = Base64.getEncoder().encodeToString(publicKeyDer)

    /** Base64 PKCS8 private key - enables decrypting. */
    val private64: String = Base64.getEncoder().encodeToString(privateKeyDer)

    companion object {
        private const val KEY_SIZE_BITS = 3072

        fun generate(): EncryptionKeyPair {
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_SIZE_BITS) }.generateKeyPair()
            return EncryptionKeyPair(keyPair.private, keyPair.public.encoded, keyPair.private.encoded)
        }

        /**
         * Wraps an already-loaded private key, deriving its matching public key from its CRT
         * parameters via [KeyFactory.getKeySpec] rather than an `as RSAPrivateCrtKey` cast (some
         * providers return a `PrivateKey` that doesn't implement
         * [java.security.interfaces.RSAPrivateCrtKey] even for a fully CRT-bearing key).
         *
         * Only reliable for a private key that still carries retrievable CRT parameters, which in
         * practice means one straight from [KeyPairGenerator] (as [generate] uses) - **not**
         * necessarily one reconstructed via `KeyFactory.generatePrivate(PKCS8EncodedKeySpec(...))`
         * from stored bytes: Conscrypt (Android's default JCE provider) doesn't retain CRT
         * parameters across that round trip, so this throws for such a key. Callers that already
         * have the public key material separately (e.g. [security.domain.KeyBackupRepository]'s
         * `.kkey` round trip, which stores the public key independently of the private key) should
         * use that directly instead of calling this.
         */
        fun fromExistingKey(privateKey: PrivateKey): EncryptionKeyPair {
            val keyFactory = KeyFactory.getInstance("RSA")
            val crtSpec = try {
                keyFactory.getKeySpec(privateKey, RSAPrivateCrtKeySpec::class.java)
            } catch (e: Exception) {
                // GeneralSecurityException for a key this provider can't translate at all;
                // ClassCastException for one it translates into a non-CRT RSAPrivateKeySpec
                // instead (Conscrypt's behavior for a PKCS8-reconstructed key - see kdoc above).
                if (e is GeneralSecurityException || e is ClassCastException) {
                    throw IllegalArgumentException("privateKey must carry its CRT parameters (as any KeyPairGenerator-produced RSA private key does).", e)
                }
                throw e
            }
            val publicKey = keyFactory.generatePublic(RSAPublicKeySpec(crtSpec.modulus, crtSpec.publicExponent))
            return EncryptionKeyPair(privateKey, publicKey.encoded, privateKey.encoded)
        }
    }
}
