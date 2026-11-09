package com.lavasec.app.reactnative

import android.app.Application
import com.lavasec.app.foundation.backup.BackupRestoreOwner
import com.lavasec.app.security.SecurityAuthorization
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class NativePortableBackupAuthorizationTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `dismissed authentication cancels every portable action without accessing files or restore owner`() = runTest {
        verifyEarlyAuthorization(SecurityAuthorization.CANCELLED, PortableBackupResult("cancelled", "dismissed"))
    }

    @Test fun `denied authentication refuses every portable action without accessing files or restore owner`() = runTest {
        verifyEarlyAuthorization(SecurityAuthorization.DENIED, PortableBackupResult("rejected", "unauthorized"))
    }

    private suspend fun verifyEarlyAuthorization(authorization: SecurityAuthorization, expected: PortableBackupResult) {
        val activity = Robolectric.buildActivity(LavaReactActivity::class.java).get()
        var requests = 0
        val source = object : PortableBackupSource {
            override val restoreOwner: BackupRestoreOwner get() = error("A refused action cannot prepare or apply restore")
            override suspend fun capture(): PortableBackupSnapshot? = error("A refused action cannot capture plaintext")
            override suspend fun authorize(): SecurityAuthorization { requests++; return authorization }
            override fun publicationStillAuthorized(): Boolean = false
        }
        val pending = AndroidPendingPortableBackupStore(folder.root.resolve("pending"))
        val flow = NativePortableBackupFlow(activity, source, pending)
        assertEquals(expected, flow.export())
        assertEquals(expected, flow.restore())
        assertEquals(expected, flow.resume())
        assertEquals(expected, flow.discard())
        assertEquals(4, requests)
        assertEquals("none", pending.status())
        assertFalse(folder.root.resolve("pending").exists())
        assertNull(shadowOf(activity).nextStartedActivity)
    }
}
