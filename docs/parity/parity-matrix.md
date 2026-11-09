# Android/iOS parity matrix — slice 07 acceptance rows

Checked in for the parity-07 QA gate. Every shipped user flow has one row naming
the Android route, the iOS source anchor, the durable owner/state contract, the
automated test class that pins the behavior, and the visual fixture the
evidence harness captures. "Deferred" fixtures carry the written platform
decision instead of a screenshot.

Sources of truth:

- Android baseline: this repository at the slice-07 head (stack
  `stack/android-parity-07-qa-gates`).
- iOS baseline: `lavasec-ios` commit `55aad81` on `promote/main`.
- Device evidence: Pixel 3 API 28 (1080×2160) and API 36, captured with
  `scripts/capture-parity-evidence` (see `docs/parity/visual-evidence-harness.md`).
- Geometry/semantics acceptance: `ProductShellGeometryTest`,
  `ProductScreenContractTest`, `ProductShellSemanticsTest`, and
  `ActivityScreenSemanticsTest` in `:presentation` (API 28/API 36).

Android routes are canonical `ProductNavigation` paths (`[Guard]`, `[Guard,
Activity, TopDomains]`, ...). iOS anchors are file paths in the pinned iOS
checkout.

| # | User flow | Android route | iOS source anchor (55aad81) | Owner / state contract | Test class | Visual fixture |
|---|---|---|---|---|---|---|
| 1 | Fresh install + onboarding | `MainActivity` composes `OnboardingScreen` until commit | `LavaSecApp/OnboardingFlowView.swift` `LavaOnboardingView` | `OnboardingOwner` (foundation reducer + durable commit); `OnboardingUiState` | `OnboardingScreenTest`, `OnboardingOwnerTest`, `MainProductViewModelTest` | `onboarding` (operator walk) |
| 2 | Guard off | `[Guard]` | `LavaSecApp/GuardView.swift` `GuardView` | `ProtectionOwner`; `GuardProtectionHealthUi` (`INACTIVE`) | `GuardScreenTest`, `GuardStatusMappingTest`, `ProtectionPresentationTest` | `guard-off` |
| 3 | Guard on (protected) | `[Guard]` | `GuardView.swift` `ProtectionStatusPanel` | `ProtectionOwner`; `GuardLifecycleUi.PROTECTED` + `GuardConnectivityUi.HEALTHY` | `GuardScreenTest`, `ProtectionOwnerTest` | `guard-protected` |
| 4 | Guard paused | `[Guard]` long-press pause menu | `LavaSecApp/TemporaryProtectionPauseController.swift` | `ProtectionOwner` pause deadline; `GuardPauseMenuUi` | `GuardScreenTest`, `GuardStatusMappingTest` | `guard-paused` |
| 5 | Guard reconnecting | `[Guard]` | `LavaSecApp/ProtectionConnectivityPresentation.swift` | `ProtectionOwner`; `GuardLifecycleUi.RECONNECTING` | `GuardScreenTest`, `GuardStatusMappingTest` | `guard-reconnecting` |
| 6 | Guard fallback (device / encrypted) | `[Guard]` | `LavaSecApp/ProtectionConnectivityPresentation.swift` | `ProtectionOwner` fallback state; `GuardConnectivityUi.DEVICE_DNS_FALLBACK` / `ENCRYPTED_FALLBACK` | `GuardStatusMappingTest`, `ProtectionPresentationTest` | `guard-fallback` |
| 7 | Filters overview | `[Guard, Filters]` | `LavaSecApp/FiltersView.swift` `FiltersView` | `FilterLibraryOwner` state; `FilterLibraryState` + `FilterOverviewNavigation` | `FiltersScreenTest`, `ProductShellSemanticsTest` | `filters-overview` |
| 8 | Filter detail (active, "My list") | `[Guard, Filters, FilterDetail]` | `LavaSecApp/FilterMyListView.swift` `MyListCover` | `FilterLibraryOwner`; `FilterDetailState` (draft overlay, in-effect/frozen facts) | `FilterDetailScreenTest` | `filter-detail` |
| 9 | Filter editor (catalog toggles, block/allow sheets, validators) | `[Guard, Filters, FilterDetail]` edit mode | `LavaSecApp/FilterDomainSheets.swift` `AddBlockedDomainSheet` / `AddAllowedExceptionSheet` | `FilterLibraryOwner` draft ports; `FilterDetailState.editing` | `FilterDetailScreenTest` | `filter-editor` |
| 10 | Filter library (create, rename, staged delete, restore, cap) | `[Guard, Filters, FilterLibrary]` | `LavaSecApp/FilterLibraryView.swift` `AllFiltersView` | `FilterLibraryOwner`; `FilterLibraryState` + `FilterLibraryActions` + `FilterLibraryMutationUiState` | `FilterLibraryScreenTest`, `FilterLibraryOwnerTest`, `CredentialFilterLibraryStateTest` | `filters-library` |
| 11 | Filter review (grouped diff, allow warning, disabled Confirm) | `[Guard, Filters, FilterDetail, FilterReview]` | `LavaSecApp/FilterReviewFlowView.swift` `FilterConfirmationSheet` | `FilterLibraryOwner`; `FilterReviewState` + `FilterDraftDiffUi` | `FilterReviewScreenTest` | `filter-review` |
| 12 | Filter preparation + apply | `[Guard, Filters, FilterDetail, FilterReview, FilterPreparation]` | `FilterReviewFlowView.swift` `FilterPreparationScreen` | `FilterLibraryOwner` mutation coordinator; `FilterPreparationUiState` | `FilterPreparationScreenTest`, `FilterLibraryOwnerTest` | `filter-preparing` |
| 13 | Filters share / import | No Android UI rows (platform import path pending) | `LavaSecApp/ShareableFiltersUI.swift` `ShareFiltersSheet` / `ImportFiltersFlow` | `FilterShareContract` + `FilterImportPreflight` payload contract; no UI row until the platform import path lands | `FilterShareContractTest`, `FilterImportPreflightTest` | **deferred** — decision in `docs/parity/first-build.md` (share/import UI rows stay absent; no inert buttons) |
| 14 | Settings root | `[Settings]` | `LavaSecApp/SettingsView.swift` `SettingsView` | `SettingsScreen` rows from `settingsRowStates` (typed state, never copy-compared) | `SettingsScreenTest`, `ProductShellGeometryTest` | `settings-root-top`, `settings-root-middle`, `settings-root-max` |
| 15 | Account & Backup | `[Settings, Account]` | `LavaSecApp/AccountBackupSettingsView.swift` `AccountSettingsView` | `AccountUiState.Unavailable` (honest unavailable state) | `AccountSettingsScreenTest` | `account-unavailable` |
| 16 | Upgrade | `[Settings, Upgrade]` | `LavaSecApp/SettingsView.swift` upgrade destination | `EntitlementUiState.Unavailable` | `AccountSettingsScreenTest` (upgrade route), `SettingsScreenTest` | `upgrade-unavailable` |
| 17 | Customization | `[Settings, Customization]` | `LavaSecApp/CustomizationSettingsView.swift` `CustomizationSettingsView` | `CustomizationOwner` + `CustomizationPreferenceStore`; `CustomizationUiState` | `CustomizationSettingsScreenTest`, `CustomizationOwnerTest` | `customization` |
| 18 | Privacy & data (retention toggles, clear, export) | `[Settings, PrivacyData]` | `LavaSecApp/PrivacySecuritySettingsView.swift` `PrivacyDataSettingsView` | `PrivacyOwner` + `CredentialPrivacyFence`; `PrivacyDataUiState` | `PrivacyDataScreenTest`, `LocalPrivacyServicesTest`, `CredentialPrivacyFenceTest` | `privacy-data` |
| 19 | Security (passcode, lock timeout, protected surfaces) | `[Settings, Security]` | `PrivacySecuritySettingsView.swift` `SecuritySettingsView` | `SecurityOwner` + `AndroidKeystorePasscodeVerifier`; `SecurityUiState` | `SecuritySettingsScreenTest`, `SecurityOwnerTest` | `security` |
| 20 | Bug report | `[Settings, BugReport]` | `LavaSecApp/BugReportSettingsView.swift` `BugReportSettingsView` | `DiagnosticsFeedbackOwner` + `LocalFeedbackComposer`; `BugReportUiState` (opt-in attachments, redaction) | `BugReportScreenTest`, `MainProductViewModelBugReportTest` | `bug-report` |
| 21 | Legal notices | `[Settings, LegalNotices]` | `LavaSecApp/LegalVersionSettingsView.swift` `LegalNoticesView` | `CatalogLegalNotices`; `LegalNoticeSectionUi` | `ProductShellSemanticsTest` | `legal-notices` |
| 22 | Nerd stats (version, tunnel health) | `[Settings, NerdStats]` | `LegalVersionSettingsView.swift` `VersionNerdStatsView` | `CredentialOperationalDiagnosticsOwner`; `NerdStatsUiState` | `NerdStatsScreenTest`, `CredentialOperationalDiagnosticsOwnerTest` | `nerd-stats` |
| 23 | DNS Resolver: provider, transport, fallback, custom, validation | `[Settings, DnsResolver]` | `LavaSecApp/DNSResolverSettingsView.swift` `DNSResolverSettingsView` | `ResolverPlanSwitchOwner`; `SettingsResolverUiState` (plan, in-flight, honest unavailable reasons for DoT/DoQ/custom) | `DnsResolverSettingsScreenTest`, `ResolverPresetPresentationTest`, `ResolverHealthUiTest`, `ResolverPlanSwitchOwnerTest` | `dns-resolver` |
| 24 | Activity overview + date ranges (24-month window) | `[Guard, Activity]` | `LavaSecApp/DiagnosticsView.swift` `ActivityView` | `ActivityOwner`; `ActivityUiState` (counts, flow bar, date control) | `ActivityPresentationTest`, `ActivityScreenSemanticsTest`, `ActivityOwnerTest` | `activity-overview` |
| 25 | Top Domains | `[Guard, Activity, TopDomains]` | `LavaSecApp/DiagnosticsTopDomains.swift` `TopDomainsView` | `ActivityOwner`; `ActivityContentState<TopDomainsSnapshot>` | `ActivityPresentationTest`, `ActivityScreenSemanticsTest` | `top-domains` |
| 26 | Domain History | `[Guard, Activity, DomainHistory]` | `LavaSecApp/DiagnosticsDomainHistory.swift` | `ActivityOwner`; `ActivityContentState<DomainHistorySnapshot>` + sentinel paging | `ActivityPresentationTest`, `ActivityScreenSemanticsTest` | `domain-history` |
| 27 | Domain action review (copy/block/allow under immutable review) | dialog over `[Guard, Activity, *]` | `LavaSecApp/DiagnosticsDomainHistory.swift` review surface | `DomainActionOwner` + `NativeDomainActionReviewBridge`; `DomainActionUiState` | `ActivityScreenSemanticsTest`, `DomainActionOwnerTest`, `NativeDomainActionReviewBridgeTest` | `domain-action-review` |
| 28 | Network Activity: retention, clear, export | `[Settings, NetworkActivity]` | `LavaSecApp/DiagnosticsNetworkActivity.swift` `NetworkActivityLogView` | `CredentialNetworkActivityRecorder` + runtime store (300-row cap, 7-day retention); `ActivityContentState<NetworkActivitySnapshot>` | `NetworkActivityScreenTest`, `CredentialNetworkActivityRecorderTest`, `CredentialNetworkActivityRuntimeStoreTest`, `LocalPrivacyServicesTest` | `network-activity-events`, `network-activity-empty` |
| 29 | Ambient protection notification | ongoing notification (no route) | `LavaSecApp/LavaLiveActivityController.swift` (Live Activity equivalent) | `ProtectionAmbientNotificationOwner`; `ProtectionAmbientUiState` (domain-free, PRIVATE lock-screen, LOW importance, app-driven removal) | `ProtectionAmbientNotificationFactoryTest`, `ProtectionAmbientUiStateTest`, `ProtectionAmbientNotificationOwnerTest` | `ambient-notification` |
| 30 | Widget / Quick Settings tile / launcher shortcuts | Not shipped | `LavaSecWidget/LavaSecWidget.swift`, `LavaSecApp/SwitchFilterShortcut.swift` | No Android component (VPN consent cannot be driven from a background component) | `ProtectionAmbientNotificationFactoryTest` pins the reused open-app intent semantics | **deferred** — decision in `docs/parity/first-build.md` (open-app shortcut semantics live in the ambient notification; no partial component ships) |

## Cross-cutting acceptance (rows above reference these)

| Concern | Android mechanism | iOS anchor | Test | Fixture |
|---|---|---|---|---|
| Geometry tokens (18dp inset, 64dp row, 38dp badge, 14dp badge gap, 10dp card gap, 56dp Guard action, 96dp guardian, 16dp clearance) | `LavaMetrics` / `LavaNavigationCardMetrics` / `GuardMetrics` consumed by shell and screens | `SettingsView.swift`, `RootView.swift`, `GuardView.swift` `ProtectionStatusMetrics` | `ProductShellGeometryTest` + `ParityGeometryAssertions` | shell geometry is dp-asserted, not screenshot-diffed |
| Screen tags + headings + merged navigation rows | root `testTag` + `heading()` on every screen; `LavaNavigationCard` merges title/summary | — | `ProductScreenContractTest`, `LavaComponentsTest` | — |
| Localization (10 locales) | `values/strings.xml` + 9 locale trees in `:app`, `:presentation`, `:vpn` | `LavaSecApp/LavaStrings.swift` | `ci/test_android_localization.py` | TalkBack/1.3× font scale device pass |
| Dark mode + contrast | `LavaDarkColorScheme` (static palette, dynamic color opt-in API 31+) | — | `LavaThemeContrastTest`, `LavaThemeApi28Test` | dark-mode coverage notes in `qa-gates.md` |
| Reduce motion | `LocalLavaReducedMotion` + Customization toggle | `CustomizationSettingsView.swift` | `ProductShellSemanticsTest` (bounds stability), `CustomizationSettingsScreenTest` | reduce-motion device pass |
| Lifecycle / privacy / security | generation-fenced owners, `allowBackup="false"`, redaction fence, Direct Boot split | — | `MainActivityDeepLinkTest`, `ApplicationGraphLifecycleOwnerTest`, `MainProductViewModelBugReportTest`, `CredentialPrivacyFenceTest`, `ci/test_android_foundation_contract.py` | rotation/force-stop checklist in `qa-gates.md` |
