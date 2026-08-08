# Encryption setup

Port the key-management + file-encryption library and its menu UI from the `keibler` Android app
into this app (`Capture`, `com.example.capture`). Source repo, on this same machine:
`C:\Users\Christopher.Rettig\src\rettigcd\keibler\android`. Read the source files directly (paths
below) rather than assuming their exact contents from this doc — this doc records *what to build
and why*, not a byte-for-byte dump of source.

## Why this isn't a plain copy

The source app has **no DI framework** (a hand-written `AppContainer` + `CompositionLocal`) and a
custom `KFileSystem` suspend-file-I/O abstraction plus a `MapperRegistry`/`ImageMapper` storage
model. This app uses **Hilt** (`di/AppModule.kt`, `di/SettingsModule.kt` show the pattern: `@Binds`
in an abstract `@Module` class, `@Inject constructor` impls, `@ApplicationContext` qualifier), does
file I/O directly with `java.io.File` + `DispatcherProvider.io` (see
`settings/data/FileOverlayImageStore.kt`), and follows a strict `domain` (pure Kotlin, no
Android/Compose types) / `data` (the one place touching real Android APIs) / `ui` (stateless Compose
+ ViewModel) split per feature, enforced by this repo's test structure — **do not** import
`KFileSystem`, `MapperRegistry`, or the `AppContainer` pattern; port against this repo's own
conventions instead.

**Format compatibility is load-bearing.** The `.kkey` (encrypted private-key backup) and `.kenc`
(encrypted photo) binary formats must stay byte-for-byte compatible with an existing .NET/MAUI app
and the source Android app. When porting the crypto logic (PBKDF2 iteration count, salt/nonce/tag
sizes, header/AAD strings, RSA key size, PEM formatting), copy the exact constants from source —
do not "clean up," round, or otherwise alter any of them, even if they look arbitrary.

## Source files to read and port

Pure crypto, no Android dependency — port near-verbatim, just repackage:
- `android/core/src/main/kotlin/com/keibler/core/encryption/EncryptionKeyPair.kt`
- `android/core/src/main/kotlin/com/keibler/core/encryption/ImportedKeyPair.kt`
- `android/core/src/main/kotlin/com/keibler/core/storage/encrypted/KencCodec.kt`
- `android/core/src/main/kotlin/com/keibler/core/storage/encrypted/EncryptedBlock.kt`
- `android/core/src/main/kotlin/com/keibler/core/storage/encrypted/PrivateKeyException.kt`
- The `KeyStatus` enum (`NONE`/`PRIVATE`/`PUBLIC`) from
  `android/core/src/main/kotlin/com/keibler/core/storage/MapperRegistry.kt`

Mixed crypto + file I/O — port the crypto, rewrite the I/O against plain `File`:
- `android/core/src/main/kotlin/com/keibler/core/encryption/KeyBackupService.kt` (`.kkey`
  read/write — reads/writes an RSA private key, PBKDF2+AES-GCM wrapped, from a passphrase)

Mixed crypto + Keibler-specific storage coupling — port only the encrypt/decrypt-bytes logic, drop
the rest:
- `android/core/src/main/kotlin/com/keibler/core/storage/encrypted/EncryptedMapper.kt` — its
  `MapperRegistry`/`ImageMapper`/`ImageMetadata`/`ImageFileService` constructor dependencies are
  Keibler's own multi-format image storage system and have no equivalent here; do not port them.
  What's worth keeping is the hybrid RSA-OAEP(AES key) + AES-256-GCM(payload) encrypt/decrypt logic
  itself, built on `KencCodec`/`EncryptedBlock`.

App-layer glue to port and adapt to Hilt/Compose conventions here:
- `android/app/src/main/kotlin/com/keibler/android/data/keysession/KeySessionRepository.kt`
  (sign-in/out, create/import/export/change-passphrase orchestration, 5-minute inactivity auto-lock
  via `ProcessLifecycleOwner`)
- `android/app/src/main/kotlin/com/keibler/android/data/keysession/KeySessionState.kt`
- `android/app/src/main/kotlin/com/keibler/android/storage/KeyFileSharing.kt` (`FileProvider`
  share-Intent builder for key export)
- `android/app/src/main/kotlin/com/keibler/android/ui/keysession/EncryptionKeyScreen.kt`
- `android/app/src/main/kotlin/com/keibler/android/ui/keysession/KeyDialog.kt`
- `android/app/src/main/kotlin/com/keibler/android/ui/keysession/KeySessionViewModel.kt`
- `android/app/src/main/kotlin/com/keibler/android/ui/keysession/KeySessionBar.kt`

## Target layout

New feature package `security/`, mirroring `settings/`'s shape:

```
com/example/capture/security/
  domain/
    EncryptionKeyPair.kt        // pure RSA-3072 keypair gen/wrap/export (ported near-verbatim)
    ImportedKeyPair.kt
    PrivateKeyException.kt
    KeyStatus.kt                 // NONE / PRIVATE / PUBLIC
    KeySessionState.kt           // ported: SignInResult, CreateKeyResult, ImportKeyResult, etc.
    KeyBackupRepository.kt       // interface: create/import/export/changePassphrase over ByteArray
    KeySessionRepository.kt      // interface: sign-in state, auto-lock
    PhotoEncryptor.kt            // NEW interface: encrypt(ByteArray)/decrypt(ByteArray), no
                                  // equivalent in source — see "Photo encryption" below
  data/
    FileKeyBackupRepository.kt   // KeyBackupRepository impl: java.io.File on context.filesDir +
                                  // DispatcherProvider.io, ported crypto from KeyBackupService.kt
    DefaultKeySessionRepository.kt // KeySessionRepository impl, ported from
                                    // data/keysession/KeySessionRepository.kt
    KencCodec.kt                 // ported near-verbatim
    EncryptedBlock.kt            // ported near-verbatim
    KencPhotoEncryptor.kt        // PhotoEncryptor impl: extracted encrypt/decrypt-bytes logic
                                  // from EncryptedMapper.kt, without MapperRegistry/ImageMapper
    KeyFileSharing.kt            // ported near-verbatim (FileProvider share Intent)
  ui/
    EncryptionKeyScreen.kt       // ported, strings moved to strings.xml (source hardcodes them —
                                  // this repo doesn't; see SettingsScreen.kt for the pattern)
    KeyDialog.kt
    KeySessionViewModel.kt       // @HiltViewModel @Inject constructor, not a manual viewModelFactory
    KeySessionRoute.kt           // hiltViewModel() entry point, matching SettingsRoute.kt's shape
```

`di/SecurityModule.kt` (new file, pattern from `di/AppModule.kt`):
```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityModule {
    @Binds abstract fun bindKeyBackupRepository(impl: FileKeyBackupRepository): KeyBackupRepository
    @Binds abstract fun bindKeySessionRepository(impl: DefaultKeySessionRepository): KeySessionRepository
    @Binds abstract fun bindPhotoEncryptor(impl: KencPhotoEncryptor): PhotoEncryptor
}
```

## Wiring into the app

1. **Manifest**: this app's `AndroidManifest.xml` has no `FileProvider` yet. Add one (see the
   source's `AndroidManifest.xml` for the exact `<provider>` block) and a `res/xml/file_paths.xml`
   scoped to `context.filesDir` (mirror `android/app/src/main/res/xml/file_paths.xml`), using this
   app's `applicationId` (`com.example.capture`) for the authority.
2. **Navigation**: `CaptureApp.kt` uses plain string routes in a `NavHost` (`CAMERA_ROUTE`,
   `SETTINGS_ROUTE`), not the source's type-safe `@Serializable` destinations. Add an
   `ENCRYPTION_ROUTE` constant and a `composable(ENCRYPTION_ROUTE) { KeySessionRoute(onBack = { navController.popBackStack() }) }` entry, following the existing two entries.
3. **Entry point**: `SettingsScreen.kt` is a flat, stateless list of controls (no sub-navigation
   rows currently exist there) — add a button/row that calls a new `onNavigateToEncryptionKey`
   callback, threaded through `SettingsRoute.kt` the same way `onBack` already is, and wire the
   actual `navController.navigate(ENCRYPTION_ROUTE)` call at the `CaptureApp.kt` level (matching
   how `onOpenSettings` is wired for the camera→settings navigation today).
4. **Strings**: add every user-facing string (dialog titles/prompts, button labels, error messages)
   to `strings.xml` — this app does not hardcode UI text in Compose files.
5. **Tests**: follow this repo's conventions (see root `CLAUDE.md`) — Fakes, not mocks, added to
   `app/src/test/.../testing/TestDoubles.kt` (`FakeKeyBackupRepository`, `FakeKeySessionRepository`,
   `FakePhotoEncryptor`); pure crypto/domain logic gets plain JUnit tests; Compose screens get
   Robolectric tests via stateless `UiState`/callback params, same as `SettingsScreenTest`.
6. **Format-compat tests**: port (or write fresh, but behavior-equivalent) tests analogous to
   `KeyBackupServiceTest.kt` / `EncryptedMapperTest.kt` / `EncryptDecryptWorkflowTest.kt` from
   `android/core/src/test/kotlin/com/keibler/core/...` to lock in byte-format compatibility —
   ideally including a fixed known-good `.kkey`/`.kenc` sample file (if one exists in the source
   repo's test resources) round-tripped through the ported code.

## Explicitly out of scope for this pass

Wiring `PhotoEncryptor` into actual photo capture (`camera/data/MediaStorePhotoStorage.kt`) is
**not** covered here. This app saves photos via `MediaStore` (scoped storage, minSdk 29); the source
app saves to app-private files via its own `ImageMapper`/`ImageFileService` abstraction. Those are
different storage models — MediaStore-visible entries can't simply be replaced with opaque encrypted
bytes and still function as photos in the system gallery — so encrypting captured photos end-to-end
needs its own design decision (e.g., a separate app-private encrypted store rather than MediaStore,
or an export-time-only encryption step) before implementing. Flag this to the user rather than
guessing at a resolution.

## Build/version notes

This app: AGP built-in Kotlin (no `kotlin-android` plugin), compileSdk/targetSdk 37, minSdk 29, JVM
21 toolchain, ktlint 1.5.0 enforced (`ignoreFailures = false`) — run ktlint formatting on every
ported file before considering the port done, since the source repo does not use ktlint and its
formatting will not automatically match. Source app: AGP 9.2.0, compileSdk 36, minSdk 33, JVM 17 —
no APIs used by the ported code require API 33+, so the lower minSdk (29) here is not a blocker, but
double-check anything new pulled in during the port against minSdk 29.
