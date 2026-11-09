# Lava Security for Android

Reviewed source for Lava Security's Kotlin, Rust/JNI and React Native Android app.
One shared implementation builds two editions:

| Edition | Package | Product behavior |
| --- | --- | --- |
| Play | `com.lavasec.app` | Free/paid Plus, accounts and cloud backup |
| FOSS | `com.lavasec.app.foss` | All local features, portable encrypted backups, no accounts/cloud backup |

FOSS uses selected DNS/VPN upstreams and filter-source downloads. Lava support is
explicitly initiated; verification failure leaves local features available.
See [distribution and release gates](docs/android-distribution.md) for availability,
signing, migration and qualification requirements. Source availability does not
claim Play production approval, F-Droid admission or real-device qualification.

## Build from source

Install JDK 21, Android SDK/build-tools 36, NDK 27.2.12479018, CMake 3.30.5 and Node
matching `^22.13.0 || ^24.3.0 || >= 26.0.0`. CI uses Node 22. Rust 1.94.0 and
cargo-ndk 3.5.4 are pinned by the native build scripts. Use public Maven, npm and
crates.io inputs; the complete shared UI is committed here and needs no private checkout.

```sh
export ANDROID_SDK_ROOT=/path/to/Android/sdk
bash scripts/prepare-wireguard-core-android.sh
cd android
./gradlew --no-daemon testDebugUnitTest lintDebug :app:assemblePlayDebug :app:assembleFossDebug :app:bundlePlayRelease :app:assembleFossRelease
```

`bundleReactNativeJs` bundles the committed shared UI with `package-lock.json`;
no prebuilt JS/native output is needed. Release artifacts above are unsigned.
Never use the public debug key for a release. Both editions read the marketing
version from `release/android-version.properties`; release tags supply the
monotonic version code. See [candidate qualification](docs/android-release-ci.md).

## Source and licenses

Public snapshots contain source and a deterministic `release/promotion-manifest.json`
receipt, without development Git history. Rust dependency locks, upstream notices,
React Native source provenance and bundled dependency license texts are included.
See [CONTRIBUTING.md](CONTRIBUTING.md), [SECURITY.md](SECURITY.md),
[LICENSE](LICENSE) and `android/app/src/main/assets/licenses/`.
