package com.lavasec.app.reactnative.components

/**
 * Pure state machine behind the `LavaNativePage` containment contract.
 *
 * Android port of the pinned iOS `LavaNativePageContent` (`c4658d9`) admission model. The native
 * controls, private drafts and system sheets belong to the native owner; the enclosing React
 * navigation stack owns the one header, the page transition and destination admission. This file
 * owns only the decisions that must be identical to iOS and therefore are the JVM-testable part of
 * the F07 contract:
 *
 *  - every page visit gets an identity and an activation generation;
 *  - blur and an invalidated authentication revision revoke interaction synchronously;
 *  - an admitted page may only navigate to its allowlisted destinations and only for its own visit;
 *  - a stale callback (recycled view, previous visit, revoked activation) is dropped, never applied.
 */

/**
 * An accepted admission token. [activation] is the page's activation generation at the moment the
 * grant was accepted and [securityRevision] is the credential-graph revision it was bound to. A
 * later blur, recycle or credential change advances one of them and the token stops authenticating.
 */
data class LavaNativePageAdmission(
    val activation: Long,
    val securityRevision: Long,
)

/**
 * The complete native-page containment state. [page] is the canonical page name, [visit] is the
 * per-visit identity (recycled views must not inherit the previous visit), [activation] advances on
 * every focus/blur/revoke boundary, [focused] mirrors the RN `focused` prop and [active] mirrors the
 * foreground scene phase. [admission] is non-null only while interaction is authorized.
 */
data class LavaNativePageState(
    val page: String = "",
    val visit: Long = 0L,
    val activation: Long = 0L,
    val focused: Boolean = false,
    val active: Boolean = false,
    val admission: LavaNativePageAdmission? = null,
) {
    /** Interaction is open iff a current admission token exists. */
    val interactionAllowed: Boolean get() = admission != null

    /**
     * Whether the current visit has already been superseded. A callback that captured an older
     * visit (before a recycle or a page change) must be refused even if a new visit looks admitted.
     */
    fun isCurrentVisit(visit: Long): Boolean = this.visit == visit
}

object LavaNativePagePolicy {
    /**
     * The destinations every native page may return to through React navigation, mirroring the
     * canonical `NativeSettingsPage.open` guard: only `DNS` and `Upgrade` are reachable, and the
     * DNS-patch explainer may additionally open VPN chaining.
     */
    val DEFAULT_DESTINATIONS: Set<String> = setOf("DNS", "Upgrade")

    fun allowedDestinations(page: String): Set<String> =
        if (page == "dnsPatch" || page == "phoneQA") DEFAULT_DESTINATIONS + "VPNChaining" else DEFAULT_DESTINATIONS

    /**
     * Opens [page]. A page change starts a fresh visit and activation and clears any admission; the
     * same page is a no-op so a snapshot push cannot churn the identity (matching the iOS
     * `guard self.page != page`).
     */
    fun open(state: LavaNativePageState, page: String): LavaNativePageState =
        if (state.page == page) {
            state
        } else {
            state.copy(
                page = page,
                visit = state.visit + 1L,
                activation = state.activation + 1L,
                focused = false,
                admission = null,
            )
        }

    /**
     * Applies the `focused` prop. The transition always advances the activation generation; a blur
     * clears the admission synchronously so a queued native event cannot be accepted afterwards.
     */
    fun setFocused(state: LavaNativePageState, focused: Boolean): LavaNativePageState =
        if (state.focused == focused) {
            state
        } else {
            state.copy(
                focused = focused,
                activation = state.activation + 1L,
                admission = if (focused) state.admission else null,
            )
        }

    /**
     * Applies the foreground scene phase. A background transition revokes synchronously; returning
     * to the foreground does not restore the grant — the page must be re-admitted.
     */
    fun setActive(state: LavaNativePageState, active: Boolean): LavaNativePageState = when {
        state.active == active -> state
        !active -> state.copy(active = false, admission = null, activation = state.activation + 1L)
        else -> state.copy(active = true)
    }

    /**
     * Grants interaction when the page is focused and foreground and the credential revision matches
     * the revision the caller observed. The grant binds to the current activation generation.
     */
    fun grant(
        state: LavaNativePageState,
        securityRevision: Long,
        expectedRevision: Long,
    ): LavaNativePageState =
        if (!state.focused || !state.active || securityRevision != expectedRevision) {
            state
        } else {
            state.copy(admission = LavaNativePageAdmission(state.activation, securityRevision))
        }

    /**
     * Revokes interaction synchronously (blur invalidation, credential change, dirty dismissal).
     * Advancing the activation makes the prior token stale even if a later grant reuses the visit.
     */
    fun revoke(state: LavaNativePageState): LavaNativePageState =
        if (state.admission == null) {
            state
        } else {
            state.copy(admission = null, activation = state.activation + 1L)
        }

    /**
     * Whether the admission is still valid for the given token. A callback that captured an
     * activation/security pair must pass both; anything else is stale.
     */
    fun admits(state: LavaNativePageState, activation: Long, securityRevision: Long): Boolean =
        state.admission?.activation == activation &&
            state.admission.securityRevision == securityRevision

    /**
     * Whether a `onBack`/`onNavigate` request from the given visit may be published. A stale visit,
     * a revoked admission or a destination outside [allowedDestinations] is refused.
     */
    fun canNavigate(
        state: LavaNativePageState,
        visit: Long,
        destination: String,
        allowed: Set<String>,
    ): Boolean = state.interactionAllowed && state.isCurrentVisit(visit) && destination in allowed

    /** Fabric may reuse the same view for another visit; every transient field must be reset. */
    fun resetForRecycle(state: LavaNativePageState): LavaNativePageState =
        state.copy(
            page = "",
            visit = state.visit + 1L,
            activation = state.activation + 1L,
            focused = false,
            admission = null,
        )
}
