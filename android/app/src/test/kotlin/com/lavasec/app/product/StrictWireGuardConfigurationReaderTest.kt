package com.lavasec.app.product

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.CharacterCodingException
import java.io.IOException
import com.lavasec.app.platform.VpnConfigurationPick
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class StrictWireGuardConfigurationReaderTest {
    @Test fun `exact 64 KiB UTF8 and BOM are preserved without arbitrary share-code truncation`() {
        val input = "#".repeat(StrictWireGuardConfigurationReader.MAXIMUM_BYTES)
        assertEquals(input, StrictWireGuardConfigurationReader.read(ByteArrayInputStream(input.toByteArray())))
        val unicode = "\uFEFF[Interface]\n# café 日本語\n"
        assertEquals(unicode, StrictWireGuardConfigurationReader.read(ByteArrayInputStream(unicode.toByteArray(Charsets.UTF_8))))
    }

    @Test fun `oversize source consumes at most limit plus one even when provider metadata would lie`() {
        var consumed = 0
        val stream = object : InputStream() {
            override fun read(): Int { ++consumed; return '#'.code }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                java.util.Arrays.fill(bytes, offset, offset + length, '#'.code.toByte())
                consumed += length
                return length
            }
        }
        assertThrows(StrictWireGuardConfigurationReader.TooLarge::class.java) {
            StrictWireGuardConfigurationReader.read(stream)
        }
        assertEquals(StrictWireGuardConfigurationReader.MAXIMUM_BYTES + 1, consumed)
    }

    @Test fun `invalid and truncated UTF8 are rejected instead of replacing confidential input bytes`() {
        for (bytes in listOf(byteArrayOf(0xc3.toByte(), 0x28), byteArrayOf(0xe2.toByte(), 0x82.toByte()))) {
            assertThrows(CharacterCodingException::class.java) {
                StrictWireGuardConfigurationReader.read(ByteArrayInputStream(bytes))
            }
        }
    }

    @Test fun `short and zero provider reads make progress while keeping the same bounded text`() {
        val expected = "# bounded provider read"
        val source = ByteArrayInputStream(expected.toByteArray())
        var zero = true
        val stream = object : InputStream() {
            override fun read() = source.read()
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (zero) { zero = false; return 0 }
                return source.read(bytes, offset, minOf(length, 2))
            }
        }
        assertEquals(expected, StrictWireGuardConfigurationReader.read(stream))
    }

    @Test fun `document classification preserves actual too large and nontext causes without retaining supplied bytes`() {
        val cases = listOf(
            ByteArray(StrictWireGuardConfigurationReader.MAXIMUM_BYTES + 1) { '#'.code.toByte() } to VpnConfigurationPick.ImportFailureReason.TOO_LARGE,
            byteArrayOf(0xc3.toByte(), 0x28) to VpnConfigurationPick.ImportFailureReason.NOT_TEXT,
            byteArrayOf(0xe2.toByte(), 0x82.toByte()) to VpnConfigurationPick.ImportFailureReason.NOT_TEXT,
        )
        for ((bytes, reason) in cases) {
            var closed = false
            val stream = object : ByteArrayInputStream(bytes) { override fun close() { closed = true; super.close() } }
            val picked = readVpnConfigurationDocument { stream }
            assertEquals(VpnConfigurationPick.ImportFailed(reason), picked)
            assertTrue(closed)
            assertFalse(picked.toString().contains("PrivateKey"))
        }
        val text = "#".repeat(StrictWireGuardConfigurationReader.MAXIMUM_BYTES)
        assertEquals(VpnConfigurationPick.Read(text), readVpnConfigurationDocument { ByteArrayInputStream(text.toByteArray()) })
    }

    @Test fun `unavailable stream and IO remain generic while cancellation keeps its original identity`() {
        assertEquals(VpnConfigurationPick.Unavailable, readVpnConfigurationDocument { null })
        assertEquals(VpnConfigurationPick.Unavailable, readVpnConfigurationDocument { throw IOException("private provider location") })
        val cancelled = CancellationException("original owner cancelled")
        try { readVpnConfigurationDocument { throw cancelled }; fail("Cancellation must not become an import error") }
        catch (actual: CancellationException) { assertSame(cancelled, actual) }
    }
}
