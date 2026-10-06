#!/usr/bin/env bash
#
# Refresh the bundled ZeroClaw engine + web dashboard from an upstream release.
#
# Usage: scripts/fetch-engine.sh [version]   (default: 0.8.5)
#
# The Android wrapper runs the engine as a separate process, so it ships the
# upstream release binary and dashboard rather than linking the Rust crates.
# Only arm64-v8a is published by upstream; emulator (x86_64) support requires
# building from source (see the note at the bottom).
set -euo pipefail

VERSION="${1:-0.8.5}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

URL="https://github.com/zeroclaw-labs/zeroclaw/releases/download/v${VERSION}/zeroclaw-aarch64-linux-android.tar.gz"
echo "Downloading $URL"
curl -fsSL -o "$TMP/engine.tar.gz" "$URL"
tar xzf "$TMP/engine.tar.gz" -C "$TMP"

JNI_DIR="$ROOT/app/src/main/jniLibs/arm64-v8a"
WEB_DIR="$ROOT/app/src/main/assets/zeroclaw-web"
mkdir -p "$JNI_DIR" "$WEB_DIR"
rm -rf "$WEB_DIR"/*

install -m 0755 "$TMP/zeroclaw" "$JNI_DIR/libzeroclaw_engine.so"
cp -r "$TMP/web/dist/." "$WEB_DIR/"

echo "Installed engine -> $JNI_DIR/libzeroclaw_engine.so"
echo "Installed dashboard -> $WEB_DIR"
echo
echo "Note: upstream publishes no x86_64-linux-android artifact. To support"
echo "emulators, build the engine from source with the Android NDK:"
echo "  cargo install cargo-ndk"
echo "  rustup target add x86_64-linux-android"
echo "  (cd zeroclaw && cargo ndk -t x86_64-linux-android -o <out> build --release --bin zeroclaw)"
