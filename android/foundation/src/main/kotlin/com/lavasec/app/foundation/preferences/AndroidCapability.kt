package com.lavasec.app.foundation.preferences

/**
 * Honest Android mapping of each canonical retained product preference.
 *
 * Lava's product preferences are defined once in the iOS client
 * (`ReactNative/native-app/LavaAppSettings.swift`, `settings.set` dispatch at
 * c4658d973bae05dd3b7aa84c014cb2f76ff61a0b) and several of them are backed by
 * platform APIs Android does not expose. This mapping exists so no caller can
 * present an iOS-only capability as working: a surface must consult
 * [AndroidCapabilityMap.capabilityFor] before it claims a preference takes
 * effect.
 *
 * The iOS-only capabilities in this slice are:
 *  - **Live Activities** (Lock Screen / Dynamic Island) — no Android equivalent
 *    platform surface in this app, so [AndroidCapability.UNAVAILABLE_PER_PLATFORM].
 *  - **Alternate app icon** — the Guard "look" gallery drives iOS
 *    `setAlternateIconName`; Android has no alternate launcher icon.
 *  - **Managed DNS patch** (`NEDNSSettingsManager` via `dnsPatch*` keys) — not a
 *    member of the retained key set here; recorded so it is never inferred as
 *    available from an adjacent key.
 *
 * [AndroidCapability.EQUIVALENT_DEGRADED] means Android honours the intent with a
 * narrower mechanism (for example, a single app-wide notification channel instead
 * of iOS per-category toggles), and any UI must disclose the reduction rather than
 * render the iOS affordance verbatim.
 */
enum class AndroidCapability {
    /** Android implements the preference with equivalent semantics. */
    AVAILABLE,

    /** Android implements a reduced/different equivalent; disclose the difference. */
    EQUIVALENT_DEGRADED,

    /** iOS-only platform API; must never be presented as working on Android. */
    UNAVAILABLE_PER_PLATFORM,
    ;

    /** Whether a surface may present the capability as working at all. */
    val isPresentableAsWorking: Boolean
        get() = this != UNAVAILABLE_PER_PLATFORM
}

/**
 * Exhaustive capability classification over [ProductPreferenceKey]. The map
 * delegates to the key's own [ProductPreferenceKey.capability], which is set at
 * the single model definition site; adding a canonical key without choosing a
 * capability is impossible (it would not compile), and unknown wire keys are
 * rejected by [ProductPreferenceKey.fromWire].
 */
object AndroidCapabilityMap {
    fun capabilityFor(key: ProductPreferenceKey): AndroidCapability = key.capability

    /** Classifies a wire key, or `null` when the key is not a retained canonical key. */
    fun capabilityFor(wireKey: String): AndroidCapability? =
        ProductPreferenceKey.fromWire(wireKey)?.capability

    /** Every classified key, equal to the canonical retained key set. */
    val classifiedKeys: Set<ProductPreferenceKey>
        get() = ProductPreferenceKey.entries.toSet()

    /** The keys a caller must not present as working. */
    val unavailablePerPlatform: List<ProductPreferenceKey>
        get() = ProductPreferenceKey.entries.filter {
            it.capability == AndroidCapability.UNAVAILABLE_PER_PLATFORM
        }
}
