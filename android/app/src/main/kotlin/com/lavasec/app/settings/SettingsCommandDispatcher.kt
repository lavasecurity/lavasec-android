package com.lavasec.app.settings

import com.lavasec.app.foundation.notifications.NotificationCategory
import com.lavasec.app.foundation.preferences.AndroidCapability
import com.lavasec.app.foundation.preferences.PreferenceValue
import com.lavasec.app.foundation.preferences.PreferenceValueType
import com.lavasec.app.foundation.preferences.ProductPreferenceKey
import com.lavasec.app.foundation.preferences.type
import com.lavasec.app.foundation.privacy.PrivacyRetention
import com.lavasec.app.foundation.privacy.PrivacyRetentionCategory
import com.lavasec.app.foundation.privacy.RetentionChoice
import com.lavasec.app.foundation.security.ProtectedSurface
import com.lavasec.app.platform.LoggingSettings

/** The typed outcome of one canonical `settings.set`, mapped to a stable wire result by the bridge. */
internal sealed interface SettingsSetResult {
    /** Applied through a real owner; [capability] says how faithfully Android honours it. */
    data class Applied(val capability: AndroidCapability) : SettingsSetResult

    /** A recognized iOS-only capability: classified, never applied. */
    data class CapabilityUnavailable(val capability: AndroidCapability) : SettingsSetResult

    /** A canonical key whose owner is a different (not yet ported) slice. */
    data object Unsupported : SettingsSetResult

    /** A key outside the canonical `settings.set` vocabulary. */
    data object UnknownKey : SettingsSetResult

    /** A canonical key with a malformed or out-of-domain value. */
    data class InvalidValue(val key: String) : SettingsSetResult

    /** A real owner could not durably apply the change; the previous value stays authoritative. */
    data object Refused : SettingsSetResult
}

/** The owners and platform ports the dispatcher needs; production wires the durable owners. */
internal interface SettingsCommandPorts {
    suspend fun applyPreference(key: ProductPreferenceKey, value: PreferenceValue): PreferenceWrite

    suspend fun applyNotification(category: NotificationCategory, enabled: Boolean): NotificationWrite

    /** The current retained local-log policy, or `null` when the product cannot supply it. */
    fun currentLoggingSettings(): LoggingSettings?

    suspend fun applyLoggingSettings(settings: LoggingSettings): Boolean

    /** The existing customization owner is the durable authority for haptics. */
    suspend fun applyHaptics(enabled: Boolean): Boolean

    /**
     * Applies the canonical `backup.automatic` preference through the backup owner — the same
     * owner that reads it back for the `backup` snapshot. A missing owner or a refused durable
     * write is an honest failure, never a fabricated success.
     */
    suspend fun applyBackupAutomatic(enabled: Boolean): PreferenceWrite

    /**
     * Applies one canonical `protectedActions.*` choice through the SecurityOwner — the same owner
     * that enforces the surface. The owner runs the fresh authentication a removal requires; the
     * dispatcher adds no policy of its own.
     */
    suspend fun applyProtectedSurface(surface: ProtectedSurface, enabled: Boolean): PreferenceWrite

    /**
     * The biometric capability the SecurityOwner reports for `settings.set biometrics`. The
     * fail-closed default is the honest Android platform-unavailable classification; a build that
     * registers a real platform authenticator overrides it. Never fabricated as available.
     */
    fun biometricCapability(): AndroidCapability = AndroidCapability.UNAVAILABLE_PER_PLATFORM

    /**
     * Applies the canonical `biometrics` preference through the SecurityOwner — the same owner
     * that projects the `security` snapshot family and enforces the gate. The owner applies the
     * canonical enable/disable policy; the dispatcher adds no policy of its own.
     */
    suspend fun applyBiometrics(enabled: Boolean): PreferenceWrite = PreferenceWrite.FAILED
}

/** The capability classification of an Android-honoured preference, as a stable wire code. */
internal fun AndroidCapability.wireValue(): String = when (this) {
    AndroidCapability.AVAILABLE -> "available"
    AndroidCapability.EQUIVALENT_DEGRADED -> "equivalent-degraded"
    AndroidCapability.UNAVAILABLE_PER_PLATFORM -> "unavailable-per-platform"
}

/**
 * The canonical dynamic `settings.set` dispatcher.
 *
 * Acceptance mirrors the iOS `LavaAppBridge.setSetting` dispatch at
 * c4658d973bae05dd3b7aa84c014cb2f76ff61a0b exactly where Android has an owner:
 *
 * - Every canonical retained key is resolved by [ProductPreferenceKey] and its declared
 *   value shape/domain; a wrong type or an out-of-domain value is an [SettingsSetResult.InvalidValue].
 * - The iOS-only platform capabilities (Live Activities, alternate icon and the managed DNS
 *   patch keys) return their [AndroidCapability] classification — `unavailable-per-platform` —
 *   instead of pretending success.
 * - `logs.*` is delegated to the pure [PrivacyRetention] model and persisted through the
 *   privacy owner; `notifications.*` is delegated to [NotificationCategory] through the
 *   notification owner.
 * - `haptics` is delegated to the existing customization owner.
 * - `backup.automatic` is delegated to the backup owner, the same durable authority the `backup`
 *   snapshot projects.
 * - `biometrics` is delegated to the SecurityOwner; when no platform authenticator is registered
 *   the owner reports the honest platform-unavailable classification.
 * - A canonical key owned by another slice (DNS selection, security) is
 *   [SettingsSetResult.Unsupported], never an unknown-key rejection.
 *
 * The dispatcher performs no I/O and no policy of its own: it validates the canonical shape,
 * selects the owner and returns a typed outcome.
 */
internal class SettingsCommandDispatcher(
    private val ports: SettingsCommandPorts,
) {
    suspend fun dispatch(key: String, value: PreferenceValue): SettingsSetResult {
        val retained = ProductPreferenceKey.fromWire(key)
        if (retained != null) {
            if (value.type != retained.valueType) return SettingsSetResult.InvalidValue(key)
            // iOS-only capabilities are classified, never applied — even when the shape is valid.
            if (retained.capability == AndroidCapability.UNAVAILABLE_PER_PLATFORM) {
                return SettingsSetResult.CapabilityUnavailable(retained.capability)
            }
            return applyRetained(retained, value)
        }
        MANAGED_DNS_PATCH_TYPES[key]?.let { expected ->
            if (value.type != expected) return SettingsSetResult.InvalidValue(key)
            return SettingsSetResult.CapabilityUnavailable(AndroidCapability.UNAVAILABLE_PER_PLATFORM)
        }
        if (key == BIOMETRICS_KEY) {
            if (value.type != PreferenceValueType.BOOLEAN) return SettingsSetResult.InvalidValue(key)
            val enabled = (value as? PreferenceValue.Bool)?.value
                ?: return SettingsSetResult.InvalidValue(key)
            // The SecurityOwner is the biometric authority. When no platform authenticator is
            // registered it reports the honest platform-unavailable classification (never a faked
            // on); otherwise the owner applies the canonical enable/disable policy and a refusal
            // reaches the shared screen as a typed Refused.
            val capability = ports.biometricCapability()
            if (capability == AndroidCapability.UNAVAILABLE_PER_PLATFORM) {
                return SettingsSetResult.CapabilityUnavailable(capability)
            }
            return when (ports.applyBiometrics(enabled)) {
                PreferenceWrite.APPLIED -> SettingsSetResult.Applied(capability)
                PreferenceWrite.INVALID -> SettingsSetResult.InvalidValue(key)
                PreferenceWrite.FAILED, PreferenceWrite.CLOSED -> SettingsSetResult.Refused
            }
        }
        PROTECTED_ACTIONS[key]?.let { surface ->
            if (value.type != PreferenceValueType.BOOLEAN) return SettingsSetResult.InvalidValue(key)
            val enabled = (value as? PreferenceValue.Bool)?.value
                ?: return SettingsSetResult.InvalidValue(key)
            return when (ports.applyProtectedSurface(surface, enabled)) {
                PreferenceWrite.APPLIED -> SettingsSetResult.Applied(AndroidCapability.AVAILABLE)
                PreferenceWrite.INVALID -> SettingsSetResult.InvalidValue(key)
                PreferenceWrite.FAILED, PreferenceWrite.CLOSED -> SettingsSetResult.Refused
            }
        }
        CANONICAL_UNOWNED_TYPES[key]?.let { expected ->
            if (value.type != expected) return SettingsSetResult.InvalidValue(key)
            return SettingsSetResult.Unsupported
        }
        return SettingsSetResult.UnknownKey
    }

    private suspend fun applyRetained(
        key: ProductPreferenceKey,
        value: PreferenceValue,
    ): SettingsSetResult = when (key) {
        ProductPreferenceKey.HAPTICS -> {
            val enabled = (value as? PreferenceValue.Bool)?.value
                ?: return SettingsSetResult.InvalidValue(key.wire)
            if (ports.applyHaptics(enabled)) {
                SettingsSetResult.Applied(key.capability)
            } else {
                SettingsSetResult.Refused
            }
        }
        ProductPreferenceKey.LOGS_FILTERING_COUNTS,
        ProductPreferenceKey.LOGS_DOMAINS,
        ProductPreferenceKey.LOGS_NETWORK_ACTIVITY,
        ProductPreferenceKey.LOGS_LAVA_GUARD_PROGRESS,
        -> applyRetention(key, value)

        ProductPreferenceKey.NOTIFICATIONS_FILTER_CHANGES,
        ProductPreferenceKey.NOTIFICATIONS_FILTER_COULD_NOT_SWITCH,
        ProductPreferenceKey.NOTIFICATIONS_PROTECTION_RESUMED,
        ProductPreferenceKey.NOTIFICATIONS_CONNECTION_UPDATES,
        -> applyNotification(key, value)

        // Automatic backup is a preference, not a retained customization value: the backup owner
        // (B15/B16) is its durable authority and also projects it back in the `backup` snapshot.
        ProductPreferenceKey.BACKUP_AUTOMATIC -> {
            val enabled = (value as? PreferenceValue.Bool)?.value
                ?: return SettingsSetResult.InvalidValue(key.wire)
            when (ports.applyBackupAutomatic(enabled)) {
                PreferenceWrite.APPLIED -> SettingsSetResult.Applied(key.capability)
                PreferenceWrite.INVALID -> SettingsSetResult.InvalidValue(key.wire)
                PreferenceWrite.FAILED, PreferenceWrite.CLOSED -> SettingsSetResult.Refused
            }
        }

        else -> when (ports.applyPreference(key, value)) {
            PreferenceWrite.APPLIED -> SettingsSetResult.Applied(key.capability)
            PreferenceWrite.INVALID -> SettingsSetResult.InvalidValue(key.wire)
            PreferenceWrite.FAILED, PreferenceWrite.CLOSED -> SettingsSetResult.Refused
        }
    }

    private suspend fun applyRetention(
        key: ProductPreferenceKey,
        value: PreferenceValue,
    ): SettingsSetResult {
        val category = PrivacyRetentionCategory.entries.firstOrNull { it.preferenceKey == key }
            ?: return SettingsSetResult.Unsupported
        val enabled = (value as? PreferenceValue.Bool)?.value
            ?: return SettingsSetResult.InvalidValue(key.wire)
        val current = ports.currentLoggingSettings()
        val model = if (current == null) {
            PrivacyRetention.canonicalDefaults()
        } else {
            PrivacyRetention.fromPersisted(current.toRetentionPersisted())
        }
        // A toggle sets only this category's choice; clearing retained data is a separate
        // privacy mutation, so disabling must not destroy a sibling or this category's rows.
        val updated = model.withChoice(category, RetentionChoice.fromPersisted(enabled))
        return if (ports.applyLoggingSettings(updated.toLoggingSettings())) {
            SettingsSetResult.Applied(key.capability)
        } else {
            SettingsSetResult.Refused
        }
    }

    private suspend fun applyNotification(
        key: ProductPreferenceKey,
        value: PreferenceValue,
    ): SettingsSetResult {
        val category = NotificationCategory.fromPreferenceKey(key.wire)
            ?: return SettingsSetResult.Unsupported
        val enabled = (value as? PreferenceValue.Bool)?.value
            ?: return SettingsSetResult.InvalidValue(key.wire)
        return when (ports.applyNotification(category, enabled)) {
            NotificationWrite.APPLIED -> SettingsSetResult.Applied(key.capability)
            NotificationWrite.FAILED, NotificationWrite.CLOSED -> SettingsSetResult.Refused
        }
    }

    private companion object {
        /** The canonical biometrics preference key, delegated to the SecurityOwner. */
        const val BIOMETRICS_KEY = "biometrics"

        /**
         * The six exact canonical `protectedActions.*` suffixes mapped to the security owner's
         * surface. A different suffix is an unknown key, never a synonym for a canonical surface.
         */
        val PROTECTED_ACTIONS: Map<String, ProtectedSurface> = mapOf(
            "protectedActions.App Unlock" to ProtectedSurface.APP_UNLOCK,
            "protectedActions.Turn on/off Lava" to ProtectedSurface.PROTECTION_CONTROL,
            "protectedActions.Pause Lava" to ProtectedSurface.PROTECTION_PAUSE,
            "protectedActions.Update domains and lists" to ProtectedSurface.FILTERS,
            "protectedActions.View Activities" to ProtectedSurface.ACTIVITY,
            "protectedActions.Update App Settings" to ProtectedSurface.APP_SETTINGS,
        )

        /** iOS-only managed DNS patch keys: recognized canonical keys, no Android platform API. */
        val MANAGED_DNS_PATCH_TYPES: Map<String, PreferenceValueType> = mapOf(
            "dnsPatchProvider" to PreferenceValueType.TEXT,
            "dnsPatchSetup" to PreferenceValueType.BOOLEAN,
            "dnsPatchCheck" to PreferenceValueType.BOOLEAN,
            "dnsPatchRemove" to PreferenceValueType.BOOLEAN,
        )

        /** Canonical keys whose owner is another slice; refused rather than mis-applied here. */
        val CANONICAL_UNOWNED_TYPES: Map<String, PreferenceValueType> = mapOf(
            "deviceDNS" to PreferenceValueType.BOOLEAN,
            "fallback" to PreferenceValueType.BOOLEAN,
            "provider" to PreferenceValueType.TEXT,
            "providerID" to PreferenceValueType.TEXT,
            "transport" to PreferenceValueType.TEXT,
        )
    }
}

/** Maps the retained local-log policy onto the pure retention model's persisted shape. */
internal fun LoggingSettings.toRetentionPersisted(): Map<PrivacyRetentionCategory, Boolean?> = mapOf(
    PrivacyRetentionCategory.FILTERING_COUNTS to keepCounts,
    PrivacyRetentionCategory.DOMAIN_HISTORY to keepHistory,
    PrivacyRetentionCategory.NETWORK_ACTIVITY to keepNetworkActivity,
    PrivacyRetentionCategory.GUARD_PROGRESS to keepLavaGuardProgress,
)

/** Derives the durable local-log policy from the retention model's per-category choices. */
internal fun PrivacyRetention.toLoggingSettings(): LoggingSettings = LoggingSettings(
    keepCounts = isCollecting(PrivacyRetentionCategory.FILTERING_COUNTS),
    keepHistory = isCollecting(PrivacyRetentionCategory.DOMAIN_HISTORY),
    keepNetworkActivity = isCollecting(PrivacyRetentionCategory.NETWORK_ACTIVITY),
    keepLavaGuardProgress = isCollecting(PrivacyRetentionCategory.GUARD_PROGRESS),
)
