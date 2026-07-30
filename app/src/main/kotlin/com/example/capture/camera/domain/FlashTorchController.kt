package com.example.capture.camera.domain

/**
 * Forces the camera flash and torch off (see "Flash and Torch Restrictions" in app-spec.md).
 * There is currently no user-facing control that turns either on, but this exists so that
 * constraint is explicit and actively enforced - regardless of whatever state the flash/torch
 * were previously in - rather than merely relying on nothing else ever enabling them.
 */
interface FlashTorchController {
    suspend fun disableFlashAndTorch()
}
