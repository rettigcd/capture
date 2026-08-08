package com.example.capture.security.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.capture.R
import com.example.capture.security.data.buildKeyFileShareIntent
import com.example.capture.security.domain.ChangePassphraseResult
import com.example.capture.security.domain.CreateKeyResult
import com.example.capture.security.domain.ImportKeyResult
import com.example.capture.security.domain.KeySessionRepository
import com.example.capture.security.domain.KeySessionState
import com.example.capture.security.domain.KeyStatus
import com.example.capture.security.domain.SignInResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the key-management dialog state machine (see [KeyDialog]) and delegates the actual
 * crypto/file work to [KeySessionRepository], which is deliberately `Context`-free. This
 * ViewModel holds the application `Context` only for the two operations that are inescapably
 * Android-API-bound: reading a picked import file's bytes from a `content://` Uri, and building a
 * `FileProvider`-backed share `Intent` for export - both are thin glue, not business logic, and an
 * `Application` context (not an Activity/View) is safe to hold for a ViewModel's lifetime.
 */
@HiltViewModel
class KeySessionViewModel @Inject constructor(
    private val repository: KeySessionRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val sessionState: StateFlow<KeySessionState> = repository.state

    private val _dialog = MutableStateFlow<KeyDialog>(KeyDialog.None)
    val dialog: StateFlow<KeyDialog> = _dialog.asStateFlow()

    private val _events = MutableSharedFlow<KeySessionEvent>()
    val events: SharedFlow<KeySessionEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch { repository.initialize() }
    }

    // ---- entry points ----

    fun onSignInOrLockClicked() {
        if (sessionState.value.keyStatus == KeyStatus.PRIVATE) {
            viewModelScope.launch { repository.signOut() }
        } else {
            _dialog.value = KeyDialog.SignInPrompt(fingerprintHint(sessionState.value.keyFingerprint))
        }
    }

    fun onCreateKeyRequested() {
        _dialog.value = if (sessionState.value.hasKeyFile) {
            KeyDialog.ConfirmReplaceKey(ReplaceFlow.CREATE)
        } else {
            KeyDialog.CreateKeyEnterPassphrase()
        }
    }

    fun onImportKeyRequested() {
        if (sessionState.value.hasKeyFile) {
            _dialog.value = KeyDialog.ConfirmReplaceKey(ReplaceFlow.IMPORT)
        } else {
            emitEvent(KeySessionEvent.LaunchImportPicker)
        }
    }

    fun onConfirmReplaceKey(flow: ReplaceFlow) {
        when (flow) {
            ReplaceFlow.CREATE -> _dialog.value = KeyDialog.CreateKeyEnterPassphrase()
            ReplaceFlow.IMPORT -> {
                _dialog.value = KeyDialog.None
                emitEvent(KeySessionEvent.LaunchImportPicker)
            }
        }
    }

    fun onExportKeyRequested() {
        val state = sessionState.value
        if (!state.hasKeyFile) {
            _dialog.value = KeyDialog.InfoMessage(
                context.getString(R.string.encryption_key_export_key),
                context.getString(R.string.encryption_key_export_no_key_message),
            )
            return
        }
        val intent = buildKeyFileShareIntent(context, File(repository.keyFilePath()))
        emitEvent(KeySessionEvent.LaunchExportShare(intent))
    }

    fun onChangePassphraseRequested() {
        if (sessionState.value.keyStatus != KeyStatus.PRIVATE) {
            _dialog.value = KeyDialog.InfoMessage(
                context.getString(R.string.encryption_key_change_passphrase_title),
                context.getString(R.string.encryption_key_change_requires_sign_in_message),
            )
            return
        }
        _dialog.value = KeyDialog.ChangePassphraseCurrent(fingerprintHint(sessionState.value.keyFingerprint))
    }

    fun onDialogCancelled() {
        _dialog.value = KeyDialog.None
    }

    // ---- dialog step submission ----

    fun onSignInPassphraseSubmitted(passphrase: String) {
        viewModelScope.launch {
            when (val result = repository.signIn(passphrase)) {
                SignInResult.Success -> _dialog.value = KeyDialog.None
                SignInResult.NoKeyFile ->
                    _dialog.value = KeyDialog.InfoMessage(
                        context.getString(R.string.encryption_key_unlock_title),
                        context.getString(R.string.encryption_key_no_key_found_message),
                    )
                SignInResult.IncorrectPassphrase ->
                    _dialog.value = signInDialogWithError(context.getString(R.string.encryption_key_incorrect_passphrase_error))
                is SignInResult.Failure -> _dialog.value = signInDialogWithError(result.message)
            }
        }
    }

    fun onCreateKeyPassphraseEntered(passphrase: String) {
        if (passphrase.length < MIN_PASSPHRASE_LENGTH) {
            _dialog.value = KeyDialog.CreateKeyEnterPassphrase(
                error = context.getString(R.string.encryption_key_passphrase_min_length_error, MIN_PASSPHRASE_LENGTH),
            )
            return
        }
        _dialog.value = KeyDialog.CreateKeyConfirmPassphrase(passphrase)
    }

    fun onCreateKeyPassphraseConfirmed(confirmPassphrase: String) {
        val current = _dialog.value as? KeyDialog.CreateKeyConfirmPassphrase ?: return
        if (confirmPassphrase != current.firstPassphrase) {
            _dialog.value = current.copy(error = context.getString(R.string.encryption_key_passphrase_mismatch_error))
            return
        }
        viewModelScope.launch {
            val title = context.getString(R.string.encryption_key_create_title)
            _dialog.value = when (val result = repository.createKey(current.firstPassphrase)) {
                CreateKeyResult.Success -> KeyDialog.InfoMessage(title, context.getString(R.string.encryption_key_create_success_message))
                is CreateKeyResult.Failure -> KeyDialog.InfoMessage(title, result.message)
            }
        }
    }

    fun onChangePassphraseCurrentEntered(currentPassphrase: String) {
        _dialog.value = KeyDialog.ChangePassphraseNew(currentPassphrase)
    }

    fun onChangePassphraseNewEntered(newPassphrase: String) {
        val current = _dialog.value as? KeyDialog.ChangePassphraseNew ?: return
        if (newPassphrase.length < MIN_PASSPHRASE_LENGTH) {
            _dialog.value = current.copy(
                error = context.getString(R.string.encryption_key_passphrase_min_length_error, MIN_PASSPHRASE_LENGTH),
            )
            return
        }
        _dialog.value = KeyDialog.ChangePassphraseConfirm(current.currentPassphrase, newPassphrase)
    }

    fun onChangePassphraseConfirmEntered(confirmPassphrase: String) {
        val current = _dialog.value as? KeyDialog.ChangePassphraseConfirm ?: return
        if (confirmPassphrase != current.newPassphrase) {
            _dialog.value = current.copy(error = context.getString(R.string.encryption_key_passphrase_mismatch_error))
            return
        }
        viewModelScope.launch {
            val title = context.getString(R.string.encryption_key_change_passphrase_title)
            _dialog.value = when (val result = repository.changePassphrase(current.currentPassphrase, current.newPassphrase)) {
                ChangePassphraseResult.Success -> KeyDialog.InfoMessage(title, context.getString(R.string.encryption_key_change_success_message))
                ChangePassphraseResult.IncorrectCurrentPassphrase ->
                    KeyDialog.ChangePassphraseCurrent(
                        fingerprintHint(sessionState.value.keyFingerprint),
                        error = context.getString(R.string.encryption_key_change_incorrect_current_error),
                    )
                is ChangePassphraseResult.Failure -> KeyDialog.InfoMessage(title, result.message)
            }
        }
    }

    // ---- import/export glue (the two genuinely Context-bound operations) ----

    fun onImportFilePicked(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
            val title = context.getString(R.string.encryption_key_import_key)
            if (bytes == null) {
                _dialog.value = KeyDialog.InfoMessage(title, context.getString(R.string.encryption_key_import_read_failed_message))
                return@launch
            }
            when (val result = repository.importKeyFile(bytes)) {
                ImportKeyResult.Success ->
                    _dialog.value = KeyDialog.InfoMessage(title, context.getString(R.string.encryption_key_import_success_message))
                is ImportKeyResult.Failure -> _dialog.value = KeyDialog.InfoMessage(title, result.message)
            }
        }
    }

    private fun signInDialogWithError(message: String): KeyDialog.SignInPrompt {
        val current = _dialog.value as? KeyDialog.SignInPrompt
        return KeyDialog.SignInPrompt(current?.fingerprintHint ?: fingerprintHint(sessionState.value.keyFingerprint), error = message)
    }

    private fun emitEvent(event: KeySessionEvent) {
        viewModelScope.launch { _events.emit(event) }
    }

    private fun fingerprintHint(fingerprint: String?): String? = fingerprint?.takeIf { it.length >= 16 }?.take(16)

    private companion object {
        const val MIN_PASSPHRASE_LENGTH = 8
    }
}
