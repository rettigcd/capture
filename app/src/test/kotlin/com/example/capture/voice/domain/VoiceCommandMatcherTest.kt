package com.example.capture.voice.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VoiceCommandMatcherTest {

    private val matcher = VoiceCommandMatcher()

    @Test
    fun `normalize lowercases trims and strips punctuation`() {
        assertThat(matcher.normalize("  Photo!  ")).isEqualTo("photo")
        assertThat(matcher.normalize("Cheese??")).isEqualTo("cheese")
        assertThat(matcher.normalize("Take   a   PICTURE")).isEqualTo("take a picture")
    }

    @Test
    fun `exact configured phrases match`() {
        assertThat(matcher.match("photo")).isEqualTo("photo")
        assertThat(matcher.match("Picture")).isEqualTo("picture")
        assertThat(matcher.match("CAPTURE")).isEqualTo("capture")
        assertThat(matcher.match("cheese")).isEqualTo("cheese")
    }

    @Test
    fun `a configured command inside a longer utterance still matches`() {
        assertThat(matcher.match("okay cheese now")).isEqualTo("cheese")
        assertThat(matcher.match("take a picture please")).isEqualTo("picture")
    }

    @Test
    fun `unrelated phrases are rejected`() {
        assertThat(matcher.match("hello there")).isNull()
        assertThat(matcher.match("what time is it")).isNull()
        assertThat(matcher.match("")).isNull()
        assertThat(matcher.match("   ")).isNull()
    }

    @Test
    fun `a custom vocabulary can be supplied`() {
        val custom = VoiceCommandMatcher(vocabulary = setOf("snap"))
        assertThat(custom.match("snap")).isEqualTo("snap")
        assertThat(custom.match("photo")).isNull()
    }
}
