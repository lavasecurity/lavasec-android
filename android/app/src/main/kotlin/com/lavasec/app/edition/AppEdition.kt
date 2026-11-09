package com.lavasec.app.edition

import com.lavasec.app.BuildConfig

/** Build-time service capabilities. A missing service is never inferred from blank URLs. */
internal enum class AppEdition(
    val id: String,
    val localFeaturesUnlocked: Boolean,
    val accounts: Boolean,
    val cloudBackup: Boolean,
    val purchases: Boolean,
    val passkeyRecovery: Boolean,
    val dynamicCatalog: Boolean,
    val nativeSupport: Boolean,
) {
    PLAY("play", false, true, true, true, true, true, true),
    // Native FOSS support stays closed until a server verification contract is qualified.
    FOSS("foss", true, false, false, false, false, false, false);

    val isFoss: Boolean get() = this == FOSS
    val deepLinkScheme: String get() = if (isFoss) "lavasecurity-foss" else "lavasecurity"
    companion object {
        val current: AppEdition = if (BuildConfig.IS_FOSS) FOSS else PLAY
    }
}
