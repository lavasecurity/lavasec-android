package com.lavasec.app.account

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lavasec.app.platform.ChainedUpstreamKeyItemStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36], application = android.app.Application::class)
class AndroidAccountSessionStoreTest {
    @get:org.junit.Rule val directory = org.junit.rules.TemporaryFolder()
    private fun isolatedContext(): Context = object : android.content.ContextWrapper(
        ApplicationProvider.getApplicationContext<Context>(),
    ) {
        override fun getNoBackupFilesDir(): File = directory.root
    }

    private class Items : ChainedUpstreamKeyItemStore {
        val values = mutableMapOf<String, ByteArray>()
        var refuseAdd = false
        var failDelete = false
        override fun add(value: ByteArray, account: String): Boolean {
            if (refuseAdd || values.containsKey(account)) return false
            values[account] = value.copyOf(); return true
        }
        override fun load(account: String) = values[account]?.copyOf()
        override fun delete(account: String): Boolean { if (failDelete) throw java.io.IOException("ciphertext store unavailable"); values.remove(account); return true }
        override fun accounts() = values.keys.toList()
    }

    @Test fun `rotation commits an opaque pointer then sweeps old encrypted generations`() = runTest {
        val context = isolatedContext()
        val items = Items()
        val store = AndroidAccountSessionStore(context, items)
        store.clear()
        val session = AccountSession("secret-access", "secret-refresh", 3600,
            "10000000-0000-0000-0000-000000000000", null, setOf("google"))
        store.save(session)
        val first = items.accounts().single()
        store.save(session)
        assertEquals(1, items.accounts().size)
        assertFalse(items.accounts().contains(first))
        assertEquals(items.accounts().single(), File(context.noBackupFilesDir, "account-session-v1").readText())
        val restored = requireNotNull(store.load()) { "Committed session pointer did not load its ciphertext" }
        assertEquals(session.refreshToken, restored.refreshToken)
        val pointer = File(context.noBackupFilesDir, "account-session-v1").readText()
        assertEquals(items.accounts().single(), pointer)
        assertFalse(pointer.contains("secret"))
        store.clear()
        assertNull(store.load())
        assertTrue(items.accounts().isEmpty())
        assertEquals("", File(context.noBackupFilesDir, "account-session-v1").readText())
    }

    @Test fun `restored data without this devices key is signed out`() = runTest {
        val context = isolatedContext()
        val items = Items()
        val store = AndroidAccountSessionStore(context, items)
        store.clear()
        store.save(AccountSession("access", "refresh", 3600,
            "10000000-0000-0000-0000-000000000000", null, setOf("google")))
        items.values.clear()
        assertNull(store.load())
    }

    @Test fun `interrupted pointer replacement recovers the previous encrypted session`() = runTest {
        val context = isolatedContext()
        val items = Items()
        val store = AndroidAccountSessionStore(context, items)
        store.save(AccountSession("access", "refresh", 3600,
            "10000000-0000-0000-0000-000000000000", null, setOf("google")))
        val pointer = File(context.noBackupFilesDir, "account-session-v1")
        assertTrue(pointer.renameTo(File(pointer.path + ".bak")))
        pointer.writeText("interrupted")
        assertEquals("refresh", AndroidAccountSessionStore(context, items).load()?.refreshToken)
        assertEquals(items.accounts().single(), pointer.readText())
    }

    @Test fun `failed ciphertext insertion preserves the previous committed session`() = runTest {
        val context = isolatedContext()
        val items = Items()
        val store = AndroidAccountSessionStore(context, items)
        val first = AccountSession("access", "old-refresh", 3600,
            "10000000-0000-0000-0000-000000000000", null, setOf("google"))
        store.save(first)
        items.refuseAdd = true
        assertTrue(runCatching { store.save(AccountSession("new-access", "new-refresh", 7200,
            first.userId, null, first.providers)) }.exceptionOrNull() is java.io.IOException)
        assertEquals("old-refresh", store.load()?.refreshToken)
    }

    @Test fun `durable logout survives ciphertext cleanup failure and restart`() = runTest {
        val context = isolatedContext()
        val items = Items()
        val store = AndroidAccountSessionStore(context, items)
        store.save(AccountSession("access", "refresh", 3600,
            "10000000-0000-0000-0000-000000000000", null, setOf("google")))
        items.failDelete = true
        store.clear()
        assertTrue(items.accounts().isNotEmpty())
        assertNull(AndroidAccountSessionStore(context, items).load())
    }
}
