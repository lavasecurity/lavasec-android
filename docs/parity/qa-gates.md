# Android parity QA and release gates (slice 07)

Operator-facing checklists plus the automatable gates behind them. The
automatable parts live in `ci/` (localization, preview, foundation contracts),
`:presentation` JVM suites (geometry, semantics, screen contract), and `:app`
JVM suites (lifecycle, redaction, privacy). Everything in this document is a
device pass the slice does not automate: it is a checklist with exact steps and
expected outcomes, not an automation claim.

## 1. Localization gate

Automatable (runs in CI):

```sh
python3 -m unittest ci.test_android_localization
```

Covers all ten locales (`default`, `de`, `es`, `fr`, `it`, `ja`, `ko`, `pt-BR`,
`zh-CN`, `zh-TW`) across `:app`, `:presentation`, and `:vpn`: key-set equality
against English, no English leakage beyond the allowlist, CJK single-quantity
plurals, and real quantities in the romance/German locales.

Device pass (operator):

1. On each API level, `adb shell settings put system user_locale` is not used —
   set the device language in Android Settings per locale, then relaunch Lava.
2. Set Android Display size → Font size to **largest** (≈1.3×) and walk: Guard
   status panel, one Filters row with a long catalog name, Settings root at
   maximum scroll, DNS Resolver provider rows, Bug Report consent section, and
   one Network Activity row.
3. Accept: no clipped summary, no truncated button label, no control pushed
   behind the navigation surface. Any clipping is a defect, not a translation
   fix.
4. Spot-check ja and zh-TW with the same font scale (the two layouts most
   prone to wrapping differences).

## 2. Accessibility gate (TalkBack manual script)

Run TalkBack once on API 28 and once on API 36. Swipe-through order = visual
order; every focus announces exactly once.

### Guard root

- [ ] Focus "Guard" heading: announced once, as a heading.
- [ ] Focus the protection status panel: one element, label "Protection
      status", value composed from title + subtitle (e.g. "Protection Off.
      Local DNS filtering is off"). No separate announcement for the mascot.
- [ ] With protection on, primary action announces its label ("Turn on" /
      "Turn off" / "Resume" / "Reconnect"), state description "Controls Lava's
      local DNS protection.", and — only while pausable — the long-press hint.
- [ ] The mascot graphic is not a focusable element and contributes no
      announcement (no decorative noise).
- [ ] Filters and Activity rows each announce title + summary as ONE element
      ("How Lava filters, See how local filtering works, button") — never the
      badge, icon, or chevron separately.
- [ ] Toggling protection announces the distinct settled status change exactly
      once (repeated polling does not re-announce).

### Settings root and children

- [ ] Every root row announces title + summary in one element; the DNS Resolver
      row summary is the real plan-derived provider/transport line, never a
      static placeholder.
- [ ] Selected state: on the root navigation, the selected tab announces
      "Selected" and its label is read once (no double read).
- [ ] Each detail screen announces its title as a heading, then Back ("Back,
      button"), then content.
- [ ] Security: passcode field announces "Enter Passcode"; wrong passcode
      announces the "Wrong passcode" error; the announcement fires on submit,
      not per keystroke.
- [ ] Bug Report: attachment inventory announces each row's Included / Not
      included value; the redacted preview is announced verbatim; toggles
      announce their checked state.
- [ ] Network Activity empty/disabled states announce the honest empty copy;
      no row announces without a real event behind it.

### Error announcements

- [ ] Force a resolver switch failure (airplane mode with an encrypted-only
      plan if reachable, or the prepared failure state): the failure panel
      copy is announced, and the retry control is reachable.
- [ ] Domain action review: the warning ("Be extra careful") precedes Confirm;
      Confirm announces disabled while a rejection is present.

### Automation notes

- The stable status/value pairs, merged row semantics, heading nodes, selected
  state, and one-read root labels are pinned in JVM by
  `ProductScreenContractTest`, `ProductShellSemanticsTest`,
  `ActivityScreenSemanticsTest`, and `LavaComponentsTest`. The TalkBack pass
  verifies the same contract on a real screen reader; a device finding that
  contradicts a JVM assertion is a product bug, not a checklist waiver.

## 3. Reduce motion and dark mode coverage

**Reduce motion**: `LavaTheme` observes the Android reduced-motion setting by
default, and the Customization screen exposes the explicit override. The JVM
suite pins that reduced motion keeps the navigation surface and Guard content
bounds stable across root switches
(`ProductShellSemanticsTest.reduced motion keeps navigation and Guard content
bounds stable across root switches`) and the mascot animation honors
`LocalLavaReducedMotion` (`GuardianMascotAnimationTest`). Device check: enable
"Remove animations" in Android accessibility settings, switch Guard ↔
Settings, and confirm no bounds shift or looping animation.

**Dark mode**: supported by the design system — `LavaTheme` follows
`isSystemInDarkTheme()` with the static `LavaDarkColorScheme` and dark
semantic palette (dynamic color remains an explicit API-31+ opt-in, not an
automatic platform behavior). The contrast rationale is pinned in JVM:
`LavaThemeContrastTest` asserts normal-text contrast (≥4.5:1) for the exposed
static Material container pairs and semantic text roles in both schemes, and
non-text contrast for the active outlines. There is therefore **no recorded
"dark mode unsupported" decision**; the decision is the inverse: dark mode is
shipped, and contrast is enforced by test rather than by screenshot. Device
check: toggle system dark mode on both API levels; walk Guard, Filters, and
Settings roots; confirm every summary line is readable and the selected tab
color is distinguishable (the semantic safe-green treatment documented in
`ProductShell.kt`).

## 4. Lifecycle, privacy, and security gate

### Rotation checklist (device, exact steps)

Run each on API 28 (three-button nav forces the same lifecycle) and API 36.

1. **Onboarding**: start onboarding, advance to the protection-choice page,
   rotate. Expect: same page, same selections, no re-committed step.
2. **Filter preparation**: start applying a filter (preparing state), rotate.
   Expect: preparation continues or fails honestly; the previous filter stays
   in effect; back is disabled while preparing.
3. **Resolver save**: change the DNS Resolver plan, rotate immediately after
   the in-flight state appears. Expect: the committed plan or the honest
   failure panel; never a half-applied plan.
4. **Account sign-in**: Account & Backup is the honest unavailable state; open
   it and rotate. Expect: unavailable copy preserved, no fabricated spinner.
5. **Backup restore**: not shipped on Android (no backend contract); the
   unavailable state is the whole flow. Record a pass when rotation preserves
   it.
6. **Feedback submission**: reach the Bug Report consent step, rotate. Expect:
   topic, description (still redacted in the preview), and consent toggles
   preserved; no submission fires on rotation.

### Force-stop and relaunch checklist

1. **Filter switching**: apply "Extra", force-stop mid-confirmation, relaunch.
   Expect: the last durably committed filter in effect; no half-compiled
   artifact.
2. **Resolver fallback**: enable the fallback toggle, force-stop, relaunch.
   Expect: the toggle reads its committed value; no stale in-flight spinner.
3. **Network Activity recording**: record a few events, force-stop, relaunch.
   Expect: the in-memory runtime store starts empty (documented behavior —
   generation-scoped store, no durable persistence); the retention toggle and
   7-day disclosure stay accurate.
4. **Ambient notification publishing**: turn protection on, confirm the
   ambient notification is showing, force-stop, relaunch. Expect: the
   notification is removed on process death and republished by the credential
   graph on relaunch with protection on; it never outlives its owner.

### Privacy invariants (automatable, already pinned in JVM/CI)

- **Local Activity/diagnostics never enter account backup**: the manifest
  denies backup outright (`android:allowBackup="false"`) and the foundation
  contract verifier rejects its removal
  (`ci/test_android_foundation_contract.py`, private-app-surface test).
- **Default bug-report attachments**: report body + app/device info always;
  diagnostics and the Activity export only with their explicit toggles. Pinned
  by `MainProductViewModelBugReportTest.default submission excludes
  diagnostics and export without explicit consent toggles` and the redaction
  fence test (`submission redacts domains account data and identifiers before
  composing`).
- **Export/clear share one exclusion boundary**: `CredentialPrivacyFenceTest`
  (export, clear, attachment, graph rotation, and telemetry share the fence).
- **Retired credential graph generations cannot mutate runtime state after
  rotation/reconciliation**: `ApplicationGraphLifecycleOwnerTest.stale
  rotation request cannot retire the installed successor` and the
  retired-owner port assertions in `MainProductViewModelTest`; the
  reconciliation request carries the failed graph generation and the lifecycle
  owner ignores it once a successor is installed.
- **Navigation save/restore across rotation and process death**:
  `MainActivityDeepLinkTest` (configuration recreation, process restore,
  stale-intent precedence, fail-closed malformed paths).

### Security checklist (device)

- [ ] VPN consent is consumed exactly once per installation: denying it shows
      the honest denial panel; re-requesting works after the denial is
      dismissed.
- [ ] App lock: set a passcode, rotate on the passcode entry screen, relaunch
      after force-stop — the lock survives both and wrong passcodes never
      unlock.
- [ ] Protected surfaces (Activity/Filters) re-lock after the chosen timeout
      on return from background.
- [ ] The share sheet for an Activity export exposes exactly one opaque
      provider URI (the JVM contract is
      `LocalPrivacyServicesTest.share intent grants exactly one opaque
      provider uri`).
- [ ] Deep links still navigate only the four canonical roots; an invalid link
      leaves the restored path untouched.

## 5. Exit criteria for the slice

- Every parity-matrix row has an Android route, durable state contract,
  automated test class, and a visual fixture or a written deferral decision
  (see `parity-matrix.md`).
- `settings-root-max` and `filters-overview` captures contain no clipped row
  or hidden actionable control on both API levels.
- Ten locales, TalkBack on API 28/36, reduced motion, dark mode, rotation,
  force-stop, and the privacy invariants above all pass on device; the JVM/CI
  gates stay green at the slice head.
