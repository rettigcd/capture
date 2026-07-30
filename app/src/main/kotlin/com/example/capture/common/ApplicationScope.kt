package com.example.capture.common

import javax.inject.Qualifier

/**
 * Qualifies a [kotlinx.coroutines.CoroutineScope] whose lifetime is the process, not a single
 * `ViewModel` or `Activity`. Used for cleanup work (e.g. releasing the voice recognizer) that
 * must still run after `ViewModel.onCleared()` has already cancelled `viewModelScope` -
 * deliberately not `GlobalScope`, since this scope is still owned, injectable, cancellable as a
 * unit, and swappable for a `TestScope` in tests.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
