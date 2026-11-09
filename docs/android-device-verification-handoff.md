# Android parity close-out — device-verification handoff

Dated 2026-09-29. This is the **Wave 5 stop-boundary handoff** for the Android parity close-out on
`feat/rn-309-gaps`. It records what is landed and locally verified, what remains implementable
without a device, and the exact operator lanes (Q1–Q4) that cannot proceed from this workspace.

It performs no merge, push, deploy, upload, purchase, report or device action. Companion documents:
`docs/android-wave-plan.md` (scope freeze), `docs/android-rn-qualification-ledger.md` (F25 evidence
ledger; all rows `not-run`), `docs/android-rn-release-handoff.md` (F26 signed-candidate runbook),
`docs/android-parity-target.md` (accepted iOS target).

## A. Landed and locally verified

Integrated head: `feat/rn-309-gaps` (wave 0/1 commits + merges of `w2b`, `w2e`, `w2g`, `w2c`, `w2d`).
Gate evidence on this box: `npm test` (typecheck + `canonical:check` + **340 Jest / 25 suites**), the
Python contract suite (`test_android_bundle`, `test_android_rn_qualification`,
`test_android_canonical_provenance`, `test_android_react_native_contract`, `test_android_localization`,
`test_public_snapshot`, `test_android_preview_contract`, `test_android_parity_evidence`), and Gradle
`:app:compileDebugKotlin` + `:app:compileDebugUnitTestKotlin`.

| Wave | Delivered |
| --- | --- |
| 0 | `docs/android-wave-plan.md`; F25 ledger A01–A15 + `DV-*` Q1–Q4 rows; verifier enumerates them (all `not-run`). |
| 1 | Additive `0ab08db` contract adopted (vpn family, `vpn.*`, `dns.toggle`, `DNSChoice.isEnabled`, `reset`/`swap`, `prominent`, `nativeSwitch`, outline symbols, `LavaToggleControl`); overlays preserved; manifest regenerated. |
| 2b | `SystemCapabilityCatalog` + `NotificationActionRouting`/`Intents` ported from #84; notification factories routed. |
| 2c | `BiometricAuthenticatorPort` behind `SecurityOwner`; dispatcher + `security` snapshot project real capability; fail-closed default. |
| 2d | Bounded injectable import/share acquisition + review pipeline; bridge wired; denial/cancel/rejection mapped to stable codes + copy. |
| 2e | `LavaNativePage` policy/containment + `LavaNativeFlowHost` ported; manager registered; stub replaced. |
| 2g | Jest adopters: token/geometry conformance, `LavaToggleControl`, adopter a11y audits, Explore/Sudoku. |

Robolectric and conscrypt tests cannot execute on the aarch64 box (environmental); they compile and
run on Ubicloud CI. See `docs/gotchas` 2026-09-27.

## B. Remaining implementable items (no device required)

These are honest residuals, each with its owner and required test; they are **not** device-gated.

| Item | Owner / files | Required test | Why not landed now |
| --- | --- | --- | --- |
| 2a `dns.toggle` + ordered-tier DNSScreen | `ResolverPlanSwitchOwner`, `MainProductViewModel.applyDnsFallbackEnabled`, `ProductCommandState.kt`, `NativeProductBridge.kt`, `NativeProductSnapshot.kt`, `runtime.ts`, plus canonical `review/{DNSScreen,scaffold,settings-scaffold,dns-editor}.tsx` adoption | Kotlin owner/parser/bridge tests + RN runtime forwarding test | Needs the 2-slot→tier projection **and** the shared-screen canonical adoption together; half-adoption regresses (decisions.md 2026-09-29) |
| 2f B02/B14/B17 fixtures/semantics | `AppConfigurationWitness` production capture; `BackupPayloadMapper`/`RestorePlan` prior-version + interrupted-write vectors; privacy clear-one-category tests | Kotlin fixtures/tests | Pure extension; scheduled |
| Wave 3 `vpn.*` draft editor | `ChainedConfigurationCommandOwner` + credential repository, then bridge/snapshot/RN `NativePageScreen` | Kotlin owner tests + bridge/snapshot + RN | Requires the single-rotation vs multi-row draft decision first (decisions.md 2026-09-29) |
| F21 route-gating wiring | `LavaUIReview.android.tsx` route gate consults `SystemCapabilityPolicy`; project the QA/debug capability | RN route test | Lead-owned shared file; catalog landed in 2b |
| F07 FlowHost bridge wiring | `NativeProductBridge.kt` one-flow host seam | Kotlin bridge test | Lead-owned shared file; seam landed in 2e |
| B04 biometric bridge port override | `NativeProductBridge.kt` + `MainProductViewModel.securitySetBiometrics` | Kotlin bridge test | Lead-owned shared file; port landed in 2c |
| B08 device picker/scanner adapter | a device `FilterShareCandidateSource` → `InjectedFilterShareInputPort` | instrumented test | Device `ActivityResultContracts`/permission work |

## C. Q1–Q4 operator lanes (device / live-service / signing)

Every lane below is `not-run`. The F25 ledger owns the per-row evidence; the `DV-*` rows in
`docs/android-rn-qualification-ledger.md` §G are the smallest operator actions. A lane may only be
marked complete with a redacted, retrievable evidence link on an identified signed artifact.

### Q1 — Credential, service and release-environment readiness (`DV-Q1-ENV`)

**Required:** Play app-signing lineage + upload key/WIF in the protected `lavasec-runner`
environment; Google OAuth client registered for the exact release certificate; Apple client +
callback; Play products/base plans + licence-test accounts; Play Integrity project/package/cert
binding + keyless verifier; controlled backup service account; approved feedback receipt endpoint;
Supabase public coordinates/RLS.
**Smallest operator action:** complete the redacted environment matrix in
`docs/android-account-service-setup.md` and the release runbook (§1, §8 of
`docs/android-rn-release-handoff.md`) — names/locations only, never values.
**Covers:** `DV-CAP-SIGNING`, `DV-CAP-OAUTH-GOOGLE`, `DV-CAP-OAUTH-APPLE`, `DV-CAP-PLAY`,
`DV-CAP-INTEGRITY`, `DV-CAP-BACKUP`, `DV-CAP-REPORT`, `U11`, `U12`, `U13`, `U14`, `A07`, `A08`,
`A09`, `A12`.

### Q2 — Combined backend and user-journey qualification (`DV-Q2-COMBINED`)

**Required:** API 28 + API 36 emulators and a signed physical reference device plus a representative
OEM with restrictive background behavior; controlled accounts/services from Q1.
**Smallest operator action:** build/install the candidate on the emulators, run A01–A15 and U01–U18
on one candidate, attach redacted evidence to the matching ledger rows.
**Covers:** all `A01`–`A15`, `U01`–`U18`, `NF-*`, `AF-*`, `INT-*`, `A11Y-*`, `DV-CAP-DEVICE`,
`DV-CAP-EMULATOR`.

### Q3 — Signed candidate and install/update (`DV-Q3-SIGNED`)

**Required:** merge/approval + protected signing/WIF + Play internal-test track access.
**Smallest operator action:** after reviewing the exact head, follow
`docs/android-rn-release-handoff.md` §2–§5 (`tag-release.yml` → `android-release.yml`,
`channel=internal`), verify the AAB digest and installed signer, then install over the previous
build without clearing data.
**Covers:** `A15`, `U18`, `NF-PAGE-PURCHASE`, `NF-SYS-*`, `DV-CAP-SIGNING`, `DV-CAP-PLAY`.

### Q4 — Handoff and rollback (`DV-Q4-HANDOFF`)

**Required:** Q1–Q3 evidence.
**Smallest operator action:** review `docs/android-rn-release-handoff.md` §6–§9 and record the
candidate-specific release decision + rollback against the exact artifact.
**Covers:** `DV-Q4-HANDOFF`, `A15`.

## D. Explicitly not performed / not authorized

- **Not performed:** no push, merge, tag, build, signing, Play/internal-track upload, deploy, live
  account/purchase/backup/report/deletion, or device install/update was executed.
- **Not authorized:** any of the above requires explicit operator action; this document grants none.
- **Not asserted:** no build number, `versionCode`, Play acceptance, installed-device identity or
  performance budget exists. Every F25/F26 row remains `not-run`.

## E. Addendum (2026-09-29, pass 2) — §B residual resolved on `feat/rn-309-gaps` @ `95e4556`

The §B "remaining implementable items" are now landed and locally green (see
`docs/android-parity-slice-status.md` for the full matrix):

- 2a `dns.toggle` + ordered-tier DNSScreen — **implemented** (`61deff3`), 2-slot projection.
- 2f B02 configuration witness production capture + fixtures — **implemented** (`a7b2798`,
  `93290ab`); fence enforced only when captured (decisions.md).
- F21 route-gating wiring (`LavaUIReview.android.tsx` consults `systemCapabilities.ts`; `qaTools`
  projected from the native DEBUG_ONLY capability) — **implemented** (`a539c45`).
- F07 FlowHost bridge wiring (one `LavaNativeFlowHost`) — **implemented** (`a539c45`).
- B04 biometric bridge port override — **implemented** (`61deff3`); device grant still gated.
- B08 device picker/scanner adapter seam — **implemented** (`7d55df3` + `93290ab`); real camera
  capture and device permission remain device-gated.

Still open and **not** device-gated (stated openly in the matrix residual section): the `vpn.*`
editor (deviation recorded), the Apple/link account adapter, the Play paywall RN adaptation, the
backup owner layer, activity charts/dates/domain actions, shared RN destinations, and the
`catalog.query`/`connection` projections.

## E. Exact state to hand over

- Branch: `feat/rn-309-gaps`. Integrated head: the wave commits and merges on top of `d892d3e`.
- Change inventory: see `docs/android-wave-plan.md` §Wave 1/2 and the commit series.
- Provenance: manifest pinned to iOS `c4658d9` (166 upstream / 18 overlay / 14 ios-platform /
  31 divergence / 9 android-only).
- Ledger: `docs/android-rn-qualification-ledger.md` (`not-run` throughout; A01–A15, U01–U18,
  native/atomic/AX matrices, `DV-*`).
