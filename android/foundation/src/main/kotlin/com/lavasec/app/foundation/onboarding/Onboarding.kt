package com.lavasec.app.foundation.onboarding

import com.lavasec.app.foundation.config.AppConfiguration
import com.lavasec.app.foundation.config.ProtectionLevel
import com.lavasec.app.foundation.filter.FilterProfile
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import com.lavasec.app.foundation.resolver.ResolverConfiguration
import com.lavasec.app.foundation.resolver.ResolverTier
import com.lavasec.app.foundation.resolver.toSelection
import com.lavasec.app.foundation.resolver.BuiltInDohResolverIds
import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashSet
import kotlin.ConsistentCopyVisibility

enum class OnboardingPage {
    LAVA,
    GUARD_INTRO,
    FEATURES,
    VPN,
    NOTIFICATIONS,
    PROTECTION_LEVEL,
    CONNECTION_QUALITY,
    DONE,
}

@ConsistentCopyVisibility
data class OnboardingState private constructor(
    val page: OnboardingPage,
    val reached: Set<OnboardingPage>,
    val vpnConfigured: Boolean,
    val notificationHandled: Boolean,
    val draft: AppConfiguration,
    private val navigationHistory: List<OnboardingPage>,
) {
    init {
        require(reached.isNotEmpty()) { "at least the Lava page must be reached" }
        require(page in reached) { "current page must be reached" }

        val furthestReached = reached.maxBy(OnboardingPage::ordinal)
        val expectedPrefix = OnboardingPage.entries.take(furthestReached.ordinal + 1).toSet()
        require(reached == expectedPrefix) { "reached pages must form a contiguous prefix" }
        require(!vpnConfigured || OnboardingPage.VPN in reached) {
            "VPN cannot be configured before reaching its page"
        }
        require(!notificationHandled || OnboardingPage.NOTIFICATIONS in reached) {
            "notifications cannot be handled before reaching their page"
        }
        require(
            furthestReached.ordinal < OnboardingPage.NOTIFICATIONS.ordinal || vpnConfigured,
        ) { "reaching notifications requires VPN configuration" }
        require(
            furthestReached.ordinal < OnboardingPage.PROTECTION_LEVEL.ordinal || notificationHandled,
        ) { "reaching protection level requires handling notifications" }
    }

    val canGoBack: Boolean
        get() = navigationHistory.isNotEmpty()

    internal constructor(
        page: OnboardingPage,
        reached: Set<OnboardingPage>,
        vpnConfigured: Boolean,
        notificationHandled: Boolean,
        draft: AppConfiguration,
    ) : this(
        page = page,
        reached = immutableSet(reached),
        vpnConfigured = vpnConfigured,
        notificationHandled = notificationHandled,
        draft = draft,
        navigationHistory = emptyList(),
    )

    internal fun withDraft(draft: AppConfiguration): OnboardingState =
        copy(draft = draft)

    internal fun withVpnConfigured(): OnboardingState =
        copy(vpnConfigured = true)

    internal fun withNotificationHandled(): OnboardingState =
        copy(notificationHandled = true)

    internal fun moveTo(next: OnboardingPage): OnboardingState = copy(
        page = next,
        reached = immutableSet(reached + next),
        navigationHistory = immutableList(navigationHistory + page),
    )

    internal fun back(): OnboardingState {
        val previous = navigationHistory.lastOrNull() ?: return this
        return copy(page = previous, navigationHistory = immutableList(navigationHistory.dropLast(1)))
    }

    companion object {
        fun initial(): OnboardingState = OnboardingState(
            page = OnboardingPage.LAVA,
            reached = setOf(OnboardingPage.LAVA),
            vpnConfigured = false,
            notificationHandled = false,
            draft = AppConfiguration.default().let { defaults ->
                defaults.copy(resolver = defaults.resolver.copy(
                    encryptedFallbackId = BuiltInDohResolverIds.QUAD9_UNFILTERED,
                ))
            },
        )
    }
}

private fun <T> immutableSet(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

sealed interface OnboardingAction {
    data object Forward : OnboardingAction
    data object Back : OnboardingAction
    data class PageSelected(val page: OnboardingPage) : OnboardingAction
    data class VpnConfigurationResult(val successful: Boolean) : OnboardingAction
    data class NotificationPermissionResult(val granted: Boolean) : OnboardingAction
    data class ProtectionLevelSelected(val level: ProtectionLevel) : OnboardingAction
    data class ResolverSelected(val plan: MilestoneResolverPlan) : OnboardingAction
    /** Native setup changes route shape without substituting its retained provider. */
    data class ResolverRouteSelected(val useDeviceDns: Boolean, val useEncryptedFallback: Boolean) : OnboardingAction
    data class FallbackSelected(val enabled: Boolean) : OnboardingAction
}

object OnboardingReducer {
    fun reduce(state: OnboardingState, action: OnboardingAction): OnboardingState = when (action) {
        OnboardingAction.Forward -> forward(state)
        OnboardingAction.Back -> back(state)
        is OnboardingAction.PageSelected -> selectPage(state, action.page)
        is OnboardingAction.VpnConfigurationResult -> vpnResult(state, action.successful)
        is OnboardingAction.NotificationPermissionResult -> notificationResult(state, action.granted)
        is OnboardingAction.ProtectionLevelSelected -> selectProtectionLevel(state, action.level)
        is OnboardingAction.ResolverSelected -> selectResolver(state, action.plan)
        is OnboardingAction.ResolverRouteSelected -> selectResolverRoute(state, action.useDeviceDns, action.useEncryptedFallback)
        is OnboardingAction.FallbackSelected -> if (state.page != OnboardingPage.CONNECTION_QUALITY) state else
            state.withDraft(state.draft.copy(resolver = ResolverConfiguration(
                useDeviceDns = true, useEncryptedFallback = action.enabled,
                encryptedFallbackId = BuiltInDohResolverIds.QUAD9_UNFILTERED,
            )))
    }

    fun complete(state: OnboardingState): AppConfiguration? =
        state.draft.takeIf {
            state.page == OnboardingPage.DONE &&
                state.vpnConfigured &&
                state.notificationHandled
        }

    private fun forward(state: OnboardingState): OnboardingState = when (state.page) {
        OnboardingPage.VPN,
        OnboardingPage.DONE,
        -> state

        // A denial keeps the page with an explanation; the primary action advances
        // only after the notification decision is recorded, mirroring the VPN page's
        // result-driven advancement.
        OnboardingPage.NOTIFICATIONS -> if (state.notificationHandled) {
            moveTo(state, OnboardingPage.PROTECTION_LEVEL)
        } else {
            state
        }

        else -> moveTo(state, state.page.next())
    }

    private fun back(state: OnboardingState): OnboardingState {
        return state.back()
    }

    private fun selectPage(state: OnboardingState, page: OnboardingPage): OnboardingState =
        if (page == state.page || page !in state.reached) state else moveTo(state, page)

    private fun vpnResult(state: OnboardingState, successful: Boolean): OnboardingState =
        if (state.page != OnboardingPage.VPN || !successful) state
        else moveTo(state.withVpnConfigured(), OnboardingPage.NOTIFICATIONS)

    private fun notificationResult(state: OnboardingState, granted: Boolean): OnboardingState =
        if (state.page != OnboardingPage.NOTIFICATIONS) {
            state
        } else if (granted) {
            moveTo(state.withNotificationHandled(), OnboardingPage.PROTECTION_LEVEL)
        } else {
            // Denied stays on the notifications page so the post-denial explanation
            // renders; the decision is still recorded as handled so the primary
            // action can advance.
            state.withNotificationHandled()
        }

    private fun selectProtectionLevel(
        state: OnboardingState,
        protectionLevel: ProtectionLevel,
    ): OnboardingState = if (state.page != OnboardingPage.PROTECTION_LEVEL) {
        state
    } else {
        val profile = FilterProfile.forLevel(protectionLevel)
        state.withDraft(state.draft.copy(
            protectionLevel = protectionLevel,
            activeFilterId = profile.id,
        ))
    }

    private fun selectResolver(
        state: OnboardingState,
        plan: MilestoneResolverPlan,
    ): OnboardingState = if (state.page != OnboardingPage.CONNECTION_QUALITY) {
        state
    } else {
        state.withDraft(state.draft.copy(resolver = plan.configuration))
    }

    private fun selectResolverRoute(
        state: OnboardingState, useDeviceDns: Boolean, useEncryptedFallback: Boolean,
    ): OnboardingState {
        if (state.page != OnboardingPage.CONNECTION_QUALITY || !useDeviceDns && !useEncryptedFallback) return state
        val original = state.draft.resolver
        // Custom endpoint custody cannot be expressed by the scalar device-only route.
        if (!useEncryptedFallback && (original.customDohUrl != null || original.customDotEndpoint != null ||
                original.customDoqEndpoint != null)) return state
        val scalar = original.copy(savedTiers = null)
        val next = scalar.copy(useDeviceDns = useDeviceDns, useEncryptedFallback = useEncryptedFallback)
        val retained = original.savedTiers
        val configuration = if (retained == null) next else {
            // The three setup cards must not replace a richer restored provider/order with
            // a compatibility scalar. Only reconcile a ladder expressing this exact source.
            if (original.toSelection().activeTiers.map { it.entry } != scalar.toSelection().activeTiers.map { it.entry }) return state
            val desired = next.toSelection().activeTiers.map { it.entry }
            val missing = desired.filter { entry -> retained.none { it.entry == entry } }
            if (retained.size + missing.size > 2) return state
            val rows = retained.map { it.copy(isEnabled = it.entry in desired) }.toMutableList()
            for (entry in missing) {
                val following = desired.drop(desired.indexOf(entry) + 1).firstOrNull { candidate -> rows.any { it.entry == candidate } }
                val index = following?.let { candidate -> rows.indexOfFirst { it.entry == candidate } } ?: rows.size
                rows.add(index, ResolverTier(entry))
            }
            if (rows.filter { it.isEnabled }.map { it.entry } != desired) return state
            next.copy(savedTiers = rows)
        }
        return state.withDraft(state.draft.copy(resolver = configuration))
    }

    private fun moveTo(state: OnboardingState, next: OnboardingPage): OnboardingState = state.moveTo(next)

    private fun OnboardingPage.next(): OnboardingPage = OnboardingPage.entries[ordinal + 1]
}
