package com.example.capture.di

import com.example.capture.voice.data.AndroidSpeechRecognizerAdapter
import com.example.capture.voice.domain.VoiceCommandMatcher
import com.example.capture.voice.domain.VoiceCommandRecognizer
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class VoiceModule {

    @Binds
    abstract fun bindVoiceCommandRecognizer(impl: AndroidSpeechRecognizerAdapter): VoiceCommandRecognizer

    companion object {
        // See VoiceCommandMatcher's kdoc for why this is a @Provides function instead of an
        // @Inject constructor.
        @Provides
        @Singleton
        fun provideVoiceCommandMatcher(): VoiceCommandMatcher = VoiceCommandMatcher()
    }
}
