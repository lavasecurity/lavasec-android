#!/usr/bin/env bash
# Shared by source CI and the credential-free release build. Keep the toolchain
# selection here so a release cannot silently omit the ignored jniLibs outputs.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export PATH="${HOME}/.cargo/bin:${PATH}"
if ! command -v rustup >/dev/null 2>&1; then
  # Rust's archived 1.28.2 installers, pinned to publisher SHA-256 checksums.
  case "$(uname -s)/$(uname -m)" in
    Linux/x86_64) target=x86_64-unknown-linux-gnu; digest=20a06e644b0d9bd2fbdbfd52d42540bdde820ea7df86e92e533c073da0cdd43c ;;
    Linux/aarch64) target=aarch64-unknown-linux-gnu; digest=e3853c5a252fca15252d07cb23a1bdd9377a8c6f3efa01531109281ae47f841c ;;
    Darwin/arm64) target=aarch64-apple-darwin; digest=20ef5516c31b1ac2290084199ba77dbbcaa1406c45c1d978ca68558ef5964ef5 ;;
    Darwin/x86_64) target=x86_64-apple-darwin; digest=9c331076f62b4d0edeae63d9d1c9442d5fe39b37b05025ec8d41c5ed35486496 ;;
    *) echo "Unsupported rustup bootstrap host" >&2; exit 1 ;;
  esac
  installer_dir="$(mktemp -d)"
  trap 'rm -rf "$installer_dir"' EXIT
  curl --proto '=https' --tlsv1.2 --fail --silent --show-error \
    "https://static.rust-lang.org/rustup/archive/1.28.2/${target}/rustup-init" \
    -o "$installer_dir/rustup-init"
  actual="$(shasum -a 256 "$installer_dir/rustup-init" | cut -d ' ' -f 1)"
  [[ "$actual" == "$digest" ]] || { echo "rustup installer checksum mismatch" >&2; exit 1; }
  chmod 700 "$installer_dir/rustup-init"
  "$installer_dir/rustup-init" -y --profile minimal --default-toolchain none --no-modify-path
  rm -rf "$installer_dir"
  trap - EXIT
fi
sdkmanager "ndk;27.2.12479018"
export ANDROID_NDK_HOME="${ANDROID_SDK_ROOT:?Android SDK root is required}/ndk/27.2.12479018"
cd "${repo_root}/ThirdParty/wireguard-core"
# rust-toolchain.toml pins both rustc and every supported Android target.
rustup show
if [[ "$(cargo ndk --version 2>/dev/null || true)" != "cargo-ndk 3.5.4" ]]; then
  cargo install cargo-ndk --locked --version 3.5.4 --force
fi
bash "${repo_root}/scripts/build-wireguard-core-android.sh"

bash "${repo_root}/scripts/build-doq-core-android.sh"
