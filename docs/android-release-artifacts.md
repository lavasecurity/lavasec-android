# Android release artifacts

**Android release artifacts** builds a universal FOSS APK for
`com.lavasec.app.foss` and a Google Play AAB for `com.lavasec.app`.
The build is credential-free. A separate protected job signs only the Play AAB
with the existing upload key; the FOSS APK stays unsigned for qualification.
This workflow does not upload to either store or access a private repository.

Every job uses the standard GitHub-hosted `ubuntu-24.04` runner in this public
repository. These are the [free standard public-repository runners](https://docs.github.com/en/actions/how-tos/write-workflows/choose-where-workflows-run/choose-the-runner-for-a-job).
The internal source keeps an inactive copy so future source exports preserve the
pipeline.

## Build without a release tag

1. Open **Actions → Android release artifacts → Run workflow**.
2. Select `main`, then run the workflow.
3. After the build passes, review the source/run and approve the
   `play-release-signing` deployment to allow Play signing.
4. Download `android-release-play-signed-<run>-<attempt>` from the successful run.
   Use `lavasec-play-<version>-<code>-upload-signed.aab` for Google Play.

The signed distribution contains:

- The Play upload-signed AAB, `play-signing-record.json` and
  `PLAY-SIGNED-SHA256SUMS`, covering the complete download.
- The unchanged six-file unsigned build described below. The original Play AAB
  is retained as signing evidence.

The unsigned build contains:

- `lavasec-foss-<version>-<code>-unsigned.apk`, including all four supported ABIs.
- `lavasec-play-<version>-<code>-unsigned.aab`.
- `SHA256SUMS` and `release-manifest.json`, identifying the exact public source,
  version, dependency-lock digests, workflow run and artifact hashes.
- `foss-boundary.json` and `foss-apk-boundary.json`, recording the resolved FOSS
  graph, actual packaged manifest, DEX, assets and native-library checks.

The signing record binds the original build to the signed AAB and existing upload
certificate. Every AAB payload entry must remain identical and have a valid
signature from that certificate. The original
`android-release-unsigned-<run>-<attempt>` download is available after the build,
including while signing awaits approval.

Actions downloads are retained for one day, matching repository CI storage policy.
A manual run creates no tag, GitHub Release or store upload. PRs changing the
pipeline, helpers or version/signing policy run the same unsigned build as
validation; PRs cannot enter signing or prepare a GitHub Release.

## Prepare artifacts for a stable version

1. Update `release/android-version.properties` through a reviewed PR. Both
   editions share its version name and deterministic stable version code.
2. Merge and check the source's CI evidence. Create a fresh `vX.Y.Z` tag on the
   reviewed public `main` commit, matching those properties, then push the tag.
3. The workflow tests and lints both editions, builds from source, verifies both
   release bundles, and checks the actual FOSS APK's package/version, release
   flags, 16 KB ZIP alignment and payload against the audited FOSS bundle.
4. Approve the protected signing deployment after reviewing its source and build.
   The job signs the Play AAB and verifies its signature, certificate and payload.
5. A separate job verifies the exact signed distribution archive and prepares an
   **unpublished draft GitHub Release** with the files above.

Build and signing jobs have read-only repository access. Only the tag-only draft
job has release-writing permission, and it receives no signing secrets. Signing
and draft jobs run neither Gradle/npm nor code from the downloaded artifacts.
Existing releases and assets are never overwritten. Retry all jobs for a failed
signing/draft handoff so artifact attempt identities remain consistent; inspect
any partially created draft first. Use a manual `main` run for another inspection
build instead of moving a tag or replacing versioned bytes.

## Protected Play signing

The public repository's `play-release-signing` environment restricts deployments
to `main` and `v*` tags and requires maintainer approval, with administrator bypass
disabled. The workflow independently restricts signing to manual `main` builds
or matching stable tags from reviewed main history. PRs and forks cannot sign.
The sole maintainer may approve their own run.

Three environment secrets contain the existing upload credentials:

- `ANDROID_UPLOAD_KEYSTORE_BASE64`
- `ANDROID_UPLOAD_KEYSTORE_PASSWORD`
- `ANDROID_UPLOAD_KEY_PASSWORD`

Restore these from the existing secure backup, not a new signing key. The reviewed
public policy in `release/android-play-signing.json` pins the existing alias,
keystore SHA-256 and upload certificate SHA-256. A mismatching key fails signing.
Key material is confined to a temporary owner-only directory, removed on success
or failure, and excluded from artifacts. Passwords are passed through subprocess
environment variables rather than command arguments. No Google credentials or
store upload permissions are configured for this job.

## F-Droid and store delivery

The FOSS APK remains unsigned and cannot be installed as distributed here.

F-Droid normally [builds applications from source](https://f-droid.org/docs/Building_Applications/).
This APK is a qualification/reproducibility input. Upstream-signed F-Droid distribution also
needs a [reproducible rebuild and accepted signing metadata](https://f-droid.org/docs/Reproducible_Builds/).
The review template in `release/fdroid/com.lavasec.app.foss.yml` remains disabled
until its buildserver inputs, dependency review and signing strategy are qualified.

The Play AAB is signed with the upload key for Play Console submission; Google
Play App Signing handles the APKs delivered to users. Publishing the draft,
Google Play upload and F-Droid admission remain separate release actions.
See [editions and distribution](android-distribution.md) for channel acceptance
and signing ownership.
