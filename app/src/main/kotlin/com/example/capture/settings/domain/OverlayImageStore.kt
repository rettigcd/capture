package com.example.capture.settings.domain

/**
 * Durably persists the overlay image so it survives an app/device restart, independent of
 * whatever access grant the Uri it was picked with came with.
 *
 * This exists because the system Photo Picker's read grant for a picked `content://` Uri does
 * *not* reliably survive a real process restart (confirmed on-device: reopening the app after a
 * restart throws `SecurityException: ... does not have permission to access picker uri ...` when
 * trying to load the Uri DataStore had otherwise correctly remembered). Copying the bytes into
 * app-private storage at selection time - while the picker's grant is still valid - avoids
 * depending on that grant's lifetime at all.
 */
interface OverlayImageStore {
    /**
     * Copies the image at [sourceUriString] into durable app-private storage and returns a Uri
     * string that remains readable across restarts. Throws if [sourceUriString] can't be read.
     */
    suspend fun persist(sourceUriString: String): String
}
