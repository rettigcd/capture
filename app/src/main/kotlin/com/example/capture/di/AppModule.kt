package com.example.capture.di

import com.example.capture.common.ApplicationScope
import com.example.capture.common.DefaultDispatcherProvider
import com.example.capture.common.DispatcherProvider
import com.example.capture.common.SystemTimeProvider
import com.example.capture.common.TimeProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    abstract fun bindDispatcherProvider(impl: DefaultDispatcherProvider): DispatcherProvider

    @Binds
    abstract fun bindTimeProvider(impl: SystemTimeProvider): TimeProvider

    companion object {
        /** See [ApplicationScope]'s kdoc for why this exists instead of `GlobalScope`. */
        @Provides
        @Singleton
        @ApplicationScope
        fun provideApplicationScope(dispatcherProvider: DispatcherProvider): CoroutineScope =
            CoroutineScope(SupervisorJob() + dispatcherProvider.default)
    }
}
