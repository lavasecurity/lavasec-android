package com.lavasec.app.billing

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.lavasec.app.platform.ChainedUpstreamKeyItemStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36], application = android.app.Application::class)
class AndroidPlayReceiptStoreTest {
    @get:Rule val directory = TemporaryFolder()
    private fun context(): Context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        override fun getNoBackupFilesDir() = directory.root
    }
    private fun receipt(token: String = "secret-purchase") =
        PlayPurchase(token, setOf("lava_security_plus_yearly"), true, false)

    @Test fun `restart reads only the committed encrypted receipt generation`() = runTest {
        val items = Items()
        val store = AndroidPlayReceiptStore(context(), items)
        assertTrue(store.load().isEmpty())
        store.save(listOf(receipt()))
        val old = items.accounts().single()
        store.save(listOf(receipt("replacement-purchase")))
        assertFalse(items.accounts().contains(old))
        assertEquals("replacement-purchase", AndroidPlayReceiptStore(context(), items).load().single().token)
        assertEquals(items.accounts().single(), File(directory.root, "play-receipts-v1").readText())
        assertFalse(File(directory.root, "play-receipts-v1").readText().contains("purchase"))
    }

    @Test fun `missing key and invalid pointer are unavailable rather than an empty purchase set`() = runTest {
        val items = Items()
        val store = AndroidPlayReceiptStore(context(), items)
        store.save(listOf(receipt()))
        items.values.clear()
        assertTrue(runCatching { store.load() }.exceptionOrNull() is IOException)
        for (invalid in listOf("invalid", "")) {
            File(directory.root, "play-receipts-v1").writeText(invalid)
            assertTrue(runCatching { store.load() }.exceptionOrNull() is IOException)
        }
    }

    @Test fun `interrupted publication and refused ciphertext write preserve prior verification material`() = runTest {
        val items = Items()
        val store = AndroidPlayReceiptStore(context(), items)
        store.save(listOf(receipt()))
        val pointer = File(directory.root, "play-receipts-v1")
        assertTrue(pointer.renameTo(File(pointer.path + ".bak")))
        pointer.writeText("interrupted")
        assertEquals("secret-purchase", AndroidPlayReceiptStore(context(), items).load().single().token)
        items.refuseAdd = true
        assertTrue(runCatching { store.save(emptyList()) }.exceptionOrNull() is IOException)
        assertEquals("secret-purchase", store.load().single().token)
    }

    @Test fun `confirmed empty set survives failed cleanup of old ciphertext`() = runTest {
        val items = Items()
        val store = AndroidPlayReceiptStore(context(), items)
        store.save(listOf(receipt()))
        items.failDelete = true
        store.save(emptyList())
        assertEquals(2, items.accounts().size)
        assertTrue(AndroidPlayReceiptStore(context(), items).load().isEmpty())
    }

    private class Items : ChainedUpstreamKeyItemStore {
        val values = mutableMapOf<String, ByteArray>()
        var refuseAdd = false
        var failDelete = false
        override fun add(value: ByteArray, account: String): Boolean {
            if (refuseAdd || values.containsKey(account)) return false
            values[account] = value.copyOf()
            return true
        }
        override fun load(account: String) = values[account]?.copyOf()
        override fun delete(account: String): Boolean {
            if (failDelete) throw IOException("unavailable")
            values.remove(account)
            return true
        }
        override fun accounts() = values.keys.toList()
    }
}
