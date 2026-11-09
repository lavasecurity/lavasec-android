# Visual evidence harness (slice 07)

The parity QA gate captures the same named product state on the API 28 and
API 36 devices at a fixed compact width and density, so "looks like iOS" is
replaced by named, dated, commit-pinned screenshots plus the dp-level geometry
assertions in `ProductShellGeometryTest`.

## Invocation

```sh
# On the API 28 device (Pixel 3, 1080x2160, 440dpi):
scripts/capture-parity-evidence --out parity-evidence/api28

# On the API 36 device:
scripts/capture-parity-evidence --out parity-evidence/api36
```

Options:

- `--serial SERIAL` selects one device when several are attached.
- `--states a,b` captures a subset (see `--list`).
- `--skip-nav` captures the current screen without running the per-state
  navigation.
- `--no-install` skips the build+install provenance step for exploratory
  captures of an existing install. Those captures are recorded WITHOUT a
  commit pin (`commit: "no-commit-pin"`,
  `appProvenance: "unverified-installed"`) and must never gate a parity or
  release decision.
- `--list` prints the state names.

The script refuses to run with zero or several unattributed devices and
aborts without writing anything when `screencap` returns no bytes (screen off
or locked).

## Provenance

By default the harness builds the debug APK from the current checkout
(`./gradlew :app:assembleDebug`, needs `ANDROID_HOME`) and installs it before
capturing, so the recorded commit SHA is the exact revision running on the
device. Only this mode produces commit-pinned evidence; the `--no-install`
mode labels its captures `unverified-installed` instead.

## Capture states

Every non-deferred fixture of `parity-matrix.md` is capturable (see
`--list`):

| State | What the operator leaves on screen |
|---|---|
| `onboarding` | Onboarding first page (fresh install) |
| `guard-off` | Guard, protection off (fresh-install onboarding walked once) |
| `guard-protected` | Guard, Protected after accepting the VPN-consent dialog |
| `guard-paused` | Guard, Resume countdown after a pause duration |
| `guard-reconnecting` | Guard showing the Reconnecting status |
| `guard-fallback` | Guard showing the resolver-fallback status |
| `filters-overview` | Filters overview, top |
| `filters-library` | Filters library list |
| `filter-detail` | Active filter detail (editor) |
| `filter-editor` | Filter detail with a pending draft edit |
| `filter-review` | Review diff of a draft commit |
| `filter-preparing` | Preparation cover before it settles |
| `settings-root-top` | Settings root, top |
| `settings-root-middle` | Settings root, Support group centered |
| `settings-root-max` | Settings root, absolute bottom, version footer fully visible |
| `dns-resolver` | DNS Resolver provider list |
| `privacy-data` | Privacy & data retention toggles |
| `security` | Security app-lock controls |
| `customization` | Customization appearance controls |
| `account-unavailable` | Account & Backup unavailable panel |
| `upgrade-unavailable` | Upgrade unavailable panel |
| `bug-report` | Bug report topic step |
| `legal-notices` | Legal Notices first section |
| `nerd-stats` | Nerd Stats list |
| `network-activity-events` | Network Activity with recorded rows |
| `network-activity-empty` | Network Activity empty state |
| `activity-overview` | Activity overview with counts + flow bar |
| `top-domains` | Top Domains ranking |
| `domain-history` | Domain History paged rows |
| `domain-action-review` | Domain action review dialog |
| `ambient-notification` | Notification shade, Protection status visible |

## Output contract

Every capture is stored as

```
<state>__<device-tag>__<commit-sha12>__<utc-stamp>.png
```

where `<device-tag>` is `model-api<sdk>-<WxH>-<dpi>dpi`; `--no-install`
captures use `no-commit-pin` in that slot. A bare name like `current.png`
cannot be produced by this harness. Each screenshot also gets a record
appended to `manifest.json` in the output directory:

```json
{
  "state": "settings-root-max",
  "file": "settings-root-max__Pixel3-api28-1080x2160-440dpi__16e5a12c3d4f__2026-08-16T100000Z.png",
  "device": {"model": "Pixel3", "api": 28, "display": "1080x2160", "densityDpi": 440, "locale": "en-US"},
  "commit": "16e5a12c3d4f",
  "appProvenance": "built-from-head",
  "capturedAtUtc": "2026-08-16T100000Z"
}
```

## What the operator validates against each capture

- `settings-root-max`: no clipped row or hidden actionable control; the
  version footer clears the navigation surface (the JVM-side assertion for the
  16dp clearance is `ProductShellGeometryTest`).
- `filters-overview` / `filters-library`: complete overview diagram and rows,
  no card clipped by the navigation surface.
- `guard-*`: the selected-tab treatment (safe-green selected root) and the
  readable status summary.
- `dns-resolver` / `network-activity-*`: compact rows with readable summaries;
  the empty state is the honest empty state, not a fabricated zero row.
- Screenshot differences are triaged against the parity matrix row for the
  state; a difference is either an intended deviation (recorded in
  `first-build.md`) or a bug filed against this slice.

## Robolectric limitation (documented)

In the JVM suite, `SemanticsActions.ScrollBy` does not advance the Robolectric
viewport scroll offset and nodes scrolled below the fold report degenerate
zero bounds. The token-geometry tests therefore compose a tall viewport
(1200dp) so every measured row has real bounds, and the max-scroll clearance
acceptance is verified on device through this harness plus the existing
clearance test (which measures composed bounds whenever the environment lays
them out). See the test comments in `ProductShellGeometryTest`.
