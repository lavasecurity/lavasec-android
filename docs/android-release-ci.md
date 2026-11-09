# Android candidate qualification

`android.yml` runs the foundation contracts, Kotlin tests, lint, React Native
typechecking and Jest, then builds APK/AAB artifacts. Owned builds cross-compile
the pinned Rust engine and build React Native's appmodules JNI registration.
The release candidate is R8-minified and resource-shrunk (with resource names
left unobfuscated so React Native's dynamic drawable lookup still resolves); the
debug assembly alone would not prove the registration survives shrinking.
The verifier requires both native libraries for all four ABIs, checks their ELF
architecture and 16 KB LOAD alignment, and compares
packaged bytes with the generated JS and Gradle's stripped native libraries. It
reads the shrunk release DEX and rejects a bundle whose string table no longer
carries the JS-facing registration names (`NativeLavaApp` and each native Android
view manager), and it rejects any packaged JS source map.
The protected tag-release controller runs its own self-contained copy
(`release/android/verify_android_bundle.py`, checked out as
`trusted-controller/release/android/...`), so the DEX-registration and source-map
contract is enforced on the release path too, not only in source CI.
`ci/test_android_bundle.py` pins the registration, ABI, library and asset
constants the two copies share, so a `ci/`-only edit cannot silently leave the
release gate weaker than CI.
Fork builds run on ephemeral hosted runners and do not receive secrets.

`android-device.yml` executes the app storage, RN launcher, VPN descriptor, and native-engine
instrumentation suites on API 28 and API 36, using credential-free hosted
emulators. Four minimum-OS storage cases explicitly require API 28 and retain
their exact SDK assertions; `SdkSuppress` selects them only on that API. Shared
storage ownership, RN startup and all VPN cases run on both APIs.
The native smoke test loads JNI, creates and frees a test session,
and produces a handshake without network access. Reports and emulator
diagnostics are retained for one day. These
checks exercise device APIs; they do not establish real-network VPN behavior,
Play purchases, OAuth, backup interoperability, TalkBack, or physical-device
lifecycle qualification. Those remain separate release acceptance gates.

The source and protected release controller use JDK 21, Node 22, Android SDK 36,
and the same `scripts/prepare-wireguard-core-android.sh`. That script installs
NDK 27.2.12479018 and cargo-ndk 3.5.4, then uses the engine's checked-in Rust
toolchain and Cargo lockfile. Every app build job also explicitly installs
NDK 27.2.12479018 and CMake 3.30.5 for Gradle's appmodules build, including fork
builds that skip the WireGuard bootstrap. Ignored `jniLibs` outputs must be regenerated from
the source candidate; a clean release checkout cannot rely on local binaries.

Internal delivery runs entirely in this repository on Ubicloud. No runner-repo
merge, dispatch token, or cross-repository checkout is required.

Before tagging an internal candidate:

1. Merge the reviewed Android change into `main`, including
   `release-notes/en-US.txt`. Notes must be nonempty, at most 500 characters,
   and contain no control characters except line feeds.
2. Wait for Android CI, both API 28/36 device jobs, and Android release controller
   tests to pass for that exact `main` commit. The tag preflight checks successful
   runs of all three workflows for the exact commit.
3. Create and push a fresh `vX.Y.Z-rcN` tag on that commit. The tag's base version
   must match `release/android-version.properties`; RC ordinals must be 1–9998.
4. Follow **Android internal tag release** in this repository. Success means the
   signed bundle was committed to the existing Play internal track and a fresh
   Play readback confirmed the version, status and notes. Inspect the retained
   `android-release-record-<run>-<attempt>` artifact before device acceptance.

The tag workflow rejects tags whose peeled commit differs from current `main`,
failed or missing source checks, and workflow reruns. Release notes and source,
controller, artifact, certificate and version coordinates are verified across
all job handoffs. Version codes are deterministic:
`major * 100000000 + minor * 1000000 + patch * 10000 + rcOrdinal`.
For example, `v2.2.3-rc3` produces version code `202030003`.

Build jobs receive no release secrets or Google identity. Separate signing and
upload jobs use this repository's tag-restricted `play-internal` environment.
Only signing references the upload keystore; only upload requests Google OIDC.
The uploader uses the existing Workload Identity Federation provider and service
account, without a Google JSON key. The build never executes with signing or
Play credentials. The upload destination is fixed to `com.lavasec.app` / `internal`.

The environment requires three existing secrets:
`ANDROID_UPLOAD_KEYSTORE_BASE64`, `ANDROID_UPLOAD_KEYSTORE_PASSWORD`, and
`ANDROID_UPLOAD_KEY_PASSWORD`. Public variables are `ANDROID_UPLOAD_KEY_ALIAS`,
`ANDROID_UPLOAD_KEYSTORE_SHA256`, `ANDROID_UPLOAD_CERT_SHA256`,
`GCP_WORKLOAD_IDENTITY_PROVIDER`, `GCP_PLAY_SERVICE_ACCOUNT`, `PLAY_PACKAGE_NAME`,
and `PLAY_BOOTSTRAP_COMPLETE=true` after the one-time Play bootstrap. No dispatch
PAT is consumed. Restrict release tag creation to trusted maintainers and keep
workflow/controller edits under review.

Releases queue serially within this repository and are never cancelled by a
newer tag. Do not concurrently dispatch the legacy runner's Android internal
lane: GitHub concurrency groups do not span repositories. If a release fails,
inspect its evidence and current Play inventory before cutting a new RC tag;
do not move a tag, reuse a version code, or rerun a mutating release. Existing
Play inventory checks reject a version code that was already uploaded.

The controller tests exercise policy, tampered archives/manifests, opaque signing
with a temporary test key, and atomic Play edits/readback using a fake client.
They do not substitute for a real signed Play delivery and purchase test.

The public export now includes the native engines, shared UI, lockfiles, build
scripts, device workflow and both copies of the bundle verifier. The exporter
retains strict path classification, private-reference/credential checks, exact
reviewed binary-asset hashes and byte-reviewed public administration templates.
Run the exported tree's source contracts and build both editions in a clean
checkout before approving promotion; see [the promotion contract](parity/release-promotion.md).
Source inclusion alone does not establish clean-build or device evidence.

CI tests/lints both app flavors and produces Play/FOSS minified release bundles
plus a FOSS release APK. Packaged runtime verification runs for both bundles. A separate FOSS gate checks
the resolved release graph, package/component manifest decoded from the actual AAB
with checksum-pinned bundletool, and actual DEX for
excluded billing/Google integrations, retaining their digest-bound audit receipt.
The existing API 28/36 journeys remain Play-specific; FOSS needs its own no-Google,
traffic, backup/migration and coexistence acceptance before launch. The protected
internal tag controller continues to build/sign/upload only Play, with its existing
exact-commit, source-check, upload-inventory and certificate controls.

The separate external stable controller must be qualified against these inputs
before dispatch. The legacy controller checkout inspected during this change still
used JDK 17, unflavored `bundleRelease`/output paths and lacked the current native
and React Native preparation; it cannot serve as evidence that the new public
snapshot is deliverable. Update and test the actual controller's JDK/toolchains,
Play flavor tasks, native/JS preparation and packaged-runtime verification in its
owner repository before enabling an external stable upload. This Android change
updates the internal controller only and does not authorize production delivery.

For local packaging inspection after building:

```sh
python3 ci/verify_android_bundle.py \
  android/app/build/outputs/bundle/playRelease/app-play-release.aab \
  --jni-root android/app/build/intermediates/stripped_native_libs/playRelease/stripPlayReleaseDebugSymbols/out/lib \
  --js-bundle android/app/src/main/assets/index.android.bundle
```
