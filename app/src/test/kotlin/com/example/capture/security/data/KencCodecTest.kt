package com.example.capture.security.data

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class KencCodecTest {

    private fun sampleBlock() = EncryptedBlock(
        encryptedAesKey = byteArrayOf(1, 2, 3, 4),
        nonce = byteArrayOf(5, 6, 7),
        tag = byteArrayOf(8, 9, 10, 11, 12),
        cipher = byteArrayOf(13, 14, 15, 16, 17, 18),
    )

    @Test
    fun `writeHeader then readHeader round trips the image block length`() {
        val header = KencCodec.writeHeader(imageBlockLength = 123_456_789L)

        assertThat(KencCodec.readHeader(header).imageBlockLength).isEqualTo(123_456_789L)
    }

    @Test
    fun `readHeader rejects bad magic bytes`() {
        val header = KencCodec.writeHeader(imageBlockLength = 10L)
        header[0] = 'X'.code.toByte()

        assertThrows(IllegalArgumentException::class.java) { KencCodec.readHeader(header) }
    }

    @Test
    fun `isEncryptedHeader is true only for a full-length header with the KENC magic`() {
        val header = KencCodec.writeHeader(imageBlockLength = 10L)

        assertThat(KencCodec.isEncryptedHeader(header, header.size)).isTrue()
        assertThat(KencCodec.isEncryptedHeader(ByteArray(KencCodec.HEADER_SIZE), KencCodec.HEADER_SIZE)).isFalse()
        assertThat(KencCodec.isEncryptedHeader(header, 3)).isFalse()
    }

    @Test
    fun `writeBlock then readBlock round trips every field`() {
        val block = sampleBlock()
        val written = KencCodec.writeBlock(block)

        val (read, consumed) = KencCodec.readBlock(written, 0)

        assertThat(read).isEqualTo(block)
        assertThat(consumed).isEqualTo(written.size)
        assertThat(consumed.toLong()).isEqualTo(block.fileFormatLength)
    }

    @Test
    fun `readBlock at a non-zero offset skips the prefix correctly`() {
        val block = sampleBlock()
        val prefix = byteArrayOf(0, 0, 0)
        val written = prefix + KencCodec.writeBlock(block)

        val (read, consumed) = KencCodec.readBlock(written, prefix.size)

        assertThat(read).isEqualTo(block)
        assertThat(consumed).isEqualTo(written.size - prefix.size)
    }

    @Test
    fun `readBlock on a truncated buffer throws`() {
        assertThrows(IllegalArgumentException::class.java) { KencCodec.readBlock(ByteArray(10), 0) }
    }

    @Test
    fun `encodeStandaloneBlock then decodeStandaloneBlock round trips every field`() {
        val block = sampleBlock()

        val decoded = KencCodec.decodeStandaloneBlock(KencCodec.encodeStandaloneBlock(block))

        assertThat(decoded).isEqualTo(block)
    }

    @Test
    fun `standalone and file-format encodings are not interchangeable`() {
        val block = sampleBlock()

        // Standalone uses a 4-byte cipher length; the file format's block header is 20 bytes
        // with an 8-byte cipher length - reading one as the other must not silently succeed.
        assertThat(KencCodec.encodeStandaloneBlock(block).size).isNotEqualTo(KencCodec.writeBlock(block).size)
    }
}
