#!/usr/bin/env bash
# Clones the pinned FreeRDP release into third_party/freerdp and checks that
# the checkout is the expected commit.
set -euo pipefail

FREERDP_TAG="3.32.2"
FREERDP_COMMIT="97eff6ddfbbfaa8e42a126d362686bd001ff8bdc"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/third_party/freerdp"

if [[ ! -d "$DEST/.git" ]]; then
	mkdir -p "$ROOT/third_party"
	git clone --depth 1 --branch "$FREERDP_TAG" https://github.com/FreeRDP/FreeRDP.git "$DEST"
fi

HEAD_COMMIT="$(git -C "$DEST" rev-parse HEAD)"
if [[ "$HEAD_COMMIT" != "$FREERDP_COMMIT" ]]; then
	echo "third_party/freerdp is at $HEAD_COMMIT, expected $FREERDP_COMMIT ($FREERDP_TAG)." >&2
	echo "Delete third_party/freerdp and run this script again." >&2
	exit 1
fi
echo "FreeRDP $FREERDP_TAG ($FREERDP_COMMIT) ready in $DEST"
