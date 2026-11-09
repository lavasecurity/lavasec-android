package com.lavasec.app.reactnative

import com.lavasec.app.BuildConfig
import com.lavasec.app.R
import com.lavasec.app.MainProductUiState
import com.lavasec.app.account.AccountPresentation
import com.lavasec.app.backup.BackupSnapshot
import com.lavasec.app.data.CatalogQueryResult
import com.lavasec.app.foundation.biometrics.BiometricAccessPolicy
import com.lavasec.app.foundation.biometrics.BiometricAvailability
import com.lavasec.app.foundation.biometrics.BiometricKind
import com.lavasec.app.foundation.biometrics.BiometricSettings
import com.lavasec.app.foundation.chained.ChainedConfigurationIssue
import com.lavasec.app.foundation.chained.ChainedUpstreamRotationFreshness
import com.lavasec.app.foundation.filter.FilterDraftReviewRejection
import com.lavasec.app.foundation.filter.FilterLibraryEditSession
import com.lavasec.app.foundation.flows.NativeFlow
import com.lavasec.app.foundation.notifications.NotificationCategory
import com.lavasec.app.foundation.notifications.NotificationPreferences
import com.lavasec.app.foundation.onboarding.OnboardingPhase
import com.lavasec.app.foundation.onboarding.ProtectionRecoveryAction
import com.lavasec.app.foundation.preferences.ProductPreferences
import com.lavasec.app.foundation.resolver.CatalogResolverTransport
import com.lavasec.app.foundation.resolver.ChainedDnsEditState
import com.lavasec.app.foundation.resolver.CustomResolverDraft
import com.lavasec.app.foundation.resolver.CustomResolverDraftSession
import com.lavasec.app.foundation.resolver.CustomResolverValidator
import com.lavasec.app.foundation.resolver.ResolverCatalog
import com.lavasec.app.foundation.resolver.ResolverEndpoint
import com.lavasec.app.foundation.resolver.ResolverProvider
import com.lavasec.app.foundation.resolver.ResolverProviderId
import com.lavasec.app.foundation.resolver.ResolverSelection
import com.lavasec.app.foundation.resolver.toCatalogTransport
import com.lavasec.app.foundation.security.ProtectedSurface
import com.lavasec.app.foundation.sudoku.SudokuGame
import com.lavasec.app.notification.SystemCapability
import com.lavasec.app.notification.SystemCapabilityPolicy
import com.lavasec.app.platform.ChainedConfigurationDraft
import com.lavasec.app.platform.ChainedConfigurationDraftRow
import com.lavasec.app.platform.LoggingSettings
import com.lavasec.app.platform.ProtectionState
import com.lavasec.app.platform.StoredChainedUpstreamConfiguration
import com.lavasec.app.presentation.GuardPrimaryAction
import com.lavasec.app.presentation.ProductDestination
import com.lavasec.app.presentation.ProductNavigation
import com.lavasec.app.presentation.activity.ActivityContentState
import com.lavasec.app.product.ResolverDnsContext
import com.lavasec.app.security.SecurityAvailability
import com.lavasec.app.security.SecurityFailure
import com.lavasec.app.security.SecurityOwnerState
import com.lavasec.app.sudoku.toWireJson
import com.lavasec.app.vpn.chained.ChainedActivitySignal
import com.lavasec.app.vpn.chained.ChainedRuntimeMode
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Closed flow vocabulary: JS cannot construct arbitrary intents or bypass native route gates. */
internal object NativeProductRoutes {
    fun navigation(route: String): ProductNavigation? = when (route) {
        "onboarding", "guard" -> ProductNavigation.guard
        "filters" -> ProductNavigation.guard.open(ProductDestination.Filters)
        "activity" -> ProductNavigation.guard.open(ProductDestination.Activity)
        "settings" -> ProductNavigation.settings
        "resolver" -> ProductNavigation.settings.open(ProductDestination.DnsResolver)
        "networkActivity" -> ProductNavigation.settings.open(ProductDestination.NetworkActivity)
        "privacy" -> ProductNavigation.settings.open(ProductDestination.PrivacyData)
        "security" -> ProductNavigation.settings.open(ProductDestination.Security)
        "customization" -> ProductNavigation.settings.open(ProductDestination.Customization)
        "feedback" -> ProductNavigation.settings.open(ProductDestination.BugReport)
        "legal" -> ProductNavigation.settings.open(ProductDestination.LegalNotices)
        // The canonical `native.flow licenses` row (shared Legal screen) opens the same offline
        // LegalNotices presentation as the `legal` deep link; without this mapping the bridge would
        // refuse it as `invalid-flow`, leaving a required F13/U08 destination as a dead end.
        NativeFlow.LICENSES.wireName -> ProductNavigation.settings.open(ProductDestination.LegalNotices)
        "diagnostics" -> ProductNavigation.settings.open(ProductDestination.NerdStats)
        "account" -> ProductNavigation.settings.open(ProductDestination.Account)
        "plus" -> ProductNavigation.settings.open(ProductDestination.Upgrade)
        else -> null
    }

    /**
     * The canonical import flows are recognizable (`NativeFlow.IMPORT`/`IMPORT_CODE`/`IMPORT_SCAN`)
     * but no Android import presentation exists yet, so the bridge refuses them with a typed,
     * explicit unavailable reason instead of the generic unknown-flow rejection. The check reads
     * the foundation flow inventory so a renamed wire value cannot drift from the contract.
     */
    fun importPresentationUnavailable(route: String): Boolean =
        route == NativeFlow.IMPORT.wireName ||
            route == NativeFlow.IMPORT_CODE.wireName ||
            route == NativeFlow.IMPORT_SCAN.wireName

    /**
     * Retained typed reasons for the canonical encrypted-backup flows. Both `backupSetup` and
     * `backupRestore` are now handled by real owner-backed paths in the bridge (the setup dialog
     * and the reviewed restore-apply owner), so this function is no longer on the dispatch path;
     * it remains as an explicit fallback that names the exact feature rather than the generic
     * unknown-flow rejection. No recovery material or restore-review handle of any kind crosses
     * the bridge; only the reason code does.
     */
    fun backupPresentationUnavailable(route: String): String? = when (route) {
        NativeFlow.BACKUP_SETUP.wireName -> "backup-setup-unavailable"
        NativeFlow.BACKUP_RESTORE.wireName -> "backup-restore-unavailable"
        else -> null
    }

    /**
     * The Android-only reviewed-import confirmation flow. The canonical iOS inventory has no
     * `native.flow` apply route because iOS presents its own import confirmation; Android has no
     * native importer, so the shared RN chooser confirms a reviewed code through this route. It
     * carries the reviewed target (absent = add-as-new) and never a payload the review did not
     * produce.
     */
    const val IMPORT_APPLY = "importApply"
}

internal fun GuardPrimaryAction.wireCode(): String = when (this) {
    GuardPrimaryAction.TurnOn -> "turnOn"
    GuardPrimaryAction.TurnOff -> "turnOff"
    GuardPrimaryAction.Resume -> "resume"
    GuardPrimaryAction.Reconnect -> "reconnect"
    is GuardPrimaryAction.OpenSettings -> "settings"
}

@Suppress("UNUSED_PARAMETER")
internal fun MainProductUiState.isLocked(surface: ProtectedSurface, now: Long): Boolean =
    security?.isAuthorized(surface) != true

/** Only confirmed owner facts cross the bridge. Locked/background evidence is omitted at source. */
internal fun productSnapshot(
    snapshotState: MainProductUiState,
    revision: Long,
    foreground: Boolean,
    now: Long,
    plus: com.lavasec.app.billing.NativePlusState? = null,
    purchases: com.lavasec.app.billing.PlayPurchaseSnapshot? = null,
    plusPresentation: com.lavasec.app.billing.NativePlusPresentation? = null,
    sudoku: SudokuGame? = null,
    dnsCustomDraft: CustomResolverDraftSession? = null,
    dnsCustomToken: String? = null,
    dnsCustomSaved: CustomResolverDraft = CustomResolverDraft(),
    backup: BackupSnapshot? = null,
    libraryEditing: FilterLibraryEditSession? = null,
    catalog: com.lavasec.app.data.CatalogSyncSnapshot? = null,
    preferences: ProductPreferences = ProductPreferences.canonicalDefaults(),
    notifications: NotificationPreferences? = null,
    locale: String = "en",
    savedFilters: List<com.lavasec.app.foundation.filter.FilterProfileDraft> = emptyList(),
    account: AccountPresentation? = null,
    vpn: VpnFamilySnapshot? = null,
    vpnRuntime: VpnRuntimeRotationWitness? = null,
    qaTools: Boolean = SystemCapabilityPolicy.isAvailable(SystemCapability.DEVICE_QA, BuildConfig.DEBUG),
    guardProgress: com.lavasec.app.data.GuardianProgressState? = null,
    domainCheckRevision: String? = null,
): String {
    val appLocked = !foreground || snapshotState.isLocked(ProtectedSurface.APP_UNLOCK, now)
    // Redact at the native boundary as well as covering the rendered React root.
    val state = if (appLocked) MainProductUiState(
        graphGeneration = snapshotState.graphGeneration, security = snapshotState.security,
    ) else snapshotState
    val available = state.productAvailable && state.onboarding?.completed == true
    val filtersLocked = appLocked || state.isLocked(ProtectedSurface.FILTERS, now)
    val activityLocked = appLocked || state.isLocked(ProtectedSurface.ACTIVITY, now)
    val guard = state.protection?.guard
    val protectionLifecycle = state.protection?.lifecycle
    val overview = if (!activityLocked) {
        (state.activity.overview as? ActivityContentState.Ready)?.value
    } else null
    return JSONObject().apply {
        put("schema", 1)
        put("revision", revision)
        put("generation", state.graphGeneration?.toString() ?: JSONObject.NULL)
        put("available", available)
        put("domainCheckAvailable", available && domainCheckRevision != null)
        if (!filtersLocked && domainCheckRevision != null) put("domainCheckRevision", domainCheckRevision)
        // A live first-run graph can open setup before any configuration exists.
        // This grants the shared bootstrap page, not fabricated configured content.
        put("onboardingAvailable", !appLocked && state.graphGeneration != null && state.onboarding?.completed == false)
        put("onboardingRecoveryRequired", !appLocked && state.onboarding?.reconstructionRequired == true)
        put("onboardingReconstructionInFlight", !appLocked && state.onboardingReconstructionInFlight)
        put("onboardingRequired", state.onboarding?.completed == false)
        put("foreground", foreground)
        put("version", BuildConfig.VERSION_NAME)
        put("build", BuildConfig.VERSION_CODE.toString())
        // F21: the QA/debug capability the RN route gate reads. It is the native
        // capability table's `deviceQA` disposition (DEBUG_ONLY), so a release build
        // reports false and no QA route can be reached from JS. Derived, never a
        // hand-set constant.
        put("qaTools", qaTools)
        put("plus", if (appLocked) plusSnapshot(null, null, null) else plusSnapshot(plus, purchases, plusPresentation))
        put("limits", limitsSnapshot(plus?.limits ?: state.featureLimits))
        put("protection", JSONObject().apply {
            put("chainedEstablishing", available && foreground &&
                nativeChainedEstablishing(vpn?.runtimeWitness, vpnRuntime, guard?.lifecycle))
            put("lifecycle", guard?.lifecycle?.name?.lowercase(Locale.ROOT) ?: "unavailable")
            put("connectivity", guard?.connectivity?.name?.lowercase(Locale.ROOT) ?: "unavailable")
            put("action", guard?.primaryAction?.wireCode() ?: JSONObject.NULL)
            put("disabled", !available || !foreground || state.protection?.commandInFlight != false ||
                state.protection?.reconstructionRequired == true)
            put("commandInFlight", available && foreground && state.protection?.commandInFlight == true)
            put("canPause", available && foreground && guard?.canPause == true)
            put("pauseUntil", guard?.pause?.deadlineEpochMillis ?: JSONObject.NULL)
            put("message", guard?.message?.name?.lowercase(Locale.ROOT) ?: JSONObject.NULL)
            put("error", state.protection?.error ?: JSONObject.NULL)
            // Canonical requested-vs-confirmed + first-run preparation (B18). The requested bit is
            // explicit intent; the confirmed bit is the platform observation (or its armed
            // projection). They are allowed to disagree, so neither is derived from the other.
            // A missing lifecycle is reported as null/`unavailable`, never a fabricated value.
            put("requested", protectionLifecycle?.isRequestedEnabled ?: JSONObject.NULL)
            put("confirmed", protectionLifecycle?.isConfirmedEnabled ?: JSONObject.NULL)
            put(
                "onboarding",
                protectionLifecycle?.onboarding?.snapshotWire()
                    ?: state.protectionOnboardingPhase?.snapshotWire()
                    ?: "unavailable",
            )
            put("recovery", protectionLifecycle?.recovery?.action?.snapshotWire() ?: JSONObject.NULL)
        })
        put("filters", JSONObject().apply {
            put("locked", filtersLocked)
            put("available", available && !filtersLocked && state.filterLibrary != null)
            val total = state.filterCompiledRuleCount.takeIf { available && !filtersLocked }
            val maximum = (plus?.limits ?: state.featureLimits).maxFilterRules
            val over = total?.let { it > maximum }
            put("budget", JSONObject().apply {
                put("compiledTotal", total ?: JSONObject.NULL)
                put("maximum", maximum)
                put("overBudget", over ?: JSONObject.NULL)
                put("status", when (over) { true -> "tier-rule-limit"; false -> "within-limit"; null -> "unavailable" })
            })
            put("rows", JSONArray().apply {
                if (available && !filtersLocked) state.filterLibrary?.filters?.forEach { filter ->
                    val staged = libraryEditing?.takeIf { it.isEditing }?.displayedFilters?.firstOrNull { it.id == filter.id }
                    put(JSONObject().apply {
                        put("id", filter.id)
                        put("name", staged?.name ?: filter.name)
                        put("active", filter.active)
                        put("frozen", filter.frozen)
                        put("ruleCount", filter.activeRuleCount ?: JSONObject.NULL)
                        put("empty", filter.blocksNothing)
                        savedFilters.firstOrNull { it.id == filter.id }?.let { saved ->
                            put("emoji", staged?.emoji ?: saved.emoji)
                            put("lists", JSONArray(saved.enabledSourceIds.toList()))
                            put("blockedDomainCount", saved.manualRules.blockedDomains.size)
                            put("allowedExceptionCount", saved.manualRules.allowedDomains.size)
                        }
                    })
                }
            })
        })
        // This identity is editor-only: no Guard, library, share or backup consumer sees it as saved.
        // Omit it once the id is already a saved row: the library commit publishes the profile (rows)
        // before the owner clears `newFilter`, and an intermediate emission carrying both would make
        // the shared parser reject the snapshot ("New filter is already saved") and crash the app.
        state.newFilter?.takeIf { created ->
            available && !filtersLocked && state.filterLibrary?.filters?.none { it.id == created.id } != false
        }?.let { created ->
            put("newFilter", JSONObject().apply {
                put("id", created.id)
                put("name", created.name)
                put("emoji", created.emoji)
                put("lists", JSONArray(created.enabledSourceIds.toList()))
                put("empty", created.enabledSourceIds.isEmpty() && created.manualRules.blockedDomains.isEmpty() &&
                    created.manualRules.allowedDomains.isEmpty())
                put("blockedDomainCount", created.manualRules.blockedDomains.size)
                put("allowedExceptionCount", created.manualRules.allowedDomains.size)
            })
        }
        // Canonical `libraryEditing` family, projected only from the live whole-library edit
        // session owned by FilterLibraryOwner. Omitted while the session is unavailable or the
        // surface is locked, so JS never renders a staged set the owner cannot honor.
        libraryEditing
            ?.takeIf { available && !filtersLocked }
            ?.let { session ->
                put("libraryEditing", JSONObject().apply {
                    put("active", session.isEditing)
                    put("hasChanges", session.hasChanges)
                    put("deletions", JSONArray().apply {
                        session.stagedDeletions.sorted().forEach { put(it) }
                    })
                })
            }
        // The saved baseline and current draft remain distinct. Removed rows stay visible with
        // pending/undo affordances until the native owner saves or discards the draft.
        state.filterDetail
            ?.takeIf { available && !filtersLocked }
            ?.let { detail ->
                val review = state.filterReview?.takeIf { it.filterId == detail.filterId }
                val saved = savedFilters.firstOrNull { it.id == detail.filterId }
                    ?: state.newFilter?.takeIf { it.id == detail.filterId }
                if (saved != null) put("filterSession", JSONObject().apply {
                    put("id", detail.filterId)
                    put("name", detail.name)
                    put("editing", detail.editing)
                    put("lists", JSONArray(detail.sources.filter { it.selected }.map { it.id }))
                    put("savedLists", JSONArray(saved.enabledSourceIds.toList()))
                    put("blocked", JSONArray(detail.blockedDomains))
                    put("allowed", JSONArray(detail.allowedDomains))
                    put("savedBlocked", JSONArray(saved.manualRules.blockedDomains.toList()))
                    put("savedAllowed", JSONArray(saved.manualRules.allowedDomains.toList()))
                    put("names", JSONObject().apply { detail.sources.forEach { put(it.id, it.name) } })
                    put("ruleCounts", JSONObject().apply { detail.sources.forEach { source ->
                        source.ruleCount?.takeIf { it >= 0 }?.let { put(source.id, it) }
                    } })
                    put("customLists", JSONArray(detail.sources.filter { it.category == com.lavasec.app.foundation.filter.FilterSourceCategory.CUSTOM }.map { it.id }))
                })
                put("filterEditing", JSONObject().apply {
                    put("canSave", detail.draftHasChanges && review?.rejection == null)
                    put("reviewCanConfirm", review != null && review.rejection == null)
                    put("validation", review?.rejection?.name?.lowercase(Locale.ROOT) ?: JSONObject.NULL)
                    // Canonical `filterEditing.upgradeReason`: only a native typed capacity refusal
                    // offers a Plus upgrade. Safety/read/authentication refusals stay empty.
                    put("upgradeReason", filterDraftUpgradeReason(review?.rejection, plus?.localFeaturesAvailable == true))
                    // The catalog sync owner is the real in-flight refresh fact; `false` only when
                    // no sync owner is attached (the shared "Refresh now" control stays enabled).
                    put("refreshing", catalog?.refreshing == true)
                    fun rows(ids: List<String>, baseline: List<String>) = JSONArray().apply {
                        (ids + if (detail.editing) baseline else emptyList()).distinct().forEach { id ->
                            put(JSONObject().put("id", id).put("pending", id !in ids)
                                .put("undo", detail.editing && ((id in ids) != (id in baseline))))
                        }
                    }
                    put("lists", rows(detail.sources.filter { it.selected }.map { it.id }, saved?.enabledSourceIds?.toList().orEmpty()))
                    put("blocked", rows(detail.blockedDomains, saved?.manualRules?.blockedDomains?.toList().orEmpty()))
                    put("allowed", rows(detail.allowedDomains, saved?.manualRules?.allowedDomains?.toList().orEmpty()))
                })
            }
        put("activity", JSONObject().apply {
            put("locked", activityLocked)
            put("status", when {
                activityLocked -> "locked"
                state.activity.overview is ActivityContentState.Loading -> "loading"
                state.activity.overview is ActivityContentState.Failed -> "failed"
                overview != null -> "ready"
                else -> "unavailable"
            })
            // Decimal strings preserve Long counters exactly through JS.
            put("allowed", overview?.counts?.allowed?.toString() ?: JSONObject.NULL)
            put("blocked", overview?.counts?.blocked?.toString() ?: JSONObject.NULL)
            put("startDate", overview?.dateRange?.start?.toString() ?: JSONObject.NULL)
            put("endDate", overview?.dateRange?.endInclusive?.toString() ?: JSONObject.NULL)
            // Use the owner's calendar zone, never the JS device clock or a historical range label.
            put("todayDate", overview?.dateRange?.let {
                java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.of(it.zoneId)).toLocalDate().toString()
            } ?: JSONObject.NULL)
        })
        // Canonical Domain History availability. `true` only when the owner confirms retained
        // rows; the retention toggle alone is not evidence, and the pre-owner window is null.
        val retainedDomainHistory = when (val retained = state.activity.retainedDomainLogs) {
            is ActivityContentState.Ready -> retained.value
            is ActivityContentState.Loading -> retained.lastGood
            is ActivityContentState.Failed -> retained.lastGood
            ActivityContentState.Disabled,
            ActivityContentState.Unavailable,
            -> null
        }
        put("hasDomainHistory", retainedDomainHistory ?: JSONObject.NULL)
        // The number of rows currently loaded into the shareable Domain History snapshot, used by
        // the shared screen only to restart its own paging window. Null until the first read.
        put(
            "domainHistoryCount",
            (state.activity.domainHistory as? ActivityContentState.Ready)?.value?.rows?.size
                ?: JSONObject.NULL,
        )
        put("settings", JSONObject().apply {
            put("resolver", if (available) state.resolverPlan?.name ?: "unavailable" else "unavailable")
            put("securityEnabled", state.security?.enabled == true)
        })
        // Canonical `security` family, projected only while a live SecurityOwner exists. The owner
        // owns the passcode and biometric authority and the protected-surface set; no credential
        // material, verifier, prompt handle or biometric result ever reaches this family. The
        // biometric fields are the owner's real port facts, so a build with no registered
        // authenticator reports genuine unavailability and the shared screen hides the row.
        state.security?.let { put("security", securitySnapshotJson(it)) }
        // Canonical `presentation` family: the app locale plus the effective text scale.
        // `textScales` is null exactly when the retained preference follows the system size
        // (matching iOS `PresentationSnapshot`), otherwise the confirmed override for every role.
        put("presentation", presentationSnapshot(locale, preferences))
        put("preferences", JSONObject().apply {
            put("appearance", preferences.appearance.wire)
            put("look", preferences.look.wire)
            put("matchTextSize", preferences.matchTextSize)
            put("textSize", preferences.textSize.index)
            put("haptics", preferences.haptics)
        })
        if (!appLocked && guardProgress?.available == true) put("guardProgress", JSONObject().apply {
            put("unlockedIDs", JSONArray(guardProgress.ledger.records.map { it.guardID }))
            put("qualifiedUsageDays", if (state.privacy?.logging?.keepLavaGuardProgress == true)
                guardProgress.qualifiedUsageDays else 0)
        })
        // Canonical `account` snapshot family, projected only while the process account owner is
        // installed. The family carries no token/user id/email: status text, the linked provider
        // identifiers Supabase reported, provider capability and the transient command state. An
        // absent owner omits the family, so JS never fabricates a signed-in or linked provider.
        account?.let { put("account", accountSnapshotJson(it)) }
        // Canonical per-category notification toggles, keyed by the user-facing category label the
        // shared customization screen renders. Projected only from a live owner; omitted otherwise,
        // so the screen never shows a fabricated notification preference.
        notifications?.let { prefs ->
            put("notifications", JSONObject().apply {
                NotificationCategory.entries.forEach { category ->
                    put(category.preferenceKey.removePrefix("notifications."), prefs.isEnabled(category))
                }
            })
        }
        // Canonical `settingsSummary` family, projected only for the fields Android actually
        // owns (confirmed resolver selection, retained log policy, security state). A field
        // whose owner is absent is omitted rather than fabricated.
        settingsSummarySnapshot(state)?.let { put("settingsSummary", it) }
        // Canonical `dns` snapshot family (JS `DNSChoice` / `AppSnapshot['dns']`). Only the
        // CONFIRMED selection is projected as `tiers`; the custom editor draft is the
        // REQUESTED value and is carried separately (`customDraft`/`customDraftToken`). The
        // family is omitted entirely when the product cannot supply a selection.
        state.resolverSelection?.takeIf { available }?.let { selection ->
            put("dns", dnsSnapshot(state.chainedDnsEdit, selection, dnsCustomDraft, dnsCustomToken, dnsCustomSaved))
        }
        // Canonical `vpn` snapshot family. Android's authority is a SINGLE ROTATION, so the canonical
        // multi-profile editor is projected onto at most one row (see `ChainedConfigurationCommandOwner`
        // and decisions.md 2026-09-29). Omitted entirely with no live composition, so the shared screen
        // renders honest-unavailable rather than a fabricated chained upstream.
        vpn?.takeIf { !appLocked }?.let { family -> put("vpn", vpnSnapshotJson(family)) }
        // Canonical `connection` family (Explore / Settings connection overview). A read-only
        // projection from owners that already exist — the active filter, the confirmed resolver
        // selection and the live chained status. Omitted when no resolver selection is confirmed;
        // never a fabricated connection.
        if (available && !appLocked) {
            connectionSnapshot(state, vpn)?.let { put("connection", it) }
        }
        // Canonical `sudoku` snapshot family (JS `SudokuGame`): the resumable durable game, or
        // omitted entirely when none has been persisted. Never a fabricated empty board.
        if (foreground && available) {
            state.privacy?.logging?.takeIf { !state.privacy.inFlight }?.let { logging ->
                put("logging", JSONObject().apply {
                    put("keepCounts", logging.keepCounts)
                    put("keepHistory", logging.keepHistory)
                    put("keepNetworkActivity", logging.keepNetworkActivity)
                    put("keepLavaGuardProgress", logging.keepLavaGuardProgress)
                })
            }
            if (state.canRetainSudoku()) sudoku?.let { put("sudoku", it.toWireJson()) }
        }
        put("backup", backupSnapshotJson(if (appLocked) BackupSnapshot.UNAVAILABLE else backup ?: BackupSnapshot.UNAVAILABLE))
        // Canonical `catalog` sync metadata. Omitted entirely when no sync owner exists, so the
        // remote-catalog surface is never invented; the bundled anchor is the effective catalog.
        catalog?.let { put("catalog", catalogSnapshotJson(it)) }
    }.toString()
}

/** Stable first-run preparation phase code (wire contract); never localized copy. */
internal fun OnboardingPhase.snapshotWire(): String = when (this) {
    OnboardingPhase.FRESH_INSTALL -> "freshInstall"
    OnboardingPhase.PREPARING_DEFAULT_FILTER -> "preparingDefaultFilter"
    OnboardingPhase.READY -> "ready"
    OnboardingPhase.PREPARATION_FAILED -> "preparationFailed"
}

/** Stable recovery-action code (wire contract); the surface maps it to its own copy. */
internal fun ProtectionRecoveryAction.snapshotWire(): String = when (this) {
    ProtectionRecoveryAction.RETRY_DEFAULT_FILTER_PREPARATION -> "retryDefaultFilterPreparation"
    ProtectionRecoveryAction.RETRY_START -> "retryStart"
    ProtectionRecoveryAction.RETRY_STOP -> "retryStop"
    ProtectionRecoveryAction.RETRY_PAUSE -> "retryPause"
    ProtectionRecoveryAction.RETRY_RESUME -> "retryResume"
}

/** Canonical `catalog` snapshot family. Provenance and status are stable codes, not localized copy. */
internal fun catalogSnapshotJson(catalog: com.lavasec.app.data.CatalogSyncSnapshot): JSONObject =
    JSONObject().apply {
        put("installed", catalogProvenanceWire(catalog.provenance))
        put("version", catalog.catalogVersion)
        put("generatedAt", catalog.generatedAtEpochSeconds)
        put("sourceCount", catalog.sourceCount)
        put("guardrailCount", catalog.guardrailCount)
        put("fresh", catalog.fresh)
        put("sourceRepairRequired", catalog.sourceRepairRequired)
        put("ageSeconds", catalog.ageSeconds ?: JSONObject.NULL)
        put("refreshing", catalog.refreshing)
        put("lastStatus", catalog.lastStatus?.let(::catalogStatusWire) ?: JSONObject.NULL)
    }

private fun catalogProvenanceWire(provenance: com.lavasec.app.foundation.catalog.CatalogProvenance): String =
    when (provenance) {
        com.lavasec.app.foundation.catalog.CatalogProvenance.BUNDLED -> "bundled"
        com.lavasec.app.foundation.catalog.CatalogProvenance.LAST_KNOWN_GOOD -> "lastKnownGood"
        com.lavasec.app.foundation.catalog.CatalogProvenance.REMOTE -> "remote"
    }

/**
 * Canonical `catalog.query` result (`ReactNative/review/FilterScreens.tsx` `CatalogResponse`). Every
 * section/source is a real catalog entry; `count`/`fraction` are the honest unknown (the picker bar
 * is `indeterminate` because Android has no per-source rule compile). No `metadata` is emitted when
 * the catalog does not carry a rule count.
 */
internal fun catalogQueryJson(result: CatalogQueryResult): JSONObject = JSONObject().apply {
    put("sections", JSONArray().apply {
        result.sections.forEach { section ->
            put(JSONObject().apply {
                put("title", section.title)
                if (section.isCustom) put("isCustom", true)
                put("sources", JSONArray().apply {
                    section.sources.forEach { source ->
                        put(JSONObject().apply {
                            put("id", source.id)
                            put("name", source.name)
                            put("licenseName", source.licenseName)
                            put("sourceURL", source.sourceUrl)
                            source.metadata?.let { put("metadata", it) }
                        })
                    }
                })
            })
        }
    })
    put("count", result.count)
    put("budget", result.budget)
    put("pending", result.pending)
    put("exceeded", result.exceeded)
    put("summary", result.summary)
    put("fraction", result.fraction)
    put("indeterminate", result.indeterminate)
    put("atOrOverBudget", result.atOrOverBudget)
}

private fun catalogStatusWire(status: com.lavasec.app.data.CatalogRefreshStatus): String =
    when (status) {
        com.lavasec.app.data.CatalogRefreshStatus.UPDATED -> "updated"
        com.lavasec.app.data.CatalogRefreshStatus.UNCHANGED -> "unchanged"
        com.lavasec.app.data.CatalogRefreshStatus.KEPT_INSTALLED -> "keptInstalled"
        com.lavasec.app.data.CatalogRefreshStatus.BUNDLED_FALLBACK -> "bundledFallback"
        com.lavasec.app.data.CatalogRefreshStatus.OFFLINE -> "offline"
        com.lavasec.app.data.CatalogRefreshStatus.PERSIST_FAILED -> "persistFailed"
        com.lavasec.app.data.CatalogRefreshStatus.INSTALL_FAILED -> "installFailed"
        com.lavasec.app.data.CatalogRefreshStatus.SUPERSEDED -> "superseded"
    }

/**
 * Canonical `account` snapshot family. Every field is either owner-confirmed (signed-in state,
 * linked providers, capability) or the bridge's transient command state; no credential,
 * access/refresh token, user id or email is ever serialized.
 */
private fun accountSnapshotJson(account: AccountPresentation): JSONObject = JSONObject().apply {
    put("signedIn", account.signedIn)
    put("status", account.status)
    put("detail", account.detail)
    put("appleTitle", account.appleTitle)
    put("googleTitle", account.googleTitle)
    put("appleConnected", account.appleConnected)
    put("googleConnected", account.googleConnected)
    put("appleBusy", account.appleBusy)
    put("googleBusy", account.googleBusy)
    put("busy", account.busy)
    put("message", account.message)
    put("googleAvailable", account.googleAvailable)
    put("appleAvailable", account.appleAvailable)
}

/** Canonical `dns` snapshot family. Confirmed selection and requested draft stay distinct. */
private fun dnsSnapshot(
    chained: ChainedDnsEditState,
    selection: ResolverSelection,
    draft: CustomResolverDraftSession?,
    token: String?,
    saved: CustomResolverDraft,
): JSONObject = JSONObject().apply {
    put("editable", chained.canEditDns)
    put("transportDetail", DNS_TRANSPORT_DETAIL)
    put("deviceDetail", ResolverCatalog.deviceDns.privacySummary)
    put("fallbackDetail", DNS_FALLBACK_DETAIL)
    // A custom resolver may occupy either rung (primary or the encrypted fallback), so the
    // snapshot scans the whole retained ladder rather than only the active primary.
    val customTier = selection.tiers.firstOrNull { it.entry.providerId == ResolverProviderId.CUSTOM }
    val customEntry = customTier?.entry
    put("customSelected", customEntry != null)
    // A confirmed custom selection is servable, so its capability checks carry its normalized
    // endpoint (a DoH URL or a DoT host:port); every built-in selection passes null (unchanged).
    val selectedCustomDohUrl = customEntry?.customDohUrl
    val selectedCustomDotEndpoint = customEntry?.customDotEndpoint
    val selectedCustomDoqEndpoint = customEntry?.customDoqEndpoint
    val selectedCustomPlainEndpoint = customEntry?.customPlainEndpoint
    val customDohFor: (ResolverProvider) -> String? = { provider ->
        if (provider.id == ResolverProviderId.CUSTOM) selectedCustomDohUrl else null
    }
    val customDotFor: (ResolverProvider) -> String? = { provider ->
        if (provider.id == ResolverProviderId.CUSTOM) selectedCustomDotEndpoint else null
    }
    val customDoqFor: (ResolverProvider) -> String? = { provider ->
        if (provider.id == ResolverProviderId.CUSTOM) selectedCustomDoqEndpoint else null
    }
    put("transports", JSONArray().apply {
        selection.primary.provider.supportedTransports
            .filter {
                ResolverCatalog.capability(
                    selection.primary.provider,
                    it,
                    selectedCustomDohUrl,
                    selectedCustomDotEndpoint,
                    selectedCustomDoqEndpoint,
                    selectedCustomPlainEndpoint,
                ).available
            }
            .sortedBy(CatalogResolverTransport.entries::indexOf)
            .forEach { put(it.token) }
    })
    put("providers", JSONArray().apply {
        ResolverCatalog.providers
            .filter { it.id != ResolverProviderId.DEVICE_DNS &&
                it.supportedTransports.any { transport ->
                    ResolverCatalog.capability(
                        it,
                        transport,
                        customDohFor(it),
                        customDotFor(it),
                        customDoqFor(it),
                        if (it.id == ResolverProviderId.CUSTOM) selectedCustomPlainEndpoint else null,
                    ).available
                } }
            .forEach { provider ->
                put(JSONObject().apply {
                    put("id", provider.id.rawId)
                    put("name", provider.displayName)
                    put("address", providerAddress(provider))
                    put(
                        "selected",
                        selection.tiers.any { it.entry.providerId == provider.id },
                    )
                })
            }
    })
    put("choices", JSONArray().apply {
        ResolverCatalog.providers.forEach { provider ->
            provider.supportedTransports
                .filter {
                    ResolverCatalog.capability(
                        provider,
                        it,
                        customDohFor(provider),
                        customDotFor(provider),
                        customDoqFor(provider),
                        if (provider.id == ResolverProviderId.CUSTOM) selectedCustomPlainEndpoint else null,
                    ).available
                }
                .sortedBy(CatalogResolverTransport.entries::indexOf)
                .forEach { transport -> put(if (provider.id == ResolverProviderId.CUSTOM) dnsChoiceFor(
                    provider, transport, name = customTier?.retainedName ?: customEntry?.customName ?: "",
                    nameIsDefault = customTier?.retainedName == null && customEntry?.customName.isNullOrBlank(),
                    primary = selectedCustomDohUrl ?: selectedCustomDotEndpoint ?: selectedCustomDoqEndpoint ?: selectedCustomPlainEndpoint ?: "",
                    secondary = customEntry?.customSecondaryEndpoint ?: "",
                ) else dnsChoiceFor(provider, transport)) }
        }
    })
    put("tiers", JSONArray().apply {
        // The committed ladder projects every retained row in order, matching the shared
        // ordered-tier editor (iOS `DNSChoice.isEnabled`): a disabled row keeps its identity and
        // position, so a later toggle restores the exact same resolver instead of collapsing to a
        // single boolean. The active projection is recomputed by the owner from the enabled rows.
        selection.tiers.forEach { tier ->
            put(
                dnsChoiceFor(
                    provider = tier.entry.provider,
                    transport = tier.entry.transport,
                    enabled = tier.isEnabled,
                    // The screen supplies a generic label for an unnamed custom resolver.
                    // Keep its editable value empty so saving another row cannot persist that label.
                    nameIsDefault = tier.retainedName == null && tier.entry.customName.isNullOrBlank(),
                    name = if (tier.entry.providerId == ResolverProviderId.CUSTOM)
                        tier.retainedName ?: tier.entry.customName ?: "" else tier.displayName,
                    primary = tier.entry.customDohUrl ?: tier.entry.customDotEndpoint ?: tier.entry.customDoqEndpoint ?: tier.entry.customPlainEndpoint ?: "",
                    secondary = tier.entry.customSecondaryEndpoint ?: "",
                ),
            )
        }
    })
    put("tiersContext", ResolverDnsContext.encode(selection))
    put(
        "customDraft",
        draft?.let { session ->
            dnsChoice(
                providerId = ResolverProviderId.CUSTOM.rawId,
                transportToken = customTransportToken(session.draft),
                name = session.draft.name,
                nameIsDefault = session.draft.name.isBlank(),
                primary = session.draft.primaryAddress,
                secondary = session.draft.secondaryAddress,
                metadata = ResolverCatalog.custom.privacySummary,
            )
        } ?: JSONObject.NULL,
    )
    put("customDraftToken", token ?: "")
    put("custom", JSONObject().apply {
        put("name", saved.name)
        put("primary", saved.primaryAddress)
        put("secondary", saved.secondaryAddress)
        // A custom endpoint is servable only when its canonical value matches the
        // selected encrypted transport or plain IP-literal route.
        val customEndpointSelected = selection.primary.providerId == ResolverProviderId.CUSTOM &&
            selection.primary.transport in setOf(
                CatalogResolverTransport.DOH,
                CatalogResolverTransport.DOT,
                CatalogResolverTransport.DOQ,
                CatalogResolverTransport.PLAIN_DNS,
            ) &&
            (selection.primary.customDohUrl != null || selection.primary.customDotEndpoint != null || selection.primary.customDoqEndpoint != null || selection.primary.customPlainEndpoint != null)
        put(
            "valid",
            customEndpointSelected ||
                CustomResolverValidator.normalizedDohUrl(saved.trimmedPrimary) != null ||
                CustomResolverValidator.normalizedDotEndpoint(saved.trimmedPrimary) != null ||
                CustomResolverValidator.normalizedDoqEndpoint(saved.trimmedPrimary) != null ||
                CustomResolverValidator.normalizedPlainEndpoint(saved.trimmedPrimary) != null,
        )
        put("metadata", ResolverCatalog.custom.privacySummary)
        put("context", ResolverDnsContext.encodeCustom(selection, saved))
    })
}

private fun dnsChoiceFor(
    provider: ResolverProvider,
    transport: CatalogResolverTransport,
    enabled: Boolean = true,
    name: String = provider.displayName,
    nameIsDefault: Boolean = provider.id != ResolverProviderId.CUSTOM,
    primary: String = "",
    secondary: String = "",
): JSONObject =
    dnsChoice(
        providerId = provider.id.rawId,
        transportToken = transport.token,
        name = name,
        nameIsDefault = nameIsDefault,
        primary = primary,
        secondary = secondary,
        metadata = providerMetadata(provider, transport),
        enabled = enabled,
    )

private fun dnsChoice(
    providerId: String,
    transportToken: String,
    name: String,
    primary: String,
    secondary: String,
    metadata: String,
    nameIsDefault: Boolean = false,
    enabled: Boolean = true,
): JSONObject = JSONObject().apply {
    put("id", ResolverDnsWire.encode(providerId, transportToken))
    put("name", name)
    put("nameIsDefault", nameIsDefault)
    put("primary", primary)
    put("secondary", secondary)
    put("transport", transportToken)
    put("metadata", metadata)
    put("isEnabled", enabled)
}

private fun providerAddress(provider: ResolverProvider): String {
    val transport = provider.supportedTransports
        .minByOrNull(CatalogResolverTransport.entries::indexOf)
        ?: return ""
    return providerMetadata(provider, transport)
}

private fun providerMetadata(provider: ResolverProvider, transport: CatalogResolverTransport): String {
    if (provider.id == ResolverProviderId.DEVICE_DNS) return "Device DNS"
    if (provider.id == ResolverProviderId.CUSTOM) return ResolverCatalog.custom.privacySummary
    return when (transport) {
        CatalogResolverTransport.PLAIN_DNS -> provider.endpoints
            .filterIsInstance<ResolverEndpoint.Addresses>()
            .flatMap { it.ipv4 + it.ipv6 }
            .joinToString(", ")
        CatalogResolverTransport.DOH -> provider.endpoints
            .filterIsInstance<ResolverEndpoint.Doh>()
            .joinToString(", ") { it.url }
        CatalogResolverTransport.DOT,
        CatalogResolverTransport.DOQ,
        -> provider.endpoints
            .filterIsInstance<ResolverEndpoint.HostPort>()
            .joinToString(", ") { "${it.hostname}:${it.port}" }
    }
}

/**
 * Canonical `connection` snapshot family, projected read-only from existing owners: the active
 * filter, the confirmed resolver selection (`dns`) and the live chained status (`vpn`). Device DNS
 * carries no endpoint/protocol detail, matching iOS. `vpn.eligible` is the owner's real enable gate;
 * an absent composition is honestly ineligible rather than a fabricated chained upstream.
 */
private fun connectionSnapshot(state: MainProductUiState, vpn: VpnFamilySnapshot?): JSONObject? {
    val selection = state.resolverSelection ?: return null
    return JSONObject().apply {
        state.filterLibrary?.filters?.firstOrNull { it.active }?.let { active ->
            put("filter", JSONObject().apply {
                put("id", active.id)
                put("name", active.name)
                put("count", active.activeRuleCount?.toString() ?: "")
            })
        }
        put("dns", JSONObject().apply {
            // `usesWireGuard` means chaining owns the tunnel's DNS (iOS `presentation.usesWireGuard`),
            // not merely that a configuration is stored.
            put("usesWireGuard", state.chainedDnsEdit.usesWireGuard)
            put("editable", state.chainedDnsEdit.canEditDns)
            put("primary", resolverConnectionPart(selection.activeTiers.first()))
            put("fallback", selection.activeTiers.getOrNull(1)?.let(::resolverConnectionPart) ?: JSONObject.NULL)
        })
        put("vpn", JSONObject().apply {
            put("eligible", vpn?.canEnable == true)
            put("enabled", vpn?.enabled == true)
            put("fallbackEnabled", vpn?.fallback ?: JSONObject.NULL)
            put("configured", vpn?.setup == true)
            vpn?.rows?.firstOrNull()?.name?.takeIf { it.isNotBlank() }?.let { put("name", it) }
            vpnStatusLabel(vpn)?.let { put("statusLabel", it) }
        })
    }
}

/** One canonical `connection.dns` resolver part; Device DNS has no chosen endpoint or transport. */
private fun resolverConnectionPart(
    tier: com.lavasec.app.foundation.resolver.ResolverTier,
): JSONObject {
    val slot = tier.entry
    val deviceDns = slot.providerId == ResolverProviderId.DEVICE_DNS
    return JSONObject().apply {
        put("name", resolverTierDisplayName(tier))
        put("nameIsDefault", tier.retainedName == null && slot.customName.isNullOrBlank())
        val customPrimary = slot.customDohUrl ?: slot.customDotEndpoint ?: slot.customDoqEndpoint ?: slot.customPlainEndpoint
        put("detail", if (deviceDns) "" else customPrimary?.let { primary ->
            listOfNotNull(primary, slot.customSecondaryEndpoint).joinToString(", ")
        } ?: providerMetadata(slot.provider, slot.transport))
        put("transport", if (deviceDns) "" else slot.transport.token)
    }
}

/** A stable connection status label from the live chained facts; null when unavailable. */
private fun vpnStatusLabel(vpn: VpnFamilySnapshot?): String? = when {
    vpn == null || vpn.unavailable -> null
    !vpn.setup -> "Not configured"
    vpn.needsRepair -> "Repair needed"
    vpn.enabled -> "Enabled"
    else -> "Disabled"
}

private fun customTransportToken(draft: CustomResolverDraft): String =
    CustomResolverValidator.parse(draft.trimmedPrimary)
        ?.transport
        ?.toCatalogTransport()
        ?.token
        ?: CatalogResolverTransport.DOH.token

private const val DNS_TRANSPORT_DETAIL =
    "IP uses standard DNS. DNS over HTTPS (DoH), TLS (DoT), and QUIC (DoQ) encrypt allowed lookups to the resolver."
private const val DNS_FALLBACK_DETAIL =
    "When the encrypted fallback is enabled, Lava uses it during recovery and returns to Device DNS when it is healthy."

/**
 * Canonical `presentation` family from a real owner: the locale the app is serving and the
 * text scale the retained preference requests. `textScales` is null (follow the system size)
 * when `matchTextSize` is on. The manual Android scale reaches every shared semantic ramp,
 * preserving their relative baseline sizes rather than leaving headings and row labels unchanged.
 */
private fun presentationSnapshot(locale: String, preferences: ProductPreferences): JSONObject =
    JSONObject().apply {
        put("locale", locale)
        put(
            "textScales",
            if (preferences.matchTextSize) {
                JSONObject.NULL
            } else {
                JSONObject().apply {
                    val scale = TEXT_SIZE_BODY_SCALES[preferences.textSize.index]
                    TEXT_SIZE_RAMPS.forEach { put(it, scale) }
                }
            },
        )
    }

/**
 * Canonical `settingsSummary` family, one owner-backed entry per field. A field with no live
 * owner is omitted, so JS never renders a fabricated summary for an unavailable surface.
 */
private fun settingsSummarySnapshot(state: MainProductUiState): JSONObject? {
    val summary = JSONObject()
    state.resolverSelection?.let { summary.put("dns", dnsResolutionSummary(it)) }
    state.privacy?.logging?.let { summary.put("privacy", localLogsSummary(it)) }
    state.security?.let { summary.put("security", securitySummary(it)) }
    return summary.takeIf { it.length() > 0 }
}

private fun resolverTierDisplayName(tier: com.lavasec.app.foundation.resolver.ResolverTier): String =
    tier.retainedName ?: tier.entry.customName?.takeIf { it.isNotBlank() } ?: tier.entry.provider.displayName

private fun dnsResolutionSummary(selection: ResolverSelection): String {
    val primary = resolverTierDisplayName(selection.activeTiers.first())
    val fallback = selection.activeTiers.getOrNull(1)
    return if (fallback == null) primary else "$primary + Fallback"
}

private fun localLogsSummary(logging: LoggingSettings): String {
    val enabled = buildList {
        if (logging.keepCounts) add("counts")
        if (logging.keepHistory) add("domain history")
        if (logging.keepNetworkActivity) add("network activity")
        if (logging.keepLavaGuardProgress) add("Lava Guard progress")
    }
    return when (enabled.size) {
        0 -> "All local logs off"
        4 -> "All local logs on"
        else -> enabled.joinToString(", ") { it.replaceFirstChar(Char::uppercase) } + " on"
    }
}

private fun securitySummary(security: SecurityOwnerState): String = when {
    security.availability != SecurityAvailability.Available || security.recoveryRequired ->
        "Security settings unavailable"
    security.enabled -> "Passcode on"
    else -> "Passcode off"
}

/**
 * Canonical `security` snapshot family. `unavailable` and `recoveryRequired` are the owner's
 * honest Keystore failure states, so the shared Security screen never renders a control that
 * cannot work. `protectedSurfaces` is the exact owner-committed opt-in set and
 * `authenticatedSurfaces` the current navigation-turn grants; a surface is never reported opted-in
 * when no credential exists. The biometric fields are derived from the owner's real
 * [BiometricAuthenticatorPort] facts through [BiometricAccessPolicy]: a build with no registered
 * authenticator reports genuine unavailability rather than a fabricated enrollment.
 */
private fun securitySnapshotJson(security: SecurityOwnerState): JSONObject {
    val available = security.availability == SecurityAvailability.Available && !security.recoveryRequired
    val availability = if (available) security.biometricAvailability else BiometricAvailability.Unavailable
    val settings = BiometricSettings(
        passcodeEnabled = security.enabled,
        biometricEnabled = security.biometricEnabled,
    )
    return JSONObject().apply {
        put("unavailable", !available)
        put("hasAuthenticationMethod", available && security.enabled)
        put("readRevision", security.authenticationRevision)
        put("updatingSurface", security.inFlight)
        put("recoveryRequired", security.recoveryRequired)
        put("failure", security.failure?.securityFailureWireCode() ?: JSONObject.NULL)
        put("protectedSurfaces", JSONArray(
            security.protectedSurfaces.map { it.canonicalSecurityId() }.sorted(),
        ))
        put("authenticatedSurfaces", JSONArray(
            security.authenticatedSurfaces.map { it.canonicalSecurityId() }.sorted(),
        ))
        // Keep the user's stored opt-in visible when enrollment or hardware becomes unavailable,
        // so a fresh passcode can disable it. Enabling and actual biometric authentication still
        // require the usable-key policy; the displayed preference is never authentication evidence.
        put("showBiometrics", available && (security.biometricEnabled || BiometricAccessPolicy.shouldShowToggle(availability)))
        put("canEnableBiometrics", available && BiometricAccessPolicy.canEnable(settings, availability))
        put("biometrics", available && security.biometricEnabled)
        put("biometricLockout", available && security.biometricLockout)
        put("biometricTitle", biometricTitle(availability))
        put("status", securityStatus(security, available))
    }
}

/** The canonical platform biometric name; falls back to the generic label with no usable key. */
private fun biometricTitle(availability: BiometricAvailability): String =
    when ((availability as? BiometricAvailability.Available)?.kind) {
        BiometricKind.FACE -> "Face"
        BiometricKind.FINGERPRINT -> "Fingerprint"
        BiometricKind.IRIS -> "Iris"
        BiometricKind.WEAK_CREDENTIAL, null -> "Biometrics"
    }

/** The honest status line the shared Security screen renders; empty when no note is warranted. */
private fun securityStatus(security: SecurityOwnerState, available: Boolean): String = when {
    security.recoveryRequired ->
        "Unlock this device and reopen Lava to read its security settings."
    !available -> "Security settings are unavailable on this device right now."
    else -> ""
}

/** Canonical wire id for a protected surface, the exact vocabulary the shared screen reads. */
private fun ProtectedSurface.canonicalSecurityId(): String = when (this) {
    ProtectedSurface.ACTIVITY -> "activityViewing"
    ProtectedSurface.FILTERS -> "filterEditing"
    ProtectedSurface.PROTECTION_CONTROL -> "protectionControl"
    ProtectedSurface.PROTECTION_PAUSE -> "protectionPause"
    ProtectedSurface.APP_SETTINGS -> "appSettings"
    ProtectedSurface.APP_UNLOCK -> "appUnlock"
}

/** Stable wire code for an owner security failure; the screen maps it to copy, never raw text. */
private fun SecurityFailure.securityFailureWireCode(): String = when (this) {
    SecurityFailure.WRONG_PASSCODE -> "wrongPasscode"
    SecurityFailure.SECURE_STORAGE -> "secureStorage"
    SecurityFailure.OPERATION_FAILED -> "operationFailed"
}

/** Android's single body ramp for the canonical seven-step text size (iOS body at `.large` = 1.0). */
internal val TEXT_SIZE_BODY_SCALES = doubleArrayOf(0.82, 0.88, 0.94, 1.0, 1.12, 1.23, 1.35)
// Frozen iOS LavaAppPresentation projects these UIFontMetrics styles. Android applies its
// supported seven-step override to each role; platform-specific fonts retain their own metrics.
private val TEXT_SIZE_RAMPS = listOf("body", "headline", "subheadline", "footnote", "caption1", "caption2",
    "title1", "title2", "title3", "largeTitle", "callout")

/**
 * Canonical `backup` snapshot family. The enablement block always carries a state and a nullable
 * value: a pending deletion or unreadable fence must reach JS as `null`, never as a false Off.
 */
internal fun backupSnapshotJson(backup: BackupSnapshot): JSONObject {
    val enablement = backup.enablement
    return JSONObject().apply {
        put("enablement", JSONObject().apply {
            put("state", enablement.state.wireValue)
            put("value", enablement.value ?: JSONObject.NULL)
            put("canEnable", enablement.canEnable)
            put("canDisable", enablement.canDisable)
            put("canBackUp", enablement.canBackUp)
            put("canRestore", enablement.canRestore)
            put("canChangeAutomatic", enablement.canChangeAutomatic)
            put("canRetryDeletion", enablement.canRetryDeletion)
        })
        put("configured", backup.configured)
        put("title", BACKUP_TITLE)
        put("summary", backup.detail ?: BACKUP_SUMMARY)
        put("detail", backup.detail ?: JSONObject.NULL)
        put("remoteStatus", backup.remoteStatus.wireValue)
        put("remoteAvailable", backup.remoteAvailable)
        put("remoteUploadedAt", backup.remoteUploadedAtEpochSeconds ?: JSONObject.NULL)
        put("statusUnavailable", backup.statusUnavailable)
        put("needsAttention", backup.needsAttention)
        put("deletionPending", backup.deletionPending)
        put("backingUp", backup.backingUp)
        put("busy", backup.busy)
        put("automatic", backup.automatic)
    }
}

private const val BACKUP_TITLE = "Back up your settings securely"
private const val BACKUP_SUMMARY = "Your settings are encrypted on this device before they are uploaded."

/**
 * The confirmed `vpn` snapshot family built by the bridge from the live chained composition.
 *
 * Every field is owner-confirmed; the family projects the ordered native rotation
 * and never carries configuration text or key material. [draft] is the live in-memory
 * editor draft (if any), overlaid at read time.
 */
internal data class VpnFamilySnapshot(
    val setup: Boolean,
    val enabled: Boolean,
    val canEnable: Boolean,
    val canEdit: Boolean,
    val fallback: Boolean,
    val canChangeFallback: Boolean,
    val needsPlus: Boolean,
    val busy: Boolean,
    val restriction: String,
    val error: String,
    val unavailable: Boolean,
    val needsRepair: Boolean,
    val generation: String,
    val rows: List<ChainedConfigurationDraftRow>,
    val draft: ChainedConfigurationDraft? = null,
    val rotationNote: String = "",
    /** Captured installed-owner status; never serialized or inferred from saved preferences. */
    val runtimeWitness: VpnRuntimeRotationWitness? = null,
) {
    companion object {
        /** No live composition (pre-unlock or retired): the shared screen renders unavailable. */
        fun unavailable(): VpnFamilySnapshot = VpnFamilySnapshot(
            setup = false, enabled = false, canEnable = false, canEdit = false, fallback = false,
            canChangeFallback = false, needsPlus = false, busy = false, restriction = "", error = "",
            unavailable = true, needsRepair = false, generation = "", rows = emptyList(), draft = null,
        )
    }
}

private fun vpnSnapshotJson(family: VpnFamilySnapshot): JSONObject = JSONObject().apply {
    put("setup", family.setup)
    put("enabled", family.enabled)
    put("canEnable", family.canEnable)
    put("canEdit", family.canEdit)
    put("fallback", family.fallback)
    put("canChangeFallback", family.canChangeFallback)
    put("needsPlus", family.needsPlus)
    put("busy", family.busy)
    put("restriction", family.restriction)
    put("error", family.error)
    put("unavailable", family.unavailable)
    put("needsRepair", family.needsRepair)
    put("generation", family.generation)
    put("rotationNote", family.rotationNote)
    put("rows", JSONArray().apply { family.rows.forEach { put(vpnDraftRowJson(it)) } })
    put("draft", family.draft?.let(::draftJson) ?: JSONObject.NULL)
}

/** One captured publication from the installed owner and actual service replacement state. */
internal data class VpnRuntimeRotationWitness(
    val activity: ChainedActivitySignal,
    val state: ProtectionState,
)

/** Display only established differences; this projection cannot create a restart obligation. */
internal fun vpnRotationNoteResource(
    runtime: VpnRuntimeRotationWitness,
    stored: StoredChainedUpstreamConfiguration?,
    issue: ChainedConfigurationIssue?,
): Int? {
    if (runtime.state is ProtectionState.Starting || runtime.state is ProtectionState.Reconnecting) return null
    val running = runtime.activity.takeIf { it.active && it.mode == ChainedRuntimeMode.CHAINED }
        ?.runningConfigurationGeneration ?: 0L
    val authority = when {
        stored != null && stored.pendingImportId == null ->
            ChainedUpstreamRotationFreshness.StoredRotation.Present(stored.identity.generation)
        stored != null -> ChainedUpstreamRotationFreshness.StoredRotation.Unreadable
        issue == ChainedConfigurationIssue.MISSING_CONFIGURATION ->
            ChainedUpstreamRotationFreshness.StoredRotation.Absent
        else -> ChainedUpstreamRotationFreshness.StoredRotation.Unreadable
    }
    return when (ChainedUpstreamRotationFreshness.verdict(running, authority)) {
        is ChainedUpstreamRotationFreshness.Verdict.AwaitingRestart -> R.string.vpn_rotation_awaiting_restart
        is ChainedUpstreamRotationFreshness.Verdict.RunningRemovedRotation -> R.string.vpn_rotation_running_removed
        else -> null
    }
}

/** The canonical `VPNDraft` shape plus Android's `removeStaged` removal projection. */
internal fun draftJson(draft: ChainedConfigurationDraft): JSONObject = JSONObject().apply {
    put("id", draft.id)
    put("revision", draft.revision)
    put("changed", draft.changed)
    put("containsFullTunnel", draft.containsFullTunnel)
    put("removeStaged", draft.removeStaged)
    put("rows", JSONArray().apply { draft.rows.forEach { put(vpnDraftRowJson(it)) } })
}

private fun vpnDraftRowJson(row: ChainedConfigurationDraftRow): JSONObject = JSONObject().apply {
    put("name", row.name)
    put("mode", row.mode)
    put("isEnabled", row.isEnabled)
}

/** Only a confirmed, idle privacy owner can authorize retained game writes or reads. */
internal fun MainProductUiState.canRetainSudoku(): Boolean =
    productAvailable && onboarding?.completed == true &&
        privacy?.inFlight == false && privacy.logging?.keepLavaGuardProgress == true

/**
 * The Plus upgrade reason for a filter draft, mirroring iOS
 * `AppViewModel.filterDraftUpgradeReason`: only native typed capacity refusals offer an upgrade
 * (`blockedDomains`/`allowedDomains`/`filters`). Safety, read and authentication refusals keep
 * their normal recovery paths, an entitled user has no higher tier to upgrade to, and an empty
 * string means no upgrade is offered.
 */
internal fun filterDraftUpgradeReason(
    rejection: FilterDraftReviewRejection?,
    hasPlus: Boolean,
): String {
    if (hasPlus) return ""
    return when (rejection) {
        FilterDraftReviewRejection.BLOCKED_DOMAIN_LIMIT -> "blockedDomains"
        FilterDraftReviewRejection.ALLOWED_DOMAIN_LIMIT -> "allowedDomains"
        FilterDraftReviewRejection.FILTER_LIMIT_REACHED -> "filters"
        else -> ""
    }
}
