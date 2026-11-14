#!/bin/bash
# Removes what install.sh set up. ~/.hermes/tailscale-auth.json (the shared secret) is kept
# unless --purge is given; removing it signs every phone out for good.
#
#   ./uninstall.sh [--purge]
set -uo pipefail

HERMES_HOME="${HERMES_HOME:-$HOME/.hermes}"
HERMES="$HERMES_HOME/hermes-agent/venv/bin/hermes"
TAILSCALE="${TAILSCALE:-$(command -v tailscale || true)}"
APP_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/hermes-mobile"
UNITS="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"

PURGE=0
[ "${1:-}" = --purge ] && PURGE=1

say() { printf '\033[1m==>\033[0m %s\n' "$*"; }

for unit in hermes-mobile-serve-sync.service hermes-mobile-ts-auth.service; do
  say "Removing systemd user service $unit"
  systemctl --user disable --now "$unit" >/dev/null 2>&1
  rm -f "${UNITS:?}/$unit"
done
systemctl --user daemon-reload 2>/dev/null

if [ -n "$TAILSCALE" ] && [ -x "$TAILSCALE" ]; then
  say "Stopping tailscale serve for this machine"
  "$TAILSCALE" serve --https=443 --set-path /ts-auth off >/dev/null 2>&1
  "$TAILSCALE" serve --https=443 off >/dev/null 2>&1
fi

if [ -x "$HERMES" ]; then
  say "Disabling the tailscale-auth plugin"
  "$HERMES" plugins disable tailscale-auth >/dev/null 2>&1
fi
rm -rf "${HERMES_HOME:?}/plugins/tailscale-auth" "${APP_DIR:?}"

if [ "$PURGE" = 1 ]; then
  say "Deleting $HERMES_HOME/tailscale-auth.json"
  rm -f "${HERMES_HOME:?}/tailscale-auth.json"
fi

say "Done. Restart Hermes so it drops the plugin."
