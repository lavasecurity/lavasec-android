# Android RN qualification ledger (plan 309 F25)

> Recovered/reconciled 2026-09-29 (convergence C1/C3). The scaffold was authored on the parallel #84
> branch and is ported onto the canonical #85 continuation (PR #85 head
> `89051565b9fd4cbd8a66be523e20dda88d9b7cf8`, based on `main` `b14cbb5`). It replaces the older
> F01 inventory references with the reconciled inventory
> (`docs/android-rn-build-inventory.md`); the canonical iOS target remains
> `3bd86116f0d12595965df03cf634066d393b4896` as frozen in
> `docs/android-parity-target.md`.

This is the **F25 evidence ledger scaffold**. It enumerates every required journey, native flow and
shared atomic family that must be qualified on an immutable integrated build, with the columns needed
to record a real device/live-service result. The complete row obligations below remain **not executed**. The current continuation
has local API-36 ARM and CI API-28/36 emulator access, plus native builds and controlled
journey evidence. Those scoped runs do not cover each complete physical/signed/interruption
obligation in this ledger. Physical/OEM, licence-test, release OAuth identity and protected
internal-track access remain pending. Every row below remains `not-run` without a full row result.

The ledger is populated from the F01 inventory (`docs/android-rn-build-inventory.md`) and the plan's
qualification matrix (`U01`–`U18`). Do **not** infer a pass from a Jest/Kotlin test: those prove wiring,
not a rendered, interrupted, signed, device journey. A row may only move to `pass` when its Evidence
column holds a redacted, retrievable link and the columns that describe the exact artifact/device are
filled.

## Machine contract (enforced by `ci/test_android_rn_qualification.py`)

Every qualification row is one Markdown table row whose **first cell is the Test ID wrapped in
backticks**, e.g. `` | `U01` | ... |``. Rows have exactly these twelve columns:

```
Test ID | Source/runner SHA | Artifact digest | Signing variant | OS/device
        | Service environment | Preconditions | Steps | Expected | Observed | Status | Evidence
```

- `Status` is one of `not-run`, `pass`, `fail`, `blocked`.
- `Evidence` is a redacted link (run URL, artifact store key, attached-image ref) or empty. An empty
  evidence cell is legal only while `Status` is `not-run`/`blocked`/`fail`; a `pass` with no evidence
  fails the check.
- Unfilled cells use `—`. Never put secrets, tokens, recovery material or personal histories here.

## Revisions and environment (fill at execution time)

| Field | Value |
| --- | --- |
| Android source revision | `—` (pin at the built commit) |
| Infra revision (plan 309 / backend) | `—` |
| Release controller revision (`release/android/`, same immutable SHA as tagged Android source) | `—` |
| Shared RN / canonical revision | `3bd86116f0d12595965df03cf634066d393b4896` (frozen target 2026-10-02; re-pin at build) |
| Artifact digest (AAB) | `—` |
| Signing variant | `—` (`internal` / Play app-signing lineage) |
| Emulator lanes | API 28 and API 36, Ubicloud |
| Physical devices | signed reference device + representative OEM with restrictive background behavior |
| Live-service lanes | controlled account / Play licence-test / backup / report, each with explicit authorization |
| Appearance/text | light + dark + system; normal + largest supported text |
| Locales | `en`, `de`, `es`, `fr`, `it`, `ja`, `ko`, `pt-BR`, `zh-CN`, `zh-TW` |
| Navigation | gesture and three-button where available; hardware keyboard/IME |

## A0. Backend acceptance scenarios (plan 308 A01–A15)

Each row executes one integrated backend scenario from infra plan #308's *Acceptance scripts for the
integrated candidate* on the **same** candidate as the U-rows. Every row is `not-run` and carries no
evidence link. A row whose dependency owner is still absent is `blocked`, not `pass`.

| Test ID | Source/runner SHA | Artifact digest | Signing variant | OS/device | Service environment | Preconditions | Steps | Expected | Observed | Status | Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `A01` | `—` | `—` | `—` | emulator + physical | local, offline then online | clean signed install | Prepare default filter; grant VPN; query allowed/blocked domains | Usable canonical default; traffic agrees with confirmed snapshot; denied permission recoverable; no fake connected state | `—` | `not-run` | `—` |
| `A02` | `—` | `—` | `—` | emulator + physical | local filters | existing library | Add/rename/emoji/delete in one edit then cancel; repeat and save; mutate configuration before review confirm | Cancel persists nothing; save atomic; stale confirm rejected; active/frozen restrictions and cache preservation match canon | `—` | `not-run` | `—` |
| `A03` | `—` | `—` | `—` | emulator + physical | local secure | passcode configured | Enable each protected surface; invoke RN command directly; background during prompt; rotate; biometric cancel/lockout | Opt-in choices persist; no bypass or cross-purpose grant; app-unlock lifetime differs from view turn; locked snapshots redacted | `—` | `not-run` | `—` |
| `A04` | `—` | `—` | `—` | emulator + physical | resolver runtime | DNS configured | Select each provider/transport/custom/fallback; fail primary; switch underlying network | Actual transport and permitted fallback agree with plan; no loop or unintended plaintext; late responses ignored | `—` | `not-run` | `—` |
| `A05` | `—` | `—` | `—` | signed physical | resolver runtime | chained config saved | Import/review/save; start; break DNS then egress; Wi-Fi/cellular/airplane/Doze; edit/remove config | Canonical outage transitions and fallback policy; one TUN; shared filtering works; reload acknowledges the exact intended generation | `—` | `not-run` | `—` |
| `A06` | `—` | `—` | `—` | signed physical | Play licence-test track | verified Plus; full library; 500,001-rule artifact | Force authoritative lapse while resident; admit a new lease | Saved data preserved; no lapse-triggered reconnect; new Free admission denied correctly; snapshot explains budget without leaking locked data | `—` | `not-run` | `—` |
| `A07` | `—` | `—` | `—` | signed physical | controlled account + provider | Google client for exact cert | Sign in Google/Apple/link; begin work as A; switch/sign out to B during callback/refresh/upload | No stale session resurrection or cross-account write; provider busy/message state settles; signed certificates/callbacks proven | `—` | `not-run` | `—` |
| `A08` | `—` | `—` | `—` | signed physical | Play licence-test track | product + licence-test account | Approved test purchase signed out of Lava; pending/cancel/success; kill after verification; restore; expiry/refund with app stopped | Access independent of Lava login; receipts/retries durable; authoritative negative handled; unknown/offline preserves prior verified plan | `—` | `not-run` | `—` |
| `A09` | `—` | `—` | `—` | signed physical + emulator | controlled backup service | backup account; iOS↔Android payload | Set up recovery; back up on one platform; restore on the other; change local baseline before confirm; wrong secret/corrupt payload | Full compatible configuration; excluded device secrets retained/excluded per canon; stale/malformed restore changes nothing | `—` | `not-run` | `—` |
| `A10` | `—` | `—` | `—` | signed physical + emulator | controlled backup service | backup account | Queue offline edits; switch account; disable/delete then restart before acknowledgement; interrupt restore publication | Account-bound queue; durable pending deletion; no resurrection; one coherent local state with truthful runtime pending/failure | `—` | `not-run` | `—` |
| `A11` | `—` | `—` | `—` | emulator + physical | local activity | history enabled | Disable each retention option; write/query/clear/export concurrently; lock/background; share/cancel exported file | Collection stops; bounded retention; correct category clear; no stale private response; export explicit and report-independent | `—` | `not-run` | `—` |
| `A12` | `—` | `—` | `—` | emulator + physical | approved feedback lane | authorized receipt endpoint | Review optional diagnostics; cancel; send one approved controlled report; retry unchanged; edit after attempt; inject 429/mismatched receipt | Consent-bound body/identity; only matching receipt succeeds; 429 no fallback; no automatic history/credential upload | `—` | `not-run` | `—` |
| `A13` | `—` | `—` | `—` | emulator + physical | local VPN | permission states | Deny/revoke VPN; another VPN; pause then reboot; always-on/lockdown; notification action locked; app update | Honest requested/confirmed state and canonical authorization; no duplicate service; recoverable permission/storage state | `—` | `not-run` | `—` |
| `A14` | `—` | `—` | `—` | emulator + physical | local | all screens reachable | Navigate every shared screen; issue every required command/query; open/cancel each native flow; recreate app | No prototype-only feature gaps or fake success; real owner state survives; Android-specific prompts settle correctly | `—` | `not-run` | `—` |
| `A15` | `—` | `—` | `—` | signed physical | internal testing track | immutable candidate + signing | Build immutable candidate; verify/sign; approved internal-track install/update; repeat A01/A07/A08/A09 smoke | Correct packaged JS/native bytes and signing identity; release behavior matches qualification; artifact/run links recorded | `—` | `not-run` | `—` |

## A. Journey qualification rows (plan qualification matrix `U01`–`U18`)

| Test ID | Source/runner SHA | Artifact digest | Signing variant | OS/device | Service environment | Preconditions | Steps | Expected | Observed | Status | Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `U01` | `—` | `—` | `—` | API 28 + API 36 emulator; physical | offline then online | clean install | Launch offline; deny then grant VPN consent; cancel then relaunch preparation | Full RN entry, truthful startup, first allowed/blocked traffic, no Metro server | `—` | `not-run` | `—` |
| `U02` | `—` | `—` | `—` | emulator + physical | local VPN | configured, consent granted | Guard off→on→ready/protected; pause/resume/recovery; stop during compile | Real owner/runtime alignment, one cue per transition, urgent command stays responsive | `—` | `not-run` | `—` |
| `U03` | `—` | `—` | `—` | emulator + physical | local | configured | Traverse Guard/Settings stacks; protected entry/return; native page; back gesture | One header/back owner, no protected flash, no duplicate pop/prompt | `—` | `not-run` | `—` |
| `U04` | `—` | `—` | `—` | emulator + physical | local filters | existing + clean data | Filter create/emoji/rename/edit/undo/review/apply; library cancel/commit | Canonical saved/draft separation, actual changed filtering, stale review rejected | `—` | `not-run` | `—` |
| `U05` | `—` | `—` | `—` | emulator + physical | local, cross-device payload | share source available | Import text/file/QR and custom list; denied camera; dirty cancel; share retry | Actual cross-platform payload, bounded native acquisition, no unintended commit | `—` | `not-run` | `—` |
| `U06` | `—` | `—` | `—` | emulator + physical | local activity | populated + empty history | Three charts/ranges; inspect/drag/outside tap; custom dates/cancel | Correct real values/ranges, stable frame, no swallowed action or duplicate feedback | `—` | `not-run` | `—` |
| `U07` | `—` | `—` | `—` | emulator + physical | local activity | history enabled | Domain/history/network queries; stage domain action; privacy clear/export race | Correct filter target, stale read retirement, no private data after revocation | `—` | `not-run` | `—` |
| `U08` | `—` | `—` | `—` | emulator + physical | offline legal | configured | Traverse every Settings row; theme/text/haptic choices; legal offline | Real destination/setting, common anatomy, no dead row or missing notice | `—` | `not-run` | `—` |
| `U09` | `—` | `—` | `—` | emulator + physical | local secure | passcode + biometric configured | Security choices; native credential/biometric flow; lock/background/recycle | Purpose-correct admission, secret isolation, privacy cover and focus recovery | `—` | `not-run` | `—` |
| `U10` | `—` | `—` | `—` | emulator + physical | resolver runtime | DNS configured; chained entry if released | DNS provider/transport/fallback/custom; chained config save/remove | Real runtime plan, private key isolation, truthful pending/failed state | `—` | `not-run` | `—` |
| `U11` | `—` | `—` | `—` | signed physical | controlled account + provider | Google client registered for exact cert | Signed Google/Apple/link; cancel/browser return; sign-out/account switch | Actual callback identity, no stale session or duplicate launch | `—` | `not-run` | `—` |
| `U12` | `—` | `—` | `—` | signed physical | Play licence-test track | product + licence-test account | Play offers; signed-out-of-Lava purchase; pending/cancel/restore/manage | Actual Play terms/price, verified access and preserved uncertainty semantics | `—` | `not-run` | `—` |
| `U13` | `—` | `—` | `—` | signed physical + emulator | controlled backup service | backup account; iOS↔Android payload | Backup opt-in/automatic/recovery/restore/delete; stale review; restart | Real interoperable payload, no secrets in JS, durable nullable/pending state | `—` | `not-run` | `—` |
| `U14` | `—` | `—` | `—` | emulator + physical | approved feedback lane | authorized receipt endpoint | Feedback long text/IME; drag footer; dirty dismissal; approved receipt/retry | No accidental Send, exact consent/receipt semantics, no private export attachment | `—` | `not-run` | `—` |
| `U15` | `—` | `—` | `—` | emulator + physical | local | guard choices + saved Sudoku | Explore demo/learning and Sudoku/Guard choices; blur/background | Shared canonical interaction, saved game/progress, optional audio stops | `—` | `not-run` | `—` |
| `U16` | `—` | `—` | `—` | emulator + physical | local VPN + notifications | notification permission states | Notification/deep link; always-on/revocation; process death; upgrade | Gated routes, owner actions, preserved settings/VPN/accessibility data | `—` | `not-run` | `—` |
| `U17` | `—` | `—` | `—` | emulator + physical | local | all locales/appearance/text | Compact/wide/landscape, dark/light, large text, keyboard, TalkBack, locales | Rendered family comparison plus bounds/AX/interaction evidence | `—` | `not-run` | `—` |
| `U18` | `—` | `—` | `—` | signed physical | internal testing track | minified signed release | Minified signed release, all modules/components, install/update/internal track | Matching bundle/native digests, no fixtures/QA leakage, exact shipped smoke | `—` | `not-run` | `—` |

## B. Native flow qualification rows

Each row qualifies one native boundary from the plan's *Native implementation inventory* and the F01
inventory: the three module contracts, the seven generated native components, and the native
page/modal/system placements. A `pass` needs rendered + accessible evidence on the artifact, not a
Jest mock.

**Modules (3)**

| Test ID | Source/runner SHA | Artifact digest | Signing variant | OS/device | Service environment | Preconditions | Steps | Expected | Observed | Status | Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `NF-MOD-APP` | `—` | `—` | `—` | emulator + physical | local owner graph | app graph live | `getSnapshot`/`command`/`onSnapshot` cold start, event-before-initial-read, teardown | Canonical JSON envelope, monotonic revisions, teardown leaves no listener | `—` | `not-run` | `—` |
| `NF-MOD-APPEARANCE` | `—` | `—` | `—` | emulator + physical | local | configured | `getSnapshot`/`setPreference`/`onSnapshot`; light/dark/system before first paint | No startup flash, durable preference, native/RN theme consistent | `—` | `not-run` | `—` |
| `NF-MOD-REVIEW` | `—` | `—` | `—` | emulator + physical | local + legal/share content | configured | Call each helper used by production: legal notices, guard accents, catalog, share preview, date picker, normalize domain, close, narration capability | Each production caller gets a real result or an explicit capability result; no blanket preview stub | `—` | `not-run` | `—` |

**Generated components (7)**

| Test ID | Source/runner SHA | Artifact digest | Signing variant | OS/device | Service environment | Preconditions | Steps | Expected | Observed | Status | Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `NF-CMP-TEXTFIELD` | `—` | `—` | `—` | emulator + physical | local | a form using the field | Focus/submit; IME composition (CJK); resetRevision including cancel; multiline measurement | Native buffer preserved, no composition rewrite, correct measured height | `—` | `not-run` | `—` |
| `NF-CMP-CHOICE` | `—` | `—` | `—` | emulator + physical | local | a choice/stepper/pages surface | Tap and reselect; rejected update; recycled-view callback after a newer choice | Acknowledged revision wins, no snapback, value/size events match spec | `—` | `not-run` | `—` |
| `NF-CMP-SLIDER` | `—` | `—` | `—` | emulator + physical | local | a slider surface | Drag beyond bounds; parent scroll; disabled interaction; rotate/recycle | Spec range/measurement honored, no duplicate event, accessible value | `—` | `not-run` | `—` |
| `NF-CMP-SWITCH` | `—` | `—` | `—` | emulator + physical | local | a toggle row | Controlled toggle, pending optimistic state, disabled, theme change | Stable control identity, event identity, no false commit | `—` | `not-run` | `—` |
| `NF-CMP-CONTEXTMENU` | `—` | `—` | `—` | emulator + physical | local | a long-press/overflow action | Open/cancel native menu; eligibility per action; destructive semantics | Native menu/cancel, accessible equivalent, no stray action | `—` | `not-run` | `—` |
| `NF-CMP-DECORATION` | `—` | `—` | `—` | emulator + physical | local | Guard/mascot artwork | Render each artwork state; sizing/theme; gesture where canonical | Valid glyph/artwork, no missing boxes, noninteractive decoration is silent | `—` | `not-run` | `—` |
| `NF-CMP-NATIVEPAGE` | `—` | `—` | `—` | emulator + physical | local | a pushable native page | `page`/`focused`/`onBack`/`onNavigate`; blur behind another page; revoke; recycle; stale callback | No private view interactive after revocation, no leaked composition or duplicate prompt | `—` | `not-run` | `—` |

**Native pages, flows and system placements**

| Test ID | Source/runner SHA | Artifact digest | Signing variant | OS/device | Service environment | Preconditions | Steps | Expected | Observed | Status | Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `NF-PAGE-ACCOUNT` | `—` | `—` | `—` | signed physical | controlled provider | Google client for exact cert | Launch provider sheet, cancel, browser return, sign-out, account switch | Callback bound to initiating operation, tokens native, no stale session | `—` | `not-run` | `—` |
| `NF-PAGE-PASSCODE` | `—` | `—` | `—` | emulator + physical | local secure | passcode set | Setup/verify/remove; cancel; biometric disable/lockout; process recreation | Secret never in JS/props/logs, focus returns to initiating screen | `—` | `not-run` | `—` |
| `NF-PAGE-PURCHASE` | `—` | `—` | `—` | signed physical | Play licence-test track | product + licence-test account | Buy, pending, cancel, restore, manage, offer expiry before click | Verified entitlement only, no optimistic Plus, no duplicate launch | `—` | `not-run` | `—` |
| `NF-PAGE-BACKUP` | `—` | `—` | `—` | signed physical + emulator | controlled backup service | backup account; iOS↔Android payload | Setup, recovery (cancel/wrong secret), restore review, delete/retry | Frozen review confirmed exactly, no recovery material leaks, durable pending state | `—` | `not-run` | `—` |
| `NF-PAGE-CUSTOMENTRY` | `—` | `—` | `—` | emulator + physical | local | DNS/custom-list draft | Open editor; invalid input; save; dismiss; keyboard + large text | Exact draft handle, one navigation owner, long fields usable, no premature commit | `—` | `not-run` | `—` |
| `NF-PAGE-FEEDBACK` | `—` | `—` | `—` | emulator + physical | approved feedback lane | authorized endpoint | Long text/IME; drag footer; dirty cancel; duplicate/edited retry; 429 | Native review freezes content, no accidental Send, exact consent/receipt semantics | `—` | `not-run` | `—` |
| `NF-PAGE-VPNCHAINING` | `—` | `—` | `—` | emulator + physical | resolver runtime | capability gate decides release entry | Open config editor only under the release/QA capability; save/remove during running tunnel; invalid endpoint | Private keys native, truthful pending/failed state, one navigation owner | `—` | `not-run` | `—` |
| `NF-PAGE-AUTOMATION` | `—` | `—` | `—` | emulator + physical | local | capability policy resolves | Open `automation` and `dnsPatch`; verify Android disposition | Platform-only items are truthfully unavailable, no Apple setup text on Android | `—` | `not-run` | `—` |
| `NF-PAGE-QA` | `—` | `—` | `—` | emulator (release) + debug build | local | release build | Probe PhoneQA/DeviceQA/Components by guessed route in release | No production access; QA/gallery stays release-gated | `—` | `not-run` | `—` |
| `NF-PAGE-FILTERFORMS` | `—` | `—` | `—` | emulator + physical | local filters | dirty draft | Create/rename/delete forms; preparation; import methods/code/scanner; cancellation | Owner-controlled drafts, current route identity, IME not an invalid-name alert per keystroke | `—` | `not-run` | `—` |
| `NF-PAGE-SHARE` | `—` | `—` | `—` | emulator + physical | local + chooser | share source | Share query/copy/card/present; file picker; clipboard; legal notices; date picker; camera denial | Narrow URI grants with cleanup, offline legal content, cancel does not resurrect stale payload | `—` | `not-run` | `—` |
| `NF-SYS-VPNPERMISSION` | `—` | `—` | `—` | emulator + physical | local VPN | fresh install | Deny, grant, another VPN, OS settings return | Consent granted is not protection confirmed; owner state authoritative | `—` | `not-run` | `—` |
| `NF-SYS-NOTIFICATIONS` | `—` | `—` | `—` | emulator + physical | local notifications | channel states | Foreground VPN notification; notification action while locked; denied/revoked | Required notification present, action routed through owner admission | `—` | `not-run` | `—` |
| `NF-SYS-OSSETTINGS` | `—` | `—` | `—` | emulator + physical | local | permission revoked | Deep-link cold entry; app-link callback; always-on/lockdown; reboot return | Gated route, owner action, coherent return navigation, no notification-only protection | `—` | `not-run` | `—` |

## C. Atomic family qualification rows

Shared F04/F22 atoms with their light/dark, selected/disabled/error, compact/wide and enlarged-text
fixture evidence. Render checks from Jest prove structure only; these rows require the real rendered
Android artifact.

| Test ID | Source/runner SHA | Artifact digest | Signing variant | OS/device | Service environment | Preconditions | Steps | Expected | Observed | Status | Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `AF-TYPE` | `—` | `—` | `—` | emulator + physical | local | fixture grid reachable in eligible build | Render title/body roles across light/dark, normal/largest text, compact/wide | Role metrics and heading semantics preserved, no clipping or double scaling | `—` | `not-run` | `—` |
| `AF-CARD` | `—` | `—` | `—` | emulator + physical | local | fixture grid | Render card surface light/dark | Token-derived surface, readable contrast | `—` | `not-run` | `—` |
| `AF-PANEL` | `—` | `—` | `—` | emulator + physical | local | fixture grid | Render panel surface light/dark | Token-derived surface, readable contrast | `—` | `not-run` | `—` |
| `AF-ACTION` | `—` | `—` | `—` | emulator + physical | local | fixture grid | Primary/destructive/disabled action states | Stable geometry, unique labels, disabled visibly inert | `—` | `not-run` | `—` |
| `AF-TOGGLE` | `—` | `—` | `—` | emulator + physical | local | fixture grid | Toggle on/off/disabled, pending controlled state | No snapback, accessible switch state | `—` | `not-run` | `—` |
| `AF-CHOICE` | `—` | `—` | `—` | emulator + physical | local | fixture grid | Segments selected/unselected/disabled | Accessible radiogroup semantics, unique labels | `—` | `not-run` | `—` |
| `AF-STEPPER` | `—` | `—` | `—` | emulator + physical | local | fixture grid | Stepper increment/decrement bounds | Bounds honored, accessible value/actions | `—` | `not-run` | `—` |
| `AF-PAGES` | `—` | `—` | `—` | emulator + physical | local | fixture grid | Page control across positions | Position exposed as accessible value, never dropped | `—` | `not-run` | `—` |
| `AF-DECORATION` | `—` | `—` | `—` | emulator + physical | local | fixture grid | Render each mascot/decoration state | Valid artwork per state, decoration silent to TalkBack | `—` | `not-run` | `—` |
| `AF-IDENTITY` | `—` | `—` | `—` | emulator + physical | local | fixture grid | Render emoji/identity, legacy rename identity | Unicode/emoji preserved, readable, no clipping | `—` | `not-run` | `—` |
| `AF-LEGEND` | `—` | `—` | `—` | emulator + physical | local | fixture grid | Render blocked/allowed legend and true proportions | Blocked left / allowed right, readable alternative | `—` | `not-run` | `—` |
| `AF-ROW` | `—` | `—` | `—` | emulator + physical | local | fixture grid | Row intents (page/action/destructive), selected/disabled | Unique labels, correct accessory semantics, tap target | `—` | `not-run` | `—` |
| `AF-ICON` | `—` | `—` | `—` | emulator + physical | local | fixture grid | Render every icon action glyph | Every action has a glyph, no missing boxes, semantics named | `—` | `not-run` | `—` |
| `AF-PROPORTION` | `—` | `—` | `—` | emulator + physical | local | fixture grid + Guard/Activity | Labelled vs decorative proportion bar | Labelled chart reads as one text node; decorative stays hidden | `—` | `not-run` | `—` |

## D. Interruption matrix (F23)

One row per canonical interruption kind from `android/react-native/tests/interruptionHarness.tsx`
(`INTERRUPTION_MATRIX`). Each kind must be exercised at the fetch, prompt, review, commit and result
stages of the filter, DNS, account, billing, backup and feedback journeys. Assert visible state,
durable owner state, runtime state and the NUMBER of side effects — never "no crash".

| Test ID | Source/runner SHA | Artifact digest | Signing variant | OS/device | Service environment | Preconditions | Steps | Expected | Observed | Status | Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `INT-blur-back` | `—` | `—` | `—` | emulator + physical | local | journey mid-flight | Pop/blur the route and re-enter | No durable op lost or replayed; screen state correct after return | `—` | `not-run` | `—` |
| `INT-tab-change` | `—` | `—` | `—` | emulator + physical | local | journey mid-flight | Change tab, unmount and remount | Subscription bounded, command not replayed | `—` | `not-run` | `—` |
| `INT-background-foreground` | `—` | `—` | `—` | emulator + physical | local | deferred command in flight | Background then foreground | Urgent action preserved, busy clears, no fabricated commit | `—` | `not-run` | `—` |
| `INT-rotation` | `—` | `—` | `—` | emulator + physical | local | journey mid-flight | Recreate activity | Draft/operation survives or fails honestly, no duplicate prompt | `—` | `not-run` | `—` |
| `INT-process-death` | `—` | `—` | `—` | emulator + physical | local | durable owner state present | Kill process, relaunch | Native owner survives, JS re-reads, nothing auto-replays | `—` | `not-run` | `—` |
| `INT-native-module-teardown` | `—` | `—` | `—` | emulator + physical | local | command pending | Tear down native transport | Command settles, no crash, no stale result over a later route | `—` | `not-run` | `—` |
| `INT-graph-retirement` | `—` | `—` | `—` | emulator + physical | local | credential graph live | Retire/replace generation | Owners fenced to generation, listeners disposed | `—` | `not-run` | `—` |
| `INT-account-switch` | `—` | `—` | `—` | signed physical | controlled accounts | A→B switch mid-flow | Switch account during callback/restore | New account authoritative, old session not resurrected | `—` | `not-run` | `—` |
| `INT-tier-change` | `—` | `—` | `—` | signed physical | controlled entitlement | Plus lapse/refresh mid-review | Tier changes during review/purchase | No reconnect on lapse, frozen state explained, late confirm rejected | `—` | `not-run` | `—` |
| `INT-privacy-revocation` | `—` | `—` | `—` | emulator + physical | local | in-flight query/export | Revoke privacy scope | Queries retire, no private data after revocation, no double report | `—` | `not-run` | `—` |

## E. Accessibility and localization matrix (F22)

Structural harness evidence plus the real device/TalkBack evidence the F22 scaffold explicitly
deferred. The locale inventory is fixed by the shipped Android resources; unknown tags fall back to
`en`.

**Locale coverage**

| Test ID | Source/runner SHA | Artifact digest | Signing variant | OS/device | Service environment | Preconditions | Steps | Expected | Observed | Status | Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `A11Y-LOCALE-en` | `—` | `—` | `—` | emulator + physical | local | locale set to `en` | Traverse every translated route | No missing/English leakage beyond the documented allowlist | `—` | `not-run` | `—` |
| `A11Y-LOCALE-de` | `—` | `—` | `—` | emulator + physical | local | locale set to `de` | Traverse every translated route | No missing/English leakage beyond the documented allowlist | `—` | `not-run` | `—` |
| `A11Y-LOCALE-es` | `—` | `—` | `—` | emulator + physical | local | locale set to `es` | Traverse every translated route | No missing/English leakage beyond the documented allowlist | `—` | `not-run` | `—` |
| `A11Y-LOCALE-fr` | `—` | `—` | `—` | emulator + physical | local | locale set to `fr` | Traverse every translated route | No missing/English leakage beyond the documented allowlist | `—` | `not-run` | `—` |
| `A11Y-LOCALE-it` | `—` | `—` | `—` | emulator + physical | local | locale set to `it` | Traverse every translated route | No missing/English leakage beyond the documented allowlist | `—` | `not-run` | `—` |
| `A11Y-LOCALE-ja` | `—` | `—` | `—` | emulator + physical | local | locale set to `ja` | Traverse every translated route; IME composition | CJK layout/IME correct, no half-composed validation | `—` | `not-run` | `—` |
| `A11Y-LOCALE-ko` | `—` | `—` | `—` | emulator + physical | local | locale set to `ko` | Traverse every translated route; IME composition | CJK layout/IME correct, no half-composed validation | `—` | `not-run` | `—` |
| `A11Y-LOCALE-pt-BR` | `—` | `—` | `—` | emulator + physical | local | locale set to `pt-BR` | Traverse every translated route | No missing/English leakage beyond the documented allowlist | `—` | `not-run` | `—` |
| `A11Y-LOCALE-zh-CN` | `—` | `—` | `—` | emulator + physical | local | locale set to `zh-CN` | Traverse every translated route; IME composition | CJK layout/IME correct, no half-composed validation | `—` | `not-run` | `—` |
| `A11Y-LOCALE-zh-TW` | `—` | `—` | `—` | emulator + physical | local | locale set to `zh-TW` | Traverse every translated route; IME composition | CJK layout/IME correct, no half-composed validation | `—` | `not-run` | `—` |

**Structural and interaction assertions**

| Test ID | Source/runner SHA | Artifact digest | Signing variant | OS/device | Service environment | Preconditions | Steps | Expected | Observed | Status | Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `A11Y-CONTROL-LABELS` | `—` | `—` | `—` | emulator + physical | local | representative screens | TalkBack sweep of interactive controls | Every control has a non-empty accessible role + label; no duplicates | `—` | `not-run` | `—` |
| `A11Y-HEADINGS` | `—` | `—` | `—` | emulator + physical | local | representative screens | Heading navigation | Title/section copy is a heading; body copy is not | `—` | `not-run` | `—` |
| `A11Y-DECORATION-HIDDEN` | `—` | `—` | `—` | emulator + physical | local | decorative atoms | TalkBack sweep of decoration/selection/dot/accessory | Decoration withdrawn from the accessibility tree | `—` | `not-run` | `—` |
| `A11Y-CHART-ALTERNATIVE` | `—` | `—` | `—` | emulator + physical | local | Guard/Activity chart | Focus the labelled proportion bar | One spoken alternative with equivalent values; decorative stays silent | `—` | `not-run` | `—` |
| `A11Y-PAGE-POSITION` | `—` | `—` | `—` | emulator + physical | local | pages control | Focus the page control | Position announced as "n of m"; label retained | `—` | `not-run` | `—` |
| `A11Y-RESPONSIVE` | `—` | `—` | `—` | emulator + physical | local | compact/wide/landscape | Rotate/resize; keyboard open | Whole content reachable, one page scroll where intended, final row/action visible | `—` | `not-run` | `—` |
| `A11Y-TEXT-SIZE` | `—` | `—` | `—` | emulator + physical | local | largest supported text | Traverse Guard/Settings/Explore at enlarged text | No clipping, no double scaling, no hidden keyboard footer | `—` | `not-run` | `—` |
| `A11Y-REDUCED-MOTION` | `—` | `—` | `—` | emulator + physical | local | reduced motion on | Trigger Guard/Explore animation | Animation reduced without losing meaning or controls | `—` | `not-run` | `—` |
| `A11Y-CONTRAST` | `—` | `—` | `—` | emulator + physical | local | dark + high contrast | Inspect shared atoms and adopter rows | Readable high-contrast/dark rendering per accepted design guide | `—` | `not-run` | `—` |

## F. Not-evidenced / blocked (explicit)

All 113 complete qualification rows remain **not-run**. Scoped source, controlled-test and emulator
evidence exists, but it does not satisfy the full artifact/device/service obligations of those rows.
The current evidence and remaining qualification gaps are:

- **Scoped verification:** the last complete passing preview047482a3/merge76ccf964/tree59fd7eee
  has source CI37183782121 and same-candidate device CI37183782114: API28 passes108 cases,
  API36 passes104, every prior79 identity matches raw XML/metadata/source/signers. Artifact11296262244
  remains usable, but does not qualify later source. Published0976d7e6 source CI37194874617 passes
  RN520/41,306 source contracts and unit/build/lint/AAB checks; device CI37194874633 fails both APIs
  and skips the combined cohort. Local0976 retains4,591 passing unit outcomes and both AAB verifiers.
  Reviewed repairs through93dc3949 preserve0976 production. Actual owned API36 backup2/gallery8/
  genuine Android-plus-host DoQ exchange/slider2/locale2/direct CJK2/component-control1 groups pass
  with recorded distinct test revisions and a preserved update witness. They do not establish one
  complete same-candidate cohort. The expanded default gate requires90 exact identities including
  four process phases; complete source/API28/API36 CI is still required. Raw negative receipts remain.
  Opt-in TalkBack remains unqualified: standard UiAutomation injection bypasses accessibility input
  processing, and no genuine input transport is admitted here. The test must fail before activation
  without that capability. No optional-test or emulator-only receipt grants a full F22/F25 exclusion.
  PR91's body carries current run receipts. Controlled ports do not promote full qualification rows.
- **Shared iOS composer:** PR844 was merged externally at11:17UTC (806fcf5c/9077d764), with full
  RN/UIKit795/47, Simulator compilation, Swift packages and source/security checks passing.
  Its former Mac storage blocker is recovered. Native standard QR encoders remain; pixel identity
  is not asserted. Android's frozen parity target remains3bd86116 rather than current iOS main.
- **Native source owners:** implementations now exist for custom-resolver runtime, backup setup/restore,
  biometrics, Apple browser/link sign-in, camera/document/QR import, native page hosting and Guardian.
  The F01 missing-owner inventory is historical; source implementation and controlled tests do not
  establish every rendered, interrupted, signed or live-provider outcome required here.
- **Public Auth settings:** the read-only Supabase public-settings request returned HTTP 200 with
  Apple and Google enabled. The exact signed-build redirect allowlist, private provider configuration
  and signed-device callback identities remain unproven. Public enablement does not qualify a live
  sign-in journey, and absent local environment variables do not prove credentials are absent.
- **Passkey association:** web PR63 has an actual immutable Cloudflare preview returning HTTP200,
  application/json, no redirect and the exact387-byte release-only Play-certificate association
  (SHA2569e4cae1baea17d1018edc5674f63418f92ddab44891bf7d58f7587c0039f48d9).
  Production lavasecurity.app still returned404 at12:18UTC; merge auto-deploys and is separately
  unapproved. The Android RP origin remains disabled. A hosted preview is not production
  association or a signed Credential Manager PRF ceremony.
- **Physical, signed and live qualification:** no full physical reference/OEM, protected signing/Play
  or live-service qualification is recorded. Signed install/update, licence-test purchase, controlled
  account, backup and approved feedback receipt journeys still need candidate-specific evidence.
  This is an evidence gap, not an assertion that operator keys, accounts or access do not exist.
- **No qualified physical/release performance baseline:** scoped owned-debug launch/control
  measurements exist, but they do not establish release/OEM launch, frame pacing, keyboard or
  memory budgets. No qualified numeric threshold is asserted here.

Do not relabel a missing required journey as optional polish. F26 may only proceed once F25 rows that
the build claims are complete actually carry evidence.

## G. Device-verification boundary checklist (Q1–Q4)

The close-out groups F25 obligations by infra plan #310's Q1–Q4 and names their required
capabilities. Physical/protected/signed/live actions need their operator capability and separate
authorization; source and owned-emulator work continues here while executable. These full rows
are `not-run`; a `pass` needs the complete concrete environment/artifact identity and evidence.
This checklist alone is not proof that every remaining authorized action is externally blocked.

| Test ID | Source/runner SHA | Artifact digest | Signing variant | OS/device | Service environment | Preconditions | Steps | Expected | Observed | Status | Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `DV-Q1-ENV` | `—` | `—` | `—` | `—` | signing/OAuth/Play/Integrity/backup/report | operator access | Produce the redacted environment matrix; verify each service through the Android consumer | Every required service has a controlled signed-device success/cancel/failure path or an explicit blocker; no productive secret in the app artifact | `—` | `not-run` | `—` |
| `DV-Q2-COMBINED` | `—` | `—` | `—` | emulator + physical | all lanes | Q1 ready; candidate built | Execute A01–A15 and U01–U18 on the same candidate | Expected/observed outcomes with retrievable redacted evidence; blocked/failed/not-run remain blocking | `—` | `not-run` | `—` |
| `DV-Q3-SIGNED` | `—` | `—` | internal | signed physical | Ubicloud protected `play-internal` release lane | exact protected-main RC tag + exact-source CI + approval/signing access | Build/sign directly via `tag-release.yml` and the same-SHA `release/android/` controller; verify digest + installed identity; approved internal-track upload | Reproducible signed artifact; install/update preserves user data; no QA/fixture leakage | `—` | `not-run` | `—` |
| `DV-Q4-HANDOFF` | `—` | `—` | `—` | signed physical | all lanes | Q2/Q3 evidence | Review `docs/android-rn-release-handoff.md`; record candidate-specific release decision and rollback | Handoff names the exact build and what was tested; release approval recorded against the candidate | `—` | `not-run` | `—` |
| `DV-CAP-SIGNING` | `—` | `—` | internal | signed physical | Play app-signing lineage | upload key + WIF in protected `play-internal` environment | Sign the release AAB; compare signer cert SHA-256 | Signer matches the approved lineage; no debug identity | `—` | `not-run` | `—` |
| `DV-CAP-OAUTH-GOOGLE` | `—` | `—` | internal | signed physical | Google provider | OAuth client bound to the release cert | Signed Google sign-in / cancel / browser return | Actual callback identity; no stale session or duplicate launch | `—` | `not-run` | `—` |
| `DV-CAP-OAUTH-APPLE` | `—` | `—` | internal | signed physical | Apple provider | Apple client + callback | Signed Apple/link sign-in; cancel; account switch | Callback bound to the initiating operation; tokens native | `—` | `not-run` | `—` |
| `DV-CAP-PLAY` | `—` | `—` | internal | signed physical | Play licence-test track | products + licence-test account | Purchase signed out of Lava; pending/cancel/restore/manage | Verified entitlement only; no optimistic Plus; authoritative negative handled | `—` | `not-run` | `—` |
| `DV-CAP-INTEGRITY` | `—` | `—` | internal | signed physical | Play Integrity | Integrity project/package/cert binding + keyless verifier | Attested report on the signed build | Keyless verification; no privileged key in the artifact | `—` | `not-run` | `—` |
| `DV-CAP-BACKUP` | `—` | `—` | internal | signed physical + emulator | controlled backup service | backup account; iOS↔Android payloads | Setup/password/recovery/PRF; live upload; cross-platform restore; delete/retry | Interoperable payload; no secret in JS/logs; durable nullable/pending state | `—` | `not-run` | `—` |
| `DV-CAP-REPORT` | `—` | `—` | internal | signed physical | approved feedback lane | authorized receipt endpoint | Review/send one approved report; retry; 429 injection | Consent-bound body; only matching receipt succeeds; no auto-upload | `—` | `not-run` | `—` |
| `DV-CAP-DEVICE` | `—` | `—` | internal | signed reference + OEM with restrictive background | local VPN + biometrics | physical devices | VPN consent/revocation, biometric prompt/lockout, process death, reboot, always-on/lockdown, Doze | Honest requested/confirmed state; no duplicate service; OEM background behavior recorded | `—` | `not-run` | `—` |
| `DV-CAP-EMULATOR` | `—` | `—` | debug | API 28 + API 36 emulator | local | Ubicloud emulator lanes | Run the journey/native-flow/atomic/AX matrices | Repeatable CI evidence for non-VPN/non-biometric/non-provider surfaces | `—` | `not-run` | `—` |

## Method and honesty statement

- Rows are derived from the plan qualification matrix (`U01`–`U18`), the native inventory (three
  modules, seven components, page/modal placements), the F23 `INTERRUPTION_MATRIX`, and the F22
  atom/locale matrices. The F01 inventory is the source for which Android surfaces exist at the
  audited head.
- A `pass` without a redacted, retrievable evidence link is invalid. This document contains no `pass`
  rows and was authored without device, signing or live-service access.
- Test fixtures are never production acceptance; a Jest or Kotlin result is a precondition, not a
  qualification.


## 2026-10-04 continued same-candidate qualification repair

Published e13cddc9 source CI37202855494 passes:520 RN tests/41 suites,
307 source contracts, full unit/build/lint and four-ABI/16KB/130-clip package
validation. Device CI37202855509 fails. Independently downloaded candidate
11303716940 is merge4bb9595b, parents51a54bb0+e13cddc9, branch-identical
tree1f3d7ab0; actual APK SHA256
17e51d0aef748afa6433b379928c8cc558c39ae47cc9d7862f25767e78f2f929.
Both retained device ZIPs contain that exact APK, use the stable development
signer and preserve the synthetic update witness. Raw XML exactly matches
recorded metadata: API28 has85/90 required passes, API36 has89/90. Failed
runner outcomes remain failures. Neither cohort is promoted to qualified.
Generic VPN40, genuine controlled JNI/Network-bound UDP DoQ, product entry
and four process-recovery phases pass separately on both APIs.

The five API28 failures are exact native CJK isolation2, first same-tick
slider movement, expanded native Search acquisition, and focused VPN Settings
return after actual OS Disconnect. The sole API36 failure is landscape
slider0-to3 persisting4. Reviewed repair source7e173bc0 closes public native
input-track/readiness gaps, uses a fixture-owned noneditable window handoff
before IME isolation, binds native Search to its actual public accessibility
source (API28 inherits EditText's class), and reacquires the genuinely focused
Settings root before its one Back. All owner, generation, preservation,
exact-value and original deadline assertions remain strict.

New actual owned API36 checks pass: direct composition2 in34.436s and slider
lifecycle2 in40.948s with53b2dd21 instrumentation; full real slider/history/
menu/Cancel journey in53.028s with7e173bc0 instrumentation. All use unchanged
0976 production APK9e5a8071 and retain their explicit fresh test APK under the
stable signer plus private update witness. These are scoped method receipts,
not one complete joint candidate. The intermediate042 test reaches native
and durable landscape3 but fails later on stale post-rotation AX geometry;
that negative receipt is retained. Intermediate53 Search/edit succeeds but
Copy fails: the shipping RN Pressable's500ms-after-JS-grant timer is distinct
from the actual native400ms preference. The final test holds one real DOWN
until the genuine owned native menu appears within its original20s budget,
then one UP, with CANCEL on failure. No action replay, clipboard action,
menu fabrication, OS preference change or timing waiver is introduced.

Exact build manifests, transcripts, raw XML, native geometry and preservation
records remain beneath /private/tmp/android-finish-20261003/evidence. The
independent e13 negative proof is ci-e13cddc9/independent-negative-cohort-verdict.json.
Next: complete fresh same-candidate source CI and all90 required identities
on both APIs, independently reconcile artifact/source/tree/signers/raw XML,
and repair actual failures. PR91 remains draft until that gate passes.
Useful verification must not be cancelled by doc-only pushes.

Merged iOS PR844's806fcf5c composer was independently read from GitHub and
matches reviewed Android RN source exactly (SHA256cfb09ecd29181560b51aab166dd6080d3aa326f01b1b4730743c64d12569383d).
Standard native ZXing/Core Image encoders remain; full/half decoded exports
are retained, and pixel/mask identity is not asserted. The Android parity
target remains frozen3bd86116. Web PR63 remains unmerged/unactivated.

All113 full F25 rows remain not-run. Physical/OEM, signed/live/Play/provider
qualification and genuine installed TalkBack gestures remain unqualified.
The optional TalkBack test still refuses missing genuine InputReader transport
before service/product actions. Public controller capability research is not
an input receipt or qualification. Separate protected merge/activation/signing/
upload authorization remains intact. Root owns runners; continuation remains
session-bound, and this journal is not a scheduler.


## 2026-10-05 slider delivery repair and genuine-input checkpoint

Published6801fe26 source CI37208822349 passes:520 RN tests/41 suites,
307 source contracts, full unit/build/lint/AAB, four-ABI16KB and all130
narration clips. Same-candidate device CI37208822350 fails exactly one
required slider lifecycle method on each API. Independent raw XML confirms
89/90 required passes on both APIs, none missing; failed runner outcomes
remain failures. Candidate artifact11305458378 is actual merge704bc296,
parents51a54bb0+6801fe26, branch-identical tree38874e0f, APK SHA256
f5c1d890a59e3f89569eace24a56f914c29b4778e54f6e8ec23f94111360e75d.
Both device ZIPs contain that exact APK, match recorded metadata and preserve
the synthetic update witness. CJK2, history/menu, focused VPN return, controlled
DoQ, backup and process recovery now pass both APIs. This failed cohort is
not promoted to a functional candidate.

An unchanged local slider reproduction fails16.633s. Public Window.Callback
observation in3e7fb884 then proves actual native resampling: submitted320px
MOVE arrives402.03027px with the original320px in history, persisting4 instead
of3. Reviewed81a98f1c spaces actual MOVE samples beyond the platform's20ms
resampling interval while retaining the original per-control deadline,
coordinates, exact values, forwarding and lifecycle assertions. Both actual
slider methods pass23.973s on the owned API36 emulator: all8 submitted sample
intervals exceed20ms and all8 delivered samples match exactly without history.
Disabled input, parent scroll, CANCEL, rotation, remount and durable owner
checks pass. The unchanged0976 production APK9e5a8071 plus explicit test APK
fd1b8947 retain the stable development signer and private update witness.
Intermediate failures and their failed restoration outcomes remain retained.
This scoped repair receipt is not a full90-method or API28 verdict.

Optional TalkBack source15e30c15/667a8ee2 uses a nonce-bound, single-use host
bridge to the public authenticated SDK controller and observes actual primary
InputReader kernel frames. Sixteen pure-standard-library host models pass;
these are models, not device qualification. One actual3e preflight completes
10EV records/3SYN frames but the native journey fails8.760s during transient
focused-window acquisition. Reviewedef3131a5 waits for that acquisition within
the existing deadline, retaining strict foreign/ambiguous-focus refusal. Its
actual next attempt fails8.189s before completing preflight with
KERNEL_COORDINATE_MISMATCH;7EV records/2SYN frames include the finally release.
Fresh EventHub AbsState preserves the previous Y axis across contacts; generic
getevent axis metadata does not report that per-contact state. W2 has the
exclusive ci/android_talkback_inputreader.py and companion-model lease to
repair fresh state initialization without accepting missing/guessed coordinates
or replaying input. Root owns every runner. Both attempts restore all4 secure
settings, credential-file digest and private update witness, and remove their
owned public-key registration, ready file and reverse port. Neither is a
TalkBack gesture pass; the optional test remains outside default90 without
an F22/F25 exclusion. No speech or physical/OEM qualification is asserted.

Receipts, immutable APK identities, raw transcripts and actual touch samples
are retained beneath /private/tmp/android-finish-20261003/evidence. Next: freeze
the reviewed combined source, run fresh complete source and same-candidate
API28/36 CI, independently reconcile all90 identities, raw XML, artifacts,
source/tree/signers and update preservation, and repair any actual failure.
PR91 remains draft until this gate passes. Preserve useful CI; avoid doc-only
pushes that cancel it. The coordinator remains session-bound with no scheduler.

Merged iOS PR844 head806fcf5c/merge9077d764 uses the exact shared Android RN
composer SHA256cfb09ecd29181560b51aab166dd6080d3aa326f01b1b4730743c64d12569383d.
Standard ZXing/Core Image encoders remain; different payloads can produce
different QR patterns, and pixel identity is not claimed. Full iOS checks pass.
The Android comparison target stays frozen3bd86116. Web63 remains unmerged
and unactivated. All113 fullF25 rows remain not-run. Existing protected Play,
OAuth and Integrity setup is recorded but still requires fresh signed/live
qualification. Merge87→88→89→91, protected release, production association,
physical/OEM and live-service actions retain separate authorization/capability
boundaries; no main merge, signing/upload, activation or purchase occurred.


### Reviewed source freeze after persistent-axis repair

634dbc8b integrates reviewed df863d77 on ef3131a5: the host monitor starts
before a fresh exact EventHub state snapshot, seeds only observed released X/Y,
and rejects any intervening/partial frame, changed classification, missing,
ambiguous or guessed state. New tracking/pressure, exact coordinates, new SYN,
changed-axis movement and genuine release remain mandatory. All19 host models
pass in0.179s; no device or TalkBack outcome is inferred from them. W2's two-file
lease is closed. Native slider source remains the actual passing81a98f1c source.
The combined source is now frozen for fresh full source and same-candidate
API28/36 qualification; optional TalkBack still awaits a fresh actual run.
Root owns runners; existing protected/live/physical boundaries remain unchanged.


### Passing a553 emulator candidate; accessibility repair continues (2026-10-05)

Source CI37214875055 and same-candidate device CI37214875092 pass.
Independent raw XML confirms90/90 required identities on both APIs, all119
API28 and115 API36 raw outcomes passing, matching exact APK/source/tree/
signer and retained private update witnesses. Source has520 RN tests/41 suites,
326 contracts, complete unit/build/lint/AAB, four ABI16KB checks and130 clips.
Artifact11308560947 is the retained passing preview; exact digests and owned
API36 installation/launcher evidence are in the run journal's passing-a553 entry.

Optional TalkBack remains unqualified: actual ccec diagnostic fails31.265s,
with20 EV records/6 SYN frames and three genuine HOVER events reaching the
owned window, but no Guard accessibility focus. Exact callback/settings/data/
permission/key/ready/reverse cleanup passes. Reviewed test-only diagnostics and
partial-record host guards are integrated unpublished;21 host models pass.
A bounded native decorative-hover repair is under W1 ownership/W3 review.
No production fix or gesture pass is inferred before an actual positive run.
See [the run journal](android-delivery-run.md) for immutable receipts, leases and
resume actions. Root owns all runners; keep the passing a553 preview.

All113 fullF25 rows remain not-run; frozen target3bd86116, signed/live and
physical/OEM boundaries remain unchanged. iOS844 is merged/shared RN card;
web63 is not activated. This is controlled emulator evidence, not full readiness.


### Actual repaired TalkBack journey passes; refresh combined gates (2026-10-05)

Reviewed native decorative-hover fixb4d5cb6d now passes the unchanged genuine
installed-service journey on ownedAPI36 in14.418s: Guard focus, one swipe to
Settings focus, and real double-tap Settings activation.58 actual EV records/
20 SYN frames, exact state/permission/resource/callback cleanup. Native reset/
hover-versus-touch widget regression passes1.402s; local app/test update retains
all captured preference hashes, credential digest and private witness. No fake
focus, replay, moved target, added contact or deadline/assertion change.

This exact locally built e8e2b4a1 repair matches integrationb4d5cb6d Android
app/RN source; it is not a refreshed CI artifact or speech/physical/live evidence.
See [the run journal](android-delivery-run.md) for exact digests and receipts.
Retain the previous complete passinga553 candidate while refreshing complete
source and same-candidate API28/36 gates. Root owns runners; worker leases closed.
All113 fullF25 rows remain not-run and protected/live/physical boundaries remain.


### Final e668 scoped evidence; full qualification boundary (2026-10-05)

Source CI37220428304 PASS:520 actual RN tests/41 suites,328 contracts,
complete unit/lint/APK/AAB, four ABI16KB checks and130 clips. Same-candidate
CI37220428326 PASS; independently reconciled raw outcomes are120/120 API28
and116/116 API36, with90/90 required identities passing on each API.
Artifact11309814562 binds heade66874066d2f1a9ab43f60a78c2bb17c21415645 to
actual merge04b18fb8cceb78948a25bac9f038786331ef5d88 and identical
tree4d346df599a7f0cc40879d395f41896b84a3639a, stableF6 signer. Exact public
APK digests and retained receipts are in the final e668 [run-journal checkpoint](android-delivery-run.md).
Owned CI update/launcher pass4.711s/8.302s; genuine installed API36 TalkBack
passes14.813s with57 EV/20 SYN, all releases and strict cleanup. Source/device
receipts: `/private/tmp/android-finish-20261003/evidence/ci-e6687406`;
gesture receipt: `/private/tmp/android-finish-20261003/evidence/talkback-inputreader-production-ci-e6687406-api36`.

No row status changes: all113 full F25 obligations remain not-run and M3 is
not established. iOS844 is merged with the identical RN composer
SHA256cfb09ecd29181560b51aab166dd6080d3aa326f01b1b4730743c64d12569383d;
Android remains pinned to3bd86116. Fresh production DAL returns404. Current
adb inventory is only emulator-5580; local configured Cloud project reads are
denied/unknown, without implying absent operator capability or broken
protected WIF. Actual physical/OEM, signed callbacks/backup/report/PRF and
live acceptance still need capability, authorization and complete row evidence.

After explicit approval, one main-bound final-descendant integration supersedes
unchanged87→88→89→91: main8e0fd001 is an ancestor with0 main-only commits.
Fresh review/protected checks and actual-main source/device/controller gates
precede separately approved RC signing/upload. Web63/provider activation
remains separately gated. No Android merge/tag/sign/upload, activation or
purchase occurred. Coordinator operation is session-bound, with no scheduler;
operator approval or capabilities are actual resume triggers. This docs-only
checkpoint is not a new compiled APK or a source-gate pass for its own branch.
