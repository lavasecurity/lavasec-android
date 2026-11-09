# Canonical Lava React Native source

The complete shared UI is committed here under AGPL-3.0-only and reconciled
against `lavasec-ios-internal` commit
`4e552f33ab973c32539071f327803198f40279f5`, the iOS-internal main product
baseline as of 2026-11-09. The immutable revision, per-file hashes and reviewed
Android overlay/divergence rationale are recorded in
`../scripts/canonical-source.manifest.json`. This attribution is not a build
dependency: `npm run canonical:check` verifies this tree locally and needs no
upstream repository, account or credentials.

Platform adapters live beside shared source as `.android` modules. Native
operations stay behind Android owners and the explicit Play/FOSS capability
contract. Unsupported operations reject instead of using preview fixtures.
The manifest's upstream target does not imply every iOS feature is adopted.

Android changes include extensionless platform sibling imports, `.ios.ts` native
component specifications (so Metro chooses `.android.tsx` implementations),
Android toolbar/sheet adaptation, and the edition-specific local settings route.
See `docs/parity/shared-react-native.md` from the repository root for inherited
presentation behavior, `docs/android-distribution.md` for qualification gates,
and `docs/android-ios-latest-sync-2026-11-09.md` for the current shared-source
reconciliation, device validation and native limitations. Earlier promotion
comparisons remain in the historical iOS 2.2.0 receipt.
