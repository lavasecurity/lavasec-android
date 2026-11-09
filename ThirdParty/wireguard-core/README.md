# lavasec-wireguard-core (Android packaging)

Production packaging of the WireGuard engine (boringtun's crypto core) for the Android
chained-VPN-upstream feature. The Android twin of the iOS
`lavasec-ios-internal/ThirdParty/wireguard-core`, adapted to the platform's build and
release model.

Plan: `lavasec-infra` `plans/2026-09-12-android-chained-upstream-engine-plan.md`
(Slice 1). The iOS packaging contract originates in
`plans/2026-07-22-vpn-upstream-chaining-implementation-plan.md` (D4).

## Packaging contract

- **Vendored engine source**: `boringtun/` is the exact contents of the crates.io
  `boringtun-0.7.1` tarball (sha256
  `15dd6a8a89cbe8997f37ca0cf035e6ea4d64cd2ecea4aed83ffb9f99f7126939`), consumed as a
  path dependency. BSD-3-Clause (`boringtun/Cargo.toml`); the full text is
  `LICENSE-boringtun.txt`. No local patches — any future patch is a reviewable diff
  against this baseline.
- **Transitive deps** are pinned by `Cargo.lock` sha256 checksums, not vendored.
- **Toolchain** pinned in `rust-toolchain.toml` (rustc 1.94.0; the four Android ABIs).
- **The C ABI** is `src/lib.rs` + `include/lavasec_wireguard_core.h`, byte-identical
  to the iOS engine's seven-function surface
  (`lava_wg_session_new/free/encapsulate/decapsulate/tick/force_handshake/session_stats`).

### Difference from iOS

iOS commits a 3-slice `xcframework` and CI **byte-compares a from-source rebuild**
(the AGPL provenance answer for an artifact the Xcode build cannot produce). Android
does not need that: the Gradle build never runs cargo, so CI builds the `.so`s
(`scripts/build-wireguard-core-android.sh`) and they are consumed from `:vpn`'s
`jniLibs`. Nothing prebuilt is committed; `target/` and the generated
`android/vpn/src/main/jniLibs/` are gitignored.

The determinism levers are the same idea as iOS (profile pins + `--remap-path-prefix`
+ `-ffile-prefix-map` for `ring`'s C + `SOURCE_DATE_EPOCH`); the NDK clang replaces
Xcode's as the ambient C compiler, so the pin is rustc + NDK.

## Build

Requires: `rustup` with the four Android targets, an Android NDK
(`ANDROID_NDK_HOME`), and `cargo-ndk`.

```sh
cd ThirdParty/wireguard-core
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android
cargo install cargo-ndk --locked --version 3.5.4
cd -
bash scripts/build-wireguard-core-android.sh
```

CI runs this in `.github/workflows/wireguard-core-android.yml` on every change to the
engine (and self-skips when untouched). Host-side engine ABI tests run with
`cargo test --release` from this directory.

## Attribution

`THIRD-PARTY-NOTICES.txt` is the generated notice text; `sysroot-crates.json`,
`sysroot-packages.json`, and `third-party-notices-index.json` describe the statically
linked set so the notice reconciliation can verify it. The app-facing "Bundled
Libraries" notice ships with the `CatalogLegalNotices` change.

## JNI binding

The Kotlin `NativeLavaWgCore` owner in `:vpn` calls the checked-in JNI adapter
(`src/jni.rs`) over this C ABI. CI rebuilds all four ABIs from this source and
verifies the packaged libraries/ELF page alignment; host and Android device tests
exercise ownership, handshake and cleanup. No prebuilt native library or private
source checkout is needed. Release network/device acceptance remains separate
from compiling the JNI binding.
