package com.lavasec.app.account

/** Stable provider identifiers Supabase reports in the session's `app_metadata`. */
internal object AccountProviders {
    const val GOOGLE = "google"
    const val APPLE = "apple"
}

/**
 * Stable account command reason codes crossing the RN bridge; JS maps each to localized copy.
 * The codes never carry credentials, a user id or an email — only the outcome class.
 */
internal object AccountCommandReasons {
    /** No Android Apple credential adapter exists; an Apple sign-in can never be performed. */
    const val APPLE_UNAVAILABLE = "account-apple-unavailable"

    /** Google sign-in is not configured in this build (no server client id). */
    const val GOOGLE_UNAVAILABLE = "account-google-unavailable"

    /** The operation needs a signed-in session and none is installed. */
    const val NOT_SIGNED_IN = "account-not-signed-in"

    /** The account service/backend is absent (no configuration or no unlocked graph). */
    const val SERVICE_UNAVAILABLE = "account-service-unavailable"

    /** A provider admission is already running. */
    const val BUSY = "account-busy"

    /** The account generation changed under the operation. */
    const val SUPERSEDED = "account-superseded"

    /** A durable or remote account operation did not complete. */
    const val FAILED = "account-operation-failed"
}

/**
 * The user-facing account projection derived ONLY from confirmed owner facts plus the transient
 * command state. It deliberately excludes the access token, refresh token, user id and email:
 * the bridge serializes status text, the linked provider identifiers Supabase reported, provider
 * capability and the last message, so no credential can cross to JS.
 */
internal data class AccountPresentation(
    val signedIn: Boolean,
    val status: String,
    val detail: String,
    val appleTitle: String,
    val googleTitle: String,
    val appleConnected: Boolean,
    val googleConnected: Boolean,
    val appleBusy: Boolean,
    val googleBusy: Boolean,
    val busy: Boolean,
    val message: String,
    val googleAvailable: Boolean,
    val appleAvailable: Boolean,
)

/**
 * Projects the owner snapshot plus the transient command state.
 *
 * Provider link state comes ONLY from the provider identifiers Supabase recorded on the session;
 * a linked Apple/Google account is never inferred from availability. `appleAvailable` is the Apple
 * adapter's configuration (false until an Apple/OAuth client is bound to the release certificate),
 * and Google availability is the native sign-in client's configuration, so the UI can tell an
 * unconfigured provider from a linked one.
 */
internal fun projectAccount(
    snapshot: AccountSnapshot?,
    googleAvailable: Boolean,
    signInProvider: String?,
    deleting: Boolean,
    message: String,
    appleAvailable: Boolean = false,
): AccountPresentation {
    val signedIn = snapshot?.availability == AccountAvailability.SIGNED_IN
    val providers = snapshot?.providers.orEmpty()
    val googleConnected = signedIn && AccountProviders.GOOGLE in providers
    val appleConnected = signedIn && AccountProviders.APPLE in providers
    val googleBusy = signInProvider == AccountProviders.GOOGLE
    val appleBusy = signInProvider == AccountProviders.APPLE
    val signingIn = signInProvider != null && !signedIn
    return AccountPresentation(
        signedIn = signedIn,
        status = when {
            signingIn -> "Signing in"
            !signedIn -> "Not signed in"
            googleConnected && appleConnected -> "Signed in with Apple and Google"
            appleConnected -> "Signed in with Apple"
            googleConnected -> "Signed in with Google"
            else -> "Signed in"
        },
        detail = when {
            signingIn -> "Opening sign-in."
            snapshot == null -> "Account is not connected to this interface yet."
            snapshot.availability == AccountAvailability.STORAGE_UNAVAILABLE ->
                "Lava could not read your account on this device."
            else -> ""
        },
        appleTitle = when {
            appleConnected -> "Signed in with Apple"
            appleBusy -> "Opening Apple sign-in"
            else -> "Sign in with Apple"
        },
        googleTitle = when {
            googleConnected -> "Signed in with Google"
            googleBusy -> "Opening Google sign-in"
            else -> "Sign in with Google"
        },
        appleConnected = appleConnected,
        googleConnected = googleConnected,
        appleBusy = appleBusy,
        googleBusy = googleBusy,
        busy = signInProvider != null || deleting,
        message = message,
        googleAvailable = googleAvailable,
        appleAvailable = appleAvailable,
    )
}
