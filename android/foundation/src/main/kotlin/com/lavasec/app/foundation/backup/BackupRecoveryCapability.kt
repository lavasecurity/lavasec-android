package com.lavasec.app.foundation.backup

/**
 * The user-selectable unlock method for a backup restore. Mirrors the canonical iOS
 * `BackupRestoreMode` raw values (pinned at `c4658d9`).
 */
enum class BackupRecoveryMethod(val wireValue: String) {
    DEVICE_KEY("deviceKey"),
    PASSKEY("passkey"),
    RECOVERY_CODE("recoveryCode"),
    ;

    /** Recovery is the only mode that needs a typed secret; passkey and device key are asserted. */
    val requiresTypedSecret: Boolean get() = this == RECOVERY_CODE

    companion object {
        fun fromWireValue(value: String): BackupRecoveryMethod? = entries.firstOrNull { it.wireValue == value }
    }
}

/** Whether one unlock method is actually usable on this build/device. */
enum class BackupRecoveryAvailability {
    AVAILABLE,
    UNAVAILABLE,
}

/** Availability fact for one [BackupRecoveryMethod]. Never inferred from a stored preference. */
data class BackupRecoveryCapability(
    val method: BackupRecoveryMethod,
    val availability: BackupRecoveryAvailability,
) {
    val isAvailable: Boolean get() = availability == BackupRecoveryAvailability.AVAILABLE
}

/**
 * The outcome of selecting a restore method. Deliberately a sealed result rather than a Boolean: an
 * unsupported capability (a build without passkey PRF support, a method with no recorded
 * capability) must map to [Unavailable] and can never be mistaken for a usable selection or a
 * successful restore.
 */
sealed interface BackupRecoverySelection {
    data class Usable(val method: BackupRecoveryMethod) : BackupRecoverySelection

    data class Unavailable(val method: BackupRecoveryMethod) : BackupRecoverySelection

    val isUsable: Boolean get() = this is Usable
}

/**
 * Pure recovery-capability selection. Mirrors the canonical capability branch that gates passkey
 * setup/restore on the platform: the passkey option is absent, not merely failing, when the
 * authenticator PRF extension is unavailable. There is no optimistic default — a method with no
 * capability fact is [BackupRecoveryAvailability.UNAVAILABLE].
 */
object BackupRecoveryCapabilityPolicy {

    fun availabilityOf(
        method: BackupRecoveryMethod,
        capabilities: Collection<BackupRecoveryCapability>,
    ): BackupRecoveryAvailability = capabilities.firstOrNull { it.method == method }
        ?.availability
        ?: BackupRecoveryAvailability.UNAVAILABLE

    fun select(
        requested: BackupRecoveryMethod,
        capabilities: Collection<BackupRecoveryCapability>,
    ): BackupRecoverySelection = when (availabilityOf(requested, capabilities)) {
        BackupRecoveryAvailability.AVAILABLE -> BackupRecoverySelection.Usable(requested)
        BackupRecoveryAvailability.UNAVAILABLE -> BackupRecoverySelection.Unavailable(requested)
    }
}
