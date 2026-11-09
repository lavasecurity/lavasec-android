package com.lavasec.app.notification

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lavasec.app.foundation.health.ProtectionConnectivityNotificationHistory
import com.lavasec.app.foundation.health.ProtectionConnectivityNotificationKind
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
class CredentialConnectivityNotificationHistoryStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "notification-history-${UUID.randomUUID()}"
    private val store = CredentialConnectivityNotificationHistoryStore(
        credentialContext = context,
        storeName = name,
        credentialAvailable = { true },
    )

    @After
    fun tearDown() {
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `strict credential history round trips`() {
        val expected = ProtectionConnectivityNotificationHistory(
            lastDeliveredNotificationId = "reconnect-needed:1000",
            lastDeliveredAtMillis = 1_000L,
            problemDeliveryCooldownUntilMillis = 2_000L,
            unresolvedProblemNotificationId = "reconnect-needed:1000",
            unresolvedProblemKind = ProtectionConnectivityNotificationKind.RECONNECT_NEEDED,
        )

        assertEquals(ProtectionConnectivityNotificationHistory.EMPTY, store.read())
        assertEquals(true, store.write(expected))
        assertEquals(expected, store.read())
    }

    @Test
    fun `forward or malformed record is unreadable and remains untouched`() {
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        preferences.edit().putInt("schema_version", 99).putString("private", "example.com").commit()

        assertNull(store.read())
        assertEquals("example.com", preferences.getString("private", null))
    }

    @Test
    fun `device protected context is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            CredentialConnectivityNotificationHistoryStore(
                credentialContext = context.createDeviceProtectedStorageContext(),
                storeName = name,
                credentialAvailable = { true },
            )
        }
    }
}
