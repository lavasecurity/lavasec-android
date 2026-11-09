# Android release and promotion contract

Android follows the same product-release shape as iOS while using Android-native
delivery infrastructure.

## Repository and track mapping

| Source | Trigger | Google Play destination | Approval |
|---|---|---|---|
| private `lavasec-android-internal` | `vX.Y.Z-rcN` tag whose peeled commit equals current `main` | internal testing | automatic after CI succeeds |
| public `lavasec-android` | `vX.Y.Z` tag created from a reviewed public snapshot | alpha/external testing | manual promotion; dry run by default |

The private repository is the single development source of truth. RC tags may
be annotated or lightweight: automation validates the tag ref, peels it to its
exact commit, verifies that commit equals current `main`, and builds, signs and uploads
that commit directly from this repository on Ubicloud. A tag never authorizes a different commit.

GitHub Actions authenticates to Google Cloud with short-lived OIDC credentials
through Workload Identity Federation. The Play uploader has testing-track
permissions only; production release and financial permissions are excluded.
The internal tag workflow uses Ubicloud-hosted GitHub Actions runners; it has
no cross-repository dispatch dependency. See [candidate qualification](../android-release-ci.md).

## Public snapshot boundary

External promotion starts from a new output directory and an exact 40-character
internal commit SHA reachable from `main`. The exporter copies only the reviewed
source allowlist and rejects symlinks, submodules, unclassified files, generated
artifacts, caches, local Android configuration, signing material, service-account
material, private keys, and references to private Lava Security repositories.

The public shell must be clean and match reviewed canonical administration bytes.
The source commit also carries byte-reviewed public README, ignore and Dependabot
templates. The exporter accepts either the legacy shell or the reviewed upgraded
shell and always emits the source-reviewed templates. This permits repeat promotion
without requiring an unreviewed manual shell edit. License, contributing, security
and issue policy files continue to come from the exact canonical shell.

The complete allowlist includes Android/React Native, native Rust/JNI engines,
lockfiles, notices, build scripts and credential-free verification/device tooling.
Each tracked path is either explicitly public or explicitly internal; new unknown
paths fail. Only hash-reviewed narration/font assets, public synthetic TLS fixtures
and the reviewed Gradle wrapper may bypass the text-only check. The synthetic peer
key identifies no real service and is never a production signer. Any changed asset
or fixture must be reviewed with its digest. Private URLs (including private iOS
source), real keys, generated binaries and release upload control-plane code remain
forbidden. The only app signing exception is the exact public debug override.

Run `scripts/export-public-snapshot --repo <internal> --source <40-char-main-SHA>
--public-shell <clean-public-checkout> --output <new-directory>` after all required
checks pass for that exact source. No Git history is copied. The deterministic
schema-2 manifest records source SHA, marketing version, tree digest, per-file
hash/size/mode and dependency-lock/toolchain pin digests. Review its file inventory
and the public diff, build both editions from that output in a clean environment,
and record the later public commit/artifact hashes in the release receipt.
`ci/test_public_snapshot.py` exercises two consecutive promotions, exact asset
bytes and credential/private-reference rejection. A deliberate public visibility
change, signing and publication remain channel operations; see
[distribution requirements](../android-distribution.md).
