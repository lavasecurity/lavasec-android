package com.lavasec.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CredentialChainedPreferenceStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun store(name: String): CredentialChainedPreferenceStore =
        CredentialChainedPreferenceStore(context, name)

    private fun uniqueName(): String = "chained-prefs-" + System.nanoTime()

    @Test
    fun `defaults to disabled`() = runBlocking {
        val subject = store(uniqueName())
        assertFalse(subject.loadEnabled())
        assertFalse(subject.chainedEnabled.first())
    }

    @Test
    fun `a committed enable survives a new store instance`() = runBlocking {
        val name = uniqueName()
        val first = store(name)
        assertTrue(first.setChainedEnabled(true))
        assertTrue(first.loadEnabled())
        assertTrue(first.chainedEnabled.first())

        val second = store(name)
        assertTrue(second.loadEnabled())
    }

    @Test
    fun `a write through a retiring store is observed by an already-created replacement`() =
        runBlocking {
            val name = uniqueName()
            // Both stores exist before any write, as during a credential-graph rotation.
            val retiring = store(name)
            val replacement = store(name)
            assertFalse(replacement.loadEnabled())

            assertTrue(retiring.setChainedEnabled(true))

            // The replacement must read the durable value, not its construction-time cache.
            assertTrue(replacement.loadEnabled())
            assertTrue(replacement.chainedEnabled.first())
        }

    @Test
    fun `disabling is committed as well`() = runBlocking {
        val name = uniqueName()
        val subject = store(name)
        assertTrue(subject.setChainedEnabled(true))
        assertTrue(subject.setChainedEnabled(false))
        assertFalse(subject.loadEnabled())
        assertFalse(store(name).loadEnabled())
    }
    @Test fun `chain fallback defaults off and survives replacement without changing enabled`() = runBlocking {
        val name = uniqueName()
        val retiring = store(name)
        val replacement = store(name)
        assertFalse(retiring.loadDnsFallbackEnabled())
        assertTrue(retiring.setChainedEnabled(true))
        assertTrue(retiring.setChainedDnsFallbackEnabled(true))
        assertTrue(replacement.loadDnsFallbackEnabled())
        assertTrue(replacement.loadEnabled())
        assertTrue(replacement.setChainedDnsFallbackEnabled(false))
        assertFalse(retiring.loadDnsFallbackEnabled())
        assertTrue(retiring.loadEnabled())
    }

}
