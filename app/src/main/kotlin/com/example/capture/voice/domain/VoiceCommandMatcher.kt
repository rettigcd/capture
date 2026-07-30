package com.example.capture.voice.domain

import java.util.Locale

/**
 * Normalizes recognized speech text and matches it against a small configured vocabulary.
 *
 * Kept entirely free of Android speech APIs so it can be unit tested directly with plain
 * strings, independent of [android.speech.SpeechRecognizer] or any device.
 *
 * Not constructor-injected directly: Dagger/Hilt-generated factories always pass every
 * constructor argument explicitly and ignore Kotlin default values, so `di.VoiceModule` provides
 * this type with an explicit `@Provides` function instead of an `@Inject` constructor. That keeps
 * [DEFAULT_VOCABULARY] as a real default for callers (including tests) that construct this class
 * directly.
 */
class VoiceCommandMatcher(
    private val vocabulary: Set<String> = DEFAULT_VOCABULARY,
) {
    /** Lowercases, trims, and strips punctuation so "Photo!" and " photo " compare equal. */
    fun normalize(text: String): String =
        text
            .lowercase(Locale.US)
            .replace(NON_ALPHANUMERIC, " ")
            .trim()
            .replace(EXTRA_WHITESPACE, " ")

    /**
     * Returns the configured command phrase [text] matches, or `null` if none of them do.
     * Matches both an exact utterance ("cheese") and a command appearing as one word within a
     * longer utterance ("okay cheese now"), since recognizers often return extra words.
     */
    fun match(text: String): String? {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return null
        if (normalized in vocabulary) return normalized
        val words = normalized.split(" ")
        return vocabulary.firstOrNull { command -> command in words }
    }

    companion object {
        val DEFAULT_VOCABULARY: Set<String> = setOf("photo", "picture", "capture", "cheese")
        private val NON_ALPHANUMERIC = Regex("[^a-z0-9 ]")
        private val EXTRA_WHITESPACE = Regex(" +")
    }
}
