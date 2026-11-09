# Android editions, distribution and launch evidence

The codebase builds Play (`com.lavasec.app`) and FOSS (`com.lavasec.app.foss`)
from shared protection/storage/presentation sources. Both use
`release/android-version.properties` (currently 2.2.3). The Play edition retains
paid Plus, accounts and cloud backup. FOSS unlocks all local capabilities without
purchase, offers portable encrypted file backup and has no account/cloud service.
Any FOSS Plus decoration is cosmetic and never changes admission or safety limits.
Commercial domain and filter quotas are removed. The shared compiler/artifact ceiling
of 2,000,000 rules, 45 MiB per acquired source, 64 profiles per persisted library and
64 MiB authoritative configuration record remain resource and parser safety limits.
FOSS uses the compiler ceiling independently of the Play Plus tier.

## Network and privacy contract

FOSS may resolve DNS, connect to user-selected upstreams and fetch selected filter
sources directly. It uses the bundled reviewed catalog, without dynamic Lava
catalog requests or catalog background workers. It does not initialize account,
purchase, entitlement, cloud-backup, telemetry or background attestation clients.
Support starts only through an explicit user action and consent; verification
failure must preserve local functionality and offer a deliberate human-support
alternative. No public client configuration or embedded shared secret proves app
origin. Native support upload remains closed until a matching protected service
contract is qualified. See the endpoint and edition tests for the source boundary.

Before launch, capture release traffic on fresh install, startup, VPN activation,
filter refresh, backup export/import, restart and background maintenance, including
timeouts, redirects and blocked Lava origins. Repeat on a device without Play
services. Static build/dependency checks alone do not establish this evidence.

## Source, toolchains and licenses

A reviewed public snapshot includes Kotlin/Java, Rust and vendored boringtun,
JNI/CMake, committed shared React Native sources and provenance, scripts,
Gradle wrapper/catalog, npm/Cargo locks and complete bundled notices. Ordinary
builds use public Google Maven/Maven Central, npm and crates.io. There is no build
step that fetches shared UI from the private development source. Provenance
records the original source identifier and immutable revision without requiring
access to it. Public source export retains no private Git history.

Use JDK 21, Node 22 (RN supports `^22.13.0 || ^24.3.0 || >= 26.0.0`), SDK/API
36, build-tools 36.0.0, NDK 27.2.12479018, CMake 3.30.5, Rust 1.94.0 and
cargo-ndk 3.5.4. The Gradle distribution and rustup bootstrap are checksum pinned;
Cargo/npm dependencies use lockfiles. Runtime React Native/Hermes, native and
Gradle license compatibility must be audited for the exact resolved graph before
F-Droid submission; source availability is not an inclusion attestation.

## Channels and signing

| Channel | Deliverable | Required qualification |
| --- | --- | --- |
| Play internal | Signed Play AAB from a fresh RC tag | Exact source CI/device gates and protected signing/upload/readback |
| Play external | Stable reviewed public source, alpha testing | Separately qualified controller with current toolchains/native/JS preparation and Play flavor paths; dry run by default |
| Play production | Play-delivered installation | Explicit production rollout, Console policy/listing/VPN declarations and purchase/account/service acceptance |
| Direct FOSS | Signed FOSS APK | Source tag, checksum, signer fingerprint, release notes, tested install/upgrade |
| F-Droid | FOSS source build | Accepted recipe/licenses, buildserver tooling, signing strategy and admission |

This change does not publish any of these channels or alter repository visibility.
The existing organization Play account and app are reused. Inventory the existing
app-signing and upload certificate fingerprints/recovery ownership before signing;
never replace them as a setup shortcut. Build CI is credential-free. The existing
protected Play internal controller signs only the Play variant and checks remote
inventory so an uploaded version code cannot be reused.

Prefer upstream-signed reproducible F-Droid delivery using the same FOSS signing
lineage as official direct APKs, if qualified. Otherwise explicitly document the
independent signer before first publication. Same package IDs with different
signers cannot ordinarily update one another: support encrypted backup, uninstall,
reinstall and restore. Never distribute an upstream private key to F-Droid or add
another package ID silently. Separate Play and FOSS packages keep app data and
keystore state separate; installing FOSS does not cancel a Play subscription.
Both can be installed together, but Android VPN ownership prevents promising
simultaneous VPN operation.

`release/fdroid/com.lavasec.app.foss.yml` is a disabled review template. It must
remain disabled until a real reviewed public tag, F-Droid-approved Rust/Node/native
buildserver inputs, full dependency audit, build and signing evidence are supplied.
No scanner bypass is configured. Upstream-signature delivery and any Anti-Features
(including optional support/attestation/funding network behavior) require F-Droid
review. No funding URL is shipped without a verified owner-provided destination.
An optional donation never unlocks features or requests an account/token check.

## Version and release receipts

Both editions share marketing version and may share the code because their package
IDs differ. Codes use `major*100000000 + minor*1000000 + patch*10000 + ordinal`;
RC ordinals are 1–9998, stable ordinal is 9999, minor/patch are at most 99 and the
Play maximum is 2100000000. Thus `v2.2.3-rc3` is 202030003 and stable 2.2.3 is
202039999. Check every previously uploaded code before delivery. A changed update
artifact needs a new higher code; preserve published tags and artifact bytes.

The promotion manifest contains exact internal source SHA, exported tree digest,
version, file modes/sizes/hashes and dependency-lock digests. The later release
receipt must additionally record the public commit, edition/package, code, signer
fingerprint, artifact checksum, effective toolchain versions, CI/device acceptance,
service-contract revision and actual channel availability. Passing CI does not
prove physical-device behavior, deployed support/purchase correctness, store
approval or publication. F-Droid review timing is independent of other channels.
