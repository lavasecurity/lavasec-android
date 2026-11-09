#!/usr/bin/env bash
# Build the LavaSec DoQ engine (ThirdParty/doq-core) for the four
# Android ABIs and place the `.so`s in `:vpn`'s jniLibs, where the Android Gradle
# Plugin packages them into the APK/AAB. The Android mirror of the iOS
# `scripts/build-doq-core.sh`, with two differences forced by the platform:
#   - the artifact is produced by CI and consumed from jniLibs, not committed;
#   - there is an Android NDK clang rather than Xcode's, so the pin is rustc + NDK.
#
# Determinism levers (so two runs on the pinned toolchain + NDK agree):
#   - profile pins (Cargo.toml): lto + codegen-units=1 + strip=debuginfo.
#   - --remap-path-prefix (RUSTFLAGS) normalizes embedded absolute source paths.
#   - -ffile-prefix-map (CFLAGS) does the same for `ring`'s C objects, which the cc
#     crate compiles with the NDK clang and would otherwise bake the registry path.
#   - SOURCE_DATE_EPOCH fixes archive/member metadata that honors it.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
crate="${repo_root}/ThirdParty/doq-core"
# jniLibs lives in the :vpn module; AGP packages src/main/jniLibs/<abi>/*.so by default.
out="${repo_root}/android/vpn/src/main/jniLibs"

if ! command -v rustup >/dev/null 2>&1; then
  echo "error: rustup not found on PATH." >&2
  exit 1
fi
if ! command -v cargo >/dev/null 2>&1; then
  echo "error: cargo not found on PATH." >&2
  exit 1
fi
if ! command -v cargo-ndk >/dev/null 2>&1; then
  echo "error: cargo-ndk not found. Install: cargo install cargo-ndk --locked" >&2
  exit 1
fi
ndk="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [ -z "$ndk" ] || [ ! -d "$ndk" ]; then
  echo "error: Android NDK not found (set ANDROID_NDK_HOME)." >&2
  exit 1
fi
export ANDROID_NDK_HOME="$ndk"

# Run every rustc/cargo/rustup command from the crate dir so the pinned
# rust-toolchain.toml is the active override (a fresh CI runner may have no default).
cd "$crate"

# rustup's default target and the four Android targets. cargo-ndk maps the ABI names.
targets=(aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android)
for t in "${targets[@]}"; do
  if ! rustup target list --installed | grep -qx "$t"; then
    echo "error: rust target $t not installed. Run: rustup target add $t" >&2
    exit 1
  fi
done

# Stable virtual roots for embedded source paths (mirrors the iOS script).
cargo_home="${CARGO_HOME:-$HOME/.cargo}"
sysroot="$(rustc --print sysroot)"
export RUSTFLAGS="${RUSTFLAGS:-} \
  --remap-path-prefix=${crate}=/lavasec-doq-core \
  --remap-path-prefix=${cargo_home}=/cargo \
  --remap-path-prefix=${sysroot}=/rustc \
  -C link-arg=-Wl,-z,max-page-size=16384"
export CFLAGS="${CFLAGS:-} -ffile-prefix-map=${cargo_home}=/cargo -ffile-prefix-map=${crate}=/lavasec-doq-core"
export SOURCE_DATE_EPOCH=0

mkdir -p "$out"
find "$out" -name 'liblavasec_doq_core.so' -delete

echo "==> cross-compiling for arm64-v8a, armeabi-v7a, x86_64, x86"
cargo ndk \
  -t arm64-v8a -t armeabi-v7a -t x86_64 -t x86 \
  -o "$out" \
  --platform 28 build --release --locked

echo "==> built"
find "$out" -name 'liblavasec_doq_core.so' -print | sort | while read -r so; do
  printf '    %-48s %s\n' "${so#"$repo_root"/}" "$(python3 -c 'import hashlib, sys; print(hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest())' "$so")"
done

# Play targeting SDK 36 requires 16 KB-page-compatible native libraries. Assert every
# LOAD segment is aligned to at least 16384 (the -Wl,-z,max-page-size flag above), so a
# release cannot ship a 4 KB-aligned .so and fail the compatibility check or fail to
# load on 16 KB-page devices.
readelf="$(find "${ndk}/toolchains/llvm/prebuilt" -path '*/bin/llvm-readelf' \( -type f -o -type l \) -print -quit)"
if [ -x "$readelf" ]; then
  echo "==> verifying 16 KB LOAD alignment"
  while read -r so; do
    "$readelf" -lW "$so" | python3 -c '
import sys
aligns = []
for line in sys.stdin:
    parts = line.split()
    if len(parts) >= 2 and parts[0] == "LOAD":
        try:
            aligns.append(int(parts[-1], 16))
        except ValueError:
            pass
if not aligns or min(aligns) < 16384:
    sys.exit(f"LOAD alignment below 16 KB: {[hex(a) for a in aligns]}")
'
    printf '    %-48s ok (>=16 KB)\n' "$(basename "$(dirname "$so")")"
  done < <(find "$out" -name 'liblavasec_doq_core.so')
else
  echo "error: NDK llvm-readelf unavailable; cannot verify DoQ library alignment" >&2
  exit 1
fi
