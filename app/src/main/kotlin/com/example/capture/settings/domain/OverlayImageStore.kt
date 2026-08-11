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
     * Up to [com.example.capture.settings.domain.AppSettings.MAX_COVER_PHOTOS] copies can exist at
     * once (one per configured cover photo), so each call must persist to a distinct location
     * rather than overwriting a previous one.
     */
    suspend fun persist(sourceUriString: String): String

    /**
     * Best-effort cleanup of a previously-[persist]ed copy once its cover photo is deleted from
     * the list. Never throws - a failed cleanup just leaves an orphaned file behind, which must
     * never block or fail the deletion itself.
     */
    suspend fun delete(uriString: String)
}
