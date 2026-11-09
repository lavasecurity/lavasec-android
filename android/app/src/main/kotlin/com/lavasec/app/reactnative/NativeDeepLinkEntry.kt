package com.lavasec.app.reactnative

import com.lavasec.app.foundation.security.ProtectedSurface
import com.lavasec.app.presentation.ProductDestination
import com.lavasec.app.presentation.ProductNavigation
import java.util.UUID
import java.lang.ref.WeakReference

/** Native custody for a validated public entry. No URI or input is handed to React. */
internal class NativeDeepLinkEntryRegistry {
    internal data class Destination(val tab: String, val screen: String, val surface: ProtectedSurface?)
    internal data class Navigation(val serial: Long, val tab: String, val screen: String, val surface: ProtectedSurface?)
    internal class Claim internal constructor(
        internal val entry: Entry, internal val host: Any, internal val hostID: String,
        val generation: Long,
    ) {
        val id: String get() = entry.id
        val destination: Destination get() = entry.destination
    }
    internal class SetupHandoff internal constructor(
        internal val claim: Claim, val visitID: String, internal val nativeOwner: Any,
    )

    internal class Entry(
        val id: String, val destination: Destination, val serial: Long, var generation: Long?,
        var host: Any? = null, var hostID: String? = null, var attempted: Boolean = false,
        var navigation: Navigation? = null,
        var setupHandoff: SetupHandoff? = null, var completedSetupSourceGeneration: Long? = null,
    )
    internal data class Receipt(val navigation: Navigation, val presentationSerial: Long)
    private data class Accepted(val id: String, val host: WeakReference<Any>, val hostID: String,
        val generation: Long, val receipt: Receipt)
    private var accepted: Accepted? = null
    private var serial = 0L
    private var current: Entry? = null

    @Synchronized fun stage(navigation: ProductNavigation, generation: Long?): String {
        val destination = destination(navigation) ?: error("Unsupported external destination")
        accepted = null
        return Entry(UUID.randomUUID().toString(), destination, ++serial, generation).also { current = it }.id
    }

    /** Child custody starts at Activity creation, even before a graph or unlock exists. */
    @Synchronized fun attach(id: String?, host: Any, hostID: String): Boolean {
        val entry = current?.takeIf { it.id == id } ?: return false
        if (entry.host != null && (entry.host !== host || entry.hostID != hostID)) return false
        entry.host = host; entry.hostID = hostID
        return true
    }

    /** A bootstrap token binds once to the first actual graph; rotation cannot adopt it. */
    @Synchronized fun claim(id: String?, host: Any, hostID: String, generation: Long): Claim? {
        val entry = current?.takeIf { it.id == id } ?: return null
        if (entry.host !== host || entry.hostID != hostID) return null
        if (entry.generation != null && entry.generation != generation) {
            // A queued old-owner observation may arrive after the proven receipt
            // but before its successor's first VM frame. It has no admission and
            // cannot cancel that exact successor; every other rotation retires.
            if (entry.completedSetupSourceGeneration == generation) return null
            current = null
            return null
        }
        entry.generation = generation
        return Claim(entry, host, hostID, generation)
    }

    @Synchronized fun isCurrent(claim: Claim, generation: Long): Boolean =
        current === claim.entry && claim.entry.host === claim.host && claim.entry.hostID == claim.hostID &&
            claim.entry.generation == claim.generation && claim.generation == generation

    @Synchronized fun begin(claim: Claim): Boolean {
        if (!isCurrent(claim, claim.generation) || claim.entry.attempted) return false
        claim.entry.attempted = true
        return true
    }

    /** Binds the actual native setup owner before any completion can publish a new graph. */
    @Synchronized fun prepareSetupHandoff(claim: Claim, visitID: String, nativeOwner: Any): SetupHandoff? {
        if (!isCurrent(claim, claim.generation) || !claim.entry.attempted || claim.entry.navigation != null ||
            claim.entry.setupHandoff != null || claim.entry.completedSetupSourceGeneration != null) return null
        return SetupHandoff(claim, visitID, nativeOwner).also { claim.entry.setupHandoff = it }
    }

    /** Called only by the actual durable finish's pre-publication graph receipt. */
    @Synchronized fun transferSetupHandoff(
        handoff: SetupHandoff, visitID: String, nativeOwner: Any, sourceGeneration: Long, successorGeneration: Long,
    ): Claim? {
        val original = handoff.claim
        if (visitID != handoff.visitID || nativeOwner !== handoff.nativeOwner || sourceGeneration != original.generation ||
            successorGeneration <= 0 || successorGeneration == sourceGeneration ||
            !isCurrent(original, sourceGeneration) || original.entry.setupHandoff !== handoff) return null
        original.entry.generation = successorGeneration
        original.entry.completedSetupSourceGeneration = sourceGeneration
        original.entry.setupHandoff = null
        return Claim(original.entry, original.host, original.hostID, successorGeneration)
    }

    /** Actual setup state and freshly accepted native authorization are both required. */
    @Synchronized fun publish(claim: Claim, generation: Long, setupCompleted: Boolean, authorized: Boolean): Boolean {
        if (!isCurrent(claim, generation) || !claim.entry.attempted || !setupCompleted || !authorized) return false
        claim.entry.navigation = Navigation(claim.entry.serial, claim.destination.tab, claim.destination.screen, claim.destination.surface)
        return true
    }

    @Synchronized fun navigation(id: String?, host: Any, hostID: String, generation: Long): Navigation? {
        val entry = current?.takeIf { it.id == id && it.host === host && it.hostID == hostID && it.generation == generation }
        return entry?.navigation
    }

    /** Consumes only the exact measured native redirect; it cannot replay on recreation. */
    @Synchronized fun accept(id: String?, host: Any, hostID: String, generation: Long, serial: Long, presentationSerial: Long): Boolean {
        val entry = current?.takeIf { it.id == id && it.host === host && it.hostID == hostID && it.generation == generation } ?: return false
        val navigation = entry.navigation ?: return false
        if (navigation.serial != serial || presentationSerial <= 0) return false
        accepted = Accepted(entry.id, WeakReference(host), hostID, generation, Receipt(navigation, presentationSerial))
        current = null
        return true
    }

    /** Positive native delivery fact; cancellation and an absent ticket cannot fabricate it. */
    @Synchronized fun acceptance(id: String?, host: Any, hostID: String, generation: Long): Receipt? =
        accepted?.takeIf { it.id == id && it.host.get() === host && it.hostID == hostID && it.generation == generation }?.receipt

    @Synchronized fun cancel(claim: Claim) {
        if (isCurrent(claim, claim.generation)) current = null
    }

    /** Only an actual configuration destruction permits a replacement child to claim the ticket. */
    @Synchronized fun release(id: String?, host: Any, hostID: String, recreating: Boolean) {
        val entry = current?.takeIf { it.id == id && it.host === host && it.hostID == hostID } ?: return
        if (recreating) {
            entry.host = null; entry.hostID = null; entry.attempted = false; entry.navigation = null; entry.setupHandoff = null
        } else current = null
    }

    companion object {
        val process = NativeDeepLinkEntryRegistry()
        fun destination(navigation: ProductNavigation): Destination? = when (navigation) {
            ProductNavigation.guard -> Destination("GuardTab", "Guard", null)
            ProductNavigation.guard.open(ProductDestination.Filters) -> Destination("GuardTab", "Filters", ProtectedSurface.FILTERS)
            ProductNavigation.guard.open(ProductDestination.Activity) -> Destination("GuardTab", "Activity", ProtectedSurface.ACTIVITY)
            ProductNavigation.settings -> Destination("SettingsTab", "Settings", ProtectedSurface.APP_SETTINGS)
            else -> null
        }
    }
}
