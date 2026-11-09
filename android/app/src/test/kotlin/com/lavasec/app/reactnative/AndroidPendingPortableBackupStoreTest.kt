package com.lavasec.app.reactnative

import android.app.Application
import android.util.AtomicFile
import com.lavasec.app.foundation.backup.BackupConfigurationPayload
import com.lavasec.app.foundation.backup.BackupRestoreUnlock
import com.lavasec.app.foundation.backup.PortableBackupFile
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36], application = Application::class)
class AndroidPendingPortableBackupStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val directory get() = File(folder.root, "portable-import")

    @Test fun `pending file is the exact original ciphertext without password or plaintext`() {
        val store = AndroidPendingPortableBackupStore(directory)
        assertEquals("none", store.status())
        assertTrue(store.save(original))
        assertArrayEquals(original, File(directory, AndroidPendingPortableBackupStore.FILE_NAME).readBytes())
        val stored = original.toString(Charsets.UTF_8)
        assertFalse(stored.contains(secret.concatToString()))
        assertFalse(stored.contains("private-blocked.example"))
        val captured = store.capture()!!
        assertArrayEquals(original, captured.bytes)
        assertEquals(payload, (PortableBackupFile.unlock(PortableBackupFile.decode(captured.bytes!!), "password", secret)
            as BackupRestoreUnlock.Unlocked).payload)
        captured.bytes.fill(0)
    }

    @Test fun `canonical directory instances share save and delete status`() {
        val first = AndroidPendingPortableBackupStore(directory)
        val second = AndroidPendingPortableBackupStore(File(folder.root, "unused/../portable-import"))
        assertTrue(first.save(original))
        assertEquals("available", second.status())
        val capture = second.capture()!!
        assertTrue(first.clearIfMatches(capture.token))
        capture.bytes!!.fill(0)
        assertEquals("none", second.status())
        assertNull(AndroidPendingPortableBackupStore(directory).capture())
    }

    @Test fun `invalid or oversized replacement preserves prior pending import`() {
        val store = AndroidPendingPortableBackupStore(directory)
        assertTrue(store.save(original))
        val before = store.capture()!!.also { it.bytes!!.fill(0) }.token
        assertFalse(store.save("untrusted payload".toByteArray()))
        assertFalse(store.save(ByteArray(PortableBackupFile.MAX_FILE_BYTES + 1)))
        val after = store.capture()!!
        assertEquals(before, after.token)
        assertArrayEquals(original, after.bytes)
        after.bytes!!.fill(0)
    }

    @Test fun `interrupted replacement recovers original rather than partial bytes`() {
        val store = AndroidPendingPortableBackupStore(directory)
        assertTrue(store.save(original))
        AtomicFile(File(directory, AndroidPendingPortableBackupStore.FILE_NAME)).startWrite().use {
            it.write("interrupted replacement".toByteArray())
        }
        val restored = AndroidPendingPortableBackupStore(directory).capture()!!
        assertArrayEquals(original, restored.bytes)
        restored.bytes!!.fill(0)
        assertEquals("available", store.status())
    }

    @Test fun `retirement before final atomic commit preserves original entry`() {
        val store = AndroidPendingPortableBackupStore(directory)
        assertTrue(store.save(original))
        var witnessCalls = 0
        assertFalse(store.save(replacement) { ++witnessCalls == 1 })
        assertEquals(2, witnessCalls)
        val capture = store.capture()!!
        assertArrayEquals(original, capture.bytes)
        capture.bytes!!.fill(0)
    }

    @Test fun `post commit read failure reports uncertainty and preserves actual replacement`() {
        val first = AndroidPendingPortableBackupStore(directory)
        assertTrue(first.save(original))
        var diagnosticReads = 0
        val store = AndroidPendingPortableBackupStore(directory) {
            diagnosticReads++
            throw java.io.IOException("Injected post-commit read failure")
        }
        val before = first.capture()!!.also { it.bytes!!.fill(0) }
        assertThrows(PendingPortableBackupSaveUnverifiedException::class.java) {
            store.saveIfUnchanged(replacement, before.token) { true }
        }
        assertEquals(1, diagnosticReads)
        assertEquals("unreadable", store.status())
        assertEquals("unreadable", first.status())
        assertArrayEquals(replacement, File(directory, AndroidPendingPortableBackupStore.FILE_NAME).readBytes())
        val reopened = AndroidPendingPortableBackupStore(directory).capture()!!
        assertArrayEquals(replacement, reopened.bytes)
        assertNotEquals(before.token, reopened.token)
        reopened.bytes!!.fill(0)
        assertEquals("available", store.status())
    }

    @Test fun `readable old ciphertext after silent publication failure is not successful save`() {
        val first = AndroidPendingPortableBackupStore(directory)
        assertTrue(first.save(original))
        val store = AndroidPendingPortableBackupStore(directory) { file ->
            // Simulate finishWrite logging a failed rename while the old record remains live.
            File(directory, AndroidPendingPortableBackupStore.FILE_NAME).writeBytes(original)
            file.openRead().use { readPortableBackupBytes(it) }
        }
        assertFalse(store.save(replacement))
        assertEquals("available", store.status())
        assertEquals("available", first.status())
        assertArrayEquals(original, File(directory, AndroidPendingPortableBackupStore.FILE_NAME).readBytes())
    }

    @Test fun `late restore or discard cannot delete a replacement or cross retired authority`() {
        val store = AndroidPendingPortableBackupStore(directory)
        assertTrue(store.save(original))
        val originalCapture = store.capture()!!.also { it.bytes!!.fill(0) }
        assertTrue(store.save(replacement))
        assertFalse(store.clearIfMatches(originalCapture.token))
        val newer = store.capture()!!.also { it.bytes!!.fill(0) }
        assertFalse(store.clearIfMatches(newer.token) { false })
        assertEquals("available", store.status())
        assertTrue(store.clearIfMatches(newer.token))
        assertEquals("none", store.status())
    }

    @Test fun `replacement consent cannot overwrite a newer entry or unseen insertion`() {
        val first = AndroidPendingPortableBackupStore(directory)
        val reopened = AndroidPendingPortableBackupStore(directory)
        assertTrue(first.saveIfUnchanged(original, null) { true })
        val reviewed = first.capture()!!.also { it.bytes!!.fill(0) }
        assertTrue(reopened.saveIfUnchanged(replacement, reviewed.token) { true })
        assertFalse(first.saveIfUnchanged(original, reviewed.token) { true })
        assertFalse(first.saveIfUnchanged(original, null) { true })
        val capture = first.capture()!!
        assertArrayEquals(replacement, capture.bytes)
        capture.bytes!!.fill(0)
    }

    @Test fun `corrupt saved bytes remain unreadable and require exact explicit discard`() {
        directory.mkdirs()
        File(directory, AndroidPendingPortableBackupStore.FILE_NAME).writeText("broken ciphertext")
        val store = AndroidPendingPortableBackupStore(directory)
        val capture = store.capture()!!
        assertNull(capture.bytes)
        assertEquals("unreadable", store.status())
        assertFalse(store.clearIfMatches("different record"))
        assertTrue(File(directory, AndroidPendingPortableBackupStore.FILE_NAME).exists())
        assertTrue(store.clearIfMatches(capture.token))
        assertEquals("none", store.status())
    }

    @Test fun `oversized pending file is bounded but can still be explicitly discarded`() {
        directory.mkdirs()
        File(directory, AndroidPendingPortableBackupStore.FILE_NAME).writeBytes(ByteArray(PortableBackupFile.MAX_FILE_BYTES + 1))
        val store = AndroidPendingPortableBackupStore(directory)
        val capture = store.capture()!!
        assertNull(capture.bytes)
        assertEquals("unreadable", store.status())
        assertTrue(store.clearIfMatches(capture.token))
        assertEquals("none", store.status())
    }

    @Test fun `wrong secret does not clear or rewrite retained envelope`() {
        val store = AndroidPendingPortableBackupStore(directory)
        assertTrue(store.save(original))
        val capture = store.capture()!!
        assertEquals(BackupRestoreUnlock.WrongSecret,
            PortableBackupFile.unlock(PortableBackupFile.decode(capture.bytes!!), "password", "incorrect".toCharArray()))
        capture.bytes.fill(0)
        val after = store.capture()!!
        assertEquals(capture.token, after.token)
        assertArrayEquals(original, after.bytes)
        after.bytes!!.fill(0)
    }

    @Test fun `structurally valid authenticated tampering remains stored but fails unlock`() {
        val text = original.toString(Charsets.UTF_8)
        val envelope = PortableBackupFile.decode(original)
        val cipher = java.util.Base64.getDecoder().decode(envelope.payloadCiphertext)
        cipher[cipher.lastIndex] = (cipher.last().toInt() xor 1).toByte()
        val tampered = text.replace(envelope.payloadCiphertext, java.util.Base64.getEncoder().encodeToString(cipher)).toByteArray()
        val store = AndroidPendingPortableBackupStore(directory)
        assertTrue(store.save(tampered))
        val capture = store.capture()!!
        assertEquals(BackupRestoreUnlock.CorruptPayload,
            PortableBackupFile.unlock(PortableBackupFile.decode(capture.bytes!!), "password", secret))
        capture.bytes.fill(0)
        assertEquals("available", store.status())
    }

    @Test fun `stream bounds stop before parsing and cancellation stops before another chunk`() {
        assertThrows(IllegalArgumentException::class.java) {
            readPortableBackupBytes(ByteArrayInputStream(ByteArray(PortableBackupFile.MAX_FILE_BYTES + 1)))
        }
        var reads = 0
        val stream = object : InputStream() {
            override fun read(): Int = error("chunked reader required")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int { reads++; return 1 }
        }
        assertThrows(CancellationException::class.java) {
            readPortableBackupBytes(stream) { if (reads == 1) throw CancellationException("host retired") }
        }
        assertEquals(1, reads)
    }

    @Test fun `zero progress stream fails immediately instead of spinning`() {
        var reads = 0
        val stream = object : InputStream() {
            override fun read(): Int = error("chunked reader required")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int { reads++; return 0 }
        }
        assertThrows(IllegalArgumentException::class.java) { readPortableBackupBytes(stream) }
        assertEquals(1, reads)
    }

    private companion object {
        val secret = "pending-password1!".toCharArray()
        val payload = BackupConfigurationPayload(resolverPresetID = "cloudflare-1111",
            blockedDomains = setOf("private-blocked.example"))
        val original by lazy { PortableBackupFile.seal(payload, "password", secret, 1) }
        val replacement by lazy { PortableBackupFile.seal(payload.copy(blockedDomains = setOf("replacement.example")), "password", secret, 2) }
    }
}
