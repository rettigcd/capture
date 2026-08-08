package com.example.capture.security.data

/**
 * Byte-level `.kenc` container format: a 16-byte header, then two length-prefixed
 * [EncryptedBlock]s (image, then metadata).
 *
 * `encodeStandaloneBlock`/`decodeStandaloneBlock` (used by [KencPhotoEncryptor]'s
 * `encrypt`/`decrypt`) is a **separate, differently-shaped** format from the file's own block
 * format - it interleaves length-prefix/data pairs (all four lengths as 4-byte ints, including
 * the cipher length), instead of grouping all four lengths into one header first (with an 8-byte
 * cipher length) the way the file format does. Easy to conflate; kept as two clearly separate
 * function pairs here.
 */
internal object KencCodec {

    const val MAGIC = "KENC"
    const val HEADER_SIZE = 4 + 1 + 1 + 2 + 8 // magic, version, algorithm id, reserved, image-block length

    data class Header(val imageBlockLength: Long)

    fun isEncryptedHeader(header: ByteArray, bytesRead: Int): Boolean =
        bytesRead >= HEADER_SIZE &&
            header[0] == MAGIC[0].code.toByte() && header[1] == MAGIC[1].code.toByte() &&
            header[2] == MAGIC[2].code.toByte() && header[3] == MAGIC[3].code.toByte()

    fun readHeader(bytes: ByteArray, offset: Int = 0): Header {
        require(bytes.size - offset >= HEADER_SIZE) { "Truncated .kenc header." }
        require(
            bytes[offset] == MAGIC[0].code.toByte() && bytes[offset + 1] == MAGIC[1].code.toByte() &&
                bytes[offset + 2] == MAGIC[2].code.toByte() && bytes[offset + 3] == MAGIC[3].code.toByte(),
        ) { "Invalid file (bad magic)." }

        val version = bytes[offset + 4].toInt() and 0xFF
        require(version == 1) { "Unsupported version: $version" }
        val algId = bytes[offset + 5].toInt() and 0xFF
        require(algId == 1) { "Unsupported algorithm id: $algId" }

        val imageBlockLength = readInt64LE(bytes, offset + 8)
        return Header(imageBlockLength)
    }

    fun writeHeader(imageBlockLength: Long): ByteArray {
        val header = ByteArray(HEADER_SIZE)
        header[0] = MAGIC[0].code.toByte()
        header[1] = MAGIC[1].code.toByte()
        header[2] = MAGIC[2].code.toByte()
        header[3] = MAGIC[3].code.toByte()
        header[4] = 1 // version
        header[5] = 1 // algorithm id: AES-256-GCM + RSA-OAEP-SHA256
        // header[6], header[7]: reserved, left zero
        writeInt64LE(header, 8, imageBlockLength)
        return header
    }

    /** Reads one file-format block (20-byte grouped length header, then key/nonce/tag/cipher) starting at [offset]. @return the block and its total byte length. */
    fun readBlock(bytes: ByteArray, offset: Int): Pair<EncryptedBlock, Int> {
        require(bytes.size - offset >= 20) { "Truncated .kenc block header." }
        val keyLength = readInt32LE(bytes, offset)
        val nonceLength = readInt32LE(bytes, offset + 4)
        val tagLength = readInt32LE(bytes, offset + 8)
        val cipherLength = readInt64LE(bytes, offset + 12)
        require(cipherLength in 0..Int.MAX_VALUE.toLong()) { "Cannot read a .kenc block cipher larger than 2GB." }

        var pos = offset + 20
        val key = bytes.copyOfRange(pos, pos + keyLength).also { pos += keyLength }
        val nonce = bytes.copyOfRange(pos, pos + nonceLength).also { pos += nonceLength }
        val tag = bytes.copyOfRange(pos, pos + tagLength).also { pos += tagLength }
        val cipher = bytes.copyOfRange(pos, pos + cipherLength.toInt()).also { pos += cipherLength.toInt() }

        return EncryptedBlock(key, nonce, tag, cipher) to (pos - offset)
    }

    fun writeBlock(block: EncryptedBlock): ByteArray {
        val header = ByteArray(20)
        writeInt32LE(header, 0, block.encryptedAesKey.size)
        writeInt32LE(header, 4, block.nonce.size)
        writeInt32LE(header, 8, block.tag.size)
        writeInt64LE(header, 12, block.cipher.size.toLong())
        return header + block.encryptedAesKey + block.nonce + block.tag + block.cipher
    }

    /** The standalone-blob format used by [KencPhotoEncryptor.encrypt]/[KencPhotoEncryptor.decrypt]: interleaved 4-byte-int-prefixed fields (all four lengths are 4-byte ints, including the cipher's - unlike [writeBlock]'s 8-byte cipher length). */
    fun encodeStandaloneBlock(block: EncryptedBlock): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun writeLengthPrefixed(data: ByteArray) {
            val lenBytes = ByteArray(4)
            writeInt32LE(lenBytes, 0, data.size)
            out.write(lenBytes)
            out.write(data)
        }
        writeLengthPrefixed(block.encryptedAesKey)
        writeLengthPrefixed(block.nonce)
        writeLengthPrefixed(block.tag)
        writeLengthPrefixed(block.cipher)
        return out.toByteArray()
    }

    fun decodeStandaloneBlock(bytes: ByteArray): EncryptedBlock {
        var pos = 0
        fun readLengthPrefixed(): ByteArray {
            val length = readInt32LE(bytes, pos)
            pos += 4
            return bytes.copyOfRange(pos, pos + length).also { pos += length }
        }
        val key = readLengthPrefixed()
        val nonce = readLengthPrefixed()
        val tag = readLengthPrefixed()
        val cipher = readLengthPrefixed()
        return EncryptedBlock(key, nonce, tag, cipher)
    }

    private fun readInt32LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun readInt64LE(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = value or ((bytes[offset + i].toLong() and 0xFF) shl (8 * i))
        return value
    }

    private fun writeInt32LE(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = value.toByte()
        buffer[offset + 1] = (value ushr 8).toByte()
        buffer[offset + 2] = (value ushr 16).toByte()
        buffer[offset + 3] = (value ushr 24).toByte()
    }

    private fun writeInt64LE(buffer: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) buffer[offset + i] = (value ushr (8 * i)).toByte()
    }
}
