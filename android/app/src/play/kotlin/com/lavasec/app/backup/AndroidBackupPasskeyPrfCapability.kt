package com.lavasec.app.backup

import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.android.gms.fido.Fido
import com.lavasec.app.BuildConfig
import com.lavasec.app.foundation.backup.BackupPasskeyPrfCapability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Pre-ceremony admission for the native passkey-PRF restore method.
 *
 * [BackupPasskeyPrfCapability] answers "may the passkey method be offered at all", which is a
 * device/platform fact, not proof that a particular credential supports PRF. That proof can only
 * come from a live ceremony, and the owner still refuses a ceremony that yields no authenticator
 * output. Two platform facts are therefore required before the method is offered:
 *  1. a framework credential-provider service is present, or the bundled Google provider reports
 *     a user-verifying platform authenticator, and
 *  2. a relying-party origin is configured for this build ([BuildConfig.BACKUP_PASSKEY_RP_ORIGIN]).
 *
 * A blank/invalid RP origin keeps [isAvailable] false. The host must separately serve the matching
 * `/.well-known/assetlinks.json`; configuration alone cannot prove that external association.
 * A library version or a synthetic value never establishes PRF availability by itself.
 */
internal class AndroidBackupPasskeyPrfCapability(
    private val platform: BackupPasskeyPlatformProbe,
) : BackupPasskeyPrfCapability {

    override fun isAvailable(): Boolean =
        platform.credentialProviderPresent() && platform.relyingPartyConfigured()

    internal companion object {
        /** The production instance, probed against the live platform and build configuration. */
        fun production(context: Context): AndroidBackupPasskeyPrfCapability =
            AndroidBackupPasskeyPrfCapability(AndroidBackupPasskeyPlatformProbe(context.applicationContext))
    }
}

/** The pre-ceremony platform facts a capability probe can establish; injectable for tests. */
internal interface BackupPasskeyPlatformProbe {
    /** True when the device exposes a credential provider the adapter can drive. */
    fun credentialProviderPresent(): Boolean

    /** True when a relying-party origin (asset-link domain) is configured for this build. */
    fun relyingPartyConfigured(): Boolean
}

/** Cached admission over public provider probes and the build's configured RP origin. */
internal class AndroidBackupPasskeyPlatformProbe(
    private val facts: BackupPasskeyProviderFacts,
    private val origin: String = BuildConfig.BACKUP_PASSKEY_RP_ORIGIN,
) : BackupPasskeyPlatformProbe {
    constructor(context: Context) : this(AndroidBackupPasskeyProviderFacts(context.applicationContext))

    // Provider evidence is a device fact, independent of account/owner generations. The callback
    // captures no Activity, credentials or product owner, and grants no authorization.
    @Volatile private var frameworkProvider: Boolean? = null
    @Volatile private var googlePlatformAuthenticator: Boolean? = null

    init {
        if (relyingPartyConfigured()) {
            facts.probeFrameworkProviderService { frameworkProvider = it }
            facts.probeGooglePlatformAuthenticator { googlePlatformAuthenticator = it }
        }
    }

    override fun credentialProviderPresent(): Boolean =
        frameworkProvider == true || googlePlatformAuthenticator == true

    override fun relyingPartyConfigured(): Boolean =
        BackupPasskeyRelyingParty.fromOrigin(origin, "user", "Lava backup") != null
}

/** Public local provider probes. Presence permits an attempt; a credential's PRF remains unknown. */
internal interface BackupPasskeyProviderFacts {
    fun probeFrameworkProviderService(onResult: (Boolean) -> Unit)
    fun probeGooglePlatformAuthenticator(onResult: (Boolean) -> Unit)
}

internal class AndroidBackupPasskeyProviderFacts(private val context: Context) : BackupPasskeyProviderFacts {
    override fun probeFrameworkProviderService(onResult: (Boolean) -> Unit) {
        // Package-manager service resolution may cross a process boundary. Resolve once off the
        // snapshot/main path; this short-lived job captures only the application context.
        CoroutineScope(Dispatchers.IO).launch {
            onResult(runCatching { frameworkProviderServicePresent() }.getOrDefault(false))
        }
    }

    internal fun frameworkProviderServicePresent(): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        return context.packageManager.queryIntentServices(Intent(PROVIDER_ACTION), 0).any { resolved ->
            val service = resolved.serviceInfo
            service != null && service.enabled && service.exported && service.applicationInfo?.enabled == true &&
                service.permission == PROVIDER_PERMISSION
        }
    }

    override fun probeGooglePlatformAuthenticator(onResult: (Boolean) -> Unit) {
        try {
            Fido.getFido2ApiClient(context).isUserVerifyingPlatformAuthenticatorAvailable()
                .addOnSuccessListener { onResult(it == true) }
                .addOnFailureListener { onResult(false) }
                .addOnCanceledListener { onResult(false) }
        } catch (_: Exception) {
            onResult(false)
        }
    }

    companion object {
        const val PROVIDER_ACTION = "android.service.credentials.CredentialProviderService"
        const val PROVIDER_PERMISSION = "android.permission.BIND_CREDENTIAL_PROVIDER_SERVICE"
    }
}
