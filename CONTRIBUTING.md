# Contributing

Thank you for helping improve Lava Security for Android.

The public repository contains reviewed source snapshots. Proposed public
changes are reviewed here, then reapplied and tested in the private development
source before a later public snapshot. This keeps one development source of
truth while preserving a public review surface.

Before opening a large change, start with an issue describing the user outcome,
security implications, and test approach.

## Ground rules

- Contributions are accepted under the repository's AGPL-3.0 license.
- Never commit credentials, keystores, signing configuration, private endpoints,
  generated APK/AAB files, or local Android configuration.
- New network calls, telemetry, or user-data collection require explicit
  privacy and security review.
- Android UI should use native Material patterns while preserving the product
  journey shared with iOS.
- Do not claim protection, filtering, scanning, or blocking without executable
  evidence for that behavior.

Security vulnerabilities must follow [SECURITY.md](SECURITY.md), not a public
issue.
