#!/bin/bash
# Removes what install.sh set up. ~/.hermes/tailscale-auth.json (the shared secret) is kept
# unless --purge is given; removing it signs every phone out for good.
#
#   ./uninstall.sh [--purge]
set -uo pipefail

HERMES_HOME="$HOME/.hermes"
HERMES="$HERMES_HOME/hermes-agent/venv/bin/hermes"
TAILSCALE="${TAILSCALE:-/Applications/Tailscale.app/Contents/MacOS/Tailscale}"
APP_DIR="$HOME/Library/Application Support/HermesMobile"
AGENTS="$HOME/Library/LaunchAgents"
LABEL_PREFIX="dev.kamiya.hermesmobile"

PURGE=0
[ "${1:-}" = --purge ] && PURGE=1

say() { printf '\033[1m==>\033[0m %s\n' "$*"; }

for label in "$LABEL_PREFIX.serve-sync" "$LABEL_PREFIX.ts-auth"; do
  say "Removing LaunchAgent $label"
  launchctl bootout "gui/$(id -u)/$label" 2>/dev/null
  rm -f "$AGENTS/$label.plist"
done

if [ -x "$TAILSCALE" ]; then
  say "Stopping tailscale serve for this Mac"
  "$TAILSCALE" serve --https=443 --set-path /ts-auth off >/dev/null 2>&1
  "$TAILSCALE" serve --https=443 off >/dev/null 2>&1
fi

if [ -x "$HERMES" ]; then
  say "Disabling the tailscale-auth plugin"
  "$HERMES" plugins disable tailscale-auth >/dev/null 2>&1
fi
rm -rf "$HERMES_HOME/plugins/tailscale-auth" "$APP_DIR"

if [ "$PURGE" = 1 ]; then
  say "Deleting $HERMES_HOME/tailscale-auth.json"
  rm -f "$HERMES_HOME/tailscale-auth.json"
fi

say "Done. Restart Hermes Desktop so the gateway drops the plugin."
