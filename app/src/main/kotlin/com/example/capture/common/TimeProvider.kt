package com.example.capture.common

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Indirection around wall-clock time so tests can control elapsed time deterministically
 * instead of relying on real delays (e.g. when asserting debounce behavior).
 */
interface TimeProvider {
    fun currentTimeMillis(): Long
}

@Singleton
class SystemTimeProvider @Inject constructor() : TimeProvider {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}
