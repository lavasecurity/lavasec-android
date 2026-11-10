# Android release artifacts

**Android release artifacts** builds a universal FOSS APK for
`com.lavasec.app.foss` and a Google Play AAB for `com.lavasec.app`.
Both are unsigned. The workflow needs no signing keys, Google credentials or
access to a private repository.

Every job uses the standard GitHub-hosted `ubuntu-24.04` runner in this public
repository. These are the [free standard public-repository runners](https://docs.github.com/en/actions/how-tos/write-workflows/choose-where-workflows-run/choose-the-runner-for-a-job).
The internal source keeps an inactive copy so future source exports preserve the
pipeline.

## Build without a release tag

1. Open **Actions → Android release artifacts → Run workflow**.
2. Select `main`, then run the workflow.
3. Download `android-release-unsigned-<run>-<attempt>` from the successful run.

The download contains:

- `lavasec-foss-<version>-<code>-unsigned.apk`, including all four supported ABIs.
- `lavasec-play-<version>-<code>-unsigned.aab`.
- `SHA256SUMS` and `release-manifest.json`, identifying the exact public source,
  version, dependency-lock digests, workflow run and artifact hashes.
- `foss-boundary.json` and `foss-apk-boundary.json`, recording the resolved FOSS
  graph, actual packaged manifest, DEX, assets and native-library checks.

Actions downloads are retained for one day, matching this repository's CI storage
policy. A manual run creates no tag, GitHub Release or store upload. PRs changing
the pipeline, its helper or version policy run the same build as a validation
job; PRs cannot prepare a GitHub Release.

## Prepare artifacts for a stable version

1. Update `release/android-version.properties` through a reviewed PR. Both
   editions share its version name and deterministic stable version code.
2. Merge and check the source's CI evidence. Create a fresh `vX.Y.Z` tag on the
   reviewed public `main` commit, matching those properties, then push the tag.
3. The workflow tests and lints both editions, builds from source, verifies both
   release bundles, and checks the actual FOSS APK's package/version, release
   flags, 16 KB ZIP alignment and payload against the audited FOSS bundle.
4. After the build passes, a separate job verifies the exact artifact archive and
   prepares an **unpublished draft GitHub Release** with the files above.

The build has read-only repository access. Only the tag-only draft job has
release-writing permission; it does not run Gradle, npm or files from the
downloaded archive. Existing releases and assets are never overwritten. A failed
build can be retried, but the draft job refuses any existing release for that
tag. Inspect a partially created draft before retrying. Use a manual `main` run
for another inspection build instead of moving a tag or replacing versioned bytes.

## Signing and store delivery

An unsigned APK cannot be installed, and the AAB must be signed with the existing
Play upload key before uploading to Google Play. This pipeline does not use the
public CI debug key for either artifact or create a replacement signing lineage.
Keep signing credentials in a separate protected signing stage when that stage
is configured.

F-Droid normally [builds applications from source](https://f-droid.org/docs/Building_Applications/).
This APK is an inspection/reproducibility input, not an F-Droid submission or an
installable upstream-signed release. Upstream-signed F-Droid distribution also
needs a [reproducible rebuild and accepted signing metadata](https://f-droid.org/docs/Reproducible_Builds/).
The review template in `release/fdroid/com.lavasec.app.foss.yml` remains disabled
until its buildserver inputs, dependency review and signing strategy are qualified.

See [editions and distribution](android-distribution.md) for channel acceptance
and signing ownership. Publishing a draft or uploading to either store is a
separate release action.
