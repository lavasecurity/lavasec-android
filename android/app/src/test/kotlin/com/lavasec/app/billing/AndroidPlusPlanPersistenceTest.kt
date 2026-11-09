package com.lavasec.app.billing

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
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
class AndroidPlusPlanPersistenceTest {
    @get:Rule val directory = TemporaryFolder()
    private fun context(): Context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        override fun getNoBackupFilesDir(): File = directory.root
    }

    @Test fun `plan survives process replacement and confirmed lapse`() = runTest {
        assertFalse(AndroidPlusPlanPersistence(context()).load())
        AndroidPlusPlanPersistence(context()).save(true)
        assertTrue(AndroidPlusPlanPersistence(context()).load())
        AndroidPlusPlanPersistence(context()).save(false)
        assertFalse(AndroidPlusPlanPersistence(context()).load())
    }

    @Test fun `interrupted AtomicFile update restores previous plan`() = runTest {
        val store = AndroidPlusPlanPersistence(context())
        store.save(true)
        assertTrue(store.setChainedEnabled(true))
        val base = File(directory.root, "plus-plan-v1")
        assertTrue(base.renameTo(File(base.path + ".bak")))
        base.writeBytes(byteArrayOf(0))
        assertTrue(AndroidPlusPlanPersistence(context()).load())
        assertTrue(AndroidPlusPlanPersistence(context()).loadEnabled())
    }

    @Test fun `corrupt future truncated and oversized records are unavailable rather than free`() = runTest {
        val base = File(directory.root, "plus-plan-v1")
        for (bytes in listOf(
            byteArrayOf(0x4c, 0x50, 2, 1, 0),
            byteArrayOf(0x4c, 0x50, 1, 2, 0),
            byteArrayOf(0x4c, 0x50, 1),
            byteArrayOf(0x4c, 0x50, 1, 1, 0, 0),
        )) {
            base.writeBytes(bytes)
            try {
                AndroidPlusPlanPersistence(context()).load()
                fail("invalid record was accepted")
            } catch (_: IOException) { /* retained for recovery */ }
            assertArrayEquals(bytes, base.readBytes())
        }
    }

    @Test fun `confirmed lapse commits the plan and chaining preference together`() = runTest {
        val store = AndroidPlusPlanPersistence(context())
        store.save(true)
        assertTrue(store.setChainedEnabled(true))
        store.save(false)
        val restarted = AndroidPlusPlanPersistence(context())
        assertFalse(restarted.load())
        assertFalse(restarted.loadEnabled())
        assertFalse(store.setChainedEnabled(true))
        store.save(true)
        assertFalse(store.loadEnabled()) // A re-grant does not restore the revoked preference.
    }

    @Test fun `legacy preference migrates once without overriding a later committed choice`() = runTest {
        val store = AndroidPlusPlanPersistence(context(), legacyChainedPreference = { true })
        assertFalse(store.load())
        assertTrue(store.loadEnabled())
        store.save(true)
        store.setChainedEnabled(false)
        val restarted = AndroidPlusPlanPersistence(context(), legacyChainedPreference = { true })
        assertTrue(restarted.load())
        assertFalse(restarted.loadEnabled())
    }
    @Test fun `v1 plan upgrades with independent fallback off and preserves both existing bits`() = runTest {
        val base = File(directory.root, "plus-plan-v1")
        base.writeBytes(byteArrayOf(0x4c, 0x50, 1, 1, 1))
        val subject = AndroidPlusPlanPersistence(context())
        assertTrue(subject.load()); assertTrue(subject.loadEnabled())
        assertFalse(subject.loadDnsFallbackEnabled())
        assertTrue(subject.setChainedDnsFallbackEnabled(true))
        val reopened = AndroidPlusPlanPersistence(context())
        assertTrue(reopened.load()); assertTrue(reopened.loadEnabled()); assertTrue(reopened.loadDnsFallbackEnabled())
        assertArrayEquals(byteArrayOf(0x4c,0x50,2,1,1,1), base.readBytes())
        assertTrue(reopened.setChainedDnsFallbackEnabled(false))
        assertTrue(reopened.load()); assertTrue(reopened.loadEnabled())
        assertFalse(AndroidPlusPlanPersistence(context()).loadDnsFallbackEnabled())
    }

    @Test fun `fallback consent is independent of chaining and confirmed plan writes`() = runTest {
        val subject = AndroidPlusPlanPersistence(context())
        assertFalse(subject.setChainedDnsFallbackEnabled(true))
        subject.save(true)
        assertTrue(subject.setChainedDnsFallbackEnabled(true))
        assertFalse(subject.loadEnabled())
        assertTrue(subject.setChainedEnabled(true))
        subject.save(false)
        assertFalse(subject.loadEnabled())
        assertTrue(subject.loadDnsFallbackEnabled())
        assertFalse(subject.setChainedDnsFallbackEnabled(true))
        assertTrue(subject.setChainedDnsFallbackEnabled(false))
    }

    @Test fun `retired native authority rolls back both preferences at actual AtomicFile publication`() = runTest {
        val store = AndroidPlusPlanPersistence(context())
        store.save(true)
        assertTrue(store.setChainedEnabled(true))
        assertTrue(store.setChainedDnsFallbackEnabled(true))
        val base = File(directory.root, "plus-plan-v1")
        val original = base.readBytes()
        for (fallbackWrite in listOf(false, true)) {
            var checks = 0
            // Admission is current; the authority retires after staging, before finishWrite.
            val authority = { ++checks < 2 }
            val result = if (fallbackWrite) store.setChainedDnsFallbackEnabledWhileCurrent(false, authority)
                else store.setChainedEnabledWhileCurrent(false, authority)
            assertFalse(result)
            assertTrue("The real publication edge was reached", checks >= 2)
            assertArrayEquals(original, base.readBytes())
            val reopened = AndroidPlusPlanPersistence(context())
            assertTrue(reopened.load()); assertTrue(reopened.loadEnabled()); assertTrue(reopened.loadDnsFallbackEnabled())
        }
        assertTrue(store.setChainedEnabledWhileCurrent(false) { true })
        assertTrue(store.setChainedDnsFallbackEnabledWhileCurrent(false) { true })
        val reopened = AndroidPlusPlanPersistence(context())
        assertTrue(reopened.load()); assertFalse(reopened.loadEnabled()); assertFalse(reopened.loadDnsFallbackEnabled())
    }

    @Test fun `already retired native preference writes leave durable plan and grants untouched`() = runTest {
        val store = AndroidPlusPlanPersistence(context())
        store.save(true)
        val base = File(directory.root, "plus-plan-v1")
        val original = base.readBytes()
        assertFalse(store.setChainedEnabledWhileCurrent(true) { false })
        assertFalse(store.setChainedDnsFallbackEnabledWhileCurrent(true) { false })
        assertArrayEquals(original, base.readBytes())
        assertTrue(AndroidPlusPlanPersistence(context()).load())
        assertFalse(AndroidPlusPlanPersistence(context()).loadEnabled())
        assertFalse(AndroidPlusPlanPersistence(context()).loadDnsFallbackEnabled())
    }

    @org.junit.Test fun `FOSS chaining is durable without a paid flag`() = kotlinx.coroutines.runBlocking {
        val context = context()
        val persistence = AndroidPlusPlanPersistence(context, localCapabilities = true)
        assertFalse(persistence.load())
        assertTrue(persistence.setChainedEnabled(true))
        assertTrue(persistence.setChainedDnsFallbackEnabled(true))
        val reopened = AndroidPlusPlanPersistence(context, localCapabilities = true)
        assertFalse(reopened.load())
        assertTrue(reopened.loadEnabled())
        assertTrue(reopened.loadDnsFallbackEnabled())
    }

}
