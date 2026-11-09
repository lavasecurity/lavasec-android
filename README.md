# Lava Security for Android

[![Android CI](https://github.com/lavasecurity/lavasec-android/actions/workflows/android.yml/badge.svg)](https://github.com/lavasecurity/lavasec-android/actions/workflows/android.yml)
![Platform: Android 9+](https://img.shields.io/badge/platform-Android%209%2B-3DDC84?logo=android&logoColor=white)
[![License: AGPL-3.0](https://img.shields.io/badge/license-AGPL--3.0-blue)](LICENSE)

**The internet is lava — so I made a blocklist app even my dad can use.**

[Lava Security](https://lavasecurity.app) helps block malicious domains, ads and
trackers on your Android phone. Filtering happens on your device, and core
protection works without an account. The Android client is fully open source.

> **Android status:** Google Play and F-Droid releases are being prepared.
> For now, you can build the app from source. Download links will appear here
> when releases are ready.

## Highlights

- **On-device filtering** — check domains against blocklists stored on your phone.
- **Your lists, your rules** — choose filter lists and allow or block individual
  domains.
- **Encrypted DNS** — use the resolver you choose, with encrypted queries.
- **See what's happening** — review allowed and blocked DNS activity locally.
- **Save your setup** — back up and restore your configuration.
- **No account needed** — start filtering without signing up.

## How it works

DNS is how apps look up the addresses of websites and other services. Lava uses
Android's VPN connection to check those lookups against your blocklists on your
phone. Blocked domains are stopped locally. Lava can send allowed queries to
the DNS resolver you choose over an encrypted connection.

## Two editions

- **Play edition** — free core protection, with optional Plus features, accounts
  and cloud backup.
- **FOSS edition** — all local features available, with encrypted file backups
  and no accounts or cloud backup.

Both editions come from this repository. See
[editions and distribution](docs/android-distribution.md) for more detail.

## Get involved

Found a bug or have a feature idea? [Open an issue](https://github.com/lavasecurity/lavasec-android/issues).
Code contributions are welcome — start with [CONTRIBUTING.md](CONTRIBUTING.md).
Please report security vulnerabilities privately using [SECURITY.md](SECURITY.md).

## Build from source

Developers can build both editions using public dependencies and the source in
this repository.

<details>
<summary>Build requirements and commands</summary>

Install JDK 21, Android SDK/build-tools 36, NDK 27.2.12479018, CMake 3.30.5 and
Node matching `^22.13.0 || ^24.3.0 || >= 26.0.0` (CI uses Node 22). Make sure
`sdkmanager` and `cmake` are on your `PATH`. The native preparation script selects
Rust 1.94.0 and cargo-ndk 3.5.4.

```sh
git clone https://github.com/lavasecurity/lavasec-android.git
cd lavasec-android
export ANDROID_SDK_ROOT=/path/to/Android/sdk
bash scripts/prepare-wireguard-core-android.sh
cd android
./gradlew --no-daemon testDebugUnitTest lintDebug \
  :app:assemblePlayDebug :app:assembleFossDebug \
  :app:bundlePlayRelease :app:assembleFossRelease
```

The build installs locked dependencies and bundles the committed shared UI;
no private checkout or prebuilt JS/native output is needed. Release artifacts
above are unsigned. Never use the public debug key for a release. See
[candidate qualification](docs/android-release-ci.md) for release build checks.

Package IDs are `com.lavasec.app` (Play) and `com.lavasec.app.foss` (FOSS).
Both editions read their marketing version from
`release/android-version.properties`; release tags supply the version code.
Public source snapshots include a [promotion manifest](release/promotion-manifest.json)
recording the exported source and dependency locks.

</details>

## License

[GNU Affero General Public License v3.0](LICENSE). Bundled third-party license
texts are in [`android/app/src/main/assets/licenses/`](android/app/src/main/assets/licenses/).
