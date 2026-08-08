package com.example.capture.security.domain

/** Thrown when the key is missing or invalid for accessing encrypted data. */
class PrivateKeyException(val reason: Reason, cause: Throwable? = null) : Exception(reason.name, cause) {
    enum class Reason { MISSING_KEY, INVALID_KEY }
}
