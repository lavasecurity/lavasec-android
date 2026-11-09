package com.lavasec.app.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lavasec.app.foundation.security.LockTimeoutOption
import com.lavasec.app.foundation.security.ProtectedSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SecurityPreferenceStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `unlock session facts round-trip through the store`() {
        val store = SecurityPreferenceStore(context)
        store.save(
            SecurityPreferences(
                enabled = true,
                lastUnlockEpochMillis = 1_750_000_000_000L,
                lockTimeoutMinutes = 5L,
                protectedSurfaces = setOf(ProtectedSurface.ACTIVITY),
            ),
        )

        val loaded = SecurityPreferenceStore(context).load()
        assertEquals(true, loaded.enabled)
        assertEquals(1_750_000_000_000L, loaded.lastUnlockEpochMillis)
        assertEquals(5L, loaded.lockTimeoutMinutes)
        assertEquals(setOf(ProtectedSurface.ACTIVITY), loaded.protectedSurfaces)

        // Clearing the session drops the unlock timestamp.
        store.save(loaded.copy(enabled = false, lastUnlockEpochMillis = null))
        val cleared = SecurityPreferenceStore(context).load()
        assertEquals(false, cleared.enabled)
        assertNull(cleared.lastUnlockEpochMillis)
    }

    @Test
    fun `legacy explicit screen pair never selects newly implemented control gates`() {
        val pair = setOf(ProtectedSurface.ACTIVITY, ProtectedSurface.FILTERS)
        SecurityPreferenceStore(context).save(SecurityPreferences(enabled = true, protectedSurfaces = pair))
        repeat(3) {
            val store = SecurityPreferenceStore(context)
            val saved = store.load()
            assertEquals(pair, saved.protectedSurfaces)
            store.save(saved)
        }
    }

    @Test
    fun `defaults match the policy defaults`() {
        val loaded = SecurityPreferenceStore(context).load()
        assertEquals(false, loaded.enabled)
        assertNull(loaded.lastUnlockEpochMillis)
        assertEquals(LockTimeoutOption.DEFAULT.minutes, loaded.lockTimeoutMinutes)
        assertEquals(emptySet<ProtectedSurface>(), loaded.protectedSurfaces)
    }
    @Test
    fun `explicitly empty choices remain empty on repeated reopen`() {
        SecurityPreferenceStore(context).save(SecurityPreferences(
            enabled = true, protectedSurfaces = emptySet(),
        ))
        repeat(3) {
            val store = SecurityPreferenceStore(context)
            val loaded = store.load()
            assertEquals(emptySet<ProtectedSurface>(), loaded.protectedSurfaces)
            store.save(loaded)
        }
    }

    @Test
    fun `stored legacy choices survive rather than being reset to new defaults`() {
        val preferences = context.getSharedPreferences("lavasec_security", Context.MODE_PRIVATE)
        preferences.edit().putStringSet("protected_surfaces", setOf("ACTIVITY", "FILTERS")).commit()
        assertEquals(setOf(ProtectedSurface.ACTIVITY, ProtectedSurface.FILTERS),
            SecurityPreferenceStore(context).load().protectedSurfaces)
    }

    @Test
    fun `unknown choices fail closed even when mixed with known surfaces`() {
        val preferences = context.getSharedPreferences("lavasec_security", Context.MODE_PRIVATE)
        for (names in listOf(setOf("futureSurface"), setOf("ACTIVITY", "futureSurface"))) {
            preferences.edit().putStringSet("protected_surfaces", names).commit()
            assertEquals(ProtectedSurface.ALL, SecurityPreferenceStore(context).load().protectedSurfaces)
        }
    }

    @Test
    fun `pending credential publication preserves choices across reopen`() {
        val saved = SecurityPreferences(enabled = true,
            protectedSurfaces = setOf(ProtectedSurface.ACTIVITY), credentialChangePending = true)
        SecurityPreferenceStore(context).save(saved)
        assertEquals(saved, SecurityPreferenceStore(context).load())
    }

    @Test fun `independent cover choice survives damaged access preferences`() {
        val store = SecurityPreferenceStore(context)
        store.save(SecurityPreferences(enabled = true, protectedSurfaces = setOf(ProtectedSurface.ACTIVITY), appUnlockCoverChoice = false))
        context.getSharedPreferences("lavasec_security", Context.MODE_PRIVATE).edit()
            .putString("passcode_enabled", "damaged").putStringSet("protected_surfaces", setOf("futureSurface")).commit()
        assertEquals(false, SecurityPreferenceStore(context).appUnlockCoverChoice())
    }

    @Test fun `legacy app gate is unknown while legacy scoped only choices classify OFF`() {
        val raw = context.getSharedPreferences("lavasec_security", Context.MODE_PRIVATE)
        raw.edit().putStringSet("protected_surfaces", setOf("APP_UNLOCK", "FILTERS")).commit()
        val store = SecurityPreferenceStore(context)
        assertNull(store.load().appUnlockCoverChoice)
        store.save(store.load())
        assertNull(SecurityPreferenceStore(context).load().appUnlockCoverChoice)
        raw.edit().remove("app_unlock_cover_choice").putStringSet("protected_surfaces", setOf("FILTERS")).commit()
        assertEquals(false, store.load().appUnlockCoverChoice)
    }

}
