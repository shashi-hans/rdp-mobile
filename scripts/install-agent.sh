#!/usr/bin/env bash
# Builds rdpagent, installs it to ~/.local/bin, creates the token on first run,
# and starts it as a systemd user service.
#
# Bind address: the Tailscale IP when it is assigned to a local interface
# (tailscaled running as root with a tun device). With userspace networking
# there is no such interface; tailscaled forwards tailnet connections to
# 127.0.0.1, so the agent binds there.
#
# Needs: Go on PATH (or GO=/path/to/go), Tailscale installed and logged in.
# Env: TS_SOCKET for a non-default tailscaled socket, RDPAGENT_PORT (default 8765).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GO="${GO:-go}"
PORT="${RDPAGENT_PORT:-8765}"
# Fixed path: rdpagent.service reads %h/.config/rdpagent/env.
CONF="$HOME/.config/rdpagent"
TS=(tailscale)
[[ -n "${TS_SOCKET:-}" ]] && TS+=(--socket="$TS_SOCKET")

TS_IP="$("${TS[@]}" ip -4 2>/dev/null | head -1 || true)"
if [[ -z "$TS_IP" ]]; then
	echo "Tailscale is not running or not logged in." >&2
	exit 1
fi
if ip -4 -o addr show | grep -qwF "inet $TS_IP"; then
	BIND="$TS_IP"
else
	BIND="127.0.0.1"
fi

mkdir -p "$HOME/.local/bin" "$CONF" "$HOME/.config/systemd/user"
(cd "$ROOT/agent" && "$GO" build -trimpath -o "$HOME/.local/bin/rdpagent" .)

if [[ ! -f "$CONF/token" ]]; then
	"$HOME/.local/bin/rdpagent" init -token-file "$CONF/token"
else
	echo "Keeping existing token in $CONF/token (show it with: cat $CONF/token)"
fi

printf 'LISTEN=%s:%s\n' "$BIND" "$PORT" > "$CONF/env"
echo "Agent listens on $BIND:$PORT; the phone reaches it at $TS_IP:$PORT"
cp "$ROOT/agent/rdpagent.service" "$HOME/.config/systemd/user/rdpagent.service"
systemctl --user daemon-reload
systemctl --user enable rdpagent.service
systemctl --user restart rdpagent.service
systemctl --user --no-pager status rdpagent.service | head -5 || true
