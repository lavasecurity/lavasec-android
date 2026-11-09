package com.lavasec.app.security

import com.lavasec.app.foundation.security.ProtectedSurface

/** Authorization captured after a real native admission, carried to the mutation's write edge. */
internal class MutationAuthorizationWitness private constructor(
    private val owner: SecurityOwner,
    private val requiredSurfaces: Set<ProtectedSurface>,
    private val protectionMask: Set<ProtectedSurface>,
    private val authenticationRevision: Long,
) {
    /** Pure reads only: checking a publication must never prompt or adopt a later authentication. */
    fun isCurrent(): Boolean {
        val current = owner.state.value
        return current.availability == SecurityAvailability.Available && !current.recoveryRequired &&
            effectiveProtection(current, requiredSurfaces) == protectionMask &&
            (protectionMask.isEmpty() || current.authenticationRevision == authenticationRevision) &&
            requiredSurfaces.all(current::isAuthorized)
    }

    companion object {
        fun capture(owner: SecurityOwner, surface: ProtectedSurface): MutationAuthorizationWitness? {
            val required = setOf(ProtectedSurface.APP_UNLOCK, surface)
            val current = owner.state.value
            if (current.availability != SecurityAvailability.Available || current.recoveryRequired ||
                !required.all(current::isAuthorized)) return null
            return MutationAuthorizationWitness(owner, required, effectiveProtection(current, required),
                current.authenticationRevision)
        }

        private fun effectiveProtection(state: SecurityOwnerState, required: Set<ProtectedSurface>): Set<ProtectedSurface> =
            if (state.enabled) state.protectedSurfaces.intersect(required) else emptySet()
    }
}
