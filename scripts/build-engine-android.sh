#!/usr/bin/env bash
#
# Build the ZeroClaw engine for Android from the vendored `zeroclaw/` source,
# with 16 KB page-size alignment (required for 16 KB-page Android devices and
# Play compliance), then install it into the app's jniLibs.
#
# NOTE: the vendored source carries one Android portability patch in
# `crates/zeroclaw-runtime/src/rpc/local.rs`: the daemon's local-IPC lifecycle
# lock uses `flock(2)` directly instead of `std::fs::File::try_lock()`, because
# Rust std does not implement file locking on Android (`ErrorKind::Unsupported`),
# which otherwise makes the `socket` daemon component fail with
# "binding local IPC endpoint". Keep that patch when refreshing `zeroclaw/`.
#
# Usage:
#   scripts/build-engine-android.sh                 # arm64-v8a
#   TRIPLE=x86_64-linux-android ABI=x86_64 scripts/build-engine-android.sh
#
# Env overrides: ANDROID_NDK_HOME, API, TRIPLE, ABI, JOBS.
set -euo pipefail

NDK="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/27.2.12479018}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
API="${API:-31}"
TRIPLE="${TRIPLE:-aarch64-linux-android}"
ABI="${ABI:-arm64-v8a}"
JOBS="${JOBS:-3}"
MAXPAGE=16384

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/zeroclaw"

case "$TRIPLE" in
  aarch64-linux-android)
    ENV_TARGET="AARCH64_LINUX_ANDROID"
    ENV_CC="aarch64_linux_android"
    CLANG="aarch64-linux-android${API}-clang"
    CLANGXX="aarch64-linux-android${API}-clang++"
    ;;
  x86_64-linux-android)
    ENV_TARGET="X86_64_LINUX_ANDROID"
    ENV_CC="x86_64_linux_android"
    CLANG="x86_64-linux-android${API}-clang"
    CLANGXX="x86_64-linux-android${API}-clang++"
    ;;
  *)
    echo "Unsupported TRIPLE: $TRIPLE" >&2
    exit 1
    ;;
esac

export ANDROID_NDK_HOME="$NDK"
export ANDROID_NDK_ROOT="$NDK"
export "CARGO_TARGET_${ENV_TARGET}_LINKER=$TOOLCHAIN/bin/$CLANG"
export "CARGO_TARGET_${ENV_TARGET}_RUSTFLAGS=-C link-arg=-Wl,-z,max-page-size=${MAXPAGE} -C link-arg=-Wl,-z,common-page-size=${MAXPAGE}"
export "CC_${ENV_CC}=$TOOLCHAIN/bin/$CLANG"
export "CXX_${ENV_CC}=$TOOLCHAIN/bin/$CLANGXX"
export "AR_${ENV_CC}=$TOOLCHAIN/bin/llvm-ar"
export "RANLIB_${ENV_CC}=$TOOLCHAIN/bin/llvm-ranlib"

echo "Building zeroclaw for $TRIPLE (API $API, max-page-size=$MAXPAGE, jobs=$JOBS)"
cd "$SRC"
cargo build --release --locked --target "$TRIPLE" -j "$JOBS" \
  --no-default-features \
  --features agent-runtime,gateway,channels-full,channel-nostr \
  --bin zeroclaw

BIN="$SRC/target/$TRIPLE/release/zeroclaw"
DEST="$ROOT/app/src/main/jniLibs/$ABI/libzeroclaw_engine.so"
install -m 0755 "$BIN" "$DEST"

echo "Installed $DEST"
echo "LOAD segment alignment (expect 0x4000):"
readelf -l "$BIN" | awk '/LOAD/{print $NF}' | sort -u
