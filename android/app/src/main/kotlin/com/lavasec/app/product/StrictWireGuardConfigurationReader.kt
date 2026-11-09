package com.lavasec.app.product

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Reads actual bytes, independently of advisory document-provider size or MIME metadata. */
internal object StrictWireGuardConfigurationReader {
    const val MAXIMUM_BYTES = 64 * 1024

    class TooLarge : IllegalArgumentException("The WireGuard configuration exceeds 64 KiB.")

    fun read(input: InputStream): String {
        val bytes = ByteArrayOutputStream(8 * 1024)
        val buffer = ByteArray(8 * 1024)
        while (bytes.size() <= MAXIMUM_BYTES) {
            val remaining = MAXIMUM_BYTES + 1 - bytes.size()
            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) break
            if (count == 0) {
                // A provider may return a short/zero read; make bounded progress without spinning.
                val single = input.read()
                if (single < 0) break
                bytes.write(single)
            } else bytes.write(buffer, 0, count)
        }
        if (bytes.size() > MAXIMUM_BYTES) throw TooLarge()
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
    }
}
