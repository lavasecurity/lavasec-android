# Android first-build parity contract

The 2026-09-28 foreground now uses the [shared React Native launcher](shared-react-native.md)
from infra #308's `c4658d9` baseline. That frontend receipt records its supported
screens and remaining gaps; the older owner checkpoints below remain historical.

Checkpoint 25 composes the production Android host against the pinned behavior
source `lavasec-ios-internal@e10aa3c0101cc99511ad667029a32a0bcdc998cf`.
It supersedes checkpoint 24's deliberately static host while preserving the
earlier checkpoint sections below as historical evidence. It does not claim a
signed release candidate, Play delivery, external HTTPS App Link verification,
or checkpoint 26 device qualification.

## Checkpoint 25 production composition boundary

`MainActivity` owns one generation-bound `MainProductViewModel`. Each committed
credential generation replaces and closes the previous onboarding, protection,
Activity, privacy, and diagnostics owners. Deep links remain navigation-only and
are staged until onboarding commits; VPN consent is identified and consumed
exactly once. A link arriving during the durable onboarding commit waits on the
same owner boundary and is released after commit rather than dropped. API 33+
notification permission and the pre-33 handled path are explicit Android host
responsibilities.

The production composition now provides:

- credential-protected onboarding/configuration with fresh-generation rotation;
- an authenticated Direct Boot snapshot and host that can resume the verified
  public filter artifact before credential unlock, then swaps atomically to the
  credential host after unlock reconstruction;
- pinned filter-source acquisition, compilation, atomic publication, and exact
  active-filter authority for the local consumer `VpnService`;
- generation-fenced protection commands, local DNS tunnel lifecycle, health,
  Always-on ownership, connectivity, and diagnostic evidence;
- sticky `AwaitingNetwork` ownership with an exact eligible-network callback,
  plus generation-fenced, wall-clock-revalidated timed-pause expiry and
  state/heartbeat restoration;
- confirmed local counts, uptime, Top Domains, Domain History, and reviewed
  domain actions, without synthetic rows, totals, or optimistic success; and
- explicit retention toggles, export/share, confirmed Activity/diagnostics
  clearing, and feedback preparation with separate diagnostics/export opt-ins.

Only the boot-safe protection snapshot is device-protected. Credentials,
detailed Activity, exports, and diagnostics remain credential-protected.
Detailed Domain History is pruned at seven days. Disabling a retention class is
a privacy fence: new records stop and retained data is cleared through the
committed owner. There is no telemetry uploader and no historical backfill.
Nothing leaves the device unless the user explicitly shares a completed export
or includes selected local evidence in prepared feedback.

Retention opt-out commits its restrictive SQLite policy and deletion before the
credential setting write. Retention opt-in remains at the intersection of the
current and requested policies until the credential authority commits, so a
failed durable enablement cannot admit new Activity data. After a durable
commit, the SQLite policy is retried within the privacy fence. If that bounded
reconciliation still fails, the committed setting remains authoritative, the
exact current credential graph is retired, and replacement graph construction
reapplies the credential setting before admitting any Activity producer. Every
reconciliation request carries the failed graph generation, and the lifecycle
owner ignores it if a successor is already installed.

The Direct Boot host admits only the authenticated public artifact and the
boot-safe resolver/protection fields. It emits no credential Activity,
diagnostics, or telemetry evidence and performs no unlock backfill. Credential
pause state is intentionally not mirrored: before unlock an eligible durable
protection intent filters rather than making an unverifiable paused claim.
Synchronous cold-process startup authenticates only the small public envelope.
Full artifact verification runs on the IO dispatcher when the asynchronous
credential graph compares an existing mirror before any fresh-key publication,
and when a VPN attachment reopens that exact artifact. Mirror coherence requires
both matching behavior state and an artifact SHA/identity equal to credential
filter authority, so an unchanged configuration cannot retain stale public rule
bytes. A fully coherent public mirror is preserved rather than needlessly
invalidated. Device-to-credential and credential-to-device registry swaps attach
the replacement before detaching the predecessor, so an Always-on start cannot
observe an empty registry during graph rotation.

Awaiting-network retries retain their exact registration until recovery or
terminal ownership. Terminal completion removes and closes that callback before
publishing retirement. A retry failure exits the reconnect serialization
boundary before entering the service command owner, preventing the opposing
reconnect/service-monitor lock order.

The production UI renders onboarding until commit and then observes authoritative
Guard, Activity, privacy, and diagnostics state. The Filters destination now
ships the full filter product: the educational connection-preview overview with
the Phone → Lava → Internet diagram and the "What's filtering?" rows, per-filter
detail editing (catalog source toggles, block/allow domain sheets with the
exact editor validators), the grouped review diff with the allow-exception
warning and a disabled Confirm on any rejection, the full-screen preparation
phases (preparing/success/failed with retry and back-to-review), and the
multi-filter library (create, duplicate-from, rename, staged delete with
confirmation, restore defaults, Apply versus View & edit, frozen read-only
filters, and the free-tier three-filter cap). Every durable mutation — draft
commit, library edit, restore defaults, and whole-profile switches — commits
whole-profile switches through the mutation coordinator
(the generation-fenced filter authority and artifact stores), so a filter
change persists only after a verified artifact and the previous active artifact
stays in effect on failure. Filter drafts are independent per filter id, so
switching filters never saves a draft into the newly active filter. Share and
import ship their versioned payload contract (size-bounded, integrity-checked,
domain-validated with the local-editing validators) in platform-contracts with
tests; the share/import UI rows are absent from the overview until the platform
import path lands — no inert buttons. Settings surfaces the
Keep local Network Activity retention toggle through the exact
logging-settings path that already persists it, and the DNS Resolver settings
switch the persisted resolver plan across the onboarding plan choices through
the same mutation coordinator with a committed runtime reload. A bounded
on-device Network Activity producer now records only events it can truthfully
derive — admitted protection on/off/reconnect commands, exact protection and
health transitions (fallback activation, fallback recovery, reconnect-needed,
connectivity recovery), and default-network changes — into the
credential-scoped in-memory runtime store with the contract's 300-row cap and
7-day retention. The store is generation-scoped, so a graph rotation drops
retained rows; durable persistence remains out of scope. Fallback activation
carries the honest ProducerUnspecified cause, reconnect-needed carries the
honest RuntimeUnavailable reason, and device-DNS transport captures the
engine's always-first UDP/53 path because the app process has no per-query
transport evidence. Settings → Advanced gains the Network Activity destination
with the iOS privacy panel, paged rows, empty state, scoped clear
confirmation, and the honest "Local network activity off" disabled state.
These are intentional unavailable capabilities, not empty success states or
disabled fake controls.

The application manifest denies backup and cleartext traffic, explicitly marks
every component's exposure, keeps the Direct Boot receiver and both providers
private, and exposes only the exact launcher/deep-link Activity and
`BIND_VPN_SERVICE`-protected VPN service. The export provider grants only its
narrow cache path. The HTTPS App Link remains an application-side declaration;
hosted verification evidence is still pending.

Checkpoint 25 verification is source-build evidence only. The release AAB is
unsigned source-build AAB output and not a Play candidate. Exact-candidate API
28/API 36 emulator, physical-device, signed RC, Play Internal, telemetry,
retention/privacy, upgrade/rollback, accessibility, visual, and operational
qualification belong to checkpoint 26.

## Historical checkpoint 22 shell contract

| iOS journey | Android checkpoint 22 | Status/reason |
|---|---|---|
| protection home | Guard native Material screen with exhaustive lifecycle/health presentation | Pure mapper implemented; host currently supplies authoritative Off, unknown health, and no action callback |
| Filters | Guard child that reports live filter evidence unavailable | Navigation present; no fake rule count, selection, disabled mutation control, or enforcement claim |
| Activity | Guard child with native Activity presentation for overview, Top Domains, Domain History, and reviewed domain actions | Presentation and deterministic test state are complete for this subset; production remains unavailable with no fake zero totals, rows, callbacks, or collection claim |
| Settings | Material Settings root with Android baseline, version/build, and external Help | Only backed information/actions render; resolver, privacy, security, account, subscription, and other unbacked controls are omitted |
| iOS glass and root navigation | Material surfaces with adaptive Android navigation | Intentional native deviation: bottom `NavigationBar` below 600dp, `NavigationRail` at or above 600dp |
| SF Symbols and iOS gestures | Project-owned semantic vectors and Android system Back | Intentional native deviation; child Back returns to Guard and root Back delegates to the host |
| iOS platform wording | Android VPN, network, notification, and system-settings wording | Intentional platform-native deviation |

Guard and Settings are the only roots. Filters and Activity exist only as Guard
children. MainActivity deliberately supplies `ProtectionState.Off`, no health
assessment, and no protection callback. The composed Guard UI therefore omits
the Turn On control entirely; it does not render an inert or nonfunctional
action. The pure presentation mapper still models `TurnOn` and covers starting,
waiting, reconnecting, protected health/fallback, paused, stopping, failure,
Android Always-on ownership, and API-28 unknown ownership without optimistically
inventing a protected state. Runtime controller composition is later work.

At checkpoint 22, the service deliberately not connected statement described
the then-static host composition. It is historical and was superseded by
checkpoint 25. “Disabled Filters destination” described no mutation surface
while enforcement pending; checkpoint 25 kept Filters unavailable until its
editor was composed, and the later read-only-library slice replaced that shell
with the seeded profile library plus coordinator-backed whole-profile Apply
switching. “Material surfaces/dynamic color” remains the recorded
Intentional platform-native deviation, with dynamic color opt-in rather than
automatic.

## Navigation-only link contract

Only these custom/web pairs are accepted:

| Destination | Custom scheme | HTTPS manifest route |
|---|---|---|
| Guard | `lavasecurity://guard` | `https://lavasecurity.app/app/guard` |
| Filters | `lavasecurity://filters` | `https://lavasecurity.app/app/filters` |
| Activity | `lavasecurity://activity` | `https://lavasecurity.app/app/activity` |
| Settings | `lavasecurity://settings` | `https://lavasecurity.app/app/settings` |

Parsing rejects queries, fragments, user info, explicit ports, extra path
segments, alternate schemes/hosts, unknown routes, and configuration/import
subroutes. An accepted link changes only the canonical navigation path; it
cannot complete onboarding, mutate configuration, import/apply a filter, or
invoke a protection command. This implements the security correction to the
pinned iOS behavior that had coupled routing to onboarding completion.
Warm links are delivered through `singleTop`. After process restoration, a
fresh valid `ACTION_VIEW` link takes precedence over stale saved navigation;
the old Activity records `isChangingConfigurations` during `onDestroy` in an
explicitly retained, process-local ViewModel marker that the replacement
Activity consumes once. Non-configuration destruction does not mark the
handoff. Task-history relaunch preserves the user's saved path, while invalid
and non-view intents fail closed to that restored path.

Top Domains and Domain History are in-app-only grandchildren. Their canonical
paths are `[Guard, Activity, TopDomains]` and
`[Guard, Activity, DomainHistory]`; Back pops one level to the Activity overview.
Neither grandchild has a custom-scheme or HTTPS route. Cold and warm Activity
links still open only `[Guard, Activity]`, including after restoration, and an
invalid grandchild route preserves the exact current path and delivery sequence.

The manifest includes a browsable custom-scheme filter and an auto-verified
HTTPS filter restricted to `lavasecurity.app` with `/app/` path prefix. This is
only the application-side manifest contract. No externally hosted
`assetlinks.json` or successful Android domain verification is evidenced here.

## Historical checkpoint 22 consumer VPN and availability boundary

Android Always-on VPN is ordinary consumer-VPN ownership/system-settings
behavior here. Checkpoint 22 contains no managed-device enrollment or
provisioning, MDM or DPC integration, device/profile-owner or device-admin
behavior, or `DevicePolicyManager` control.

Filters explicitly says that live evidence is unavailable. Activity now owns
the complete native presentation contract, but the production host supplies the
exact unavailable value and no capability callbacks because its repository and
runtime composition do not exist yet. Unavailable capability is not represented
as a fake zero, empty success state, retry, query, paging, mutation, or disabled
control. Settings omits capabilities that have no backing implementation.

## Historical checkpoint 23 product-coordination boundary

The new `:product` module is policy and port coordination only. Its production
sources cannot use Android/Compose/runtime modules, filesystem/network/database
APIs, Android resources, assets, AIDL, native payloads, or unreviewed
`:foundation` APIs. Root/settings injection and version-catalog alias drift are
also rejected so the checked-in module file cannot be bypassed externally.

Activity coordination now defines:

- immutable summary, Top Domains, Domain History, retained-log availability,
  and Network Activity values;
- exact date/search/decision and data/policy revision provenance for every
  publication;
- exact IANA/TZDB local-day starts within the pinned 24-month-through-today
  window;
- stable 30-row pages with one private sentinel, one frozen first-page cut,
  deterministic append/deduplication, and the 300-row Network Activity cap; and
- request-epoch, cancellation-owner, revision, and query fences that keep
  stale or superseded results from publishing or suppressing retry.

Domain actions follow the pinned user flow while remaining adapter-free:
authentication can stage only an immutable allow/block/remove review, the
native surface must confirm it explicitly, and confirmation rechecks the exact
clean filter authority before an atomic generation-fenced mutation. A failed
or cancelled attempt remains retryable; ambiguous returned or thrown
acknowledgements use a bounded authority reconciliation and never become
optimistic success.

Checkpoint 23 does not wire these contracts to production storage,
authentication, filter publication, VPN/runtime state, clear/export/share,
notifications, or synthetic Activity data. Checkpoint 24 presents the immutable
values and native review lifecycle in Compose tests while deliberately leaving
the production Activity host unavailable.

## Historical checkpoint 24 native Activity presentation boundary

The native Activity presentation preserves the pinned iOS flow and stable
product ordering through a Material overview, native date dialog, Top Domains,
rolling Domain History, automatic sentinel paging, and one immutable reviewed
domain-action surface. It renders exact retained/loading/failure/disabled/empty
states without manufacturing missing evidence. Network Activity remains out of
scope.

Android deviation D-031 replaces the iOS-only long-press discovery with a
visible, 48dp, TalkBack-operable overflow menu. The semantic actions remain
exactly Copy, Block, and Allow; Block/Allow still stage an immutable review and
never mutate optimistically. Remove is not offered from Activity rows.

The seven-day local and export/bug-report disclosures are presentation copy, not
evidence of a retention job. Production Activity repository/runtime composition,
credential-backed persistence, retention, Network Activity, clear/export/share/
privacy behavior, VPN/service activation, signing, Play delivery, hosted review,
and physical-device evidence all remain pending.

`MainActivity` owns exactly one `ActivityUiState.unavailable` field, passes it to
exactly one `ProductShell` call, and passes all four Activity capability bundles
as literal `null`. Executable mutation contracts exact-audit the presentation
build and the complete canonical `MainActivity.kt` source while retaining its
state/import/header/token proof and sibling-source synthetic scans.
Compiler-backed mutations reject nonliteral, callable-reference, and
root-project dependency forms plus local, top-level, nested, root-name,
aliased, typealiased, constructor-reference, backticked, and explicitly
relabelled receiver forms. The existing runtime-composition, callback, route,
and managed-device matrices remain enforced.

Two accepted non-blocking test gaps remain:

- the Back reducer and Compose `BackHandler` are covered separately, but a real
  Android Back-dispatch integration test is not present; and
- MainActivity preserves version identity and launches the exact Help URL in
  host tests, but a rendered Compose assertion for Activity-supplied
  version/build/Help content is not present.

## Checkpoint 22 verification

Focused evidence accepted before the complete clean gate:

- navigation: 11/11 JVM tests;
- protection presentation: 11/11 JVM tests;
- shell semantics: 13/13 JVM tests;
- MainActivity links/host composition: 12/12 JVM tests;
- Python preview/foundation contracts: 32/32 tests; and
- `scripts/verify-android-foundation`: `Android foundation contract verified`.

Fresh complete evidence on 2026-08-01:

- `python3 -m unittest discover -s ci -p 'test_*.py'`: 86/86 tests passed in
  320.604 seconds;
- foundation verifier: passed;
- exact JDK 21 clean Gradle gate, run with `--no-daemon --no-build-cache`:
  successful in 3m16s, with 601 actionable tasks (`553` executed, `48`
  up-to-date, and none from the build cache);
- JVM XML results: 93 suites, 985 tests, zero failures, zero errors, and zero
  skips; and
- debug lint: all seven reports completed with zero errors. `app` reported 18
  warnings; `design-system`, `foundation`, `platform-contracts`,
  `presentation`, `test-support`, and `vpn` each reported one warning.

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| `android/app/build/outputs/apk/debug/app-debug.apk` | 45,106,937 | `38944c70877ce82f7b279b151b95e2844926c9b7f4aeff48c9167a961ba852b2` |
| `android/app/build/outputs/bundle/release/app-release.aab` | 20,994,058 | `e1adc3aca3b2e25a1a7ef2a5fdc1416c2fc1e83e3da3bd069b000c4250549c57` |
| `android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 957,113 | `1f10ba5b3b680215649e90560c2b9184b7c6096e520f9615b8620ad4c49fed88` |
| `android/vpn/build/outputs/apk/androidTest/debug/vpn-debug-androidTest.apk` | 21,916,618 | `50df16dddc2d2c802e76a13f748dbc40ba7a419549528144ff3e078aac6d2c85` |

These are local source-build artifacts, including an unsigned release bundle;
they are not device visual evidence, physical VPN evidence, a runtime protection
claim, a signed Play candidate, a Play upload/release, hosted CI, or exact-head
Kilo/Codex review evidence. The reviewable branch diff is exactly 30 paths
against the PR merge base.

Checkpoint 23 final local evidence after review hardening:

- `:product:testDebugUnitTest`: 154/154 tests across four suites, zero failures,
  errors, or skips;
- the exact product task ran with `--rerun-tasks`; all 25 tasks executed and the
  same 154/154 result passed;
- Python foundation/product-boundary checks: 40/40 passed;
- fresh whole-repository Python discovery: 101/101 passed in 242.808 seconds;
- `scripts/verify-android-foundation`: `Android foundation contract verified`;
- the exact clean JDK 21 gate, run with `--no-daemon --no-parallel
  --no-build-cache`, completed in 8m35s with 641 actionable tasks (`588`
  executed and `53` up-to-date, none from the build cache);
- JVM XML results: 97 suites, 1,139 tests, zero failures, zero errors, and zero
  skips; and
- debug lint: all eight reports completed with zero errors. `app` reported 18
  warnings; `design-system`, `foundation`, `platform-contracts`,
  `presentation`, `product`, `test-support`, and `vpn` each reported one
  warning.

The four local artifact paths, byte sizes, and SHA-256 values in the checkpoint
22 table remain exact for checkpoint 23. The new `:product` policy module is
deliberately not composed into `:app` yet, so the outputs are byte-identical;
`jarsigner -verify` confirms the release AAB is unsigned. The final reviewable
source candidate is exactly 17 paths against merged checkpoint 22.

These results prove the platform-neutral product policy boundary and the
unchanged Android application build. They are not production Activity storage
or UI evidence, a signed Play candidate, a Play upload/release, hosted CI, or
exact-head Kilo/Codex review evidence; those remain separate checkpoints.

## Checkpoint 24 verification

Final red/green focused evidence on 2026-08-02:

- the Activity screen/presentation and shell suites passed 141/141 JVM tests
  across their configured API matrix, including callback-driven
  `Ready -> Loading(null) -> Ready` query transitions and the open-dialog year
  boundary. The final four executions cover failed-page viewport reentry and a
  Russian quantity-21 plural on API 28 and API 36;
- the app bridge/mapper/deep-link suites passed 46/46 JVM tests, including the
  deterministic cancellation and pre-coordinator Applying interleavings;
- compiler-backed dependency and synthetic-binding mutations compiled before
  proving verifier rejection, and `scripts/verify-android-foundation` reported
  `Android foundation contract verified`; and
- a subsequent scoped rereview's single residual Important finding was a
  compiler-valid redeclared `MainActivity@` receiver label. Its exact fixture
  reproduced the pre-fix verifier acceptance, then a 12/12 focused matrix passed
  in 147.424 seconds after the full-file audit; and
- Kilo's first exact-head review found that a failed page could not re-admit its
  sentinel, the admission set grew per page, the singular review plural lost
  locale-selected counts such as 21, and paging tests used wall-clock polling.
  Both behavior regressions failed on API 28 and API 36 before the fix. The
  bounded visibility-cycle admission, counted plural, and deterministic idle
  assertions then passed the full presentation suite and lint. Kilo reported
  `No Issues Found / Merge` on that corrected head. Codex subsequently found a
  P2 picker that refreshed only when the enclosing year range changed, leaving
  stale selectable bounds across January 31 to February 1. The refined behavior
  regression failed on API 28 and API 36 before the picker was keyed by its
  exact minimum/maximum dates and passed on both afterward. New-head hosted
  controller rereview remains a separate gate.

Fresh complete evidence on 2026-08-02:

- `python3 -m unittest discover -s ci -p 'test_*.py'`: 119/119 tests passed in
  556.058 seconds;
- the exact clean JDK 21 Gradle gate, run with `--no-daemon`, `--no-parallel`,
  and `--no-build-cache`, completed in 8m08s with 681 actionable tasks (`624`
  executed and `57` up-to-date, none from the build cache);
- JVM XML results: 101 suites, 1,303 tests, zero failures, zero errors, and zero
  skips;
- debug lint: all eight reports completed with zero Fatal or Error issues and
  26 warnings. `app` reported 18 warnings, `presentation` reported two, and
  `design-system`, `foundation`, `platform-contracts`, `product`,
  `test-support`, and `vpn` each reported one; and
- the final reviewable source candidate is exactly 29 paths against merged
  checkpoint 23.

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| `android/app/build/outputs/apk/debug/app-debug.apk` | 45,908,879 | `28423b39e1508bc4be5b95a7c12544df43fb4b966e92ec48ecb00ba4b372704f` |
| `android/app/build/outputs/bundle/release/app-release.aab` | 21,195,044 | `7a564bdf910d9a4da0d5f3a8efe5e1bcf72c0ad1a8c9ed94718e3189cbe03f05` |
| `android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 957,113 | `1f10ba5b3b680215649e90560c2b9184b7c6096e520f9615b8620ad4c49fed88` |
| `android/vpn/build/outputs/apk/androidTest/debug/vpn-debug-androidTest.apk` | 21,916,618 | `50df16dddc2d2c802e76a13f748dbc40ba7a419549528144ff3e078aac6d2c85` |

`jarsigner -verify` reports `jar is unsigned` for the release bundle. It is an
unsigned source-build AAB and is not a Play candidate. These results are local
source, JVM, lint, and packaging evidence; they are not device visual evidence,
physical VPN evidence, a Play upload/release, hosted CI, or new-head
Kilo/Codex controller-review evidence. The complete clean evidence was produced
at final implementation head `dbb41b7ca607c38927f108a337a184dfaccd9a78`;
the subsequent tracked change only refreshes these evidence documents and is
covered by the post-commit provenance gates.

## Checkpoint 25 verification

Fresh production-composition evidence on 2026-08-02:

- `python3 -m unittest discover -s ci -p 'test_*.py'`: 118/118 contract and
  mutation tests passed in 831.928 seconds;
- `scripts/verify-android-foundation`: `Android foundation contract verified`;
- Gitleaks 8.30.1 scanned all 30 checkpoint commits, approximately 1.06 MB, and
  reported no leaks;
- the exact clean JDK 21.0.12 Gradle gate used `--no-daemon`, `--no-parallel`,
  and `--no-build-cache` and completed in 11m53s with 715 actionable tasks
  (`654` executed and `61` up-to-date, none from the build cache);
- JVM XML results: 139 suites, 1,586 tests, zero failures, zero errors, and zero
  skips;
- debug lint: all eight reports completed with zero Fatal or Error issues and
  27 warnings. `app` reported 19 warnings, `presentation` reported two, and
  `design-system`, `foundation`, `platform-contracts`, `product`,
  `test-support`, and `vpn` each reported one; and
- the implementation candidate at `303dcbfc9e866cfabf94a1d991de8fc3f9bd6a01`
  is 30 commits and 111 paths against merged checkpoint 24. No reference
  `AppContainer` or monolithic `ProductViewModel` was restored.

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| `android/app/build/outputs/apk/debug/app-debug.apk` | 52,394,678 | `bd4def0b22610323b89bce739a61e4045b77e8a5e92cb81adcca0343fc39ed4c` |
| `android/app/build/outputs/bundle/release/app-release.aab` | 23,034,680 | `f1814caee5f8736ceb666282238a0aa533d45f2c83d07b6e4ed0823822ead45e` |
| `android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 947,821 | `ab88179a372c3f0d8bccceaaf4863301d25b8a4085fd0b42a393a15a507bdfc2` |
| `android/vpn/build/outputs/apk/androidTest/debug/vpn-debug-androidTest.apk` | 22,065,635 | `86d65f13aff0ebefa2aeeb88a3e45e5d69948ac7c56b7849cdbe5bd8105f9461` |

`jarsigner -verify` reports `jar is unsigned` for the release bundle. The
tracked change after the implementation head records only this exact evidence;
post-commit contract/verifier provenance gates and hosted review apply to that
immutable review head. Hosted CI and two independent exact-head review passes
remain required before checkpoint 25 merge. API 28/API 36 emulator
behavior/instrumentation, physical-device VPN/upgrade testing, signing, and
Play Internal delivery are not claimed by this local gate and remain checkpoint
26 qualification work.

## Slice 06 diagnostics and system surfaces

Diagnostics refinement closed the remaining visual/state gaps without changing
any policy: the Activity date control now discloses the fixed 24-month window
and the device timezone below the control (copy, not a second authority — the
picker bounds remain the single source of truth); the counts empty state gains
an adjust-dates hint only when the date control is actually present; and every
diagnostic surface carries explicit privacy copy (Activity overview and Domain
Logs screens state the clear scope, Network Activity states its row cap and
scoped clear confirmation, Nerd Stats states local-only retention and the
single explicit feedback inclusion path). Domain actions remain under the
immutable review/confirmation surface; no diagnostic row ever mutates
immediately. The flow bar stays measurable (12dp, accessible counts, minimum
blocked segment, honest zero track) with geometry pinned by tests.

The Network Activity producer now covers every iOS category: protection
connected, network changes, resolver changes and fallback activation/recovery,
filter changes, and runtime failures. The three user-action categories iOS
records through AppViewModel that Android did not yet derive — resolver change,
Device-DNS-fallback toggle, and filter change — are now recorded from the exact
ResolverPlanSwitchOwner and FilterLibraryOwner/FilterProfileSwitchOwner success
flows through decorated mutation ports: the resolver category is derived from
the before/after plan (primary route or fallback preset change versus a
fallback-only flag flip), and a library mutation records "Filters changed" only
when the filter in effect actually changed (active profile id or active
rule content), never for a rename or a non-active edit. Recording happens at
the Applied result, so a failed commit is never logged as a change. Retention
off still clears rows through the existing privacy fence; share/export stays on
the single local export contract — no second log file exists.

The Android-native ambient protection surface (the iOS Live Activity
equivalent) is one ongoing notification driven by the existing credential
notification anchors — a new ProtectionAmbientNotificationOwner consuming the
same runtime flows as CredentialConnectivityNotificationOwner and wired at the
same credential-graph boundary, never published from a composable. The typed
ProtectionAmbientUiState carries status, the selected filter name and resolver
plan (the short subtitle), the status-change timestamp, and exactly one allowed
action (open the app); it is domain-free and account-free by construction. The
channel contract is explicit: LOW importance (silent, no heads-up), ongoing
non-dismissible with app-driven removal when protection is off or the
credential graph retires, lock-screen visibility PRIVATE (filter names are user
data), and no badge. The Customization notification section discloses this
contract.

Widget, Quick Settings tile, and launcher shortcuts are deliberately NOT
shipped in this slice: the Android VPN ownership/consent path cannot be safely
driven from a background component, so no on/off shortcut exists yet. The
open-app shortcut semantics every future surface would reuse — a plain
MainActivity launch restoring the saved navigation path — are exactly what the
ambient notification's OPEN_APP intent implements and its factory test pins.
The canonical deep-link routes remain unchanged and stay covered by
ProductNavigationTest/MainActivityDeepLinkTest. Focus/Digital Wellbeing
automation is likewise deferred: a future Android automation surface must
receive a schedule/context event and apply a named filter through the same
generation-fenced FilterLibraryState path as Slice 03, never through raw
configuration writes; no partial component is shipped.

## Slice 07 parity QA and release gates

This slice adds no product capability; it makes feature and visual parity
reproducible for agents and humans without requiring vision access. The
checked-in parity matrix (`docs/parity/parity-matrix.md`) names one row per
user flow — Android route, iOS source anchor at the pinned `lavasec-ios`
`55aad81`, durable owner/state contract, automated test class, and visual
fixture — including the two written deferrals (share/import UI rows and
widget/quick-settings/shortcuts) with the reasons already recorded above.

Every root/detail screen now carries a stable root `testTag` and its title as
exactly one accessibility heading, pinned by `ProductScreenContractTest` on
API 28/API 36; navigation rows merge title and summary into one semantic node
(pinned again in `LavaComponentsTest`). `ProductShellGeometryTest` now asserts
the agreed design tokens from composed bounds — the 18dp screen inset, the
64dp row minimum, the 38dp badge, the 14dp badge-to-text gap, the 10dp card
gap, the 56dp Guard primary-action minimum, the 96dp guardian, and the 16dp
bottom clearance — through the shared `ParityGeometryAssertions` helper that
compares `SemanticsNode.boundsInRoot` against the shell navigation bound
instead of any image-diff tolerance.

`scripts/capture-parity-evidence` captures the named states (Guard off and
protected, Filters overview and library, Settings root top/middle/max, DNS
Resolver, Network Activity with events, and one empty state) on API 28 and
API 36, storing each screenshot under a filename carrying the state name,
device configuration, commit SHA, and capture date plus a manifest record —
never a bare `current.png`. `docs/parity/qa-gates.md` carries the operator
checklists: the ten-locale and 1.3× font-scale pass, the TalkBack script for
both API levels, reduce-motion and dark-mode coverage (dark mode is supported
with `LavaThemeContrastTest` pinning the contrast rationale, so there is no
"dark mode unsupported" decision to record), and the rotation/force-stop/
privacy/security gate with exact steps. The privacy invariants that were
already automatable stay pinned in JVM: navigation save/restore, redaction,
backup exclusion, the generation fence against retired credential graphs, and
the default bug-report attachment set that excludes diagnostics and exports
until their explicit consent toggles authorize them.

## Build environment

Source builds use JDK 21 with Android SDK platform 36 and build-tools 36.0.0.
Java and Kotlin source and bytecode targets remain Java 17. Android 9 (API 28)
is the supported floor. The native design system uses the reviewed Lava static
palette by default; dynamic color remains an explicit API-31+ opt-in rather
than an automatic platform behavior.
