package com.example.capture.security.ui

/**
 * The currently-active key-management prompt, if any - a closed state machine rendered by
 * [EncryptionKeyScreen] rather than a chain of awaited dialog calls.
 */
sealed interface KeyDialog {
    data object None : KeyDialog

    /** Shown before create/import when a key file already exists and would be replaced. */
    data class ConfirmReplaceKey(val flow: ReplaceFlow) : KeyDialog

    data class SignInPrompt(val fingerprintHint: String?, val error: String? = null) : KeyDialog
    data class CreateKeyEnterPassphrase(val error: String? = null) : KeyDialog
    data class CreateKeyConfirmPassphrase(val firstPassphrase: String, val error: String? = null) : KeyDialog
    data class ChangePassphraseCurrent(val fingerprintHint: String?, val error: String? = null) : KeyDialog
    data class ChangePassphraseNew(val currentPassphrase: String, val error: String? = null) : KeyDialog
    data class ChangePassphraseConfirm(val currentPassphrase: String, val newPassphrase: String, val error: String? = null) : KeyDialog

    data class InfoMessage(val title: String, val message: String) : KeyDialog
}

enum class ReplaceFlow { CREATE, IMPORT }

/** One-off effects the dialog state machine can't express - launching system UI. */
sealed interface KeySessionEvent {
    data object LaunchImportPicker : KeySessionEvent
    data class LaunchExportShare(val intent: android.content.Intent) : KeySessionEvent
}
