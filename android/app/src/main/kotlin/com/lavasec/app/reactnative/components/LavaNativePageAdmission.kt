package com.lavasec.app.reactnative.components

import android.app.Activity
import com.lavasec.app.LavasecApplication
import com.lavasec.app.MainProductViewModel
import com.lavasec.app.foundation.security.ProtectedSurface
import com.lavasec.app.foundation.resolver.toSelection
import com.lavasec.app.reactnative.LavaReactActivity
import com.lavasec.app.security.presentPasscodeAuthentication
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * The page's view of the live admission authority.
 *
 * This is a thin port over the existing `SecurityOwner` (reached through the same
 * `MainProductViewModel` the `NativeProductBridge`/`LavaAppModule` use); no second authentication
 * authority is created. A fake implements it in JVM tests.
 */
internal interface LavaNativePageAdmissionPort {
    /** The credential-graph revisions, so a page can revoke synchronously when the grant expires. */
    val securityRevisions: Flow<Long>

    /** The revision currently in force; sampled after an authorization to bind the grant to it. */
    fun currentSecurityRevision(): Long

    /** Whether [page] needs an authentication turn before its native content becomes interactive. */
    fun requiresAuthentication(page: String): Boolean

    /** Requests the SecurityOwner turn for [page]; resolves `true` when accepted. */
    suspend fun authorize(activity: Activity, page: String): Boolean

    fun deviceQaPort(activityProvider: () -> Activity?): DeviceQaOwnerPort? = null
    val deviceQaAuthorityChanges: Flow<DeviceQaAuthority?> get() = kotlinx.coroutines.flow.emptyFlow()
}

/**
 * Production port over the foreground product. `feedback` mirrors the canonical short-circuit
 * (no auth), every other embedded native page requests the app-settings surface, exactly like the
 * iOS `LavaSettingsPageContent` `.task`.
 */
internal class ForegroundProductAdmissionPort(
    private val product: MainProductViewModel,
    private val application: LavasecApplication? = null,
) : LavaNativePageAdmissionPort {
    override val securityRevisions: Flow<Long> = product.state
        .map { state -> product.securityStateForSnapshot(state.graphGeneration)?.authenticationRevision ?: 0L }
        .distinctUntilChanged()

    override fun currentSecurityRevision(): Long =
        product.state.value.let { product.securityStateForSnapshot(it.graphGeneration)?.authenticationRevision ?: 0L }

    override fun requiresAuthentication(page: String): Boolean = page != "feedback"

    override suspend fun authorize(activity: Activity, page: String): Boolean {
        val generation = product.state.value.graphGeneration ?: return false
        val presenter = activity as? LavaReactActivity
        return product.securityAuthorize(generation, ProtectedSurface.APP_SETTINGS) { _, verify, cancel ->
            presenter?.presentPasscodeAuthentication(verify, cancel)
        }
    }

    override fun deviceQaPort(activityProvider: () -> Activity?): DeviceQaOwnerPort? =
        application?.let { ForegroundDeviceQaPort(product, it, activityProvider) }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val deviceQaAuthorityChanges: Flow<DeviceQaAuthority?> = if (application == null) flowOf(null) else
        combine(product.state, application.accountBackend.flatMapLatest { backend ->
            backend?.sessions?.state ?: flowOf(null)
        }) { _, _ -> ForegroundDeviceQaPort(product, application) { null }.authority() }.distinctUntilChanged()
}

/** Actual QA preparation delegates to native owners, including their final-write CAS/limits. */
private class ForegroundDeviceQaPort(
    private val product: MainProductViewModel,
    private val application: LavasecApplication,
    private val activityProvider: () -> Activity?,
) : DeviceQaOwnerPort {
    override fun authority(): DeviceQaAuthority? {
        val graph = product.state.value.graphGeneration ?: return null
        val security = product.securityStateForSnapshot(graph) ?: return null
        val account = application.accountBackend.value?.sessions?.state?.value
        return DeviceQaAuthority(graph, security.authenticationRevision, account?.generation, account?.userId)
    }

    override fun canarySnapshot(): kotlinx.coroutines.flow.StateFlow<com.lavasec.app.vpn.qa.DnsLeakCanarySnapshot>? =
        authority()?.let { application.dnsLeakCanaryOwnerFor(it.graph)?.snapshot }

    override fun configurationWitness(): DeviceQaConfigurationWitness? {
        val library = product.currentFilterLibrary() ?: return null
        val selection = library.resolverTarget.configuration.toSelection()
        // The complete immutable saved library participates, so an intervening edit invalidates
        // preparation even if its active profile did not change. No keys or raw .conf are read.
        return DeviceQaConfigurationWitness(com.lavasec.app.product.ResolverDnsContext.encode(selection),
            library, product.currentQaLogging())
    }

    override suspend fun perform(
        action: DeviceQaAction, visit: DeviceQaVisit, witness: DeviceQaConfigurationWitness?, isCurrent: () -> Boolean,
    ): DeviceQaResult {
        // Capture the actual caller before fresh auth; NonCancellable publication must never
        // replace it with a detached storage job after this visit has left the foreground.
        val callerJob = currentCoroutineContext()[Job]
            ?: return DeviceQaResult(DeviceQaOutcome.REFUSED, "page-not-admitted")
        fun current() = callerJob.isActive && isCurrent() && authority() == visit.authority
        fun refused(reason: String) = DeviceQaResult(DeviceQaOutcome.REFUSED, reason)
        if (!com.lavasec.app.BuildConfig.DEBUG || !current()) return refused("page-not-admitted")
        val activity = activityProvider() as? LavaReactActivity ?: return refused("host-unavailable")
        if (action is DeviceQaAction.Refusal) return refused(action.reason)
        if (action is DeviceQaAction.Haptic) {
            val outcome = com.lavasec.app.haptics.HapticCommandHandler(
                hapticsEnabled = { product.state.value.customization?.haptics ?: true },
                sink = com.lavasec.app.haptics.AndroidHapticEffectPlayer(activity.window.decorView),
            ).handle(action.semantic)
            return DeviceQaResult(when (outcome) {
                com.lavasec.app.haptics.HapticCommandOutcome.PLAYED -> DeviceQaOutcome.HAPTIC_PLAYED
                com.lavasec.app.haptics.HapticCommandOutcome.SUPPRESSED -> DeviceQaOutcome.HAPTIC_SUPPRESSED
                com.lavasec.app.haptics.HapticCommandOutcome.UNKNOWN_SEMANTIC -> DeviceQaOutcome.REFUSED
            })
        }
        if (action == DeviceQaAction.Feedback) {
            // This is the ordinary native feedback composer. Launch/cancel only; never submit.
            val result = LavaNativeFlowProduction.host(activity, product = product).launchRoute(
                LavaNativeFlowRequest(visit.activation, "feedback", visit.authority.graph, visit.authority.accountId),
            )
            return when (result) {
                is LavaNativeFlowOutcome.Completed -> DeviceQaResult(DeviceQaOutcome.APPLIED)
                is LavaNativeFlowOutcome.Cancelled -> DeviceQaResult(DeviceQaOutcome.CANCELLED)
                else -> refused("feedback-not-completed")
            }
        }
        // A process-owned canary may be shared across rotations. Bind the paired graph,
        // not just its owner identity, before auth and recheck it at the actual storage edge.
        val canaryBinding = if (action is DeviceQaAction.ArmCanary || action == DeviceQaAction.DisarmCanary)
            application.dnsLeakCanaryBinding?.takeIf { it.graphGeneration == visit.authority.graph }
                ?: return refused("canary-unavailable")
            else null
        val surfaces = when (action) {
            is DeviceQaAction.Scenario -> if (action.tiers == null && action.load == null) listOf(ProtectedSurface.FILTERS) else listOf(ProtectedSurface.FILTERS, ProtectedSurface.APP_SETTINGS)
            DeviceQaAction.Reset -> listOf(ProtectedSurface.FILTERS, ProtectedSurface.APP_SETTINGS, ProtectedSurface.ACTIVITY)
            is DeviceQaAction.Load, is DeviceQaAction.Probes -> listOf(ProtectedSurface.FILTERS)
            DeviceQaAction.ClearActivity -> listOf(ProtectedSurface.ACTIVITY)
            else -> listOf(ProtectedSurface.APP_SETTINGS)
        }
        for (surface in surfaces) {
            val authorization = product.securityAuthorizeResult(visit.authority.graph, surface, fresh = true) { _, verify, cancel ->
                activity.presentPasscodeAuthentication(verify, cancel)
            }
            if (!current()) return refused("page-not-admitted")
            when (authorization) {
                com.lavasec.app.security.SecurityAuthorization.CANCELLED -> return DeviceQaResult(DeviceQaOutcome.CANCELLED)
                com.lavasec.app.security.SecurityAuthorization.DENIED -> return refused("authorization-required")
                com.lavasec.app.security.SecurityAuthorization.AUTHORIZED -> Unit
            }
        }
        fun composite(result: com.lavasec.app.product.QaCompositeConfigurationResult): DeviceQaResult = when (result) {
            is com.lavasec.app.product.QaCompositeConfigurationResult.Applied -> DeviceQaResult(DeviceQaOutcome.APPLIED)
            is com.lavasec.app.product.QaCompositeConfigurationResult.Refused -> refused(result.rejection?.javaClass?.simpleName ?: "owner-not-committed")
            com.lavasec.app.product.QaCompositeConfigurationResult.Unavailable -> refused("owner-not-committed")
            com.lavasec.app.product.QaCompositeConfigurationResult.CommittedReconciliationRequired -> DeviceQaResult(DeviceQaOutcome.COMMITTED_RECONCILIATION_REQUIRED)
        }
        fun selectedSources(load: DeviceQaBlocklistLoad): Set<String>? {
            val bundled = com.lavasec.app.data.CatalogRepository(application.assets).loadBundled()
            val sources = when (load) {
                DeviceQaBlocklistLoad.MINIMAL -> emptySet()
                DeviceQaBlocklistLoad.RECOMMENDED -> bundled.defaultSourceIds
                DeviceQaBlocklistLoad.LARGE -> bundled.defaultSourceIds + setOf("hagezi-multi-pro", "oisd-small")
                DeviceQaBlocklistLoad.STRESS -> bundled.sources.map { it.id }.toSet()
            }
            return sources.takeIf { product.availableCatalogSourceIds().containsAll(it) }
        }
        if (canaryBinding != null) {
            return performDeviceQaCanaryMutation(action, canaryBinding, visit.authority.graph,
                { application.dnsLeakCanaryBinding }, ::current)
        }
        if (witness != configurationWitness()) return refused("configuration-changed")
        val applied = when (action) {
            is DeviceQaAction.Dns -> {
                val library = witness?.library ?: return refused("filter-unavailable")
                return composite(product.applyQaCompositeConfiguration(library, action.tiers, library, visit.authority.graph, ::current))
            }
            is DeviceQaAction.Scenario -> {
                val library = witness?.library ?: return refused("filter-unavailable")
                if ((action.tiers == null) != (action.load == null)) return refused("invalid-scenario")
                val desired = library.withQaProbeSet(com.lavasec.app.foundation.filter.DeviceQaProbeSet.HOSTED)
                if (action.tiers == null) {
                    product.replaceFilterLibraryForQa(desired, library, visit.authority.graph, ::current)
                } else {
                    val profile = library.profile(library.activeProfileId) ?: return refused("filter-unavailable")
                    val sources = selectedSources(checkNotNull(action.load)) ?: return refused("catalog-source-unavailable")
                    return composite(product.applyQaCompositeConfiguration(desired.withProfile(profile.withEnabledSourceIds(sources)),
                        action.tiers, library, visit.authority.graph, ::current))
                }
            }
            DeviceQaAction.Reset -> {
                val library = witness?.library ?: return refused("filter-unavailable")
                val logging = witness.logging ?: return refused("logging-unavailable")
                return composite(product.resetQaState(library, logging, visit.authority.graph, ::current))
            }
            is DeviceQaAction.History -> {
                val logging = product.state.value.privacy?.logging ?: return refused("logging-unavailable")
                product.setLoggingForRestore(logging.copy(keepHistory = action.enabled), visit.authority.graph, ::current)
            }
            DeviceQaAction.ClearActivity -> product.clearLocalActivity()
            is DeviceQaAction.Load, is DeviceQaAction.Probes -> {
                val library = product.currentFilterLibrary() ?: return refused("filter-unavailable")
                val profile = library.profile(library.activeProfileId) ?: return refused("filter-unavailable")
                if (com.lavasec.app.foundation.filter.FilterLibraryPolicy.isFrozen(library, profile.id,
                        product.currentFeatureLimits().maxFilters)) return refused("frozen-filter")
                val desired = when (action) {
                    is DeviceQaAction.Load -> {
                        val sources = selectedSources(action.load) ?: return refused("catalog-source-unavailable")
                        library.withProfile(profile.withEnabledSourceIds(sources))
                    }
                    is DeviceQaAction.Probes -> library.withQaProbeSet(action.value)
                    else -> error("unreachable")
                }
                if (!current()) return refused("page-not-admitted")
                // QA refuses open editors under the owner's admission lock and carries this
                // exact visit through preparation to the final durable write, without retry.
                product.replaceFilterLibraryForQa(
                    desired = desired,
                    expectedLibrary = library,
                    expectedGraph = visit.authority.graph,
                    isCurrent = ::current,
                )
            }
            else -> false
        }
        return if (current() && applied) DeviceQaResult(DeviceQaOutcome.APPLIED) else refused("owner-not-committed")
    }
}

/** The sole final QA canary adapter; fresh native authentication is completed by its caller. */
internal fun performDeviceQaCanaryMutation(
    action: DeviceQaAction,
    captured: com.lavasec.app.CredentialDnsLeakCanaryBinding,
    expectedGraph: Long,
    currentBinding: () -> com.lavasec.app.CredentialDnsLeakCanaryBinding?,
    isCurrent: () -> Boolean,
): DeviceQaResult {
    val admitted = { isCurrent() && currentBinding() === captured && captured.graphGeneration == expectedGraph }
    val mutation = when (action) {
        is DeviceQaAction.ArmCanary -> captured.owner.arm(action.resolverIP, admitted)
        DeviceQaAction.DisarmCanary -> captured.owner.disarm(admitted)
        else -> return DeviceQaResult(DeviceQaOutcome.REFUSED, "canary-unavailable")
    }
    return when (mutation) {
        is com.lavasec.app.vpn.qa.DnsLeakCanaryMutation.Applied -> DeviceQaResult(DeviceQaOutcome.APPLIED)
        com.lavasec.app.vpn.qa.DnsLeakCanaryMutation.InvalidAddress -> DeviceQaResult(DeviceQaOutcome.REFUSED, "invalid-canary-address")
        com.lavasec.app.vpn.qa.DnsLeakCanaryMutation.Stale -> DeviceQaResult(DeviceQaOutcome.REFUSED, "superseded")
        com.lavasec.app.vpn.qa.DnsLeakCanaryMutation.Unavailable -> DeviceQaResult(DeviceQaOutcome.REFUSED, "canary-unavailable")
    }
}
