package com.example.capture.settings.ui

/** One-off effects the settings screen's state can't express - launching system UI. */
sealed interface SettingsEvent {
    /** Launches the Storage Access Framework folder picker for "Encrypt saved photos" (see `SettingsRoute`). */
    data object LaunchFolderPicker : SettingsEvent
}
