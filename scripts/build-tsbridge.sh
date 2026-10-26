#!/usr/bin/env bash
# Builds the embedded Tailscale bridge (tsbridge/) into app/libs/tsbridge.aar.
# Needs: Go (GO, default "go"; Go downloads the 1.27.1 toolchain itself),
# gomobile and gobind in $(go env GOPATH)/bin, Android SDK + NDK 29.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GO="${GO:-go}"
SDK="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
export ANDROID_HOME="$SDK"
export ANDROID_NDK_HOME="$SDK/ndk/29.0.13113456"
export GOTOOLCHAIN=go1.27.1

GO_BIN="$(command -v "$GO" || true)"
if [[ -z "$GO_BIN" ]]; then
	echo "Go not found: set GO=/path/to/go" >&2
	exit 1
fi
GOPATH_BIN="$("$GO_BIN" env GOPATH)/bin"
export PATH="$(dirname "$GO_BIN"):$GOPATH_BIN:$PATH"

# gomobile/gobind must match the golang.org/x/mobile version in tsbridge/go.mod.
MOBILE_VERSION="$(cd "$ROOT/tsbridge" && "$GO_BIN" list -m -f '{{.Version}}' golang.org/x/mobile)"
"$GO_BIN" install "golang.org/x/mobile/cmd/gomobile@$MOBILE_VERSION"
"$GO_BIN" install "golang.org/x/mobile/cmd/gobind@$MOBILE_VERSION"

mkdir -p "$ROOT/app/libs"
cd "$ROOT/tsbridge"
gomobile bind -target=android/arm64 -androidapi 29 -javapkg rdpmobile.ts \
	-trimpath -ldflags="-s -w" -o "$ROOT/app/libs/tsbridge.aar" .
ls -l "$ROOT/app/libs/tsbridge.aar"
