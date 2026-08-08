package com.example.capture.security.data

import android.app.Application
import com.example.capture.security.domain.IncorrectPassphraseException
import com.example.capture.testing.TestDispatcherProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Runs on the JVM via Robolectric (real `context.filesDir` I/O, no emulator) - exercises the
 * `.kkey` byte format this repository ports from the keibler source app's `KeyBackupService`.
 * Uses [Dispatchers.Unconfined] rather than a virtual-time `StandardTestDispatcher`: this
 * repository has no `delay()`/timing logic to control, just file I/O, so immediate/inline
 * execution keeps these tests simple.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FileKeyBackupRepositoryTest {

    private val context get() = RuntimeEnvironment.getApplication()
    private val dispatcherProvider = TestDispatcherProvider(Dispatchers.Unconfined)

    private fun buildRepository() = FileKeyBackupRepository(context, dispatcherProvider)

    private suspend fun incorrectPassphraseOrNull(block: suspend () -> Unit): IncorrectPassphraseException? = try {
        block()
        null
    } catch (e: IncorrectPassphraseException) {
        e
    }

    @Test
    fun `hasKeyFile is false until a key is created`() = runTest {
        val sut = buildRepository()
        assertThat(sut.hasKeyFile()).isFalse()

        sut.createKeyFile("correct horse battery staple")

        assertThat(sut.hasKeyFile()).isTrue()
    }

    @Test
    fun `readPublicKeyInfo returns null when there is no key file`() = runTest {
        assertThat(buildRepository().readPublicKeyInfo()).isNull()
    }

    @Test
    fun `create then readPublicKeyInfo then importFromCurrentFile round trips the same key pair`() = runTest {
        val sut = buildRepository()
        val created = sut.createKeyFile("correct horse battery staple")

        val info = sut.readPublicKeyInfo()
        assertThat(info?.publicKeyBase64).isEqualTo(created.public64)

        val imported = sut.importFromCurrentFile("correct horse battery staple")
        assertThat(imported.publicKeyBase64).isEqualTo(created.public64)
        assertThat(imported.privateKey.encoded).isEqualTo(created.privateKey.encoded)
        assertThat(imported.fingerprintSha256).isEqualTo(info?.fingerprintSha256)
    }

    @Test
    fun `importFromCurrentFile with the wrong passphrase throws IncorrectPassphraseException`() = runTest {
        val sut = buildRepository()
        sut.createKeyFile("correct horse battery staple")

        val thrown = incorrectPassphraseOrNull { sut.importFromCurrentFile("wrong passphrase") }

        assertThat(thrown).isNotNull()
    }

    @Test
    fun `creating a second key file backs up the first one`() = runTest {
        val sut = buildRepository()
        sut.createKeyFile("first passphrase")
        sut.createKeyFile("second passphrase")

        val backups = context.filesDir.listFiles { file -> file.name.startsWith("KeyPair.kkey.") && file.name.endsWith(".bak") }
        assertThat(backups).isNotNull()
        assertThat(backups!!.size).isEqualTo(1)

        // The current file must decrypt with the second passphrase only.
        assertThat(sut.importFromCurrentFile("second passphrase").publicKeyBase64).isNotEmpty()
        assertThat(incorrectPassphraseOrNull { sut.importFromCurrentFile("first passphrase") }).isNotNull()
    }

    @Test
    fun `changePassphrase re-encrypts under the new passphrase and rejects the old one`() = runTest {
        val sut = buildRepository()
        val created = sut.createKeyFile("old passphrase")

        sut.changePassphrase("old passphrase", "new passphrase")

        val imported = sut.importFromCurrentFile("new passphrase")
        assertThat(imported.privateKey.encoded).isEqualTo(created.privateKey.encoded)
        assertThat(incorrectPassphraseOrNull { sut.importFromCurrentFile("old passphrase") }).isNotNull()
    }

    @Test
    fun `changePassphrase with the wrong current passphrase throws and leaves the file untouched`() = runTest {
        val sut = buildRepository()
        sut.createKeyFile("old passphrase")

        val thrown = incorrectPassphraseOrNull { sut.changePassphrase("wrong", "new passphrase") }
        assertThat(thrown).isNotNull()

        // Original passphrase still works - the failed attempt didn't overwrite the file.
        assertThat(sut.importFromCurrentFile("old passphrase").publicKeyBase64).isNotEmpty()
    }

    @Test
    fun `importKeyFileBytes overwrites the key file with the given bytes, restoring an earlier export`() = runTest {
        val sut = buildRepository()
        sut.createKeyFile("first passphrase")
        val firstExport = sut.exportKeyFileBytes()

        sut.createKeyFile("second passphrase") // now the current file only decrypts with "second passphrase"

        sut.importKeyFileBytes(firstExport)

        assertThat(sut.importFromCurrentFile("first passphrase").publicKeyBase64).isNotEmpty()
        assertThat(incorrectPassphraseOrNull { sut.importFromCurrentFile("second passphrase") }).isNotNull()
    }

    @Test
    fun `exportKeyFileBytes returns exactly what was written to disk`() = runTest {
        val sut = buildRepository()
        sut.createKeyFile("correct horse battery staple")

        val exported = sut.exportKeyFileBytes()
        val onDisk = File(sut.keyFilePath()).readBytes()

        assertThat(exported).isEqualTo(onDisk)
    }

    @Test
    fun `keyFilePath points at a file inside context filesDir`() = runTest {
        val sut = buildRepository()
        sut.createKeyFile("correct horse battery staple")

        assertThat(File(sut.keyFilePath()).parentFile).isEqualTo(context.filesDir)
    }
}
