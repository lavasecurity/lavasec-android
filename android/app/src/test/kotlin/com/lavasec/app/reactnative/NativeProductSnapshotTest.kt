package com.lavasec.app.reactnative

import com.lavasec.app.MainProductUiState
import com.lavasec.app.account.AccountAvailability
import com.lavasec.app.account.AccountSnapshot
import com.lavasec.app.account.projectAccount
import com.lavasec.app.backup.BackupRemoteStatus
import com.lavasec.app.backup.BackupSnapshot
import com.lavasec.app.data.CatalogRefreshStatus
import com.lavasec.app.data.CatalogSyncSnapshot
import com.lavasec.app.foundation.backup.BackupEnablementPresentation
import com.lavasec.app.foundation.backup.BackupEnablementState
import com.lavasec.app.foundation.catalog.CatalogProvenance
import com.lavasec.app.foundation.notifications.NotificationCategory
import com.lavasec.app.foundation.notifications.NotificationPreferences
import com.lavasec.app.foundation.preferences.PreferenceValue
import com.lavasec.app.foundation.preferences.ProductPreferenceKey
import com.lavasec.app.foundation.preferences.ProductPreferences
import com.lavasec.app.foundation.biometrics.BiometricAvailability
import com.lavasec.app.foundation.biometrics.BiometricKind
import com.lavasec.app.foundation.security.ProtectedSurface
import com.lavasec.app.onboarding.OnboardingOwnerState
import com.lavasec.app.presentation.activity.ActivityContentState
import com.lavasec.app.presentation.activity.ActivityCountSnapshot
import com.lavasec.app.presentation.activity.ActivityDateRangeUi
import com.lavasec.app.presentation.activity.ActivityLogAvailabilityUi
import com.lavasec.app.presentation.activity.ActivityOverviewSnapshot
import com.lavasec.app.presentation.activity.ActivityUiState
import com.lavasec.app.foundation.filter.FilterDraftReviewRejection
import com.lavasec.app.foundation.onboarding.OnboardingPhase
import com.lavasec.app.foundation.onboarding.ProtectionLifecycleMachine
import com.lavasec.app.platform.LoggingSettings
import com.lavasec.app.product.privacy.PrivacyOwnerState
import com.lavasec.app.platform.ProtectionState
import com.lavasec.app.presentation.GuardProtectionHealthUi
import com.lavasec.app.protection.ProtectionOwnerState
import com.lavasec.app.foundation.resolver.ChainedDnsEditState
import com.lavasec.app.foundation.resolver.ResolverSelection
import com.lavasec.app.foundation.resolver.ResolverSelectionPresets
import com.lavasec.app.foundation.resolver.ResolverTier
import com.lavasec.app.foundation.sudoku.SudokuGame
import com.lavasec.app.foundation.sudoku.SudokuPuzzle
import com.lavasec.app.presentation.filters.FilterDetailState
import com.lavasec.app.presentation.filters.FilterDraftDiffUi
import com.lavasec.app.presentation.filters.FilterLibraryState
import com.lavasec.app.presentation.filters.FilterReviewState
import com.lavasec.app.presentation.filters.FilterRowUi
import com.lavasec.app.security.SecurityAvailability
import com.lavasec.app.security.SecurityFailure
import com.lavasec.app.security.SecurityOwnerState
import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NativeProductSnapshotTest {
    @Test fun `chained establishment projection requires the current runtime and an admitted foreground graph`() {
        val protectedState = ProtectionState.Protected(
            com.lavasec.app.platform.ConfigurationIdentity(5L, "c".repeat(64)), com.lavasec.app.platform.VpnOwnership.User,
        )
        val live = VpnRuntimeRotationWitness(
            com.lavasec.app.vpn.chained.ChainedActivitySignal(false,
                com.lavasec.app.vpn.chained.ChainedRuntimeMode.CHAINED, 2L, 7L), protectedState,
        )
        val state = fixture.copy(protection = ProtectionOwnerState(
            guard = GuardProtectionHealthUi.from(protectedState, nowEpochMillis = 0L),
        ))
        val family = VpnFamilySnapshot.unavailable().copy(unavailable = false, runtimeWitness = live)
        fun bit(value: MainProductUiState = state, foreground: Boolean = true,
            captured: VpnFamilySnapshot? = family, current: VpnRuntimeRotationWitness? = live) =
            JSONObject(productSnapshot(value, 1, foreground, 1_000, vpn = captured, vpnRuntime = current))
                .getJSONObject("protection").getBoolean("chainedEstablishing")
        assertTrue(bit())
        assertFalse(bit(foreground = false))
        assertFalse(bit(value = state.copy(productAvailable = false)))
        assertFalse(bit(captured = null))
        assertFalse(bit(captured = VpnFamilySnapshot.unavailable()))
        assertFalse(bit(current = live.copy(activity = live.activity.copy(sequence = 3L))))
    }

    private val date = LocalDate.of(2026, 9, 27)
    private val fixture = MainProductUiState(
        graphGeneration = Long.MAX_VALUE,
        productAvailable = true,
        onboarding = OnboardingOwnerState(completed = true, onboarding = null),
        security = SecurityOwnerState(availability = SecurityAvailability.Available),
        filterLibrary = FilterLibraryState(
            listOf(FilterRowUi("one", "Private filter", true, false, false, 13)), "one", 5,
        ),
        activity = ActivityUiState.unavailable.copy(overview = ActivityContentState.Ready(
            ActivityOverviewSnapshot(
                ActivityDateRangeUi(date, date, date, "UTC"),
                ActivityCountSnapshot(Long.MAX_VALUE, 2), null, ActivityLogAvailabilityUi.Empty,
            ),
        )),
    )

    @Test fun `unconfigured live graph exposes setup without granting configured families`() {
        val firstRun = fixture.copy(productAvailable = false,
            onboarding = OnboardingOwnerState(false, com.lavasec.app.foundation.onboarding.OnboardingState.initial()))
        fun packet(state: MainProductUiState = firstRun, foreground: Boolean = true) =
            JSONObject(productSnapshot(state, 1, foreground, 1_000))
        val live = packet()
        assertFalse(live.getBoolean("available"))
        assertTrue(live.getBoolean("onboardingAvailable"))
        assertTrue(live.getBoolean("onboardingRequired"))
        assertFalse(live.getJSONObject("filters").getBoolean("available"))
        assertEquals(0, live.getJSONObject("filters").getJSONArray("rows").length())
        assertFalse(packet(foreground = false).getBoolean("onboardingAvailable"))
        assertFalse(packet(firstRun.copy(graphGeneration = null)).getBoolean("onboardingAvailable"))
        val rebuilding = packet(firstRun.copy(onboardingReconstructionInFlight = true))
        assertTrue(rebuilding.getBoolean("onboardingAvailable"))
        assertTrue(rebuilding.getBoolean("onboardingReconstructionInFlight"))
        val recovery = packet(firstRun.copy(onboarding = firstRun.onboarding!!.copy(reconstructionRequired = true)))
        assertTrue(recovery.getBoolean("onboardingAvailable"))
        assertTrue(recovery.getBoolean("onboardingRecoveryRequired"))
        assertFalse(recovery.getBoolean("available"))
        assertFalse(recovery.getJSONObject("filters").getBoolean("available"))
        assertFalse(packet(fixture).getBoolean("onboardingAvailable"))
    }

    @Test fun `earned looks survive privacy opt out but local usage is redacted on lock`() {
        val progress = com.lavasec.app.data.GuardianProgressState(
            usageByDay = mapOf("2026-10-01" to 600_000L),
            ledger = com.lavasec.app.foundation.backup.BackupLavaGuardAchievementLedger(listOf(
                com.lavasec.app.foundation.backup.BackupLavaGuardUnlockRecord("emerald", 1.0))))
        val enabled = fixture.copy(privacy = PrivacyOwnerState(logging = LoggingSettings()))
        val earned = JSONObject(productSnapshot(enabled, 1, true, 1000, guardProgress = progress))
            .getJSONObject("guardProgress")
        assertEquals("emerald", earned.getJSONArray("unlockedIDs").getString(0))
        assertEquals(1, earned.getInt("qualifiedUsageDays"))
        val optedOut = enabled.copy(privacy = PrivacyOwnerState(logging = LoggingSettings(keepLavaGuardProgress = false)))
        assertEquals(0, JSONObject(productSnapshot(optedOut, 1, true, 1000, guardProgress = progress))
            .getJSONObject("guardProgress").getInt("qualifiedUsageDays"))
        assertFalse(JSONObject(productSnapshot(enabled, 1, false, 1000, guardProgress = progress)).has("guardProgress"))
        assertFalse(JSONObject(productSnapshot(enabled, 1, true, 1000,
            guardProgress = progress.copy(available = false))).has("guardProgress"))
    }

    @Test fun `unnamed custom stamp row remains an empty editable name in the wire snapshot`() {
        val stamp = com.lavasec.app.DnsStampFixture.frozen.first {
            it.transport == com.lavasec.app.foundation.resolver.CatalogResolverTransport.DOQ
        }
        val rows = listOf(ResolverTier(stamp.entry().copy(customName = null)),
            ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN, false, "Retained device"))
        val dns = JSONObject(productSnapshot(fixture.copy(resolverSelection = ResolverSelection(rows)), 1, true, 1000))
            .getJSONObject("dns")
        val custom = dns.getJSONArray("tiers").getJSONObject(0)
        assertEquals("custom-dns|DoQ", custom.getString("id"))
        assertEquals("", custom.getString("name"))
        assertEquals(stamp.primary, custom.getString("primary"))
        assertEquals(stamp.secondary, custom.getString("secondary"))
        assertEquals("Retained device", dns.getJSONArray("tiers").getJSONObject(1).getString("name"))
        val choices = dns.getJSONArray("choices")
        val offeredCustom = (0 until choices.length()).map { choices.getJSONObject(it) }
            .single { it.getString("id") == "custom-dns|DoQ" }
        assertEquals("", offeredCustom.getString("name"))
        assertEquals(stamp.primary, offeredCustom.getString("primary"))
        assertEquals(stamp.secondary, offeredCustom.getString("secondary"))
    }

    @Test fun `tier budget uses total compiled rules and redacts them with the filters surface`() {
        val state = fixture.copy(filterCompiledRuleCount = 500001)
        fun budget(foreground: Boolean, plus: Boolean) = JSONObject(productSnapshot(state, 1, foreground, 1000,
            com.lavasec.app.billing.NativePlusState(available = true, hasPlus = plus)))
            .getJSONObject("filters").getJSONObject("budget")
        assertFalse(budget(true, true).getBoolean("overBudget"))
        assertTrue(budget(true, false).getBoolean("overBudget"))
        assertEquals("tier-rule-limit", budget(true, false).getString("status"))
        assertTrue(budget(false, false).isNull("compiledTotal"))
        assertTrue(budget(false, false).isNull("overBudget"))
        assertEquals("unavailable", budget(false, false).getString("status"))
    }

    @Test fun `snapshot uses confirmed facts with lossless counters and generation`() {
        val json = JSONObject(productSnapshot(fixture, 9, true, 1_000))
        assertEquals(1, json.getInt("schema"))
        assertEquals(Long.MAX_VALUE.toString(), json.getString("generation"))
        assertEquals(Long.MAX_VALUE.toString(), json.getJSONObject("activity").getString("allowed"))
        assertEquals("Private filter", json.getJSONObject("filters").getJSONArray("rows").getJSONObject(0).getString("name"))
    }

    @Test fun `Today date uses the owner calendar zone and redacts with activity`() {
        val now = date.atStartOfDay(java.time.ZoneId.of("UTC")).toInstant().toEpochMilli()
        val json = JSONObject(productSnapshot(fixture, 1, true, now)).getJSONObject("activity")
        assertEquals(date.toString(), json.getString("todayDate"))
        assertTrue(JSONObject(productSnapshot(fixture, 2, false, now)).getJSONObject("activity").isNull("todayDate"))
    }

    @Test fun `whole app protection redacts otherwise unprotected surfaces until its own grant`() {
        val locked = fixture.copy(security = fixture.security!!.copy(
            enabled = true, protectedSurfaces = setOf(ProtectedSurface.APP_UNLOCK),
            authenticatedSurfaces = setOf(ProtectedSurface.FILTERS),
        ))
        val json = JSONObject(productSnapshot(locked, 1, true, 1_000))
        assertFalse(json.getBoolean("available"))
        assertTrue(json.getJSONObject("filters").getBoolean("locked"))
        assertEquals(0, json.getJSONObject("filters").getJSONArray("rows").length())
        assertTrue(json.getJSONObject("activity").isNull("allowed"))
        val unlocked = locked.copy(security = locked.security!!.copy(authenticatedSurfaces = setOf(ProtectedSurface.APP_UNLOCK)))
        assertTrue(JSONObject(productSnapshot(unlocked, 2, true, 1_000)).getBoolean("available"))
        assertFalse(JSONObject(productSnapshot(unlocked, 3, false, 1_000)).getBoolean("available"))
    }

    @Test fun `background strips evidence even with an unlocked session`() {
        val json = JSONObject(productSnapshot(fixture, 1, false, 1_000))
        assertEquals(0, json.getJSONObject("filters").getJSONArray("rows").length())
        assertTrue(json.getJSONObject("activity").isNull("allowed"))
        assertTrue(json.getJSONObject("activity").isNull("startDate"))
    }

    @Test fun `persisted timestamps do not authorize and revocation redacts protected families`() {
        val state = fixture.copy(security = SecurityOwnerState(
            availability = SecurityAvailability.Available, enabled = true,
            lastUnlockEpochMillis = 1_000, lockTimeoutMinutes = 1,
        ))
        assertTrue(state.isLocked(ProtectedSurface.ACTIVITY, 1_000))
        val granted = state.copy(security = state.security!!.copy(authenticatedSurfaces = setOf(ProtectedSurface.ACTIVITY)))
        assertFalse(granted.isLocked(ProtectedSurface.ACTIVITY, Long.MAX_VALUE))
        assertTrue(granted.isLocked(ProtectedSurface.FILTERS, 1_000))
        val json = JSONObject(productSnapshot(state, 2, true, 61_001))
        assertTrue(json.getJSONObject("filters").getBoolean("locked"))
        assertEquals(0, json.getJSONObject("filters").getJSONArray("rows").length())
        assertTrue(json.getJSONObject("activity").isNull("blocked"))
    }

    @Test fun `missing security or recovery fails closed`() {
        assertTrue(fixture.copy(security = null).isLocked(ProtectedSurface.FILTERS, 1_000))
        assertTrue(fixture.copy(security = SecurityOwnerState(
            availability = SecurityAvailability.Available, recoveryRequired = true,
        )).isLocked(ProtectedSurface.ACTIVITY, 1_000))
    }

    @Test fun `security family projects owner facts and never claims biometrics or protection without a credential`() {
        val json = JSONObject(productSnapshot(fixture, 1, true, 1_000)).getJSONObject("security")
        assertFalse(json.getBoolean("unavailable"))
        assertFalse(json.getBoolean("hasAuthenticationMethod"))
        assertFalse(json.getBoolean("updatingSurface"))
        assertFalse(json.getBoolean("recoveryRequired"))
        assertTrue(json.isNull("failure"))
        assertFalse(json.getBoolean("showBiometrics"))
        assertFalse(json.getBoolean("canEnableBiometrics"))
        assertFalse(json.getBoolean("biometrics"))
        assertFalse(json.getBoolean("biometricLockout"))
        assertEquals("Biometrics", json.getString("biometricTitle"))
        assertEquals(6, json.getJSONArray("protectedSurfaces").length())
        assertEquals("", json.getString("status"))
        // No owner: the family is omitted entirely rather than fabricated.
        assertFalse(JSONObject(productSnapshot(fixture.copy(security = null), 1, true, 1_000)).has("security"))
    }

    @Test fun `security family projects real biometric facts only from a usable owner state`() {
        val biometric = fixture.copy(security = SecurityOwnerState(
            availability = SecurityAvailability.Available, enabled = true,
            biometricEnabled = true,
            biometricAvailability = BiometricAvailability.Available(BiometricKind.FINGERPRINT),
        ))
        val json = JSONObject(productSnapshot(biometric, 1, true, 1_000)).getJSONObject("security")
        assertTrue(json.getBoolean("showBiometrics"))
        assertTrue(json.getBoolean("canEnableBiometrics"))
        assertTrue(json.getBoolean("biometrics"))
        assertFalse(json.getBoolean("biometricLockout"))
        assertEquals("Fingerprint", json.getString("biometricTitle"))

        // Lockout disables authentication, not the stored opt-in. The native-confirmed ON row
        // remains available for guarded disable even when a new enable cannot be offered.
        val lockedOut = biometric.copy(security = biometric.security!!.copy(
            biometricLockout = true,
            biometricAvailability = BiometricAvailability.HardwareUnavailable,
        ))
        val lockedJson = JSONObject(productSnapshot(lockedOut, 2, true, 1_000)).getJSONObject("security")
        assertTrue(lockedJson.getBoolean("biometricLockout"))
        assertTrue(lockedJson.getBoolean("showBiometrics"))
        assertFalse(lockedJson.getBoolean("canEnableBiometrics"))
        assertTrue(lockedJson.getBoolean("biometrics"))
        assertEquals("Biometrics", lockedJson.getString("biometricTitle"))
    }

    @Test fun `stored biometric opt in stays visible without usable enrollment but never grants authentication`() {
        for (availability in listOf(BiometricAvailability.Unavailable, BiometricAvailability.NotEnrolled,
                BiometricAvailability.KeyInvalidated)) {
            val saved = SecurityOwnerState(availability = SecurityAvailability.Available, enabled = true,
                biometricEnabled = true, biometricAvailability = availability,
                protectedSurfaces = setOf(ProtectedSurface.APP_UNLOCK, ProtectedSurface.APP_SETTINGS))
            val json = JSONObject(productSnapshot(fixture.copy(security = saved), 1, true, 1_000))
                .getJSONObject("security")
            assertTrue(json.getBoolean("showBiometrics"))
            assertTrue(json.getBoolean("biometrics"))
            assertFalse(json.getBoolean("canEnableBiometrics"))
            assertEquals(0, json.getJSONArray("authenticatedSurfaces").length())
            assertFalse(saved.isAuthorized(ProtectedSurface.APP_UNLOCK))
            assertFalse(saved.isAuthorized(ProtectedSurface.APP_SETTINGS))
            assertFalse(com.lavasec.app.foundation.biometrics.BiometricAccessPolicy.effectiveEnabled(
                saved.biometricSettings(), availability))
            val off = JSONObject(productSnapshot(fixture.copy(security = saved.copy(biometricEnabled = false)),
                2, true, 1_000)).getJSONObject("security")
            assertFalse(off.getBoolean("biometrics"))
            val unavailableOwner = JSONObject(productSnapshot(fixture.copy(security = saved.copy(
                availability = SecurityAvailability.Unavailable)), 3, true, 1_000)).getJSONObject("security")
            assertFalse(unavailableOwner.getBoolean("showBiometrics"))
            assertFalse(unavailableOwner.getBoolean("biometrics"))
        }
    }

    @Test fun `security family reports an enabled passcode, opt-ins, in-flight mutation and recovery honestly`() {
        val enabled = fixture.copy(security = SecurityOwnerState(
            availability = SecurityAvailability.Available, enabled = true,
            protectedSurfaces = setOf(ProtectedSurface.APP_UNLOCK, ProtectedSurface.FILTERS),
            authenticatedSurfaces = setOf(ProtectedSurface.FILTERS),
            inFlight = true, failure = SecurityFailure.WRONG_PASSCODE,
        ))
        val json = JSONObject(productSnapshot(enabled, 1, true, 1_000)).getJSONObject("security")
        assertTrue(json.getBoolean("hasAuthenticationMethod"))
        assertTrue(json.getBoolean("updatingSurface"))
        assertEquals("wrongPasscode", json.getString("failure"))
        assertEquals(
            listOf("appUnlock", "filterEditing"),
            (0 until json.getJSONArray("protectedSurfaces").length()).map {
                json.getJSONArray("protectedSurfaces").getString(it)
            },
        )
        assertEquals(
            listOf("filterEditing"),
            (0 until json.getJSONArray("authenticatedSurfaces").length()).map {
                json.getJSONArray("authenticatedSurfaces").getString(it)
            },
        )

        val recovery = JSONObject(
            productSnapshot(
                fixture.copy(security = SecurityOwnerState(
                    availability = SecurityAvailability.Available, recoveryRequired = true,
                )),
                1, true, 1_000,
            ),
        ).getJSONObject("security")
        assertTrue(recovery.getBoolean("unavailable"))
        assertTrue(recovery.getBoolean("recoveryRequired"))
        assertFalse(recovery.getBoolean("hasAuthenticationMethod"))
        assertTrue(recovery.getString("status").isNotEmpty())
    }

    @Test fun `filter editing family projects owner detail and review facts, and redacts when locked`() {
        val detail = FilterDetailState(
            filterId = "one", name = "Private filter", inEffect = true, frozen = false,
            blocksNothing = false, ruleCount = 13, sources = emptyList(),
            blockedDomains = emptyList(), allowedDomains = emptyList(),
            maxAllowedDomains = 5, maxBlockedDomains = 5,
            editing = true, draftHasChanges = true, busy = false,
        )
        val diff = FilterDraftDiffUi(
            emptyList(), listOf("removed source"), emptyList(), emptyList(), emptyList(), emptyList(),
        )
        val blocked = fixture.copy(
            filterDetail = detail,
            filterReview = FilterReviewState("one", "Private filter", diff, FilterDraftReviewRejection.FILTER_FROZEN, false),
        )
        val rejected = JSONObject(productSnapshot(blocked, 1, true, 1_000)).getJSONObject("filterEditing")
        assertFalse(rejected.getBoolean("canSave"))
        assertFalse(rejected.getBoolean("reviewCanConfirm"))
        assertEquals("filter_frozen", rejected.getString("validation"))
        assertFalse(rejected.getBoolean("refreshing"))
        assertEquals(0, rejected.getJSONArray("lists").length())

        val reviewable = fixture.copy(
            filterDetail = detail,
            filterReview = FilterReviewState("one", "Private filter", diff, null, false),
        )
        val ready = JSONObject(productSnapshot(reviewable, 1, true, 1_000)).getJSONObject("filterEditing")
        assertTrue(ready.getBoolean("canSave"))
        assertTrue(ready.getBoolean("reviewCanConfirm"))
        assertTrue(ready.isNull("validation"))

        val locked = JSONObject(productSnapshot(reviewable, 1, false, 1_000))
        assertFalse(locked.has("filterEditing"))
    }

    @Test fun `a creation projects separately from saved rows with its real template baseline and lock redaction`() {
        val pending = com.lavasec.app.foundation.filter.FilterProfileDraft(
            "new", "My copy", null, setOf("template"),
            com.lavasec.app.foundation.filter.ManualDomainRules(blockedDomains = setOf("template.example")),
            emoji = "🌿",
        )
        val detail = FilterDetailState(
            filterId = "new", name = "My copy", inEffect = false, frozen = false,
            blocksNothing = false, ruleCount = null, sources = emptyList(),
            blockedDomains = listOf("edited.example"), allowedDomains = emptyList(),
            maxAllowedDomains = 25, maxBlockedDomains = 25,
            editing = true, draftHasChanges = true, busy = false,
        )
        val state = fixture.copy(newFilter = pending, filterDetail = detail,
            filterReview = FilterReviewState("new", "My copy",
                FilterDraftDiffUi(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()), null, false))
        val json = JSONObject(productSnapshot(state, 1, true, 1_000))
        val created = json.getJSONObject("newFilter")
        assertEquals("new", created.getString("id"))
        assertEquals("🌿", created.getString("emoji"))
        assertEquals("template", created.getJSONArray("lists").getString(0))
        assertEquals(1, created.getInt("blockedDomainCount"))
        val rows = json.getJSONObject("filters").getJSONArray("rows")
        assertEquals(1, rows.length())
        assertEquals("one", rows.getJSONObject(0).getString("id"))
        val session = json.getJSONObject("filterSession")
        assertEquals("template.example", session.getJSONArray("savedBlocked").getString(0))
        assertEquals("edited.example", session.getJSONArray("blocked").getString(0))
        assertTrue(json.getJSONObject("filterEditing").getBoolean("canSave"))
        assertFalse(JSONObject(productSnapshot(state, 1, false, 1_000)).has("newFilter"))
        assertFalse(JSONObject(productSnapshot(state.copy(productAvailable = false), 1, true, 1_000)).has("newFilter"))
        val lockedSecurity = SecurityOwnerState(availability = SecurityAvailability.Available,
            enabled = true, protectedSurfaces = setOf(ProtectedSurface.FILTERS))
        assertFalse(JSONObject(productSnapshot(state.copy(security = lockedSecurity), 1, true, 1_000)).has("newFilter"))
        // A library commit publishes the saved row before the owner clears `newFilter`; the id must
        // never project in both, or the shared parser rejects the snapshot ("New filter is already
        // saved") and the RN app crashes.
        val committed = state.copy(filterLibrary = state.filterLibrary!!.copy(
            filters = state.filterLibrary.filters + FilterRowUi("new", "My copy", false, false, false, 0)))
        assertFalse(JSONObject(productSnapshot(committed, 1, true, 1_000)).has("newFilter"))
    }

    @Test fun `filter draft preserves its saved baseline and undo rows without leaking in background`() {
        val saved = com.lavasec.app.foundation.filter.FilterProfileDraft(
            "one", "Private filter", null, setOf("old"),
            com.lavasec.app.foundation.filter.ManualDomainRules(blockedDomains = setOf("old.example")),
        )
        val detail = FilterDetailState(
            filterId = "one", name = "Private filter", inEffect = true, frozen = false,
            blocksNothing = false, ruleCount = 13,
            sources = listOf(
                com.lavasec.app.presentation.filters.FilterSourceOptionUi("old", "Old list", com.lavasec.app.foundation.filter.FilterSourceCategory.SECURITY, false),
                com.lavasec.app.presentation.filters.FilterSourceOptionUi("new", "New list", com.lavasec.app.foundation.filter.FilterSourceCategory.SECURITY, true, 53_174),
            ),
            blockedDomains = listOf("new.example"), allowedDomains = emptyList(),
            maxAllowedDomains = 25, maxBlockedDomains = 25,
            editing = true, draftHasChanges = true, busy = false,
        )
        val state = fixture.copy(filterDetail = detail)
        fun read(foreground: Boolean) = JSONObject(productSnapshot(state, 1, foreground, 1_000, savedFilters = listOf(saved)))
        val json = read(true)
        val session = json.getJSONObject("filterSession")
        assertEquals("old.example", session.getJSONArray("savedBlocked").getString(0))
        assertEquals("new.example", session.getJSONArray("blocked").getString(0))
        assertEquals("old", session.getJSONArray("savedLists").getString(0))
        assertEquals("new", session.getJSONArray("lists").getString(0))
        assertEquals(53_174, session.getJSONObject("ruleCounts").getInt("new"))
        assertFalse(session.getJSONObject("ruleCounts").has("old"))
        for (shelf in listOf("lists", "blocked")) {
            val rows = json.getJSONObject("filterEditing").getJSONArray(shelf)
            assertEquals(2, rows.length())
            assertTrue(rows.getJSONObject(0).getBoolean("undo"))
            assertFalse(rows.getJSONObject(0).getBoolean("pending"))
            assertTrue(rows.getJSONObject(1).getBoolean("undo"))
            assertTrue(rows.getJSONObject(1).getBoolean("pending"))
        }
        assertFalse(read(false).has("filterSession"))
        assertFalse(read(false).has("filterEditing"))
        assertEquals(0, read(false).getJSONObject("filters").getJSONArray("rows").length())
    }

    @Test fun `dns snapshot family projects confirmed tiers and a requested custom draft`() {
        // No confirmed selection: the whole family is omitted (never an invented selection).
        assertFalse(JSONObject(productSnapshot(fixture, 1, true, 1_000)).has("dns"))

        val selected = fixture.copy(resolverSelection = ResolverSelectionPresets.DEVICE_DNS_WITH_MULLVAD_FALLBACK)
        val dns = JSONObject(productSnapshot(selected, 1, true, 1_000)).getJSONObject("dns")
        assertTrue(dns.getBoolean("editable"))
        assertFalse(dns.getBoolean("customSelected"))
        assertEquals(2, dns.getJSONArray("tiers").length())
        assertEquals("device-dns|IP", dns.getJSONArray("tiers").getJSONObject(0).getString("id"))
        assertEquals("mullvad|DoH", dns.getJSONArray("tiers").getJSONObject(1).getString("id"))
        // Only served choices are actionable; confirmed tiers remain truthful.
        assertEquals(listOf("quad9-unfiltered", "cloudflare-1111", "hagezi-root", "google-public-dns"),
            (0 until dns.getJSONArray("providers").length()).map {
                dns.getJSONArray("providers").getJSONObject(it).getString("id")
            })
        // A retained legacy route remains exact without being offered as a new picker choice.
        assertFalse(dns.getJSONArray("providers").getJSONObject(0).getBoolean("selected"))
        assertEquals(listOf("device-dns|IP", "quad9-unfiltered|IP", "quad9-unfiltered|DoH", "quad9-unfiltered|DoT",
            "cloudflare-1111|IP", "cloudflare-1111|DoH", "cloudflare-1111|DoT",
            "hagezi-root|IP", "hagezi-root|DoH", "hagezi-root|DoT",
            "google-public-dns|IP", "google-public-dns|DoH", "google-public-dns|DoT"),
            (0 until dns.getJSONArray("choices").length()).map {
                dns.getJSONArray("choices").getJSONObject(it).getString("id")
            })
        assertEquals("IP", dns.getJSONArray("transports").getString(0))
        assertEquals(1, dns.getJSONArray("transports").length())
        assertEquals("", dns.getString("customDraftToken"))
        assertTrue(dns.isNull("customDraft"))
        assertEquals("device-dns|IP|1;mullvad|DoH|1", dns.getString("tiersContext"))
        // The ordered tiers carry their per-slot enablement; both slots are enabled here.
        assertTrue(dns.getJSONArray("tiers").getJSONObject(0).getBoolean("isEnabled"))
        assertTrue(dns.getJSONArray("tiers").getJSONObject(1).getBoolean("isEnabled"))

        // With no fallback configured the ladder carries exactly its one active tier; the shared
        // editor adds a second tier through the picker rather than a pinned placeholder row.
        val noFallback = fixture.copy(resolverSelection = ResolverSelectionPresets.DEVICE_DNS_ONLY)
        val disabled = JSONObject(productSnapshot(noFallback, 1, true, 1_000)).getJSONObject("dns")
        assertEquals(1, disabled.getJSONArray("tiers").length())
        assertTrue(disabled.getJSONArray("tiers").getJSONObject(0).getBoolean("isEnabled"))
        assertEquals("device-dns|IP", disabled.getJSONArray("tiers").getJSONObject(0).getString("id"))

        // A retained disabled row projects in place, so a later toggle restores its exact identity.
        val retained = fixture.copy(
            resolverSelection = ResolverSelection(
                listOf(
                    ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN),
                    ResolverTier(ResolverSelectionPresets.MULLVAD_DOH, isEnabled = false),
                ),
            ),
        )
        val retainedSnapshot = JSONObject(productSnapshot(retained, 1, true, 1_000)).getJSONObject("dns")
        assertEquals(2, retainedSnapshot.getJSONArray("tiers").length())
        assertFalse(retainedSnapshot.getJSONArray("tiers").getJSONObject(1).getBoolean("isEnabled"))
        assertEquals("mullvad|DoH", retainedSnapshot.getJSONArray("tiers").getJSONObject(1).getString("id"))
        assertEquals("device-dns|IP|1;mullvad|DoH|0", retainedSnapshot.getString("tiersContext"))
        val named = retained.copy(resolverSelection = ResolverSelection(listOf(
            ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN, true, "Hotel network"),
            ResolverTier(ResolverSelectionPresets.MULLVAD_DOH, false, "Private fallback"),
        )))
        val namedSnapshot = JSONObject(productSnapshot(named, 1, true, 1_000)).getJSONObject("dns")
        assertEquals("Hotel network", namedSnapshot.getJSONArray("tiers").getJSONObject(0).getString("name"))
        assertEquals("Private fallback", namedSnapshot.getJSONArray("tiers").getJSONObject(1).getString("name"))
        assertFalse(namedSnapshot.getJSONArray("tiers").getJSONObject(1).getBoolean("isEnabled"))
        assertFalse(retainedSnapshot.getString("tiersContext") == namedSnapshot.getString("tiersContext"))

        // A Mullvad DoH primary has no served second tier, so only one row is projected.
        val mullvadOnly = fixture.copy(
            resolverSelection = com.lavasec.app.foundation.resolver.ResolverSelectionPresets.MULLVAD_ONLY,
        )
        assertEquals(1, JSONObject(productSnapshot(mullvadOnly, 1, true, 1_000)).getJSONObject("dns").getJSONArray("tiers").length())

        // A chained full tunnel locks the editor in the snapshot without changing the selection.
        val locked = selected.copy(
            chainedDnsEdit = ChainedDnsEditState(
                chainingEnabled = true,
                fallbackPreference = true,
                storedIsSplitTunnel = false,
            ),
        )
        assertFalse(JSONObject(productSnapshot(locked, 1, true, 1_000)).getJSONObject("dns").getBoolean("editable"))

        // The custom editor draft is the REQUESTED value and is carried separately from custom.
        val draft = com.lavasec.app.foundation.resolver.CustomResolverDraftSession.from(
            com.lavasec.app.foundation.resolver.CustomResolverDraft(name = "Home", primaryAddress = "https://dns.example/query"),
        )
        val withDraft = JSONObject(
            productSnapshot(selected, 1, true, 1_000, dnsCustomDraft = draft, dnsCustomToken = "token-9"),
        ).getJSONObject("dns")
        assertEquals("token-9", withDraft.getString("customDraftToken"))
        assertEquals("custom-dns|DoH", withDraft.getJSONObject("customDraft").getString("id"))
        assertEquals("Home", withDraft.getJSONObject("customDraft").getString("name"))
        assertFalse(withDraft.getJSONObject("custom").getBoolean("valid"))
    }

    @Test fun `connection family projects the active filter, resolver selection and chained facts`() {
        // No confirmed resolver selection: the family is omitted rather than fabricated.
        assertFalse(JSONObject(productSnapshot(fixture, 1, true, 1_000)).has("connection"))

        val state = fixture.copy(resolverSelection = ResolverSelectionPresets.DEVICE_DNS_WITH_MULLVAD_FALLBACK)
        val family = VpnFamilySnapshot(
            setup = false, enabled = false, canEnable = true, canEdit = true, fallback = false,
            canChangeFallback = true, needsPlus = false, busy = false, restriction = "", error = "",
            unavailable = false, needsRepair = false, generation = "", rows = emptyList(),
        )
        val connection = JSONObject(
            productSnapshot(state, 1, true, 1_000, vpn = family),
        ).getJSONObject("connection")
        assertEquals("one", connection.getJSONObject("filter").getString("id"))
        assertEquals("Private filter", connection.getJSONObject("filter").getString("name"))
        assertEquals("13", connection.getJSONObject("filter").getString("count"))
        val dns = connection.getJSONObject("dns")
        assertTrue(dns.getBoolean("editable"))
        assertEquals("Device DNS", dns.getJSONObject("primary").getString("name"))
        assertEquals("", dns.getJSONObject("primary").getString("transport"))
        assertEquals("Mullvad", dns.getJSONObject("fallback").getString("name"))
        assertEquals("DoH", dns.getJSONObject("fallback").getString("transport"))
        val vpn = connection.getJSONObject("vpn")
        assertTrue(vpn.getBoolean("eligible"))
        assertFalse(vpn.getBoolean("enabled"))
        assertFalse(vpn.getBoolean("configured"))
        assertEquals("Not configured", vpn.getString("statusLabel"))

        // No live composition: an honest ineligible/off vpn, never an invented chained upstream.
        val absent = JSONObject(productSnapshot(state, 2, true, 1_000))
            .getJSONObject("connection").getJSONObject("vpn")
        assertFalse(absent.getBoolean("eligible"))
        assertFalse(absent.getBoolean("enabled"))
        assertTrue(absent.isNull("fallbackEnabled"))
        assertFalse(absent.has("name"))
    }

    @Test fun `presentation and settingsSummary project only owner-backed fields`() {
        val preferences = ProductPreferences.canonicalDefaults()
            .set(ProductPreferenceKey.MATCH_TEXT_SIZE, PreferenceValue.Bool(false)).getOrThrow()
            .set(ProductPreferenceKey.TEXT_SIZE, PreferenceValue.IntValue(6)).getOrThrow()
        val scaled = JSONObject(
            productSnapshot(fixture, 1, true, 1_000, preferences = preferences, locale = "zh-Hant"),
        )
        val presentation = scaled.getJSONObject("presentation")
        assertEquals("zh-Hant", presentation.getString("locale"))
        val ramps = setOf("body", "headline", "subheadline", "footnote", "caption1", "caption2",
            "title1", "title2", "title3", "largeTitle", "callout")
        val largestScales = presentation.getJSONObject("textScales")
        assertEquals(ramps, largestScales.keys().asSequence().toSet())
        ramps.forEach { assertEquals("Largest text must also reach $it", 1.35, largestScales.getDouble(it), 0.0001) }
        for ((index, expected) in listOf(0.82, 0.88, 0.94, 1.0, 1.12, 1.23, 1.35).withIndex()) {
            val sizedPreferences = preferences.set(ProductPreferenceKey.TEXT_SIZE, PreferenceValue.IntValue(index)).getOrThrow()
            val sizes = JSONObject(productSnapshot(fixture, 1, true, 1_000, preferences = sizedPreferences))
                .getJSONObject("presentation").getJSONObject("textScales")
            ramps.forEach { assertEquals("Text size $index must preserve relative $it metrics", expected, sizes.getDouble(it), 0.0001) }
        }

        // The canonical default follows the system text size, so no explicit ramp is claimed.
        val system = JSONObject(productSnapshot(fixture, 1, true, 1_000, locale = "en"))
        assertTrue(system.getJSONObject("presentation").isNull("textScales"))

        // The fixture has a security owner but no confirmed resolver selection or logging policy,
        // so only the security summary is projected — never a fabricated dns/privacy value.
        val summary = system.getJSONObject("settingsSummary")
        assertFalse(summary.has("dns"))
        assertFalse(summary.has("privacy"))
        assertEquals("Passcode off", summary.getString("security"))
    }

    @Test fun `material DNS wire saves preserve default source names and authored labels on native reprojection`() {
        val initial = ResolverSelectionPresets.DEVICE_DNS_WITH_MULLVAD_FALLBACK
        val context = com.lavasec.app.product.ResolverDnsContext.encode(initial)
        val before = JSONObject(productSnapshot(fixture.copy(resolverSelection = initial), 1, true, 1_000))
            .getJSONObject("dns").getJSONArray("tiers")
        assertEquals("Device DNS", before.getJSONObject(0).getString("name"))
        assertTrue(before.getJSONObject(0).getBoolean("nameIsDefault"))
        for (generated in listOf(true, false)) {
            // This is a material reorder. The actual JS selection boundary supplies empty names
            // for explicit generated defaults; authored/legacy wire names are sent unchanged.
            val rows = org.json.JSONArray()
            for (index in listOf(1, 0)) {
                val row = before.getJSONObject(index)
                rows.put(JSONObject().put("id", row.getString("id"))
                    .put("name", if (generated) "" else if (index == 0) "Device DNS" else "Custom DNS")
                    .put("primary", row.getString("primary")).put("secondary", row.getString("secondary")))
            }
            val parsed = (ProductCommandParser.parse("dns.tiers", JsonProductCommandInput(
                JSONObject().put("context", context).put("tiers", rows))) as ProductCommandParse.Supported)
                .command as ProductDnsCommand.Tiers
            val request = com.lavasec.app.foundation.resolver.SaveResolverTiers(parsed.tiers, parsed.context)
            fun evaluate(expectedContext: String = context, chained: ChainedDnsEditState = ChainedDnsEditState.editable) =
                com.lavasec.app.foundation.resolver.ResolverSelectionPolicy.evaluate(request, initial,
                    com.lavasec.app.foundation.subscription.FeatureLimits.PLUS, chained, editorContext = expectedContext)
            val accepted = evaluate() as com.lavasec.app.foundation.resolver.ResolverEditResult.Accepted
            assertEquals(initial.tiers.map { it.entry }.reversed(), accepted.commit.selection.tiers.map { it.entry })
            val after = JSONObject(productSnapshot(fixture.copy(resolverSelection = accepted.commit.selection),
                2, true, 1_000)).getJSONObject("dns").getJSONArray("tiers")
            assertEquals(listOf("mullvad|DoH", "device-dns|IP"), (0 until after.length()).map { after.getJSONObject(it).getString("id") })
            for (index in 0 until after.length()) {
                val row = after.getJSONObject(index)
                assertEquals(generated, row.getBoolean("nameIsDefault"))
                assertEquals(if (generated) null else if (index == 0) "Custom DNS" else "Device DNS",
                    accepted.commit.selection.tiers[index].retainedName)
                if (!generated) assertEquals(if (index == 0) "Custom DNS" else "Device DNS", row.getString("name"))
                assertEquals(before.getJSONObject(1 - index).getString("primary"), row.getString("primary"))
                assertEquals(before.getJSONObject(1 - index).getString("secondary"), row.getString("secondary"))
            }
            assertEquals(com.lavasec.app.foundation.resolver.ResolverEditRejection.StaleEditor,
                (evaluate(expectedContext = "retired") as com.lavasec.app.foundation.resolver.ResolverEditResult.Rejected).rejection)
            assertEquals(com.lavasec.app.foundation.resolver.ResolverEditRejection.ChainedEditLocked,
                (evaluate(chained = ChainedDnsEditState(true, false, false)) as com.lavasec.app.foundation.resolver.ResolverEditResult.Rejected).rejection)
        }
    }

    @Test fun `DNS summaries and connection names retain the exact enabled tier identity`() {
        val custom = com.lavasec.app.DnsStampFixture.frozen.first {
            it.transport == com.lavasec.app.foundation.resolver.CatalogResolverTransport.DOH
        }.entry().copy(customName = "Cancel")
        val selection = ResolverSelection(listOf(
            ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN, false, "Saved device"),
            ResolverTier(custom, true, "Save"),
        ))
        val packet = JSONObject(productSnapshot(fixture.copy(resolverSelection = selection), 1, true, 1_000))
        val dns = packet.getJSONObject("connection").getJSONObject("dns")
        assertEquals("Save", dns.getJSONObject("primary").getString("name"))
        assertFalse(dns.getJSONObject("primary").getBoolean("nameIsDefault"))
        assertTrue(dns.isNull("fallback"))
        assertEquals("Save", packet.getJSONObject("settingsSummary").getString("dns"))
        val tiers = packet.getJSONObject("dns").getJSONArray("tiers")
        assertEquals("Saved device", tiers.getJSONObject(0).getString("name"))
        assertFalse(tiers.getJSONObject(0).getBoolean("nameIsDefault"))
        assertEquals("Save", tiers.getJSONObject(1).getString("name"))
        assertFalse(tiers.getJSONObject(1).getBoolean("nameIsDefault"))
        // The current candidate and selected row are the same endpoint; the picker must not
        // replace the retained label with the custom entry's predecessor name on re-selection.
        val choices = packet.getJSONObject("dns").getJSONArray("choices")
        val offeredCustom = (0 until choices.length()).map { choices.getJSONObject(it) }
            .single { it.getString("id") == "custom-dns|DoH" }
        assertEquals("Save", offeredCustom.getString("name"))
        assertFalse(offeredCustom.getBoolean("nameIsDefault"))
        assertEquals(custom.customDohUrl, offeredCustom.getString("primary"))
    }

    @Test fun `native DNS default provenance distinguishes authored names equal to app copy`() {
        val stamp = com.lavasec.app.DnsStampFixture.frozen.first {
            it.transport == com.lavasec.app.foundation.resolver.CatalogResolverTransport.DOH
        }
        for (name in listOf("Cancel", "Save", "Device DNS", "Custom DNS")) {
            val selection = ResolverSelection(listOf(
                ResolverTier(stamp.entry().copy(customName = name)),
                ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN, true, "Device DNS"),
            ))
            val packet = JSONObject(productSnapshot(fixture.copy(resolverSelection = selection), 1, true, 1_000))
            val connection = packet.getJSONObject("connection").getJSONObject("dns")
            assertEquals(name, connection.getJSONObject("primary").getString("name"))
            assertFalse(connection.getJSONObject("primary").getBoolean("nameIsDefault"))
            assertEquals("Device DNS", connection.getJSONObject("fallback").getString("name"))
            assertFalse(connection.getJSONObject("fallback").getBoolean("nameIsDefault"))
            assertEquals("$name + Fallback", packet.getJSONObject("settingsSummary").getString("dns"))
            val tiers = packet.getJSONObject("dns").getJSONArray("tiers")
            assertEquals(name, tiers.getJSONObject(0).getString("name"))
            assertFalse(tiers.getJSONObject(0).getBoolean("nameIsDefault"))
            assertFalse(tiers.getJSONObject(1).getBoolean("nameIsDefault"))
            val choices = packet.getJSONObject("dns").getJSONArray("choices")
            val offeredCustom = (0 until choices.length()).map { choices.getJSONObject(it) }
                .single { it.getString("id") == "custom-dns|DoH" }
            assertEquals(name, offeredCustom.getString("name"))
            assertFalse(offeredCustom.getBoolean("nameIsDefault"))
        }
        val unnamed = ResolverSelection(listOf(ResolverTier(stamp.entry().copy(customName = null)),
            ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN)))
        val packet = JSONObject(productSnapshot(fixture.copy(resolverSelection = unnamed), 1, true, 1_000))
        val connection = packet.getJSONObject("connection").getJSONObject("dns")
        assertEquals("Custom DNS", connection.getJSONObject("primary").getString("name"))
        assertTrue(connection.getJSONObject("primary").getBoolean("nameIsDefault"))
        assertTrue(connection.getJSONObject("fallback").getBoolean("nameIsDefault"))
        val tiers = packet.getJSONObject("dns").getJSONArray("tiers")
        assertEquals("", tiers.getJSONObject(0).getString("name"))
        assertTrue(tiers.getJSONObject(0).getBoolean("nameIsDefault"))
        assertTrue(tiers.getJSONObject(1).getBoolean("nameIsDefault"))
    }

    @Test fun `notification toggles project only from a live owner and stay omitted otherwise`() {
        // No owner: the family is omitted entirely rather than fabricated as all-on defaults.
        assertFalse(JSONObject(productSnapshot(fixture, 1, true, 1_000)).has("notifications"))
        val prefs = NotificationPreferences.canonicalDefaults()
            .set(NotificationCategory.FILTER_CHANGED, false)
        val projected = JSONObject(
            productSnapshot(fixture, 1, true, 1_000, notifications = prefs),
        ).getJSONObject("notifications")
        assertFalse(projected.getBoolean("Filter changes"))
        assertTrue(projected.getBoolean("Connection updates"))
    }

    @Test fun `protection reports requested, confirmed, onboarding phase and recovery honestly`() {
        // No owner lifecycle: the VM-derived phase is the honest pre-owner value and the
        // requested/confirmed pair is explicitly unknown, never a fabricated boolean.
        val preOwner = fixture.copy(protectionOnboardingPhase = OnboardingPhase.FRESH_INSTALL)
        val pre = JSONObject(productSnapshot(preOwner, 1, true, 1_000)).getJSONObject("protection")
        assertTrue(pre.isNull("requested"))
        assertTrue(pre.isNull("confirmed"))
        assertEquals("freshInstall", pre.getString("onboarding"))
        assertTrue(pre.isNull("recovery"))

        // A seeded READY lifecycle exposes the requested/confirmed pair as distinct values.
        val lifecycle = ProtectionLifecycleMachine.requestStart(
            ProtectionLifecycleMachine.completeDefaultFilterPreparation(
                ProtectionLifecycleMachine.beginDefaultFilterPreparation(
                    ProtectionLifecycleMachine.freshInstall(),
                ).state,
                prepared = true,
            ).state,
        ).state
        val withOwner = fixture.copy(
            protection = ProtectionOwnerState(
                guard = GuardProtectionHealthUi.from(ProtectionState.Off, nowEpochMillis = 0L),
                lifecycle = lifecycle,
            ),
        )
        val owner = JSONObject(productSnapshot(withOwner, 1, true, 1_000)).getJSONObject("protection")
        assertTrue(owner.getBoolean("requested"))
        assertFalse(owner.getBoolean("confirmed"))
        assertEquals("ready", owner.getString("onboarding"))
        assertTrue(owner.isNull("recovery"))

        // A failed preparation projects the recovery action code, never localized copy.
        val failed = ProtectionLifecycleMachine.completeDefaultFilterPreparation(
            ProtectionLifecycleMachine.beginDefaultFilterPreparation(
                ProtectionLifecycleMachine.freshInstall(),
            ).state,
            prepared = false,
        ).state
        val failedJson = JSONObject(
            productSnapshot(
                fixture.copy(
                    protection = ProtectionOwnerState(
                        guard = GuardProtectionHealthUi.from(ProtectionState.Off, nowEpochMillis = 0L),
                        lifecycle = failed,
                    ),
                ),
                1, true, 1_000,
            ),
        ).getJSONObject("protection")
        assertEquals("preparationFailed", failedJson.getString("onboarding"))
        assertEquals("retryDefaultFilterPreparation", failedJson.getString("recovery"))    }

    @Test fun `qa tools capability follows the native debug gate and never leaks in release`() {
        // The capability is the SystemCapabilityCatalog DEVICE_QA disposition (DEBUG_ONLY), so a
        // debug build projects true and a release build false; the shared route gate reads it.
        assertTrue(JSONObject(productSnapshot(fixture, 1, true, 1_000, qaTools = true)).getBoolean("qaTools"))
        assertFalse(JSONObject(productSnapshot(fixture, 2, true, 1_000, qaTools = false)).getBoolean("qaTools"))
    }

    @Test fun `native flow routes are a closed set and cannot construct arbitrary intents`() {
        assertNotNull(NativeProductRoutes.navigation("filters"))
        assertNotNull(NativeProductRoutes.navigation("privacy"))
        assertNull(NativeProductRoutes.navigation("https://example.com"))
        assertNull(NativeProductRoutes.navigation("filter.delete"))
        // The backup flows are recognized with their own typed unavailable reasons rather than the
        // generic unknown-flow rejection; every other route is not a backup presentation.
        assertEquals("backup-setup-unavailable", NativeProductRoutes.backupPresentationUnavailable("backupSetup"))
        assertEquals("backup-restore-unavailable", NativeProductRoutes.backupPresentationUnavailable("backupRestore"))
        assertNull(NativeProductRoutes.backupPresentationUnavailable("account"))
        assertNull(NativeProductRoutes.backupPresentationUnavailable("https://example.com"))
    }

    @Test fun `sudoku family projects the durable game and is omitted when absent`() {
        val puzzle = SudokuPuzzle.generate(648)
        val emptyCell = puzzle.givens.indexOfFirst { it == 0 }
        val game = SudokuGame.fresh(puzzle).placeValue(3, emptyCell)

        assertFalse(JSONObject(productSnapshot(fixture, 1, true, 1_000)).has("sudoku"))
        val enabled = fixture.copy(privacy = PrivacyOwnerState(logging = LoggingSettings()))
        assertTrue(enabled.canRetainSudoku())
        val projected = JSONObject(productSnapshot(enabled, 1, true, 1_000, sudoku = game))
        assertTrue(projected.getJSONObject("logging").getBoolean("keepLavaGuardProgress"))
        for (redacted in listOf(
            fixture,
            enabled.copy(productAvailable = false),
            enabled.copy(privacy = enabled.privacy!!.copy(inFlight = true)),
            enabled.copy(privacy = PrivacyOwnerState(logging = LoggingSettings(keepLavaGuardProgress = false))),
        )) {
            assertFalse(redacted.canRetainSudoku())
            assertFalse(JSONObject(productSnapshot(redacted, 1, true, 1_000, sudoku = game)).has("sudoku"))
        }
        val background = JSONObject(productSnapshot(enabled, 1, false, 1_000, sudoku = game))
        assertFalse(background.has("logging"))
        assertFalse(background.has("sudoku"))
        val sudoku = projected.getJSONObject("sudoku")
        assertEquals(81, sudoku.getJSONArray("values").length())
        assertEquals(81, sudoku.getJSONArray("notes").length())
        val givens = sudoku.getJSONObject("puzzle").getJSONArray("givens")
        assertEquals(puzzle.givens, List(givens.length()) { givens.getInt(it) })
        assertEquals(3, sudoku.getJSONArray("values").getInt(emptyCell))
    }

    @Test fun `backup family emits a nullable enablement and never a false off`() {
        // No owner evidence crosses as an explicit unavailable with a null value.
        val unavailable = JSONObject(productSnapshot(fixture, 1, true, 1_000)).getJSONObject("backup")
        assertEquals("unavailable", unavailable.getJSONObject("enablement").getString("state"))
        assertTrue(unavailable.getJSONObject("enablement").isNull("value"))

        val pending = BackupSnapshot(
            enablement = BackupEnablementPresentation(
                state = BackupEnablementState.DELETION_PENDING, value = null,
                canEnable = false, canDisable = false, canBackUp = false, canRestore = false,
                canChangeAutomatic = false, canRetryDeletion = true,
            ),
            configured = false, detail = null, remoteStatus = BackupRemoteStatus.UNAVAILABLE,
            remoteAvailable = false, remoteUploadedAtEpochSeconds = null, statusUnavailable = true,
            needsAttention = true, deletionPending = true, backingUp = false, busy = false, automatic = false,
        )
        val emitted = JSONObject(productSnapshot(fixture, 1, true, 1_000, backup = pending)).getJSONObject("backup")
        assertTrue(emitted.getJSONObject("enablement").isNull("value"))
        assertEquals("deletionPending", emitted.getJSONObject("enablement").getString("state"))
        assertTrue(emitted.getBoolean("deletionPending"))
        assertEquals("unavailable", emitted.getString("remoteStatus"))
        // The automatic-upload preference is owner-confirmed and crosses as a plain boolean; no
        // envelope, key slot, recovery phrase or restore-review handle is ever serialized.
        val automatic = JSONObject(
            productSnapshot(fixture, 1, true, 1_000, backup = pending.copy(automatic = true)),
        ).getJSONObject("backup")
        assertTrue(automatic.getBoolean("automatic"))
        for (key in listOf(
            "envelope", "keySlot", "keySlots", "recoveryPhrase", "password", "passkey",
            "deviceSecret", "serverRecoveryShare", "payloadCiphertext", "review",
        )) {
            assertFalse("backup must not carry $key", automatic.has(key))
        }
    }

    @Test fun `account family is omitted without an owner and projects owner-confirmed link state`() {
        // No account owner: the family is omitted entirely, never a fabricated signed-in state.
        assertFalse(JSONObject(productSnapshot(fixture, 1, true, 1_000)).has("account"))

        val presentation = projectAccount(
            snapshot = AccountSnapshot(
                generation = 3L,
                availability = AccountAvailability.SIGNED_IN,
                providers = setOf("google"),
            ),
            googleAvailable = true,
            signInProvider = null,
            deleting = false,
            message = "",
        )
        val account = JSONObject(
            productSnapshot(fixture, 1, true, 1_000, account = presentation),
        ).getJSONObject("account")
        assertTrue(account.getBoolean("signedIn"))
        assertTrue(account.getBoolean("googleConnected"))
        assertFalse(account.getBoolean("appleConnected"))
        assertTrue(account.getBoolean("googleAvailable"))
        assertFalse(account.getBoolean("appleAvailable"))
        assertEquals("Signed in with Google", account.getString("status"))
        // No credential, token, user id or email field is ever serialized across the bridge.
        for (key in listOf("token", "accessToken", "refreshToken", "userId", "email")) {
            assertFalse("account must not carry $key", account.has(key))
        }
    }

    @Test fun `catalog family is omitted without an owner and projects provenance and status`() {
        // No sync owner: the family is omitted entirely rather than inventing a remote catalog.
        assertFalse(JSONObject(productSnapshot(fixture, 1, true, 1_000)).has("catalog"))

        val withCatalog = JSONObject(
            productSnapshot(
                fixture, 1, true, 1_000,
                catalog = CatalogSyncSnapshot(
                    provenance = CatalogProvenance.LAST_KNOWN_GOOD,
                    catalogVersion = "catalog-9",
                    generatedAtEpochSeconds = 1_700_000_000L,
                    sourceCount = 34,
                    guardrailCount = 2,
                    fresh = true,
                    ageSeconds = 60L,
                    refreshing = false,
                    lastStatus = CatalogRefreshStatus.UPDATED,
                ),
            ),
        ).getJSONObject("catalog")
        assertEquals("lastKnownGood", withCatalog.getString("installed"))
        assertEquals("catalog-9", withCatalog.getString("version"))
        assertEquals(34, withCatalog.getInt("sourceCount"))
        assertEquals(2, withCatalog.getInt("guardrailCount"))
        assertTrue(withCatalog.getBoolean("fresh"))
        assertEquals(60L, withCatalog.getLong("ageSeconds"))
        assertEquals("updated", withCatalog.getString("lastStatus"))
    }

    @Test fun `vpn family is omitted without a composition and never carries key material`() {
        // No live chained composition: the family is omitted entirely rather than inventing one.
        assertFalse(JSONObject(productSnapshot(fixture, 1, true, 1_000)).has("vpn"))

        val privateKey = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })
        val row = com.lavasec.app.platform.ChainedConfigurationDraftRow("vpn.example.com:51820", "Full tunnel", true)
        val family = VpnFamilySnapshot(
            setup = true, enabled = true, canEnable = true, canEdit = true, fallback = false,
            canChangeFallback = true, needsPlus = false, busy = false, restriction = "", error = "",
            unavailable = false, needsRepair = false, generation = "42", rows = listOf(row),
            rotationNote = "Turn protection off and on to use the saved configuration.",
            draft = com.lavasec.app.platform.ChainedConfigurationDraft(
                id = "e1", generation = 42L, revision = 3L, changed = true,
                containsFullTunnel = true, removeStaged = false, rows = listOf(row),
            ),
        )
        val raw = productSnapshot(fixture, 1, true, 1_000, vpn = family)
        assertFalse("the snapshot must never contain the private key", raw.contains(privateKey))
        val vpn = JSONObject(raw).getJSONObject("vpn")
        assertTrue(vpn.getBoolean("setup"))
        assertEquals("42", vpn.getString("generation"))
        assertEquals(family.rotationNote, vpn.getString("rotationNote"))
        assertEquals("", JSONObject(productSnapshot(fixture, 1, true, 1_000,
            vpn = VpnFamilySnapshot.unavailable())).getJSONObject("vpn").getString("rotationNote"))
        assertEquals("e1", vpn.getJSONObject("draft").getString("id"))
        assertEquals(1, vpn.getJSONArray("rows").length())
        // The family is a display-safe projection: configuration text and key material never cross.
        for (key in listOf("privateKey", "presharedKey", "private_key", "configuration", "conf")) {
            assertFalse("vpn must not carry $key", vpn.has(key))
        }
    }

    @Test fun `a command receipt reuses the exact already published projection and preserves its result`() {
        val published = productSnapshot(fixture, 41, true, 1_000)
        val response = JSONObject().put("status", "completed").put("value", false).toString()
        val reply = JSONObject(nativeCommandPublication(response, published))
        assertEquals("completed", reply.getString("status"))
        assertFalse(reply.getBoolean("value"))
        assertEquals(JSONObject(published).toString(), reply.getJSONObject("snapshot").toString())
        assertEquals(41L, reply.getJSONObject("snapshot").getLong("revision"))
        // A later projection exists, but cannot silently replace the observer's command receipt.
        val later = JSONObject(productSnapshot(fixture, 42, true, 2_000))
        assertEquals(42L, later.getLong("revision"))
        assertEquals(41L, reply.getJSONObject("snapshot").getLong("revision"))
    }

    @Test fun `normal presentation requires its exact captured native authentication revision`() {
        assertTrue(nativePresentationRevisionMatches("7", 7L))
        for (wire in listOf(null, "", "6", "8", "07", 7, 7L, true)) {
            assertFalse("Missing malformed or successor turn must not authorize normal paint", nativePresentationRevisionMatches(wire, 7L))
        }
        assertFalse("Queued higher-serial publication cannot borrow the resumed turn", nativePresentationRevisionMatches("7", 8L))
        assertTrue(nativePresentationRevisionMatches("8", 8L))
        // No native security owner has no private grant. Keep that unavailable bootstrap truthful.
        assertTrue(nativePresentationRevisionMatches(null, null))
    }

    @Test fun `failed or retired paint preserves durable completion cancellation and refusal without successor fields`() {
        val originals = listOf(
            JSONObject().put("status", "completed").put("value", "saved"),
            JSONObject().put("status", "cancelled").put("reason", "authentication").put("value", false),
            JSONObject().put("status", "rejected").put("reason", "stale-review"),
        )
        for (original in originals) for (failure in NativeCommandProjectionFailure.entries) {
            val reply = JSONObject(nativeCommandPublication(original.toString(), null, failure))
            assertEquals(original.getString("status"), reply.getString("status"))
            assertEquals(original.opt("value"), reply.opt("value"))
            assertEquals(original.opt("reason"), reply.opt("reason"))
            assertEquals(failure.wireName, reply.getString("projectionFailure"))
            assertFalse("A missing exact publication cannot expose a successor", reply.has("snapshot"))
        }
    }
}
