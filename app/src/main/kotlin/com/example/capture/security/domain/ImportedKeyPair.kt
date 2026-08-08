package com.example.capture.security.domain

import java.security.PrivateKey

data class ImportedKeyPair(
    val privateKey: PrivateKey,
    val publicKeyBase64: String,
    val fingerprintSha256: String,
)
