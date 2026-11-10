# Android check tiers

For FOSS APK and Play AAB downloads, see [release artifacts](android-release-artifacts.md).

Android follows the same separation as the iOS internal and public repositories.
Every ready pull request gets repository checks and the shared secret/mobile
security scans. Do not use skip-CI commit markers for documentation: required
contexts report successful scope receipts while expensive work stays off.

| Tier | Internal | Public |
| --- | --- | --- |
| Repository and security | Source contracts, scope fixtures, secret scan and mobile SAST | Same public source contracts and scanners |
| Shared UI | TypeScript, canonical provenance, notices, Jest, Metro bundle | Same |
| Core tests | Kotlin library tests and both edition app unit tests | Same |
| Native app | Both Debug editions and instrumentation compilation, with lint | Both editions' Debug/Release packaging, runtime-asset and FOSS boundary audits |
| Promotion | Contracts and a read-only exact-source export readiness check | The reviewed exported tree; no private promotion scripts |
| Qualification | Release packaging, optimized startup and API 28/36 journeys on main, manual runs or `full-build` PRs | Same device qualification; public native PRs also package Release |
| Advisory analysis | Shared mobile SAST; public-only CodeQL job stays inert | Java/Kotlin CodeQL on source pushes, weekly and manual runs |

Documentation-only changes run lightweight checks and report every required
context. Known JavaScript-only PR changes run UI validation and core tests while
native compilation reports a scope receipt. Specs, native files, dependency locks,
build configuration, CI changes and unknown paths rebuild. Missing/invalid Git
ranges, initial pushes and manual runs never become documentation-only.
Every non-documentation main push, including UI-only updates, retains Release
packaging and optimized startup qualification.
Classification uses the complete local Git diff, with rename detection disabled;
source deletion cannot hide behind a documentation destination. PR classification
uses the base revision's policy, so a PR cannot rewrite its own scheduling rules.

Draft PRs do not occupy build runners. Marking ready, changing `full-build`, or
converting back to draft refreshes checks and cancels obsolete PR work. Main
pushes have independent concurrency groups so a later docs push cannot cancel an
older source build.

Trusted internal work uses ephemeral Ubicloud Linux runners. Public and fork app
validation uses GitHub-hosted Linux; the shared organization security workflow
uses its pinned ephemeral scanner runners. Internal untrusted branches fail the
repository check and cannot execute the private app/promotion lanes.

Main requires pull requests, resolved conversations and passing checks, including
for administrators. Internal required contexts are Repo Checks, Android Unit Tests,
App compile (Debug, no release signing), Lava UI types, contracts, and Android
bundle, Contracts + Export Readiness, and both security scans. Public requires
Repo Checks, Android Unit Tests, Android build and both security scans. The public
build also requires successful UI validation before it may report success.

A docs/JS scope receipt is not fresh native or device qualification. Device and
optimized-startup evidence remain tied to the exact built source and APK. Source
publication does not claim store approval or F-Droid admission.

## Requesting deeper checks

Keep work in draft while iterating, then mark the PR ready to run its normal
change-based tiers. Add the `full-build` label when a PR needs Release packaging,
optimized startup and API 28/36 journeys, including for documentation or UI-only
changes. Removing the label refreshes the normal tiers and cancels the obsolete
PR run.

For a fresh qualification of main, manually run both **Android CI** and **Android
device tests** at the intended main revision. Check each run's source SHA before
using its APK or device evidence. Successful scope receipts on a later docs-only
commit do not qualify a new native artifact.
