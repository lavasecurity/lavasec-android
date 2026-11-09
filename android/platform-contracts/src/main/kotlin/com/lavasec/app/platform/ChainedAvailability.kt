package com.lavasec.app.platform

import java.util.concurrent.atomic.AtomicBoolean

/**
 * The device's memory facts, read from the platform.
 *
 * [totalMemoryBytes] is the authoritative floor term (`ActivityManager.MemoryInfo.totalMem`):
 * total physical memory, forward-compatible by construction, exactly as iOS uses
 * `ProcessInfo.processInfo.physicalMemory`. [memoryClassMegabytes] (`ActivityManager.memoryClass`)
 * is carried for diagnostics only — it is an OEM-derived per-app heap cap, not a stable hardware
 * property, so making the gate depend on it would reintroduce the model/SoC-style rot the iOS
 * policy explicitly rejects. [isLowRamDevice] is the platform's own low-memory classification,
 * which the policy uses as the conservative pre-API-30 fallback.
 */
data class ChainedDeviceMemoryReading(
    val totalMemoryBytes: Long,
    val memoryClassMegabytes: Int,
    val isLowRamDevice: Boolean,
)

/**
 * Why chained mode is unavailable on this device/account right now.
 *
 * Log/health identifiers, never user copy — the UI maps these to localized strings. Mirrors the
 * shared readiness vocabulary ([com.lavasec.app.foundation.chained.ChainedReadinessRefusal])
 * so both gates name the same condition, and the iOS `ChainedAvailability.Ineligibility` terms
 * so both clients name it in a device log too.
 */
enum class ChainedAvailabilityRefusal {
    /** Chaining is a Lava Security Plus feature and the account is not entitled. */
    NOT_ENTITLED,

    /** The device is below the chaining memory floor. */
    INSUFFICIENT_MEMORY,

    /**
     * Repeated memory-related kills excluded the engine, or a pre-API-30 device is
     * conservatively excluded by the low-RAM fallback because the OS exposes no exit reason.
     */
    MEMORY_KILL_EXCLUDED,

    /**
     * The device state could not be read (memory unavailable, or the durable exclusion record
     * unreadable). Fail closed rather than guess a verdict.
     */
    DEVICE_STATE_UNREADABLE,
    ;

    /** Stable identifier for device logs. Never user copy, never any part of a secret. */
    val logValue: String
        get() = "chained-ineligible-" + name.lowercase().replace('_', '-')
}

/**
 * The authoritative answer to "may chained mode run on this device with this account?".
 *
 * This is the single value the readiness gate's `deviceEligible` term is derived from, so the
 * entitlement + memory + exclusion composition lives in ONE place rather than being re-derived
 * at each call site (where a caller could feed a fabricated `true`). The ~50 MB engine sits
 * behind it: see [ChainedEnginePermit] for the no-instantiation guarantee.
 *
 * [Eligible] can only be constructed inside this module (`internal constructor`), and the raw
 * caller-supplied-facts policy is `internal` too, so outside this module the ONLY way to an
 * `Eligible` is [ChainedAvailabilityAuthority.current] — which reads the injected entitlement
 * port and device store. A consumer module cannot forge eligibility from synthetic facts. The
 * `Ineligible` value carries only a log identifier.
 */
sealed interface ChainedAvailability {

    /** Whether the engine may run. Never conflate with the user's preference or the latch. */
    val isEligible: Boolean

    /** Every gate cleared; the engine may be constructed. */
    class Eligible internal constructor() : ChainedAvailability {
        override val isEligible: Boolean get() = true
        override fun equals(other: Any?): Boolean = other is Eligible
        override fun hashCode(): Int = ELIGIBLE_HASH
        override fun toString(): String = "ChainedAvailability.Eligible"

        private companion object {
            const val ELIGIBLE_HASH = 0x0E11
        }
    }

    /** The engine must not be constructed; [reason] names the first gate that refused. */
    data class Ineligible(
        val reason: ChainedAvailabilityRefusal,
    ) : ChainedAvailability {
        override val isEligible: Boolean get() = false
    }
}

/**
 * The pure device/account eligibility policy, in one place, with real JVM behavioural tests.
 *
 * `internal` on purpose: [evaluate] accepts CALLER-SUPPLIED facts, so exposing it would let any
 * consumer module mint an `Eligible` verdict — and therefore a [ChainedEnginePermit] — without
 * consulting the entitlement port or the device store. Outside this module the only way to an
 * `Eligible` is [ChainedAvailabilityAuthority.current], which reads those authoritative sources.
 *
 * Mirrors iOS `ChainedAvailabilityPolicy.isEligible` (Plus AND memory AND not memory-killed) and
 * folds in the Android-specific reading outcome. It touches no Android API: the API level is a
 * plain Int supplied by the caller, so the whole policy runs on the JVM harness.
 *
 * ## Gate order
 *
 * 1. [ChainedAvailabilityRefusal.NOT_ENTITLED] — a durable account property; a non-Plus account
 *    cannot chain whatever its hardware says, mirroring iOS's "guard hasLavaSecurityPlus" first.
 * 2. [ChainedAvailabilityRefusal.DEVICE_STATE_UNREADABLE] — fail closed when a term could not be
 *    read, rather than infer one.
 * 3. [ChainedAvailabilityRefusal.INSUFFICIENT_MEMORY] — the measured hardware floor.
 * 4. [ChainedAvailabilityRefusal.MEMORY_KILL_EXCLUDED] — the durable safety net (and the
 *    pre-API-30 low-RAM fallback), after the floor so a sub-floor device reports the more
 *    fundamental cause, as iOS reports memory before its startup-loop term.
 *
 * ## The memory floor
 *
 * [MINIMUM_DEVICE_MEMORY_BYTES] sits in the empty band between the two nearest Android hardware
 * clusters rather than on a marketing number, the same method iOS uses for its 3.4 GB floor:
 * Android's own low-RAM classification boundary is 2 GB (`ActivityManager.isLowRamDevice` /
 * `ro.config.low_ram`) and such devices report roughly 1.8–1.95 GB of `totalMem`, while the next
 * common cluster, 3 GB, reports roughly 2.7–2.9 GB. Any value in that gap classifies
 * identically; 2.5 GB is stated in decimal GB to stay readable next to the reported values. A
 * 3 GB-class device carries a 192–256 MB per-app heap, so the ~50 MB engine (plan `2026-09-12`
 * Slice 6) is roughly a fifth to a quarter of the heap, with the memory-kill backoff as the
 * field-evidence safety net for what the coarse RAM cut cannot see.
 *
 * ## The pre-API-30 fallback
 *
 * `ApplicationExitInfo` (and therefore `REASON_LOW_MEMORY`) is API 30+. Below that the store
 * cannot observe a memory kill, and a killed process cannot persist a marker after the fact, so
 * the policy refuses a device the platform itself flags low-RAM ([isLowRamDevice]) rather than
 * trusting the absence of evidence. This errs toward refusal; a non-low-RAM device at or above
 * the floor still qualifies, and the post-30 path uses the real exit evidence instead.
 */
internal object ChainedAvailabilityPolicy {

    /**
     * The chaining memory floor in bytes. See the class doc for the band derivation.
     *
     * Re-derive from field evidence rather than treating this as timeless: the value is a coarse
     * hardware proxy, and Android — unlike the iOS packet tunnel — has no per-model jetsam
     * ceiling to pin it to.
     */
    const val MINIMUM_DEVICE_MEMORY_BYTES: Long = 2_500_000_000L

    /** `ApplicationExitInfo` / `Build.VERSION_CODES.R`; below this the low-RAM fallback applies. */
    const val MINIMUM_EXIT_INFO_API_LEVEL: Int = 30

    /**
     * The first reason chained mode is unavailable, or [Eligible] when every gate clears.
     *
     * @param hasEntitlement the authoritative Plus verdict from [ChainedEntitlementPort]. Never
     *   inferred here.
     * @param read the store's coherent device reading; [ChainedDeviceEligibilityRead.Unavailable]
     *   fails closed.
     */
    fun evaluate(
        hasEntitlement: Boolean,
        read: ChainedDeviceEligibilityRead,
    ): ChainedAvailability {
        if (!hasEntitlement) return ChainedAvailability.Ineligible(ChainedAvailabilityRefusal.NOT_ENTITLED)
        val available = read as? ChainedDeviceEligibilityRead.Available
            ?: return ChainedAvailability.Ineligible(ChainedAvailabilityRefusal.DEVICE_STATE_UNREADABLE)
        if (available.memory.totalMemoryBytes < MINIMUM_DEVICE_MEMORY_BYTES) {
            return ChainedAvailability.Ineligible(ChainedAvailabilityRefusal.INSUFFICIENT_MEMORY)
        }
        val conservativePreApi30Exclusion =
            available.apiLevel < MINIMUM_EXIT_INFO_API_LEVEL && available.memory.isLowRamDevice
        if (available.backoff.excluded || conservativePreApi30Exclusion) {
            return ChainedAvailability.Ineligible(ChainedAvailabilityRefusal.MEMORY_KILL_EXCLUDED)
        }
        return ChainedAvailability.Eligible()
    }
}

/**
 * The right to construct the chained engine, revalidated at the construction boundary.
 *
 * The engine is a roughly 50 MB resident (plan `2026-09-12` Slice 6) whose only legitimate reason
 * to exist is an eligible availability verdict. [issue] is the sole minter, it reads a LIVE
 * [ChainedAvailabilityProvider], and it returns `null` whenever that provider does not report
 * eligible — and [ChainedAvailability.Eligible] itself cannot be constructed outside this module —
 * so an ineligible verdict yields no usable permit and the no-instantiation guarantee is
 * structural rather than a "bounded-residency" note.
 *
 * A permit is ALSO a one-shot capability bound to the CONCRETE [ChainedAvailabilityAuthority]
 * that minted it: the engine's factory must call [consumeForConstruction], which returns `true` at
 * most once and only while the bound authority STILL reports eligible. A permit retained across an
 * entitlement lapse or a device exclusion therefore cannot construct, and one permit cannot
 * construct two sessions. That closes the gap a caller could otherwise exploit by bypassing the
 * revalidating factory and reusing a cached permit directly at `LavaWgSession.create`.
 *
 * [issue] takes the concrete store-backed authority, NOT the [ChainedAvailabilityProvider]
 * interface: a consumer could otherwise pass a synthetic provider that caches an `Eligible` value
 * and defeat the one-shot revalidation. Binding to the authority makes permit minting reachable
 * only through the composition that owns the real entitlement port and device store. Pinned by
 * `ChainedAvailabilityPolicyTest` and the foundation verifier.
 */
class ChainedEnginePermit private constructor(
    private val authority: ChainedAvailabilityAuthority,
) {
    private val consumed = AtomicBoolean(false)

    /**
     * Consumes the permit. Returns `true` only for the first call, and only when the authority
     * bound at mint time currently reports an [ChainedAvailability.Eligible] verdict.
     */
    fun consumeForConstruction(): Boolean {
        if (!consumed.compareAndSet(false, true)) return false
        return authority.current() is ChainedAvailability.Eligible
    }

    companion object {
        /**
         * Mints a one-shot permit bound to [authority], or `null` when [authority] is not
         * eligible at mint time. The concrete type is deliberate: no public provider interface can
         * mint a permit.
         */
        fun issue(authority: ChainedAvailabilityAuthority): ChainedEnginePermit? =
            if (authority.current() is ChainedAvailability.Eligible) {
                ChainedEnginePermit(authority)
            } else {
                null
            }
    }
}

/**
 * Supplies the CURRENT availability verdict on demand.
 *
 * A READ port only: it can report a verdict, but it CANNOT mint a [ChainedEnginePermit]. Permit
 * minting is typed to the concrete [ChainedAvailabilityAuthority] precisely so a synthetic or
 * caching provider cannot turn a stale `Eligible` into engine construction — a stale provider
 * would otherwise pass [ChainedEnginePermit.consumeForConstruction]'s revalidation.
 */
fun interface ChainedAvailabilityProvider {
    fun current(): ChainedAvailability
}

/**
 * Reads the two authoritative readiness inputs the foundation gate consumes and folds them into
 * one [ChainedAvailability].
 *
 * `hasEntitlement` comes from the injected [ChainedEntitlementPort]; the device verdict is the
 * composite [ChainedAvailabilityPolicy] evaluation. This is the composition seam the readiness
 * gate is fed from, so no call site hand-rolls "device eligible" (and no purchase is pretended:
 * until a monetization source exists the entitlement port reports false and every user is
 * honestly unentitled).
 *
 * [current] re-reads on every call rather than caching, which is what makes it safe to inject into
 * the reconnect-capable session factory: a lapsed entitlement is observed at the next create.
 *
 * The primary constructor is `internal`: an authority can mint engine permits via
 * [ChainedEnginePermit.issue], so letting application code build one from arbitrary ports would
 * reopen the structural gate. Production wiring goes through the named [production] factory; the
 * tests in this module use the internal constructor directly, and consumer-module tests use
 * [production] with fakes.
 */
class ChainedAvailabilityAuthority internal constructor(
    private val entitlement: ChainedEntitlementPort,
    private val eligibilityStore: ChainedDeviceEligibilityStore,
) : ChainedAvailabilityProvider {

    override fun current(): ChainedAvailability = ChainedAvailabilityPolicy.evaluate(
        hasEntitlement = entitlement.hasEntitlement(),
        read = eligibilityStore.read(),
    )

    companion object {
        /**
         * The trusted production wiring seam: the only public way to build an authority.
         *
         * The composition root calls this with the real entitlement port and device store; the
         * internal constructor is what keeps application code from minting an authority (and
         * therefore a permit) from arbitrary ports, and this named entry is the auditable point to
         * review when the wiring changes.
         */
        fun production(
            entitlement: ChainedEntitlementPort,
            eligibilityStore: ChainedDeviceEligibilityStore,
        ): ChainedAvailabilityAuthority =
            ChainedAvailabilityAuthority(entitlement, eligibilityStore)
    }
}
