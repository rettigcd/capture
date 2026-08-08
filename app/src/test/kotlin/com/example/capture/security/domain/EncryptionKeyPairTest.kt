package com.example.capture.security.domain

import com.google.common.truth.Truth.assertThat
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import org.junit.Assert.assertThrows
import org.junit.Test

class EncryptionKeyPairTest {

    @Test
    fun `generate produces a valid RSA-3072 key pair whose public and private halves match`() {
        val keyPair = EncryptionKeyPair.generate()

        val publicKey = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(keyPair.public64)))
        val privateKey = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(keyPair.private64)))

        assertThat((publicKey as java.security.interfaces.RSAPublicKey).modulus.bitLength()).isEqualTo(3072)
        assertThat((privateKey as java.security.interfaces.RSAPrivateKey).modulus).isEqualTo(publicKey.modulus)
    }

    @Test
    fun `fromExistingKey derives a public64 matching the original generated key pair`() {
        val original = EncryptionKeyPair.generate()

        val rebuilt = EncryptionKeyPair.fromExistingKey(original.privateKey)

        assertThat(rebuilt.public64).isEqualTo(original.public64)
        assertThat(rebuilt.private64).isEqualTo(original.private64)
    }

    @Test
    fun `fromExistingKey rejects a private key without CRT parameters`() {
        // A real PrivateKey (even one whose provider doesn't implement RSAPrivateCrtKey - see
        // fromExistingKey's kdoc) is still something KeyFactory.getKeySpec can translate; this
        // bogus, unrecognized stand-in is not, to exercise the guard.
        val nonCrtKey = object : java.security.PrivateKey {
            override fun getAlgorithm() = "RSA"
            override fun getFormat() = "PKCS#8"
            override fun getEncoded() = ByteArray(0)
        }

        assertThrows(IllegalArgumentException::class.java) { EncryptionKeyPair.fromExistingKey(nonCrtKey) }
    }
}
