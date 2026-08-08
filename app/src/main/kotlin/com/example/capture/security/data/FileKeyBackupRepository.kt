package com.example.capture.security.data

import android.content.Context
import com.example.capture.common.DispatcherProvider
import com.example.capture.security.domain.EncryptionKeyPair
import com.example.capture.security.domain.ImportedKeyPair
import com.example.capture.security.domain.IncorrectPassphraseException
import com.example.capture.security.domain.KeyBackupRepository
import com.example.capture.security.domain.PublicKeyInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import kotlinx.coroutines.withContext

/**
 * Reads and writes the app's single `.kkey` file (`context.filesDir/KeyPair.kkey`): a plain-text
 * export of an RSA key pair, PBKDF2-SHA256-derived-AES-256-GCM-encrypted private key,
 * integrity-bound to its own header fields. This must stay byte-for-byte compatible with what the
 * existing .NET apps produce and consume - both use the same standardized primitives (PKCS8/X.509
 * DER, PBKDF2-HMAC-SHA256, AES-256-GCM), so correctness here is about matching exact byte layouts
 * and string formats, not the crypto primitives themselves. Every string/numeric constant below
 * (including `HEADER_LINE`'s literal text) is part of that wire format and must never be "cleaned
 * up," even though this app isn't the one named Keibler.
 */
class FileKeyBackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider,
) : KeyBackupRepository {

    private val keyFile: File get() = File(context.filesDir, KEY_FILE_NAME)

    override suspend fun hasKeyFile(): Boolean = withContext(dispatcherProvider.io) { keyFile.exists() }

    override suspend fun createKeyFile(passphrase: String): EncryptionKeyPair = withContext(dispatcherProvider.io) {
        backupExistingKeyFile()
        val keyPair = EncryptionKeyPair.generate()
        keyFile.writeText(makeExportBlock(keyPair.privateKey, keyPair.public64, passphrase), Charsets.UTF_8)
        keyPair
    }

    override suspend fun readPublicKeyInfo(): PublicKeyInfo? = withContext(dispatcherProvider.io) {
        if (!keyFile.exists()) return@withContext null
        val keyFileData = parseKeyFileBlock(keyFile.readText(Charsets.UTF_8))
        validateSupportedMetadata(keyFileData)

        val derivedFingerprint = computeFingerprint(keyFileData.publicKeyPem)
        check(keyFileData.fingerprintSha256.equals(derivedFingerprint, ignoreCase = true)) { "Fingerprint verification failed." }

        val der = getPemDer(keyFileData.publicKeyPem, PUBLIC_KEY_PEM_LABEL)
        PublicKeyInfo(Base64.getEncoder().encodeToString(der), derivedFingerprint)
    }

    override suspend fun importFromCurrentFile(passphrase: String): ImportedKeyPair = withContext(dispatcherProvider.io) {
        importKeyFileBlock(keyFile.readText(Charsets.UTF_8), passphrase)
    }

    override suspend fun importKeyFileBytes(bytes: ByteArray) = withContext(dispatcherProvider.io) {
        backupExistingKeyFile()
        keyFile.writeBytes(bytes)
    }

    override suspend fun exportKeyFileBytes(): ByteArray = withContext(dispatcherProvider.io) {
        keyFile.readBytes()
    }

    override suspend fun changePassphrase(currentPassphrase: String, newPassphrase: String) {
        withContext(dispatcherProvider.io) {
            val imported = importKeyFileBlock(keyFile.readText(Charsets.UTF_8), currentPassphrase)
            backupExistingKeyFile()
            keyFile.writeText(makeExportBlock(imported.privateKey, imported.publicKeyBase64, newPassphrase), Charsets.UTF_8)
        }
    }

    override fun keyFilePath(): String = keyFile.absolutePath

    private fun backupExistingKeyFile() {
        if (!keyFile.exists()) return
        val timestamp = OffsetDateTime.now(ZoneOffset.UTC).format(BACKUP_TIMESTAMP_FORMAT)
        keyFile.copyTo(File(keyFile.parentFile, "${keyFile.name}.$timestamp.bak"), overwrite = false)
    }

    // ---- .kkey format (ported from the keibler source app's KeyBackupService) ----

    /**
     * [publicKeyBase64] is passed in rather than derived from [privateKey]: `KeyFactory` can't
     * reliably re-derive an RSA public key (specifically the public exponent) from a private key
     * that didn't come straight from [java.security.KeyPairGenerator] on every provider -
     * Conscrypt (Android's default JCE provider) doesn't retain CRT parameters across a
     * PKCS8-encode/decode round trip the way Sun/OpenJDK's provider does, so a private key that
     * was itself just imported (see [importKeyFileBlock]) can't be used to reconstruct its public
     * half this way. The public key is always independently available anyway - either freshly
     * generated ([EncryptionKeyPair.public64]) or read straight out of the file being re-encrypted
     * ([ImportedKeyPair.publicKeyBase64]) - so this never needs to be derived at all.
     */
    private fun makeExportBlock(privateKey: PrivateKey, publicKeyBase64: String, passphrase: String): String {
        require(passphrase.isNotBlank()) { "passphrase must not be blank" }

        val publicKeyDer = Base64.getDecoder().decode(publicKeyBase64)
        val publicKeyPem = toPem(publicKeyDer, PUBLIC_KEY_PEM_LABEL)
        val modulusBitLength = rsaPublicKeyFromDer(publicKeyDer).modulus.bitLength()
        val privateKeyPkcs8 = privateKey.encoded
        val kdfSalt = ByteArray(SALT_SIZE_BYTES).also(secureRandom::nextBytes)

        val metadata = createMetadataForExport(modulusBitLength, publicKeyPem, kdfSalt)
        val encryptedPayload = encryptPrivateKey(privateKeyPkcs8, passphrase, metadata)
        val completed = metadata.copy(
            nonceBase64 = Base64.getEncoder().encodeToString(encryptedPayload.nonce),
            tagBase64 = Base64.getEncoder().encodeToString(encryptedPayload.tag),
            encryptedPrivateKeyBase64 = Base64.getEncoder().encodeToString(encryptedPayload.ciphertext),
        )

        return buildFileContents(completed)
    }

    private fun importKeyFileBlock(textBlock: String, passphrase: String): ImportedKeyPair {
        require(textBlock.isNotBlank()) { "textBlock must not be blank" }
        require(passphrase.isNotBlank()) { "passphrase must not be blank" }

        val keyFileData = parseKeyFileBlock(textBlock)
        validateSupportedMetadata(keyFileData)

        val ciphertext = decodeBase64Lenient(keyFileData.encryptedPrivateKeyBase64)
        val nonce = decodeBase64Lenient(keyFileData.nonceBase64)
        val tag = decodeBase64Lenient(keyFileData.tagBase64)
        validateEncryptedPayloadSizes(nonce, tag)

        val privateKeyPkcs8 = decryptPrivateKey(ciphertext, nonce, tag, passphrase, keyFileData)
        val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(privateKeyPkcs8))

        importAndValidateDecryptedPrivateKey(privateKey, keyFileData)

        val publicKeyDer = getPemDer(keyFileData.publicKeyPem, PUBLIC_KEY_PEM_LABEL)
        val publicKeyBase64 = Base64.getEncoder().encodeToString(publicKeyDer)
        return ImportedKeyPair(privateKey, publicKeyBase64, keyFileData.fingerprintSha256)
    }

    private fun rsaPublicKeyFromDer(der: ByteArray): RSAPublicKey =
        KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der)) as RSAPublicKey

    private fun createMetadataForExport(modulusBitLength: Int, publicKeyPem: String, kdfSalt: ByteArray) = KeyFileData(
        version = HEADER_LINE,
        algorithm = "RSA-$modulusBitLength",
        createdUtc = nowCreatedUtc(),
        publicKeyPem = publicKeyPem,
        fingerprintSha256 = computeFingerprint(publicKeyPem),
        kdf = SUPPORTED_KDF,
        kdfIterations = PBKDF2_ITERATIONS,
        kdfSaltBase64 = Base64.getEncoder().encodeToString(kdfSalt),
        encryptionAlgorithm = SUPPORTED_ENCRYPTION_ALGORITHM,
        nonceBase64 = "",
        tagBase64 = "",
        encryptedPrivateKeyBase64 = "",
    )

    private fun validateSupportedMetadata(keyFileData: KeyFileData) {
        check(keyFileData.version == HEADER_LINE) { "Invalid key file format." }
        check(keyFileData.kdf == SUPPORTED_KDF) { "Unsupported KDF." }
        check(keyFileData.kdfIterations > 0) { "Invalid KDF iterations." }
        check(keyFileData.encryptionAlgorithm == SUPPORTED_ENCRYPTION_ALGORITHM) { "Unsupported encryption algorithm." }
    }

    private fun validateEncryptedPayloadSizes(nonce: ByteArray, tag: ByteArray) {
        check(nonce.size == GCM_NONCE_SIZE_BYTES) { "Invalid nonce." }
        check(tag.size == GCM_TAG_SIZE_BYTES) { "Invalid authentication tag." }
    }

    private fun importAndValidateDecryptedPrivateKey(privateKey: PrivateKey, keyFileData: KeyFileData) {
        val publicKeyDer = getPemDer(keyFileData.publicKeyPem, PUBLIC_KEY_PEM_LABEL)
        val publicKey = rsaPublicKeyFromDer(publicKeyDer)

        val expectedAlgorithm = "RSA-${publicKey.modulus.bitLength()}"
        check(keyFileData.algorithm == expectedAlgorithm) { "Algorithm metadata mismatch." }

        // Confirms the decrypted private key actually pairs with the file's stored public key -
        // a functional RSA-OAEP round trip rather than deriving+comparing DER bytes, since the
        // private key can't always be forced to reveal its CRT parameters (see makeExportBlock's
        // kdoc); the crypto round trip only needs the private key to work, not to expose them.
        check(privateKeyMatchesPublicKey(publicKey, privateKey)) { "Public/private key mismatch." }

        val derivedFingerprint = computeFingerprint(keyFileData.publicKeyPem)
        check(keyFileData.fingerprintSha256.equals(derivedFingerprint, ignoreCase = true)) { "Fingerprint verification failed." }
    }

    private fun privateKeyMatchesPublicKey(publicKey: PublicKey, privateKey: PrivateKey): Boolean {
        val probe = ByteArray(32).also(secureRandom::nextBytes)
        return try {
            val encryptCipher = Cipher.getInstance(RSA_OAEP_TRANSFORMATION)
            encryptCipher.init(Cipher.ENCRYPT_MODE, publicKey)
            val decryptCipher = Cipher.getInstance(RSA_OAEP_TRANSFORMATION)
            decryptCipher.init(Cipher.DECRYPT_MODE, privateKey)
            decryptCipher.doFinal(encryptCipher.doFinal(probe)).contentEquals(probe)
        } catch (e: GeneralSecurityException) {
            false
        }
    }

    private data class EncryptedPayload(val ciphertext: ByteArray, val nonce: ByteArray, val tag: ByteArray)

    private fun encryptPrivateKey(privateKeyPkcs8: ByteArray, passphrase: String, keyFileData: KeyFileData): EncryptedPayload {
        val kdfSalt = decodeBase64Lenient(keyFileData.kdfSaltBase64)
        val key = deriveEncryptionKey(passphrase, kdfSalt, keyFileData.kdfIterations)
        val nonce = ByteArray(GCM_NONCE_SIZE_BYTES).also(secureRandom::nextBytes)
        val associatedData = buildAssociatedData(keyFileData, "\n")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_SIZE_BYTES * 8, nonce))
        cipher.updateAAD(associatedData)
        val output = cipher.doFinal(privateKeyPkcs8) // ciphertext || tag (Java's GCM Cipher appends the tag)

        val ciphertext = output.copyOfRange(0, output.size - GCM_TAG_SIZE_BYTES)
        val tag = output.copyOfRange(output.size - GCM_TAG_SIZE_BYTES, output.size)
        return EncryptedPayload(ciphertext, nonce, tag)
    }

    // Older key files may have been exported on a platform whose Environment.NewLine was "\r\n"
    // (an associated-data quirk from the original .NET app). New exports always use "\n", but
    // import still has to accept files created before that.
    private val legacyAssociatedDataNewlines = listOf("\n", "\r\n")

    private fun decryptPrivateKey(ciphertext: ByteArray, nonce: ByteArray, tag: ByteArray, passphrase: String, keyFileData: KeyFileData): ByteArray {
        val kdfSalt = decodeBase64Lenient(keyFileData.kdfSaltBase64)
        val key = deriveEncryptionKey(passphrase, kdfSalt, keyFileData.kdfIterations)
        val combined = ciphertext + tag // Java's GCM Cipher expects ciphertext||tag as one input for decryption.

        for (newline in legacyAssociatedDataNewlines) {
            val associatedData = buildAssociatedData(keyFileData, newline)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_SIZE_BYTES * 8, nonce))
                cipher.updateAAD(associatedData)
                return cipher.doFinal(combined)
            } catch (e: javax.crypto.AEADBadTagException) {
                // tag didn't verify with this newline convention - try the next one.
            }
        }
        throw IncorrectPassphraseException()
    }

    private fun deriveEncryptionKey(passphrase: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, AES_KEY_SIZE_BYTES * 8)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    // The newline must be an explicit, fixed string here, never the JVM's line.separator - this
    // text feeds the AES-GCM associated data, so it must produce identical bytes on every
    // platform regardless of host OS.
    private fun buildAssociatedData(keyFileData: KeyFileData, newline: String): ByteArray {
        val publicKeyDer = getPemDer(keyFileData.publicKeyPem, PUBLIC_KEY_PEM_LABEL)

        val sb = StringBuilder()
        sb.append("Version:").append(keyFileData.version.trim()).append(newline)
        sb.append("Algorithm:").append(keyFileData.algorithm.trim()).append(newline)
        sb.append("Created:").append(normalizeCreatedUtc(keyFileData.createdUtc)).append(newline)
        sb.append("Fingerprint-SHA256:").append(keyFileData.fingerprintSha256.trim().uppercase()).append(newline)
        sb.append("KDF:").append(keyFileData.kdf.trim()).append(newline)
        sb.append("KDF-Iterations:").append(keyFileData.kdfIterations).append(newline)
        sb.append("KDF-Salt:").append(keyFileData.kdfSaltBase64.trim()).append(newline)
        sb.append("ENC:").append(keyFileData.encryptionAlgorithm.trim()).append(newline)
        sb.append("PublicKeyDer:").append(Base64.getEncoder().encodeToString(publicKeyDer)).append(newline)

        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun buildFileContents(keyFileData: KeyFileData): String {
        val sb = StringBuilder()
        sb.appendLine(keyFileData.version)
        sb.appendLine("Algorithm: ${keyFileData.algorithm}")
        sb.appendLine("Created: ${keyFileData.createdUtc}")
        sb.appendLine("Fingerprint-SHA256: ${keyFileData.fingerprintSha256}")
        sb.appendLine("KDF: ${keyFileData.kdf}")
        sb.appendLine("KDF-Iterations: ${keyFileData.kdfIterations}")
        sb.appendLine("KDF-Salt: ${keyFileData.kdfSaltBase64}")
        sb.appendLine("ENC: ${keyFileData.encryptionAlgorithm}")
        sb.appendLine("Nonce: ${keyFileData.nonceBase64}")
        sb.appendLine("Tag: ${keyFileData.tagBase64}")
        sb.appendLine()

        sb.appendLine(keyFileData.publicKeyPem.trim())
        sb.appendLine()
        sb.appendLine("-----BEGIN $ENCRYPTED_PRIVATE_KEY_BLOCK_LABEL-----")
        sb.appendLine(keyFileData.encryptedPrivateKeyBase64.trim())
        sb.appendLine("-----END $ENCRYPTED_PRIVATE_KEY_BLOCK_LABEL-----")
        sb.appendLine()

        return sb.toString()
    }

    private fun parseKeyFileBlock(text: String): KeyFileData {
        require(text.isNotBlank()) { "text must not be blank" }

        val lines = text.lineSequence().iterator()
        val version = if (lines.hasNext()) lines.next() else ""
        check(version.isNotBlank()) { "Invalid key file format." }

        val headers = parseHeaders(lines)

        val publicKeyPem = extractPem(text, PUBLIC_KEY_PEM_LABEL)
        val encryptedPrivateKeyBase64 = extractCustomBlockBase64(text, ENCRYPTED_PRIVATE_KEY_BLOCK_LABEL)

        return KeyFileData(
            version = version.trim(),
            algorithm = requiredHeader(headers, "Algorithm"),
            createdUtc = requiredHeader(headers, "Created"),
            fingerprintSha256 = requiredHeader(headers, "Fingerprint-SHA256"),
            kdf = requiredHeader(headers, "KDF"),
            kdfIterations = requiredHeader(headers, "KDF-Iterations").toInt(),
            kdfSaltBase64 = requiredHeader(headers, "KDF-Salt"),
            encryptionAlgorithm = requiredHeader(headers, "ENC"),
            nonceBase64 = requiredHeader(headers, "Nonce"),
            tagBase64 = requiredHeader(headers, "Tag"),
            publicKeyPem = publicKeyPem,
            encryptedPrivateKeyBase64 = encryptedPrivateKeyBase64,
        )
    }

    private fun parseHeaders(lines: Iterator<String>): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        while (lines.hasNext()) {
            val line = lines.next()
            if (line.isBlank()) break

            val colonIndex = line.indexOf(':')
            check(colonIndex > 0) { "Invalid header line: $line" }

            val name = line.substring(0, colonIndex).trim()
            val value = line.substring(colonIndex + 1).trim()
            check(name !in headers) { "Duplicate header: $name" }
            headers[name] = value
        }
        return headers
    }

    private fun requiredHeader(headers: Map<String, String>, name: String): String =
        headers[name]?.takeIf { it.isNotBlank() } ?: error("Missing required header: $name")

    private fun getPemDer(pem: String, expectedLabel: String): ByteArray {
        val trimmed = pem.trim()
        val lines = trimmed.replace("\r\n", "\n").split('\n').filter { it.isNotEmpty() }

        val begin = "-----BEGIN $expectedLabel-----"
        val end = "-----END $expectedLabel-----"
        check(lines.size >= 3 && lines.first() == begin && lines.last() == end) { "Invalid PEM." }

        val base64 = lines.subList(1, lines.size - 1).joinToString("")
        return decodeBase64Lenient(base64)
    }

    private fun extractPem(text: String, label: String): String {
        val beginLabel = "-----BEGIN $label-----"
        val endLabel = "-----END $label-----"

        val begin = text.indexOf(beginLabel)
        check(begin >= 0) { "$label block not found." }
        val end = text.indexOf(endLabel, begin)
        check(end >= 0) { "$label block not found." }

        return text.substring(begin, end + endLabel.length)
    }

    private fun extractCustomBlockBase64(text: String, label: String): String {
        val beginLabel = "-----BEGIN $label-----"
        val endLabel = "-----END $label-----"

        val begin = text.indexOf(beginLabel)
        check(begin >= 0) { "$label block not found." }
        val contentStart = begin + beginLabel.length
        val end = text.indexOf(endLabel, contentStart)
        check(end >= 0) { "$label block not found." }

        return text.substring(contentStart, end).trim()
    }

    private fun computeFingerprint(publicKeyPem: String): String {
        val der = getPemDer(publicKeyPem, PUBLIC_KEY_PEM_LABEL)
        val hash = MessageDigest.getInstance("SHA-256").digest(der)
        // Explicit masking, not "%02X".format(byte): Byte is signed, and relying on
        // java.util.Formatter's per-type width handling for %X on a boxed Byte isn't worth the
        // risk for a value used in file integrity verification.
        return hash.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }
    }

    private fun toPem(der: ByteArray, label: String): String {
        val base64 = Base64.getEncoder().encodeToString(der)
        val wrapped = base64.chunked(64).joinToString("\n")
        return "-----BEGIN $label-----\n$wrapped\n-----END $label-----"
    }

    /** .NET's `Convert.FromBase64String` tolerates embedded whitespace/newlines (from wrapped exports); strip before strict-decoding to match. */
    private fun decodeBase64Lenient(text: String): ByteArray = Base64.getDecoder().decode(text.filterNot { it.isWhitespace() })

    // Matches .NET's DateTimeOffset "O" (round-trip) format exactly: yyyy-MM-ddTHH:mm:ss.fffffff+00:00 -
    // 7 fractional digits (100ns resolution) and a numeric offset, never "Z". This string is fed into the
    // AES-GCM associated data, so an app on either platform must derive the identical bytes from it.
    private val createdFormatter: DateTimeFormatter = DateTimeFormatterBuilder()
        .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
        .appendFraction(ChronoField.NANO_OF_SECOND, 7, 7, true)
        .appendOffset("+HH:MM", "+00:00")
        .toFormatter()

    private fun nowCreatedUtc(): String = OffsetDateTime.now(ZoneOffset.UTC).format(createdFormatter)

    /** Parses whatever "Created" value is in the file and reformats it canonically - the AAD is built from this normalized form, not the raw stored text. */
    private fun normalizeCreatedUtc(text: String): String = OffsetDateTime.parse(text.trim(), createdFormatter).format(createdFormatter)

    private data class KeyFileData(
        val version: String,
        val algorithm: String,
        val createdUtc: String,
        val publicKeyPem: String,
        val fingerprintSha256: String,
        val kdf: String,
        val kdfIterations: Int,
        val kdfSaltBase64: String,
        val encryptionAlgorithm: String,
        val nonceBase64: String,
        val tagBase64: String,
        val encryptedPrivateKeyBase64: String,
    )

    private companion object {
        const val KEY_FILE_NAME = "KeyPair.kkey"

        // Wire-format constants - see this class's kdoc. Do not rename/alter, even HEADER_LINE's
        // "KEIBLER" text: the .NET/MAUI app and the keibler Android app both validate it exactly.
        const val HEADER_LINE = "KEIBLER-KEYPAIR-V1"
        const val SUPPORTED_KDF = "PBKDF2-SHA256"
        const val SUPPORTED_ENCRYPTION_ALGORITHM = "AES-256-GCM"

        const val PBKDF2_ITERATIONS = 600_000
        const val SALT_SIZE_BYTES = 16
        const val AES_KEY_SIZE_BYTES = 32
        const val GCM_NONCE_SIZE_BYTES = 12
        const val GCM_TAG_SIZE_BYTES = 16

        const val PUBLIC_KEY_PEM_LABEL = "PUBLIC KEY"
        const val ENCRYPTED_PRIVATE_KEY_BLOCK_LABEL = "KEIBLER ENCRYPTED PRIVATE KEY"

        // Only used for importAndValidateDecryptedPrivateKey's functional key-pair check, not
        // part of the wire format itself - matches KencPhotoEncryptor's own RSA transformation.
        const val RSA_OAEP_TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"

        val BACKUP_TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")

        val secureRandom = SecureRandom()
    }
}
