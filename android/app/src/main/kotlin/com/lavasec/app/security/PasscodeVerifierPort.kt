package com.lavasec.app.security

/** Hardware capability is distinct from the result of reading an existing credential. */
enum class PasscodeVerifierAvailability { Available, Unavailable }

/** A partial, malformed or undecryptable record is never treated as an absent credential. */
enum class PasscodeCredentialState { Available, Absent, Unavailable }

enum class PasscodeVerification { Verified, Wrong, Unavailable }

/** Android transport for the pinned iOS salt + SHA-256 verifier. No raw errors escape. */
interface PasscodeVerifierPort {
    fun availability(): PasscodeVerifierAvailability
    fun credentialState(): PasscodeCredentialState
    fun configure(code: String): Boolean
    fun verify(code: String): PasscodeVerification

    /** True only after both the encrypted record and key have been removed. */
    fun remove(): Boolean
}
