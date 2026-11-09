# Shared React Native launcher (Android)

The launcher imports the canonical RN app reconciled with iOS baseline
`4e552f33ab973c32539071f327803198f40279f5` through `CanonicalApp.tsx`.
`canonical/README.md` records provenance. This is a frontend integration slice,
not a declaration of Android owner parity or Play release acceptance.

The shared Guard, Settings, Explore, filter overview/library/detail, DNS, Plus and Sudoku
compositions run on Android. Native-stack and bottom navigation own navigation.
Platform siblings provide safe areas, choice/input/switch/slider controls, toolbar
and sheet actions, semantic icons and the existing Android Guardian drawing.
The full canonical Guardian catalog is projected into `guards`; the original is
the only *drawn* appearance, so every alternate look is projected `selectable:false`
and renders locked. The `look` preference is still carried to the native leaf, but
the iOS faceted shield renderers it selects are not ported (see the F05/G6 slice
below); a persisted alternate look is reported truthfully while its row stays
unselectable. The mascot animates mood transitions and an awake blink; the full
native mascot animation remains open.

`canonical/android/runtime.ts` adapts confirmed Android snapshots and forwards
supported commands with the native generation and current protection action.
Onboarding, VPN consent and authentication still use existing native owners.
There are no fabricated daily counts, prices, entitlements or successful writes.
The filter detail, Add Domain and Review screens now use native saved/draft state,
including per-row undo and removed rows. Manual-domain changes, source removal,
review, save and cancel reach the existing generation-fenced owner. Weakening
decisions use the captured immutable review, and unchanged edits are checked and
discarded under the owner's draft lock. A delayed UI projection cannot skip review
or discard a newer edit. The bridge releases admission before resolving completion,
so an immediately chained command is not falsely rejected as busy.

An explicit route gate covers the blocklist picker and other remaining editors, detailed activity, account/backup,
customization, privacy/security, diagnostics and other incomplete presentations.
Unsupported commands reject. Native authentication remains authoritative even
when the shared screen's policy projection is unavailable.

Builds install the locked npm graph in settings, before native autolinking. The
old preBuild installation would delete dependency source directories while
Gradle compiled them. The bundle source hash includes all canonical source and
platform adapters. Node is required on a clean build because autolinking and
code generation now participate in the native build.

The release candidate is R8-minified and resource-shrunk, with resource names
kept unobfuscated for React Native's dynamic drawable lookup. `proguard-rules.pro`
keeps the module and view-manager registration classes, and the bundle verifier
(`ci/verify_android_bundle.py` in source CI, and the protected controller's
self-contained copy `release/android/verify_android_bundle.py`) re-proves the
packaged registration by reading the shrunk release DEX string table, so a
stripped registration cannot ship even though it would not show up in a debug
assembly.

The prior minimal `App.tsx` remains only for existing adapter regression tests;
`index.js` registers `CanonicalApp` as the single shipping foreground.

Validation on 2026-09-28:

- TypeScript and 99 Jest tests pass, including Android component resolution,
  current-generation command forwarding, rejected writes, and stale privacy state.
- The debug APK containing all four ABIs and the instrumentation APK build successfully.
- After the startup crash fixes, the Pixel 3 / Android 12 passes 20 fresh-process
  instrumentation runs: 40 activity launches (first launch plus same-process
  reopening in each run), with actual protection text and completed native
  decoration composition. All 20 detached icon/mascot measurement checks pass.
  There were no crashes or test failures across these runs.
- All 64-bit packaged native libraries, plus the engine/appmodules libraries on
  every ABI, pass the ELF machine and 16 KB alignment checks.
- `scripts/verify-android-foundation` and all 75 bundle/foundation contract tests
  pass. Three Gradle-backed cases needed a rerun with SDK/cache access.
- All 920 app unit tests passed for the initial integration. Android lint was
  rerun successfully after the crash fixes. The first local test attempt
  hit a JBR C2 compiler crash; the successful rerun used
  `JAVA_TOOL_OPTIONS=-XX:TieredStopAtLevel=1` only for validation.
- Startup-fix debug APK SHA-256:
  `ab68456b526c87fa0a3683054376e4635619727816cfe52169c2ad84f769c94c`.

Filter integration validation on the same Pixel 3:

- TypeScript and all 101 Jest tests pass; all 87 focused native bridge/snapshot/
  product-model regression tests pass. Debug app/test APKs and lint pass.
- `LavaReactFilterDraftDeviceTest` passes against the live native bridge after RN
  startup. It adds and undoes a temporary blocked domain, adds an allowed exception,
  verifies Save returns `review`, then cancels and verifies the saved and displayed
  rule shelves exactly match the baseline. No saved filter changes are committed.
  This is bridge/device evidence, not a complete navigation or visual acceptance test.
- The device test requires explicit `-e liveFilterDraft true` on a configured,
  unlocked phone and refuses to replace an existing draft. Ordinary CI skips it.
- Installed filter-integration APK SHA-256:
  `b294897123906362fd083debfaabe6529a10b66444c0059689e9ca92eb875645`.
- Two obsolete CI assertions were updated to check generated RN autolinking and
  dependency installation before settings discovery instead of the removed manual
  package list and late `preBuild` install.

This receipt verifies runtime startup, not visual parity or purchase acceptance.
The app's secure-window policy remains enabled; no screenshot bypass was added.
The initial two-launch check missed intermittent startup crashes reported from the
phone. Subsequent logs identified two independent causes: Fabric measured the
Compose decoration before window attachment, and the navigation dependency raced
its native removal-listener initialization. The latter also explains the crash
previously suspected to be caused by appearance restoration; that attribution was
not supported. Startup appearance restoration is now connected: `LavaReactActivity`
reads the stored preference synchronously and passes it as the `initialAppearance`
launch option, `CanonicalApp` forwards it, and `AppearanceStore` seeds its first
snapshot from it so the first React frame paints the stored scheme instead of
flashing the system one. The connecting refresh then reconciles the seed with the
live native revision.

### F05/G6 platform-control slice

`LavaTextField` keeps an uncontrolled native buffer (no `value`/`defaultValue`
rewrite), honors `resetRevision` by clearing the ref once per new revision, and
supports `multiline` with measured height. `LavaChoice` holds an unresolved
selection until the authoritative `value` or a `selectionRevision` bump reconciles
it, and implements `reselectValue`, `stepper` and `pageControl`. `LavaSwitch`
holds an explicit `optimistic` intent until `resetRevision`/confirmation and honors
`pending`, `tintColor` and `onSizeChange`. `LavaSlider` holds a `value` that
arrives mid-drag (no snapback), applies `tintColor`, and reports tracking/value
events. `LavaContextMenu` and `LavaNativePage` keep their existing behavior;
`LavaNativePage` remains an honest unavailable placeholder until the F07 native
page host exists. `LavaDecoration` accepts `look` and runs a minimal mascot
animation (mood transition + awake blink) through a leaf-local driver that adds no
extra accessibility stop.

The shared selection list now carries the whole canonical `GuardianShieldStyle` catalog
(`canonical/android/guardCatalog.ts`), and the iOS faceted/obsidian shield drawing
(`ObsidianShieldBody`/`LavaGuardianShellFacet`/`LavaGuardianShieldRimShape`) is ported
into `GuardianMascotRenderer` (`GuardianObsidianShield.kt`), so every look is projected
`selectable:true` with no locked subtitle and each row renders its artwork. The decoration
leaf resolves the persisted `look` through `LavaDecorationPolicy.look` and draws the
matching shield; the settings owner (classified `EQUIVALENT_DEGRADED`) accepts any
`GuardianLook` wire value. Full native mascot animation (only mood transition + awake blink
today) remains open. True Fabric mounting is not executable on this aarch64 host
(Robolectric/conscrypt have no aarch64 binaries); the JVM lane pins the pure
decoration/slider policy and the TS lane mounts the platform siblings.

The decoration now accepts Yoga dimensions while detached and creates/measures
its Compose content only after attachment, including a posted layout when Fabric
already laid out the detached leaf. Its composition is disposed on detachment.
`react-native-screens` is pinned to 4.28.0, which contains the upstream
[removal-listener lifetime/race fix](https://github.com/software-mansion/react-native-screens/pull/4413).
The launch regression also checks detached icon/mascot measurement and waits for
native decoration composition after the first text frame.

Remaining release gates in first-build.md and release-promotion.md still apply.

### Internal candidate review follow-up

The Android runtime now forwards library edit/cancel, deletion staging and reviewed
batch commit and restore-defaults to the existing native owners. A successful reviewed library commit returns the boolean expected by the
shared screen so it exits editing only after native success. Inactive and frozen
filters always offer View; Android's three-button limit preserves switch/share
through a second menu when both are available.

Create/rename identity forms now have an Android presenter. `canonical/android/FilterIdentityForm.tsx`
mounts once beside the navigator and renders an RN modal (name field, Cancel/Create|Save) that
resolves the identity the shared `library.form` command carries; blank names cannot resolve and
cancellation issues no wire command. `runtime.ts` opens it for `library.form create`/`rename` and
forwards the collected name through the existing generation-fenced owner: create stages the named
addition and commits the one-item batch so the new filter is durable and navigable, rename stages
the identity for the shared screen's own confirm. The same presenter backs the per-filter detail
rename: `runtime.ts` opens it for `filter.renameForm` and forwards the collected name as the
canonical `filter.rename`, which the owner commits against its frozen/unique-name policy; a cancel
or blank name issues no wire command. The library entry, row-rename and detail-rename controls are
no longer withheld. Android still has no native create/rename sheet, so the flow commits on create
rather than opening a configurable unnamed draft as iOS does; that difference is the remaining
parity note, not a missing entry point.

Catalog acquisition/cache validation exists as a separate owner, but it does not yet
install into the production filter authority. Android pull-to-refresh is therefore
withheld, and `filter.refresh` is explicitly rejected. The bundled catalog remains
in use; atomic catalog install/recompile/reload is an outstanding parity slice.

Sudoku progress follows the confirmed native retention setting. Off, unavailable,
busy and background snapshots expose no saved game; opting out clears durable
progress through the privacy controller before committing the setting.

Android Security navigation now renders the shared Security screen. The native
`security` snapshot family is projected from the live `SecurityOwner` (the six
`protectedActions.*` opt-ins, `hasAuthenticationMethod`, `updatingSurface`, the
Keystore unavailable/recovery states, the failure code and the owner status
line), and `settings.set` for the six `protectedActions.<label>` keys is routed
through the same owner, which runs the fresh authentication a removal requires.
The Android owner has no ported biometric authenticator, so `showBiometrics`/
`canEnableBiometrics`/`biometrics` are reported unavailable and a `biometrics`
write is refused explicitly rather than faked; the shared screen hides the row.
`native.flow passcode` opens a native passcode management dialog (set, change or
remove) that keeps entry in the native secure surface and hands the code only to
the `SecurityOwner` — no credential material crosses the bridge. The custom-DNS
entry route now renders a shared RN editor (`canonical/android/CustomDNSEntry.tsx`)
for `kind: 'dns'` (a `kind: 'blocklist'` entry still uses the native page
container). The editor collects name/primary/secondary, opens the draft session
with `dns.customDraft`, validates the required fields locally, and saves through
`dns.custom`; `customEntry.dismiss` tears the draft down on close. The owner
remains authoritative, and the Android engine still has no custom-resolver route,
so a save's `CUSTOM_RESOLVER_RUNTIME_ABSENT`/`dns-unserved` refusal is surfaced as
its own honest unavailable copy (`Custom DNS is not available on this device yet.`)
and is never rendered as a successful save. The Android Plus page describes these
and other unfinished setup features as unavailable in this build rather than
included working capabilities.

The RN launcher also has a native whole-app lock cover. It hides React content
from drawing, input and accessibility until the current generation's APP_UNLOCK
authority admits it; pause revokes grants and covers the root synchronously.
Snapshots redact product facts and bridge writes reject while globally locked.
The native passcode presenter handles unlock, cancellation and recovery. Android
filter routes request the Filters grant before navigation, and both refresh
entry points stay hidden until catalog installation is implemented.

The Activity, Privacy, Customization, Legal, Stats, Network, Account, Feedback,
Top Domains and Domain History destinations now render their shared RN screens
instead of handing off to the native destinations. `projectAndroidSnapshot`
projects the retained `look` and the four canonical notification toggles from the
same owner the native settings write (the `notifications` family is omitted when
no owner is live, never fabricated). `Customization` is fully connected: its
appearance, text size, haptics and notification toggles forward through
`settings.set`. Legal renders the shared notice catalog surface, which reports
"Legal notices unavailable" while the Android notice catalog has no RN reader.
Activity/Top Domains/Domain History/Nerd Stats/Network render the shared screens
and their `activity.query`/`domains.query`/`stats.query`/`network.query` reads
have no Android owner yet, so each surfaces the explicit
"not available on Android yet" state rather than a fabricated result or a native
  handoff; the same honest refusal covers `logs.clear`/`logs.export`,
  `domains.*`, and `native.flow backupSetup`/`backupRestore`. The backup flows are
  recognized by `NativeProductRoutes` and answered with their own typed reasons
  (`backup-setup-unavailable`/`backup-restore-unavailable`) rather than the generic
  unknown-flow rejection, because Android still ships no native key-generation,
  passkey/recovery adapter or reviewed restore-plan owner; no recovery material or
  restore-review handle crosses the bridge. `settings.set backup.automatic` is now a real
  owner-backed preference: `BackupOwner` persists it (refused while a deletion fence blocks
  writers), projects it in the `backup` snapshot, and reports a lost durable write as a typed
  refusal. The `backup.restore` review family stays deliberately unprojected until that owner
  exists — the shared screen renders its honest unavailable state, never a fabricated review.

Account now projects the canonical `account` family from the process
`AccountSessionOwner`/`AccountCloudService`: signed-in status, the provider
identifiers Supabase actually reported, provider capability (Google configured
by the native client; Apple always unavailable because Android ships no Apple
credential adapter), busy (a provider admission or deletion in flight) and the
last auth message. The family is omitted while no account backend is installed,
so the shared screen keeps its honest unavailable fallback rather than a
fabricated sign-in. `account.google`/`account.signOut`/`account.delete` forward
through the existing owner with the account generation captured before any
provider UI or passcode prompt; `account.apple` is answered with the explicit
`account-apple-unavailable` refusal. The owner's `beginSignIn` ticket and
`requireCurrent` revalidate the generation at the durable commit, so a
concurrent sign-out or a different account signing in supersedes the operation
instead of writing to the wrong account. No token, user id or email crosses the
bridge, and the command parser refuses an injected account/identity/token. The
owner-confirmed encrypted-backup family is unchanged. One native handoff
remains: pressing a *connected* provider row still opens the native
`AccountSettingsScreen` (`native.flow account`), because the shared Account
screen exposes no sign-out/delete control; that is the honest fallback, not a
missing projection.

Security now projects its owner facts (the six protected actions,
passcode/biometric capability, failure/lockout/recovery) and renders the shared
screen; the native Security destination is gone. No field is invented for a
family an owner does not project.

The Android import chooser and filter sharing are connected to the backend
`FilterShareOwner`: the toolbar Import entry opens the shared method chooser,
`native.flow import`/`importScan`/`importCode` and `share.query`/`copy`/`present`
forward through the adapter, and a pasted code is decoded and reconciled by the
review owner (never decoded in RN). Camera and document have no platform adapter
yet, so each refuses with the owner's explicit unavailable reason rather than a
silent no-op; `share.card` (QR) is likewise unavailable on Android. A reviewed
code can now be **confirmed and persisted**: the shared screen offers Add-as-new
and (when a filter is in effect) Replace, which issues the Android-only
`native.flow importApply` with the reviewed target. The bridge owns the review
witness, calls `FilterShareOwner.confirmAsNewFilter`/`confirmReplace`, and
persists the confirmed content through the existing generation-fenced filter
library owner; a missing/mismatched review or a target that changed after review
refuses as stale, and a partially-acceptable plan refuses rather than applying a
subset, so nothing is written before confirmation. Adding curated blocklists and
auto-switch entry controls remain withheld while their presenters are
disconnected. Today counts are projected only for the native current-day calendar
range, with confirmed retention, unlocked access and counters that the shared
numeric graph can represent exactly.

Protection commands have a separate admission lane from filter publication and
ordinary mutations. Stop/Pause can reach the protection owner while a save is
suspended; the native security owner still admits only one authentication prompt.
