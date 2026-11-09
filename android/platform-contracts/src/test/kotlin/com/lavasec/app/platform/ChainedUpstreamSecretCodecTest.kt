package com.lavasec.app.platform

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The key-item codec's zeroization boundary: the plaintext blob loaded from the secure store is
 * cleared, and the decoder's own temporary key copies do not leak into the returned material.
 */
class ChainedUpstreamSecretCodecTest {

    private fun key(fill: Int): ByteArray = ByteArray(32) { fill.toByte() }

    @Test
    fun `decodeAndZeroize clears the plaintext blob and yields the material`() {
        val value = checkNotNull(ChainedUpstreamSecretCodec.encode(key(0x11), key(0x33)))

        val material = checkNotNull(ChainedUpstreamSecretCodec.decodeAndZeroize(value, 1L))

        // The plaintext blob is cleared...
        assertArrayEquals(ByteArray(value.size), value)
        // ...while the material carries the decoded keys, independent of the blob.
        assertArrayEquals(key(0x11), material.privateKeyCopy())
        assertArrayEquals(key(0x33), material.presharedKeyCopy())
    }

    @Test
    fun `decodeAndZeroize clears the blob even when the item is unrecognised`() {
        val value = ByteArray(7) { 0x7F }

        assertNull(ChainedUpstreamSecretCodec.decodeAndZeroize(value, 1L))

        assertArrayEquals(ByteArray(7), value)
    }

    @Test
    fun `decode copies the keys so the caller still owns its blob`() {
        val value = checkNotNull(ChainedUpstreamSecretCodec.encode(key(0x11), null))
        val before = value.copyOf()

        val material = checkNotNull(ChainedUpstreamSecretCodec.decode(value, 1L))

        assertArrayEquals(before, value)
        assertArrayEquals(key(0x11), material.privateKeyCopy())
        assertNull(material.presharedKeyCopy())
    }
}
