package com.lavasec.app.platform

/**
 * The native device's verified and durably persisted Plus verdict, independent of optional account
 * sign-in. Unknown storage must refuse activation without clearing a saved preference or key.
 * A confirmed lapse clears the preference atomically in the plan owner, without a tunnel reload.
 */
fun interface ChainedEntitlementPort {
    /** True only while the device plan is available and entitled. Unavailable sources may throw. */
    fun hasEntitlement(): Boolean
}

/** Safe default for compositions without a native device-plan owner. */
object UnavailableChainedEntitlementPort : ChainedEntitlementPort {
    override fun hasEntitlement(): Boolean = false
}
