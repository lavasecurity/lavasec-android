# Android RN signed-candidate release handoff (plan 309 F26)

> Recovered/reconciled 2026-09-29 (convergence C1/C3). Ported from the parallel #84 branch onto the
> canonical #85 continuation (PR #85 head `8905156`, based on `main` `b14cbb5`). This runbook authoring
> performs no merge, deploy, upload, release or device action.

This is the **F26 runbook scaffold** for creating an authorized qualification candidate and handing a
completed signed candidate to an operator. An authorized operator executes it in the protected release
environment; authoring it performs no merge, deploy, upload, release or device action. Every executable
step below is written so it can be checked against a concrete candidate. No build number, installed identity, Play acceptance
or internal-track receipt is invented here.

An operator may authorize a qualification RC build/sign and internal-track upload after the protected
workflow gates in §2 pass for the exact source. That signed candidate is used to collect F25 evidence;
full F25 acceptance is not a prerequisite for creating the candidate needed for those tests. This
preserves the green-CI-before-device-handoff order in the invoked infra `AGENT.md` §5, using Android's
current protected `tag-release.yml` lane. Passing CI does not itself authorize signing or upload.

M3 acceptance and a completed F26 handoff require backed results for every required F25 journey in
`docs/android-rn-qualification-ledger.md`. Creating a qualification RC does not waive any row or establish
readiness. The iOS-only capabilities (DNS patch, Live Activities, Focus/App Intents, app icon, widgets)
retain their documented dispositions. Android Apple browser sign-in is implemented; build enablement,
verified provider/redirect configuration and signed live-callback qualification remain pending, as described in
`docs/android-account-service-setup.md`.

## 1. Revisions and environment to record

Record the exact immutable identities in the F25 ledger header and here. Never a moving branch.

| Component | Requirement | Candidate value |
| --- | --- | --- |
| Android source (`lavasec-android-internal`) | exact commit on protected `main`; tag `vX.Y.Z-rcN` peels to it; Gradle `lavasecVersionName` matches | `—` |
| Infra plan 309 / backend | compatible B24 protected release path revision | `—` |
| Shared RN / canonical | frozen canonical revision used by this continuation | `3bd86116f0d12595965df03cf634066d393b4896` |
| Release controller (`release/android/` in `lavasecurity/lavasec-android-internal`) | same immutable SHA as Android source and `tag-release.yml`; recorded as `runner_sha` / `controller_sha` | `—` |
| Release notes | `release-notes/en-US.txt`, nonempty, ≤ 500 chars, no control chars except LF | `—` |
| Signing lineage | Play app-signing lineage + upload certificate identity (public fingerprints only) | `—` |
| Toolchain | JDK 21, Node 22, Android SDK 36, NDK 27.2.12479018, CMake 3.30.5 | as pinned by the controller |

## 2. Build and sign through the protected RC lane

The existing path is `.github/workflows/tag-release.yml` in `lavasecurity/lavasec-android-internal`.
Its `authorize`, `build`, `sign_upload` and `upload_play` jobs run directly on Ubicloud
(`ubicloud-standard-4`), using the checked-in `release/android/` controller at the same immutable
commit as the source. The signing and upload jobs use the protected `play-internal` environment.

1. Preconditions: the integrated source, workflow and controller are merged to protected `main`;
   `android.yml`, `android-device.yml` and `release-controller-tests.yml` are green for that exact
   source SHA. The protected `play-internal` environment must have its approved upload-key and WIF
   configuration before signing or Play upload. The operator must explicitly authorize the
   qualification build/sign and any internal-track upload; those approvals do not grant M3 acceptance.
2. Review `release-notes/en-US.txt` in that exact source commit and confirm the byte binding with
   `python3 ci/release_notes.py release-notes/en-US.txt`.
3. Push a fresh strict `vX.Y.Z-rcN` tag whose peeled commit equals `origin/main`. The tag-push workflow
   requires that commit to equal `github.sha`, validates the declared version and all three exact-source
   CI runs, and binds the checked-in release-note bytes. The closed policy fixes `channel=internal`,
   track `internal` and package `com.lavasec.app`; `source_sha` and `runner_sha` must be identical.
4. The credential-free build job produces the AAB and `candidate-manifest.json`. The signing job
   verifies the exact artifact ID, archive digest, manifest and packaged runtime assets before using
   the protected upload key. The source and controller stay pinned to the same immutable SHA throughout
   the handoff. Only run attempt 1 is accepted; a retry requires a fresh RC tag.
5. With `PLAY_BOOTSTRAP_COMPLETE=true`, the upload job verifies the exact signed artifact and
   `release-record.json`, obtains short-lived WIF credentials, commits one atomic Play edit and
   records fresh readback evidence. Otherwise, the signed inspection/bootstrap artifact is retained
   and the lane fails closed without attempting Play mutation. The lane serializes releases for
   `com.lavasec.app` and does not cancel an in-progress release. Fork/PR execution does not enter it.

## 3. Immutable digest and installed-identity verification

Verify the produced artifact before any install or upload. Report `PASS`/`FAIL` with the raw digest.

1. AAB digest: record the SHA-256 of the controller output artifact; it must equal the digest bound in
   the signed candidate manifest.
2. Package identity: `com.lavasec.app` (release) with the expected `versionName`/`versionCode`.
3. Signing identity: compare the installed signer certificate SHA-256 against the approved Play
   app-signing fingerprint(s) in `docs/android-account-service-setup.md` (public fingerprints only;
   never a private key). A debug or mismatched signer fails the check.
4. Native/JS contents: run the existing bundle verifier against the AAB and the regenerated stripped
   native libraries:
   ```sh
   python3 ci/verify_android_bundle.py \
     android/app/build/outputs/bundle/playRelease/app-play-release.aab \
     --jni-root android/app/build/intermediates/stripped_native_libs/playRelease/stripPlayReleaseDebugSymbols/out/lib \
     --js-bundle android/app/src/main/assets/index.android.bundle
   ```
5. Installed identity: on each smoke device, record
   `adb shell dumpsys package com.lavasec.app` signer/version and compare to the artifact.
6. QA/fixture leakage: confirm no debug gallery, PhoneQA/DeviceQA route, QA endpoint, preview fixture
   default or test menu is reachable in the release build.

## 4. Internal-track upload (operator-gated)

This step requires explicit operator authorization and a Play licence-test configuration. It is not a
build step and is not performed by this scaffold.

1. Confirm the Play app `com.lavasec.app`, the internal-test track and the release notes for this
   version.
2. Upload only the verified signed artifact to the approved internal testing track, preserving the
   existing release lineage (never a fresh upload key).
3. Verify the track reflects the uploaded `versionCode` and the mapped artifact digest before
   announcing the candidate.
4. Record whether the upload is a first upload or a replacement; do not create subscription products,
   Play Integrity links or service-account keys as part of this runbook.

## 5. Install and update preservation

Update in place; **never uninstall or reset to hide an upgrade failure**.

1. Record the pre-update state: version/build, VPN configuration, filter library, account session,
   appearance/text-size and accessibility preferences.
2. Install over the previous build (`adb install -r` on the reference device; upgrade through the
   internal track on the OEM device).
3. Confirm user data, VPN configuration, account session, receipts/backups and accessibility
   preferences are preserved; the installed signer/version match §3.
4. Capture any migration/upgrade failure with logs; roll back per §7 rather than wiping.

## 6. Cold-start and critical smoke list

Run on the shipped signed build, in the approved environment, and attach redacted evidence to the F25
ledger row named.

- [ ] `U01` — cold start offline (no Metro), truthful startup, VPN consent, first allowed/blocked.
- [ ] `U02` — Guard off/on/ready/protected, pause/resume/recovery, stop during compile.
- [ ] `U16` — notification/deep-link cold entry, always-on/revocation, reboot return, upgrade.
- [ ] `U04` — filter create/edit/review/apply and real changed filtering; library cancel/commit.
- [ ] `U10` — DNS provider/transport/fallback switch and (if released) chained config save/remove.
- [ ] `U11` — signed Google/Apple provider sign-in/cancel/return, linked-provider identity, account
      switch and sign-out.
- [ ] `U12` — Play purchase with a licence-test account; pending/cancel/restore/manage.
- [ ] `U13` — backup opt-in/automatic/backup/restore with no secret leakage; restart persistence.
- [ ] `U14` — feedback long-text/IME with an approved receipt; no accidental Send.
- [ ] `U18` — minified release loads all modules/components; no fixtures/QA leakage.
- [ ] `A11Y-LOCALE-ja` / `A11Y-RESPONSIVE` / `A11Y-TEXT-SIZE` — representative CJK, compact/wide and
      enlarged-text smoke.
- [ ] `INT-process-death` / `INT-native-module-teardown` — recovery does not replay a vetted command.

## 7. Rollback

Rollback preserves installed data and signing lineage; it never uninstalls or resets.

1. Cancel/stop the internal-track rollout and re-publish the last known-good signed artifact with the
   same signing lineage and a higher `versionCode`.
2. If a device must be recovered, install the prior signed build over the current one
   (`adb install -r`) — do not clear app data.
3. Record the failing revision, the artifact digest, the observed failure and the rollback artifact
   digest in the F25 ledger.
4. Reverting RN/native code alone does not undo a remote operation (purchase, backup mutation, account
   deletion or report send); those belong to their backend owners and their own recovery procedures.
5. Compatibility issues belong to the backend migration owners; never use a wipe to mask an upgrade
   failure.

## 8. Handoff record and redacted credential retrieval

Record and hand off: version/build/track, source and artifact digests, the actual installed device
model/API, service revision, the completed qualification matrix, explicit limitations/blockers, and
the rollback procedure.

Where private material lives (names and locations only — never values):

- **Upload key and WIF configuration:** held by this repository's protected `play-internal`
  environment; the signing job uses the approved upload key and the upload job obtains short-lived
  WIF credentials. The Play app-signing key remains in the Play Console. Private material is not
  checked into this repository.
- **Play/Integrity service-account JSON:** server-only Worker secrets
  (`GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`, `PLAY_INTEGRITY_SERVICE_ACCOUNT_JSON`) staged with
  `wrangler versions secret put`; never in an Android build or a repository property. See
  `docs/android-account-service-setup.md` §"Retrieve and store credentials safely".
- **OAuth client coordinates:** Google Auth Platform clients for project
  `lava-security-1779539336889`; only the **Web** client ID is the app's server audience. Public
  fingerprints/project numbers may be recorded; no private key is stored here.
- **Controlled test accounts / licence-test account:** held by the operator, not embedded in the
  build; test fixtures never grant production access.
- **Backup recovery material:** never stored or logged; native-only, per F14/F18.

Never paste a secret, token, recovery phrase, raw receipt or personal history into the qualification
ledger, this runbook, a PR, a shell argument or a log.

## 9. Explicitly not authorized / not performed by this slice

- **Not authorized:** merging any PR, deploying a Worker, provisioning credentials, accepting provider
  terms, uploading to the internal track, running a signed install/update, making purchases/reports/
  deletions, resetting a device, promoting to public/production, or publishing a store release.
- **Not performed:** no merge; no CI dispatch; no tag; no build; no signing; no Play or internal-track
  upload; no live account, purchase, backup, feedback or deletion test; no device install/update.
- **Not asserted:** this runbook does not establish a qualified build number, `versionCode`, Play
  acceptance, internal-track receipt, installed device identity or performance budget. Scoped source
  and emulator receipts do not complete the F25 ledger's full `not-run` obligations or establish M3.
- **Prerequisite for qualification (§§2–6):** explicit operator authorization, the exact protected-main
  source/workflow/controller gates in §2, and the capability required by each operation. Collect the
  resulting signed/device/live evidence in F25. **Completed handoff (§8) and M3 acceptance** require
  the required F25 journeys to be evidenced and the F01 handoff gaps resolved or explicitly dispositioned.
