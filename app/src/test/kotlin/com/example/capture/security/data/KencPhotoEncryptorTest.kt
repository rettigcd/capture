package com.example.capture.security.data

import com.example.capture.security.domain.EncryptionKeyPair
import com.example.capture.security.domain.PrivateKeyException
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class KencPhotoEncryptorTest {

    @Test
    fun `encrypt then decrypt round trips the plaintext after signing in with the matching key pair`() {
        val keyPair = EncryptionKeyPair.generate()
        val sut = KencPhotoEncryptor()
        sut.setPublicKey(keyPair.public64)
        assertThat(sut.setPrivateKey(keyPair.privateKey)).isTrue()

        val plain = "hello encrypted world".toByteArray()
        val decrypted = sut.decrypt(sut.encrypt(plain))

        assertThat(decrypted).isEqualTo(plain)
    }

    @Test
    fun `decrypt without a private key throws MISSING_KEY`() {
        val keyPair = EncryptionKeyPair.generate()
        val sut = KencPhotoEncryptor()
        sut.setPublicKey(keyPair.public64)
        val ciphertext = sut.encrypt("secret".toByteArray())

        val thrown = assertThrows(PrivateKeyException::class.java) { sut.decrypt(ciphertext) }

        assertThat(thrown.reason).isEqualTo(PrivateKeyException.Reason.MISSING_KEY)
    }

    @Test
    fun `decrypt with a mismatched private key throws INVALID_KEY`() {
        val keyPair = EncryptionKeyPair.generate()
        val otherKeyPair = EncryptionKeyPair.generate()
        val sut = KencPhotoEncryptor()
        sut.setPublicKey(keyPair.public64)
        val ciphertext = sut.encrypt("secret".toByteArray())

        // setPrivateKey only accepts a key matching the currently-set public key, so swap the
        // public key first to let the mismatched private key through, then decrypt against the
        // ciphertext that was encrypted for the original public key.
        sut.setPublicKey(otherKeyPair.public64)
        sut.setPrivateKey(otherKeyPair.privateKey)

        val thrown = assertThrows(PrivateKeyException::class.java) { sut.decrypt(ciphertext) }

        assertThat(thrown.reason).isEqualTo(PrivateKeyException.Reason.INVALID_KEY)
    }

    @Test
    fun `setPrivateKey rejects a private key that does not match the current public key`() {
        val keyPair = EncryptionKeyPair.generate()
        val otherKeyPair = EncryptionKeyPair.generate()
        val sut = KencPhotoEncryptor()
        sut.setPublicKey(keyPair.public64)

        assertThat(sut.setPrivateKey(otherKeyPair.privateKey)).isFalse()
        assertThat(sut.hasDecryptionKey).isFalse()
    }

    @Test
    fun `clearKeys resets both public and private key state`() {
        val keyPair = EncryptionKeyPair.generate()
        val sut = KencPhotoEncryptor()
        sut.setPublicKey(keyPair.public64)
        sut.setPrivateKey(keyPair.privateKey)

        sut.clearKeys()

        assertThat(sut.hasPublicKey).isFalse()
        assertThat(sut.hasDecryptionKey).isFalse()
    }

    @Test
    fun `setPublicKey clears any previously-set private key`() {
        val keyPair = EncryptionKeyPair.generate()
        val sut = KencPhotoEncryptor()
        sut.setPublicKey(keyPair.public64)
        sut.setPrivateKey(keyPair.privateKey)
        assertThat(sut.hasDecryptionKey).isTrue()

        sut.setPublicKey(EncryptionKeyPair.generate().public64)

        assertThat(sut.hasDecryptionKey).isFalse()
    }

    @Test
    fun `encryptToKencFile produces a genuine two-block kenc file that round trips the image and metadata`() {
        val keyPair = EncryptionKeyPair.generate()
        val sut = KencPhotoEncryptor()
        sut.setPublicKey(keyPair.public64)
        sut.setPrivateKey(keyPair.privateKey)

        val imageBytes = byteArrayOf(1, 2, 3, 4, 5)
        val metadataJson = """{"capturedAtMillis":1234}"""

        val fileBytes = sut.encryptToKencFile(imageBytes, metadataJson)

        val header = KencCodec.readHeader(fileBytes)
        val (imageBlock, imageBlockLength) = KencCodec.readBlock(fileBytes, KencCodec.HEADER_SIZE)
        assertThat(imageBlockLength.toLong()).isEqualTo(header.imageBlockLength)
        assertThat(sut.decryptBlock(imageBlock)).isEqualTo(imageBytes)

        val (metadataBlock, _) = KencCodec.readBlock(fileBytes, KencCodec.HEADER_SIZE + imageBlockLength)
        assertThat(String(sut.decryptBlock(metadataBlock), Charsets.UTF_8)).isEqualTo(metadataJson)
    }
}
