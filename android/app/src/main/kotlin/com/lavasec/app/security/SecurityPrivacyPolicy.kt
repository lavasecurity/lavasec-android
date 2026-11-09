package com.lavasec.app.security

/** Only Open Lava selects the global cover. Read failures and scoped gates cannot opt in.
 * An unclassified cold launch remains conservative; confirmed credential absence selects OFF.
 * The native owner persists this display choice independently of fail-closed access gates.
 */
object SecurityPrivacyPolicy {
    fun requiresBackgroundCover(state: SecurityOwnerState?): Boolean = state?.appUnlockCoverChoice != false
}
