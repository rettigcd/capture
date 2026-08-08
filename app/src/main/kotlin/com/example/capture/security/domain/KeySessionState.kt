package com.example.capture.security.domain

data class KeySessionState(
    val keyStatus: KeyStatus = KeyStatus.NONE,
    val keyFingerprint: String? = null,
    val hasKeyFile: Boolean = false,
)

sealed interface SignInResult {
    data object Success : SignInResult
    data object NoKeyFile : SignInResult
    data object IncorrectPassphrase : SignInResult
    data class Failure(val message: String) : SignInResult
}

sealed interface CreateKeyResult {
    data object Success : CreateKeyResult
    data class Failure(val message: String) : CreateKeyResult
}

sealed interface ImportKeyResult {
    data object Success : ImportKeyResult
    data class Failure(val message: String) : ImportKeyResult
}

sealed interface ChangePassphraseResult {
    data object Success : ChangePassphraseResult
    data object IncorrectCurrentPassphrase : ChangePassphraseResult
    data class Failure(val message: String) : ChangePassphraseResult
}
