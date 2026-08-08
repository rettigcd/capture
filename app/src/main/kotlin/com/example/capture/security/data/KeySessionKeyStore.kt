package com.example.capture.security.data

import java.security.PrivateKey

/**
 * The in-memory key-state mutation surface [DefaultKeySessionRepository] drives on
 * [KencPhotoEncryptor] (`setPublicKey`/`setPrivateKey`/`clearKeys`) - split out from that concrete
 * class into its own interface so tests can substitute a fake without needing real RSA/AES-GCM
 * plumbing, while still keeping this surface out of the [com.example.capture.security.domain.PhotoEncryptor]
 * domain interface (which only needs `encrypt`/`decrypt`).
 */
interface KeySessionKeyStore {
    val hasPublicKey: Boolean
    val hasDecryptionKey: Boolean

    fun setPublicKey(publicKeyBase64: String)

    /** @return true if [candidate] was accepted (null clears the key; a non-null key must match the currently-set public key). */
    fun setPrivateKey(candidate: PrivateKey?): Boolean

    fun clearKeys()
}
