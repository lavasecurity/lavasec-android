package com.lavasec.app.reactnative.components

import com.lavasec.app.foundation.onboarding.OnboardingAction
import com.lavasec.app.foundation.onboarding.OnboardingPage
import com.lavasec.app.foundation.onboarding.OnboardingReducer
import com.lavasec.app.foundation.onboarding.OnboardingState
import com.lavasec.app.foundation.resolver.ResolverTierChoice
import com.lavasec.app.foundation.subscription.FeatureLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext

/** A visit is bound to existing product/security/account authorities, never to preview facts. */
internal data class DeviceQaAuthority(
    val graph: Long,
    val securityRevision: Long,
    val accountGeneration: Long?,
    val accountId: String?,
)

internal data class DeviceQaConfigurationWitness(
    val dnsContext: String,
    val library: com.lavasec.app.foundation.filter.FilterLibraryDraft,
    val logging: com.lavasec.app.platform.LoggingSettings?,
)

internal data class DeviceQaVisit(val visit: Long, val activation: Long, val authority: DeviceQaAuthority)

internal enum class DeviceQaOutcome { APPLIED, COMMITTED_RECONCILIATION_REQUIRED, CANCELLED, REFUSED, HAPTIC_PLAYED, HAPTIC_SUPPRESSED }
internal data class DeviceQaResult(val outcome: DeviceQaOutcome, val reason: String? = null)

/** Public copy is finite; internal refusal codes never become rendered product text. */
internal enum class DeviceQaResultMessage {
    NO_RESULT, SAVED, RECONCILIATION_REQUIRED, DONE, DISABLED, CANCELLED, SETTINGS_CHANGED, NOT_CONFIGURED,
    CHECK_UNAVAILABLE, SETTINGS_UNAVAILABLE, HOSTED_QA_UNAVAILABLE, DNS_UNAVAILABLE, CANARY_UNAVAILABLE, INVALID_ADDRESS, INVALID_DOMAIN, COULD_NOT_UPDATE,
}

internal fun DeviceQaResult?.message(): DeviceQaResultMessage = when (this?.outcome) {
    null -> DeviceQaResultMessage.NO_RESULT
    DeviceQaOutcome.APPLIED -> DeviceQaResultMessage.SAVED
    DeviceQaOutcome.COMMITTED_RECONCILIATION_REQUIRED -> DeviceQaResultMessage.RECONCILIATION_REQUIRED
    DeviceQaOutcome.HAPTIC_PLAYED -> DeviceQaResultMessage.DONE
    DeviceQaOutcome.HAPTIC_SUPPRESSED -> DeviceQaResultMessage.DISABLED
    DeviceQaOutcome.CANCELLED -> DeviceQaResultMessage.CANCELLED
    DeviceQaOutcome.REFUSED -> when (reason) {
        "invalid-probe-suffix" -> DeviceQaResultMessage.INVALID_DOMAIN
        "invalid-canary-address" -> DeviceQaResultMessage.INVALID_ADDRESS
        "canary-unavailable" -> DeviceQaResultMessage.CANARY_UNAVAILABLE
        "configuration-changed", "superseded", "StaleEditor" -> DeviceQaResultMessage.SETTINGS_CHANGED
        "dns-unavailable", "filter-unavailable", "logging-unavailable", "host-unavailable" -> DeviceQaResultMessage.NOT_CONFIGURED
        "protected-qa-probe-owner-unavailable", "ios-constrained-path-check-unavailable" -> DeviceQaResultMessage.CHECK_UNAVAILABLE
        "system-settings-unavailable" -> DeviceQaResultMessage.SETTINGS_UNAVAILABLE
        "browser-unavailable" -> DeviceQaResultMessage.HOSTED_QA_UNAVAILABLE
        "TransportUnavailable", "TransportNotOffered" -> DeviceQaResultMessage.DNS_UNAVAILABLE
        else -> DeviceQaResultMessage.COULD_NOT_UPDATE
    }
}
internal enum class DeviceQaBlocklistLoad { MINIMAL, RECOMMENDED, LARGE, STRESS }

internal sealed interface DeviceQaAction {
    data class Dns(val tiers: List<ResolverTierChoice>) : DeviceQaAction
    data class Load(val load: DeviceQaBlocklistLoad) : DeviceQaAction
    data class Probes(val value: com.lavasec.app.foundation.filter.DeviceQaProbeSet?) : DeviceQaAction
    data class Scenario(val id: String, val tiers: List<ResolverTierChoice>? = null, val load: DeviceQaBlocklistLoad? = null) : DeviceQaAction
    data object Reset : DeviceQaAction
    data class ArmCanary(val resolverIP: String) : DeviceQaAction
    data object DisarmCanary : DeviceQaAction
    data class History(val enabled: Boolean) : DeviceQaAction
    data object ClearActivity : DeviceQaAction
    data class Haptic(val semantic: String) : DeviceQaAction
    data object Feedback : DeviceQaAction
    data class Refusal(val reason: String) : DeviceQaAction
}

/** No product store lives here. Production delegates to the foreground graph's existing owners. */
internal interface DeviceQaOwnerPort {
    fun authority(): DeviceQaAuthority?
    /** Exact confirmed DNS/filter witness. Used for preparation and manual-result invalidation. */
    fun configurationWitness(): DeviceQaConfigurationWitness?
    fun canarySnapshot(): StateFlow<com.lavasec.app.vpn.qa.DnsLeakCanarySnapshot>? = null
    suspend fun perform(action: DeviceQaAction, visit: DeviceQaVisit, witness: DeviceQaConfigurationWitness?, isCurrent: () -> Boolean): DeviceQaResult
}

internal data class DeviceQaPending(val action: DeviceQaAction, val visit: DeviceQaVisit, val witness: DeviceQaConfigurationWitness?)
internal data class DeviceQaPageState(
    val visit: DeviceQaVisit? = null,
    val busy: Boolean = false,
    val pending: DeviceQaPending? = null,
    val result: DeviceQaResult? = null,
    val showingResult: Boolean = false,
    val mockOnboarding: OnboardingState? = null,
    val previewLimits: FeatureLimits? = null,
    val preparedWitness: DeviceQaConfigurationWitness? = null,
    val preparedScenarioId: String? = null,
    /** Only manually reported results, scoped to this admitted visit. */
    val reportedResults: Map<String, String> = emptyMap(),
)

/**
 * Private debug-page state. Blur, background, graph/account rotation and recycle cancel the visit;
 * a late owner completion cannot publish into the replacement page. Mock onboarding and paid/free
 * limit previews have deliberately no mutation port. Neither changes configured product facts.
 */
internal class DeviceQaPageOwner(
    private val port: DeviceQaOwnerPort,
    private val scope: CoroutineScope,
) : AutoCloseable {
    private val mutableState = MutableStateFlow(DeviceQaPageState())
    val state: StateFlow<DeviceQaPageState> = mutableState.asStateFlow()
    private var operation: Job? = null
    @Volatile private var closed = false

    fun activate(visit: DeviceQaVisit?) {
        if (closed || mutableState.value.visit == visit) return
        operation?.cancel()
        operation = null
        mutableState.value = DeviceQaPageState(visit = visit)
    }

    fun isCurrent(visit: DeviceQaVisit): Boolean =
        !closed && mutableState.value.visit == visit && port.authority() == visit.authority

    fun prepare(action: DeviceQaAction, expectedVisit: DeviceQaVisit? = mutableState.value.visit): Boolean {
        val current = mutableState.value
        val visit = current.visit ?: return false
        if (visit != expectedVisit || !isCurrent(visit) || current.busy || current.pending != null) return false
        mutableState.value = current.copy(pending = DeviceQaPending(action, visit, port.configurationWitness()))
        return true
    }

    /** The suffix draft is private UI state. Validation never writes or clears a prior saved set. */
    fun prepareCustomProbes(suffix: String, expectedVisit: DeviceQaVisit?): Boolean {
        val current = mutableState.value
        val visit = current.visit ?: return false
        if (visit != expectedVisit || !isCurrent(visit) || current.busy || current.pending != null) return false
        val probes = try { com.lavasec.app.foundation.filter.DeviceQaProbeSet.fromSuffix(suffix) }
        catch (_: com.lavasec.app.foundation.domain.DomainValidationException) {
            mutableState.value = current.copy(result = DeviceQaResult(DeviceQaOutcome.REFUSED, "invalid-probe-suffix"), showingResult = true)
            return false
        }
        return prepare(DeviceQaAction.Probes(probes), visit)
    }

    fun canarySnapshot(): StateFlow<com.lavasec.app.vpn.qa.DnsLeakCanarySnapshot>? =
        mutableState.value.visit?.takeIf(::isCurrent)?.let { port.canarySnapshot() }

    fun probeDomains(): List<String> = port.configurationWitness()?.library?.qaProbeSet?.allDomains
        ?: com.lavasec.app.foundation.filter.DeviceQaProbeSet.HOSTED.allDomains

    fun cancelPending() {
        mutableState.value = mutableState.value.copy(pending = null)
    }

    fun confirm(): Boolean {
        val pending = mutableState.value.pending ?: return false
        mutableState.value = mutableState.value.copy(pending = null)
        return execute(pending.action, pending.visit, pending.witness)
    }

    fun perform(action: DeviceQaAction, expectedVisit: DeviceQaVisit? = mutableState.value.visit): Boolean {
        val visit = mutableState.value.visit ?: return false
        if (visit != expectedVisit) return false
        return execute(action, visit, port.configurationWitness())
    }

    private fun execute(action: DeviceQaAction, visit: DeviceQaVisit, witness: DeviceQaConfigurationWitness?): Boolean {
        if (!isCurrent(visit) || mutableState.value.busy) return false
        if (witness != port.configurationWitness()) {
            mutableState.value = mutableState.value.copy(result = DeviceQaResult(DeviceQaOutcome.REFUSED, "configuration-changed"), showingResult = true)
            return false
        }
        mutableState.value = mutableState.value.copy(busy = true, previewLimits = null, preparedWitness = null, preparedScenarioId = null)
        operation = scope.launch {
            try {
                val callerJob = currentCoroutineContext()[Job]
                val result = port.perform(action, visit, witness) { callerJob?.isActive != false && isCurrent(visit) }
                if (callerJob?.isActive != false && isCurrent(visit)) {
                    mutableState.value = mutableState.value.copy(
                        busy = false, result = result, showingResult = true,
                        preparedWitness = if (action is DeviceQaAction.Scenario && result.outcome == DeviceQaOutcome.APPLIED) port.configurationWitness() else null,
                        preparedScenarioId = if (action is DeviceQaAction.Scenario && result.outcome == DeviceQaOutcome.APPLIED) action.id else null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Do not publish exception text: native editors may carry sensitive user input.
                if (isCurrent(visit)) mutableState.value = mutableState.value.copy(
                    busy = false, result = DeviceQaResult(DeviceQaOutcome.REFUSED, "owner-operation-failed"), showingResult = true,
                )
            } finally {
                if (isCurrent(visit)) mutableState.value = mutableState.value.copy(busy = false)
            }
        }
        return true
    }

    fun showResult(show: Boolean) {
        if (mutableState.value.visit?.let(::isCurrent) != true) return
        mutableState.value = mutableState.value.copy(showingResult = show && mutableState.value.result != null)
    }

    fun preview(paid: Boolean) {
        if (mutableState.value.visit?.let(::isCurrent) != true || mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(previewLimits = if (paid) FeatureLimits.PAID else FeatureLimits.FREE)
    }

    fun closePreview() { mutableState.value = mutableState.value.copy(previewLimits = null) }

    fun preparedConfigurationMatches(scenarioId: String): Boolean = mutableState.value.let {
        it.visit?.let(::isCurrent) == true && it.preparedScenarioId == scenarioId && it.preparedWitness != null && it.preparedWitness == port.configurationWitness()
    }

    fun openMockOnboarding() {
        if (mutableState.value.visit?.let(::isCurrent) != true || mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(mockOnboarding = OnboardingState.initial())
    }

    fun mock(action: OnboardingAction) {
        if (mutableState.value.visit?.let(::isCurrent) != true) return
        val mock = mutableState.value.mockOnboarding ?: return
        // Local simulated permission results, never OS callbacks or durable owner commands.
        val simulated = when {
            action == OnboardingAction.Forward && mock.page == OnboardingPage.VPN -> OnboardingAction.VpnConfigurationResult(true)
            action == OnboardingAction.Forward && mock.page == OnboardingPage.NOTIFICATIONS -> OnboardingAction.NotificationPermissionResult(true)
            else -> action
        }
        if (mock.page == OnboardingPage.DONE && action == OnboardingAction.Forward) cancelMockOnboarding()
        else mutableState.value = mutableState.value.copy(mockOnboarding = OnboardingReducer.reduce(mock, simulated))
    }

    fun cancelMockOnboarding() {
        mutableState.value = mutableState.value.copy(mockOnboarding = null)
    }

    /** Pass/fail require a current prepared configuration, a positive control and operator evidence. */
    fun record(scenarioId: String, result: String, verified: Boolean, evidence: String): Boolean {
        val current = mutableState.value
        val visit = current.visit ?: return false
        if (!isCurrent(visit) || evidence.isBlank() || result !in setOf("passed", "failed", "blocked")) return false
        if (result != "blocked" && (!verified || current.preparedScenarioId != scenarioId || current.preparedWitness == null || current.preparedWitness != port.configurationWitness())) return false
        mutableState.value = current.copy(reportedResults = current.reportedResults + (scenarioId to "$result\n${evidence.trim()}"))
        return true
    }

    override fun close() {
        closed = true
        operation?.cancel()
        operation = null
        mutableState.value = DeviceQaPageState()
    }
}
