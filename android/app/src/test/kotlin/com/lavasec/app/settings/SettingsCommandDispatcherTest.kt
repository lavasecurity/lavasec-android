package com.lavasec.app.settings

import com.lavasec.app.foundation.notifications.NotificationCategory
import com.lavasec.app.foundation.notifications.NotificationPreferences
import com.lavasec.app.foundation.preferences.AndroidCapability
import com.lavasec.app.foundation.preferences.PreferenceValue
import com.lavasec.app.foundation.preferences.ProductPreferenceKey
import com.lavasec.app.foundation.preferences.ProductPreferences
import com.lavasec.app.foundation.security.ProtectedSurface
import com.lavasec.app.platform.LoggingSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Strict acceptance for the canonical dynamic `settings.set` dispatch (pure JVM, no Android). */
class SettingsCommandDispatcherTest {
    private class FakePorts : SettingsCommandPorts {
        var preferences = ProductPreferences.canonicalDefaults()
        var notifications = NotificationPreferences.canonicalDefaults()
        var logging: LoggingSettings? = LoggingSettings()
        var preferenceWrite = PreferenceWrite.APPLIED
        var notificationWrite = NotificationWrite.APPLIED
        var loggingWrite = true
        var hapticsWrite = true
        var protectedSurfaceWrite = PreferenceWrite.APPLIED
        var backupAutomaticWrite = PreferenceWrite.APPLIED
        var biometricCapabilityValue = AndroidCapability.UNAVAILABLE_PER_PLATFORM
        var biometricWrite = PreferenceWrite.APPLIED

        val preferencesApplied = mutableListOf<Pair<ProductPreferenceKey, PreferenceValue>>()
        val notificationsApplied = mutableListOf<Pair<NotificationCategory, Boolean>>()
        val loggingApplied = mutableListOf<LoggingSettings>()
        val hapticsApplied = mutableListOf<Boolean>()
        val protectedSurfacesApplied = mutableListOf<Pair<ProtectedSurface, Boolean>>()
        val backupAutomaticApplied = mutableListOf<Boolean>()
        val biometricsApplied = mutableListOf<Boolean>()

        override suspend fun applyPreference(
            key: ProductPreferenceKey,
            value: PreferenceValue,
        ): PreferenceWrite {
            preferencesApplied += key to value
            val updated = preferences.set(key, value)
            if (updated.isFailure) return PreferenceWrite.INVALID
            if (preferenceWrite == PreferenceWrite.APPLIED) preferences = updated.getOrThrow()
            return preferenceWrite
        }

        override suspend fun applyNotification(
            category: NotificationCategory,
            enabled: Boolean,
        ): NotificationWrite {
            notificationsApplied += category to enabled
            if (notificationWrite == NotificationWrite.APPLIED) {
                notifications = notifications.set(category, enabled)
            }
            return notificationWrite
        }

        override fun currentLoggingSettings(): LoggingSettings? = logging

        override suspend fun applyLoggingSettings(settings: LoggingSettings): Boolean {
            loggingApplied += settings
            if (loggingWrite) logging = settings
            return loggingWrite
        }

        override suspend fun applyHaptics(enabled: Boolean): Boolean {
            hapticsApplied += enabled
            return hapticsWrite
        }

        override suspend fun applyBackupAutomatic(enabled: Boolean): PreferenceWrite {
            backupAutomaticApplied += enabled
            return backupAutomaticWrite
        }

        override suspend fun applyProtectedSurface(
            surface: ProtectedSurface,
            enabled: Boolean,
        ): PreferenceWrite {
            protectedSurfacesApplied += surface to enabled
            return protectedSurfaceWrite
        }

        override fun biometricCapability(): AndroidCapability = biometricCapabilityValue

        override suspend fun applyBiometrics(enabled: Boolean): PreferenceWrite {
            biometricsApplied += enabled
            return biometricWrite
        }
    }

    private fun dispatcher(ports: FakePorts) = SettingsCommandDispatcher(ports)

    @Test
    fun `retained customization keys apply through the durable owner with their capability`() = runTest {
        val ports = FakePorts()
        val dispatcher = dispatcher(ports)

        assertEquals(
            SettingsSetResult.Applied(AndroidCapability.AVAILABLE),
            dispatcher.dispatch("appearance", PreferenceValue.Text("dark")),
        )
        assertEquals(
            SettingsSetResult.Applied(AndroidCapability.EQUIVALENT_DEGRADED),
            dispatcher.dispatch("look", PreferenceValue.Text("emerald")),
        )
        assertEquals(
            SettingsSetResult.Applied(AndroidCapability.EQUIVALENT_DEGRADED),
            dispatcher.dispatch("matchTextSize", PreferenceValue.Bool(false)),
        )
        assertEquals(
            SettingsSetResult.Applied(AndroidCapability.EQUIVALENT_DEGRADED),
            dispatcher.dispatch("textSize", PreferenceValue.IntValue(6)),
        )
        assertEquals(4, ports.preferencesApplied.size)
        assertEquals(AndroidCapability.AVAILABLE, ProductPreferenceKey.APPEARANCE.capability)
    }

    @Test
    fun `out-of-domain values fail through the model instead of persisting`() = runTest {
        val ports = FakePorts()
        val dispatcher = dispatcher(ports)

        assertEquals(
            SettingsSetResult.InvalidValue("appearance"),
            dispatcher.dispatch("appearance", PreferenceValue.Text("rainbow")),
        )
        assertEquals(
            SettingsSetResult.InvalidValue("textSize"),
            dispatcher.dispatch("textSize", PreferenceValue.IntValue(99)),
        )
        assertEquals(
            SettingsSetResult.InvalidValue("look"),
            dispatcher.dispatch("look", PreferenceValue.Text("sparkle")),
        )
    }

    @Test
    fun `haptics delegates to the existing customization owner`() = runTest {
        val ports = FakePorts()
        val dispatcher = dispatcher(ports)

        assertEquals(
            SettingsSetResult.Applied(AndroidCapability.AVAILABLE),
            dispatcher.dispatch("haptics", PreferenceValue.Bool(false)),
        )
        assertEquals(listOf(false), ports.hapticsApplied)
        assertTrue(ports.preferencesApplied.isEmpty())
    }

    @Test
    fun `logs keys delegate to the retention model and preserve sibling choices`() = runTest {
        val ports = FakePorts()
        val dispatcher = dispatcher(ports)

        assertEquals(
            SettingsSetResult.Applied(AndroidCapability.AVAILABLE),
            dispatcher.dispatch("logs.Network activity", PreferenceValue.Bool(false)),
        )
        assertEquals(
            LoggingSettings(keepCounts = true, keepHistory = true, keepNetworkActivity = false, keepLavaGuardProgress = true),
            ports.loggingApplied.last(),
        )

        assertEquals(
            SettingsSetResult.Applied(AndroidCapability.AVAILABLE),
            dispatcher.dispatch("logs.Domain logs", PreferenceValue.Bool(false)),
        )
        // The prior opt-out is carried forward; clearing retained data is a separate operation.
        assertEquals(
            LoggingSettings(keepCounts = true, keepHistory = false, keepNetworkActivity = false, keepLavaGuardProgress = true),
            ports.loggingApplied.last(),
        )
    }

    @Test
    fun `notification categories delegate to the notification model`() = runTest {
        val ports = FakePorts()
        val dispatcher = dispatcher(ports)

        assertEquals(
            SettingsSetResult.Applied(AndroidCapability.EQUIVALENT_DEGRADED),
            dispatcher.dispatch("notifications.Filter changes", PreferenceValue.Bool(false)),
        )
        assertEquals(listOf(NotificationCategory.FILTER_CHANGED to false), ports.notificationsApplied)
        assertEquals(false, ports.notifications.isEnabled(NotificationCategory.FILTER_CHANGED))
        // The other three categories are untouched.
        assertEquals(true, ports.notifications.isEnabled(NotificationCategory.CONNECTION_UPDATES))
    }

    @Test
    fun `iOS-only capabilities are classified, never applied`() = runTest {
        val ports = FakePorts()
        val dispatcher = dispatcher(ports)

        val unavailable = SettingsSetResult.CapabilityUnavailable(AndroidCapability.UNAVAILABLE_PER_PLATFORM)
        assertEquals(unavailable, dispatcher.dispatch("liveActivities", PreferenceValue.Bool(true)))
        assertEquals(unavailable, dispatcher.dispatch("liveActivityPauseMinutes", PreferenceValue.IntValue(5)))
        assertEquals(unavailable, dispatcher.dispatch("matchIcon", PreferenceValue.Bool(true)))
        assertEquals(unavailable, dispatcher.dispatch("dnsPatchProvider", PreferenceValue.Text("quad9")))
        assertEquals(unavailable, dispatcher.dispatch("dnsPatchSetup", PreferenceValue.Bool(true)))
        assertEquals(unavailable, dispatcher.dispatch("dnsPatchCheck", PreferenceValue.Bool(true)))
        assertEquals(unavailable, dispatcher.dispatch("dnsPatchRemove", PreferenceValue.Bool(true)))

        assertTrue(ports.preferencesApplied.isEmpty())
        assertTrue(ports.hapticsApplied.isEmpty())
        assertTrue(ports.loggingApplied.isEmpty())
        assertTrue(ports.notificationsApplied.isEmpty())
    }

    @Test
    fun `unknown keys and wrong value types are typed rejections`() = runTest {
        val ports = FakePorts()
        val dispatcher = dispatcher(ports)

        assertEquals(SettingsSetResult.UnknownKey, dispatcher.dispatch("frobnicate", PreferenceValue.Bool(true)))
        assertEquals(
            SettingsSetResult.InvalidValue("appearance"),
            dispatcher.dispatch("appearance", PreferenceValue.Bool(true)),
        )
        assertEquals(
            SettingsSetResult.InvalidValue("textSize"),
            dispatcher.dispatch("textSize", PreferenceValue.Text("large")),
        )
        assertEquals(
            SettingsSetResult.InvalidValue("liveActivities"),
            dispatcher.dispatch("liveActivities", PreferenceValue.IntValue(1)),
        )
        assertEquals(
            SettingsSetResult.InvalidValue("logs.Domain logs"),
            dispatcher.dispatch("logs.Domain logs", PreferenceValue.Text("yes")),
        )
        assertTrue(ports.preferencesApplied.isEmpty())
    }

    @Test
    fun `canonical keys owned by other slices are unsupported rather than unknown`() = runTest {
        val ports = FakePorts()
        val dispatcher = dispatcher(ports)

        listOf(
            "deviceDNS" to PreferenceValue.Bool(true),
            "fallback" to PreferenceValue.Bool(true),
            "provider" to PreferenceValue.Text("Mullvad"),
            "providerID" to PreferenceValue.Text("mullvad"),
            "transport" to PreferenceValue.Text("DoH"),
        ).forEach { (key, value) ->
            assertEquals("expected Unsupported for $key", SettingsSetResult.Unsupported, dispatcher.dispatch(key, value))
        }
        // A protected-action key outside the six exact suffixes is an unknown key.
        assertEquals(
            SettingsSetResult.UnknownKey,
            dispatcher.dispatch("protectedActions.Made Up", PreferenceValue.Bool(true)),
        )
        assertEquals(
            SettingsSetResult.InvalidValue("deviceDNS"),
            dispatcher.dispatch("deviceDNS", PreferenceValue.Text("yes")),
        )
    }

    @Test
    fun `protected actions delegate to the security owner and biometrics is honest platform-unavailable`() = runTest {
        val ports = FakePorts()
        val dispatcher = dispatcher(ports)

        assertEquals(
            SettingsSetResult.Applied(AndroidCapability.AVAILABLE),
            dispatcher.dispatch("protectedActions.Pause Lava", PreferenceValue.Bool(true)),
        )
        ports.protectedSurfaceWrite = PreferenceWrite.FAILED
        assertEquals(
            SettingsSetResult.Refused,
            dispatcher.dispatch("protectedActions.View Activities", PreferenceValue.Bool(false)),
        )
        assertEquals(
            listOf(
                ProtectedSurface.PROTECTION_PAUSE to true,
                ProtectedSurface.ACTIVITY to false,
            ),
            ports.protectedSurfacesApplied,
        )
        // A wrong value shape is a typed invalid; the owner is never invoked.
        assertEquals(
            SettingsSetResult.InvalidValue("protectedActions.App Unlock"),
            dispatcher.dispatch("protectedActions.App Unlock", PreferenceValue.Text("yes")),
        )
        // With no registered platform authenticator the capability port reports the honest
        // platform-unavailable classification; the preference is never faked on.
        assertEquals(
            SettingsSetResult.CapabilityUnavailable(AndroidCapability.UNAVAILABLE_PER_PLATFORM),
            dispatcher.dispatch("biometrics", PreferenceValue.Bool(true)),
        )
        assertEquals(
            SettingsSetResult.InvalidValue("biometrics"),
            dispatcher.dispatch("biometrics", PreferenceValue.Text("yes")),
        )
        assertTrue(ports.biometricsApplied.isEmpty())
        assertEquals(2, ports.protectedSurfacesApplied.size)
    }

    @Test
    fun `a registered biometric authenticator delegates the preference to the security owner`() = runTest {
        val ports = FakePorts().apply { biometricCapabilityValue = AndroidCapability.AVAILABLE }
        val dispatcher = dispatcher(ports)

        assertEquals(
            SettingsSetResult.Applied(AndroidCapability.AVAILABLE),
            dispatcher.dispatch("biometrics", PreferenceValue.Bool(true)),
        )
        assertEquals(listOf(true), ports.biometricsApplied)
        // An owner refusal (e.g. no passcode or an unreadable Keystore) is surfaced, not faked.
        ports.biometricWrite = PreferenceWrite.FAILED
        assertEquals(
            SettingsSetResult.Refused,
            dispatcher.dispatch("biometrics", PreferenceValue.Bool(false)),
        )
        // A wrong value shape is rejected before the owner is consulted.
        assertEquals(
            SettingsSetResult.InvalidValue("biometrics"),
            dispatcher.dispatch("biometrics", PreferenceValue.Text("yes")),
        )
        assertEquals(listOf(true, false), ports.biometricsApplied)
    }

    @Test
    fun `automatic backup is delegated to the backup owner and a refusal is surfaced`() = runTest {
        val ports = FakePorts()
        val dispatcher = dispatcher(ports)

        assertEquals(
            SettingsSetResult.Applied(AndroidCapability.EQUIVALENT_DEGRADED),
            dispatcher.dispatch("backup.automatic", PreferenceValue.Bool(true)),
        )
        assertEquals(listOf(true), ports.backupAutomaticApplied)
        // The backup owner refuses an enable while a deletion fence or operation is in flight;
        // that typed refusal must reach the shared screen, never a fabricated success.
        ports.backupAutomaticWrite = PreferenceWrite.FAILED
        assertEquals(
            SettingsSetResult.Refused,
            dispatcher.dispatch("backup.automatic", PreferenceValue.Bool(true)),
        )
        // A wrong value shape is rejected before the owner is consulted.
        assertEquals(
            SettingsSetResult.InvalidValue("backup.automatic"),
            dispatcher.dispatch("backup.automatic", PreferenceValue.Text("yes")),
        )
        assertEquals(listOf(true, true), ports.backupAutomaticApplied)
    }

    @Test
    fun `an owner refusal is surfaced, never faked as success`() = runTest {
        val ports = FakePorts()
        val dispatcher = dispatcher(ports)

        ports.preferenceWrite = PreferenceWrite.FAILED
        assertEquals(
            SettingsSetResult.Refused,
            dispatcher.dispatch("appearance", PreferenceValue.Text("dark")),
        )

        ports.loggingWrite = false
        assertEquals(
            SettingsSetResult.Refused,
            dispatcher.dispatch("logs.Domain logs", PreferenceValue.Bool(false)),
        )

        ports.notificationWrite = NotificationWrite.FAILED
        assertEquals(
            SettingsSetResult.Refused,
            dispatcher.dispatch("notifications.Protection resumed", PreferenceValue.Bool(false)),
        )

        ports.hapticsWrite = false
        assertEquals(
            SettingsSetResult.Refused,
            dispatcher.dispatch("haptics", PreferenceValue.Bool(true)),
        )
    }
}
