package com.example.capture.security.data

import com.example.capture.security.domain.ChangePassphraseResult
import com.example.capture.security.domain.CreateKeyResult
import com.example.capture.security.domain.ImportKeyResult
import com.example.capture.security.domain.KeyStatus
import com.example.capture.security.domain.SignInResult
import com.example.capture.testing.FakeKeyBackupRepository
import com.example.capture.testing.FakePhotoEncryptor
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultKeySessionRepositoryTest {

    private fun buildRepository(
        keyBackupRepository: FakeKeyBackupRepository = FakeKeyBackupRepository(),
        photoEncryptor: FakePhotoEncryptor = FakePhotoEncryptor(),
        testScheduler: TestCoroutineScheduler,
    ): DefaultKeySessionRepository {
        val applicationScope = CoroutineScope(StandardTestDispatcher(testScheduler))
        return DefaultKeySessionRepository(keyBackupRepository, photoEncryptor, applicationScope)
    }

    @Test
    fun `initial state has no key`() = runTest {
        val sut = buildRepository(testScheduler = testScheduler)
        assertThat(sut.state.value.keyStatus).isEqualTo(KeyStatus.NONE)
        assertThat(sut.state.value.hasKeyFile).isFalse()
    }

    @Test
    fun `createKey activates the new key and reports PRIVATE status`() = runTest {
        val sut = buildRepository(testScheduler = testScheduler)

        val result = sut.createKey("passphrase123")

        assertThat(result).isEqualTo(CreateKeyResult.Success)
        assertThat(sut.state.value.keyStatus).isEqualTo(KeyStatus.PRIVATE)
        assertThat(sut.state.value.hasKeyFile).isTrue()
    }

    @Test
    fun `signIn with no key file returns NoKeyFile`() = runTest {
        val sut = buildRepository(testScheduler = testScheduler)

        assertThat(sut.signIn("whatever")).isEqualTo(SignInResult.NoKeyFile)
    }

    @Test
    fun `signOut then signIn with the correct passphrase re-unlocks`() = runTest {
        val sut = buildRepository(testScheduler = testScheduler)
        sut.createKey("secret123")

        sut.signOut()
        assertThat(sut.state.value.keyStatus).isEqualTo(KeyStatus.PUBLIC)

        val result = sut.signIn("secret123")

        assertThat(result).isEqualTo(SignInResult.Success)
        assertThat(sut.state.value.keyStatus).isEqualTo(KeyStatus.PRIVATE)
    }

    @Test
    fun `signIn with the wrong passphrase returns IncorrectPassphrase and stays locked`() = runTest {
        val sut = buildRepository(testScheduler = testScheduler)
        sut.createKey("secret123")
        sut.signOut()

        val result = sut.signIn("wrong passphrase")

        assertThat(result).isEqualTo(SignInResult.IncorrectPassphrase)
        assertThat(sut.state.value.keyStatus).isEqualTo(KeyStatus.PUBLIC)
    }

    @Test
    fun `importKeyFile clears any signed-in session and lands on PUBLIC`() = runTest {
        val sut = buildRepository(testScheduler = testScheduler)
        sut.createKey("secret123")
        assertThat(sut.state.value.keyStatus).isEqualTo(KeyStatus.PRIVATE)

        val result = sut.importKeyFile(byteArrayOf(1, 2, 3))

        assertThat(result).isEqualTo(ImportKeyResult.Success)
        assertThat(sut.state.value.keyStatus).isEqualTo(KeyStatus.PUBLIC)
        assertThat(sut.state.value.hasKeyFile).isTrue()
    }

    @Test
    fun `changePassphrase with the correct current passphrase activates the new one`() = runTest {
        val sut = buildRepository(testScheduler = testScheduler)
        sut.createKey("old-secret")

        val result = sut.changePassphrase("old-secret", "new-secret")
        assertThat(result).isEqualTo(ChangePassphraseResult.Success)

        sut.signOut()
        assertThat(sut.signIn("new-secret")).isEqualTo(SignInResult.Success)
    }

    @Test
    fun `changePassphrase with the wrong current passphrase returns IncorrectCurrentPassphrase`() = runTest {
        val sut = buildRepository(testScheduler = testScheduler)
        sut.createKey("old-secret")

        val result = sut.changePassphrase("wrong", "new-secret")

        assertThat(result).isEqualTo(ChangePassphraseResult.IncorrectCurrentPassphrase)
    }

    @Test
    fun `initialize with an existing key file loads PUBLIC status without a private key`() = runTest {
        val keyBackupRepository = FakeKeyBackupRepository()
        val sut = buildRepository(keyBackupRepository, testScheduler = testScheduler)
        keyBackupRepository.createKeyFile("secret123") // simulates a key file that existed before this process started

        sut.initialize()

        assertThat(sut.state.value.keyStatus).isEqualTo(KeyStatus.PUBLIC)
        assertThat(sut.state.value.hasKeyFile).isTrue()
    }

    @Test
    fun `initialize with no key file leaves state at NONE`() = runTest {
        val sut = buildRepository(testScheduler = testScheduler)

        sut.initialize()

        assertThat(sut.state.value.keyStatus).isEqualTo(KeyStatus.NONE)
    }

    @Test
    fun `verifyPassphrase does not change sign-in state`() = runTest {
        val sut = buildRepository(testScheduler = testScheduler)
        sut.createKey("secret123")
        sut.signOut()

        assertThat(sut.verifyPassphrase("secret123")).isTrue()
        assertThat(sut.verifyPassphrase("wrong")).isFalse()
        assertThat(sut.state.value.keyStatus).isEqualTo(KeyStatus.PUBLIC) // unchanged by either call
    }

    @Test
    fun `the inactivity timer locks a signed-in session after 5 minutes of no auth-state changes`() = runTest {
        val sut = buildRepository(testScheduler = testScheduler)
        sut.createKey("secret123") // also starts the inactivity timer, via refreshState
        assertThat(sut.state.value.keyStatus).isEqualTo(KeyStatus.PRIVATE)

        advanceTimeBy(5.minutes.inWholeMilliseconds + 1)
        runCurrent()

        assertThat(sut.state.value.keyStatus).isEqualTo(KeyStatus.PUBLIC)
    }

    @Test
    fun `the inactivity timer does not fire before 5 minutes`() = runTest {
        val sut = buildRepository(testScheduler = testScheduler)
        sut.createKey("secret123")

        advanceTimeBy(4.minutes.inWholeMilliseconds)
        runCurrent()

        assertThat(sut.state.value.keyStatus).isEqualTo(KeyStatus.PRIVATE)
    }

    @Test
    fun `an auth-state change resets the inactivity timer instead of stacking a second lock`() = runTest {
        val sut = buildRepository(testScheduler = testScheduler)
        sut.createKey("secret123")

        advanceTimeBy(4.minutes.inWholeMilliseconds)
        runCurrent()
        sut.signOut() // resets the timer via refreshState -> initializeLocked -> refreshState
        sut.signIn("secret123") // signed back in, timer reset again

        advanceTimeBy(4.minutes.inWholeMilliseconds)
        runCurrent()

        // Only 4 minutes have passed since the last reset (signIn), even though more than 5
        // minutes have passed since createKey - the timer must not have fired early.
        assertThat(sut.state.value.keyStatus).isEqualTo(KeyStatus.PRIVATE)
    }
}
