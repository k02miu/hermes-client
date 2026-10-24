#!/bin/bash
# Hermes Mobile — Mac side.
#
# Lets the Android app reach this Mac's Hermes over Tailscale and sign in with the phone's
# Tailscale identity, no password:
#   * the `tailscale-auth` Hermes plugin (dashboard auth provider),
#   * hermes-ts-auth: a loopback endpoint at <url>/ts-auth that turns Tailscale's identity header
#     into a short-lived sign-in code,
#   * hermes-tailscale-sync: keeps `tailscale serve` pointed at the gateway, whose port changes
#     on every restart,
#   * two LaunchAgents that start both at login and restart them if they stop.
#
# Safe to run again: the shared secret is kept, files and agents are replaced.
#
#   ./install.sh [--allow you@example.com ...] [--dry-run]
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
HERMES_HOME="$HOME/.hermes"
PYTHON="$HERMES_HOME/hermes-agent/venv/bin/python"
HERMES="$HERMES_HOME/hermes-agent/venv/bin/hermes"
TAILSCALE="${TAILSCALE:-/Applications/Tailscale.app/Contents/MacOS/Tailscale}"
APP_DIR="$HOME/Library/Application Support/HermesMobile"
LOG_DIR="$HOME/Library/Logs/HermesMobile"
AGENTS="$HOME/Library/LaunchAgents"
LABEL_PREFIX="dev.kamiya.hermesmobile"
AUTH_PORT=18766
AUTH_CONFIG="$HERMES_HOME/tailscale-auth.json"
# Labels this setup used before it was packaged; replaced on install.
LEGACY_LABELS="com.kamiya.hermes-tailscale-sync com.kamiya.hermes-ts-auth"
APK_URL="https://github.com/k02miu/hermes-client/releases/latest"

DRY_RUN=0
EXTRA_LOGINS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --allow) EXTRA_LOGINS+=("$2"); shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

say() { printf '\033[1m==>\033[0m %s\n' "$*"; }
die() { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }
run() {
  if [ "$DRY_RUN" = 1 ]; then printf '    [dry-run] %s\n' "$*" >&2; else "$@"; fi
}

# ---- preconditions ----------------------------------------------------------

[ "$(uname -s)" = Darwin ] || die "macOS only"
[ -x "$PYTHON" ] && [ -x "$HERMES" ] || die "Hermes Agent not found under $HERMES_HOME (install Hermes Desktop first)"
[ -x "$TAILSCALE" ] || die "Tailscale app not found at $TAILSCALE (set TAILSCALE=/path/to/tailscale)"

STATUS_JSON="$("$TAILSCALE" status --json 2>/dev/null)" || die "tailscale status failed — is Tailscale running and signed in?"
read -r BACKEND DNS_NAME SELF_LOGIN <<EOF
$(printf '%s' "$STATUS_JSON" | "$PYTHON" -I -c '
import json, sys
d = json.load(sys.stdin)
s = d.get("Self") or {}
user = (d.get("User") or {}).get(str(s.get("UserID")), {})
print(d.get("BackendState", "?"), (s.get("DNSName") or "-").rstrip("."), user.get("LoginName") or "-")
')
EOF
[ "$BACKEND" = Running ] || die "Tailscale is not connected (state: $BACKEND)"
[ "$DNS_NAME" != - ] || die "this Mac has no Tailscale DNS name; turn on MagicDNS in the Tailscale admin console"
[ "$SELF_LOGIN" != - ] || die "could not read the signed-in Tailscale account"
PUBLIC_URL="https://$DNS_NAME"

say "Hermes:    $HERMES_HOME"
say "Tailscale: $PUBLIC_URL (allowing $SELF_LOGIN${EXTRA_LOGINS[*]:+ ${EXTRA_LOGINS[*]}})"
[ "$DRY_RUN" = 1 ] && say "dry run: nothing will be changed"

# ---- files --------------------------------------------------------------------

say "Installing helpers to $APP_DIR"
run mkdir -p "$APP_DIR/bin" "$LOG_DIR" "$AGENTS"
run install -m 0755 "$HERE/bin/hermes-tailscale-sync" "$APP_DIR/bin/hermes-tailscale-sync"
run install -m 0644 "$HERE/bin/hermes-ts-auth" "$APP_DIR/bin/hermes-ts-auth"

PLUGIN_DIR="$HERMES_HOME/plugins/tailscale-auth"
PLUGIN_WAS_ENABLED=0
"$HERMES" config get plugins.enabled 2>/dev/null | grep -q 'tailscale-auth' && PLUGIN_WAS_ENABLED=1
say "Installing the tailscale-auth plugin to $PLUGIN_DIR"
run rm -rf "$PLUGIN_DIR"
run mkdir -p "$PLUGIN_DIR"
run install -m 0644 "$HERE/plugin/tailscale-auth/__init__.py" "$HERE/plugin/tailscale-auth/plugin.yaml" "$PLUGIN_DIR/"

# The secret is created once and kept: replacing it would sign every phone out.
say "Writing $AUTH_CONFIG (secret kept if it already exists)"
if [ "$DRY_RUN" = 0 ]; then
  ( umask 077
    "$PYTHON" -I - "$AUTH_CONFIG" "$PUBLIC_URL" "$SELF_LOGIN" ${EXTRA_LOGINS[@]+"${EXTRA_LOGINS[@]}"} <<'PY'
import json, os, secrets, sys
path, url, *logins = sys.argv[1:]
cfg = {}
if os.path.exists(path):
    with open(path) as f:
        cfg = json.load(f)
cfg.setdefault("secret", secrets.token_urlsafe(48))
cfg["public_url"] = url
cfg["allowed_logins"] = sorted(set(cfg.get("allowed_logins") or []) | set(logins))
cfg.setdefault("session_ttl_seconds", 7 * 24 * 60 * 60)
tmp = path + ".tmp"
with open(tmp, "w") as f:
    json.dump(cfg, f, indent=2)
os.chmod(tmp, 0o600)
os.replace(tmp, path)
print("    allowed:", ", ".join(cfg["allowed_logins"]))
PY
  )
fi

say "Enabling the plugin and setting dashboard.public_url"
run "$HERMES" plugins enable tailscale-auth >/dev/null
run "$HERMES" config set dashboard.public_url "$PUBLIC_URL" >/dev/null

# ---- LaunchAgents ---------------------------------------------------------------

write_agent() { # label, program args...
  local label="$1"; shift
  local plist="$AGENTS/$label.plist" args="" a
  for a in "$@"; do args="$args    <string>$a</string>
"; done
  say "LaunchAgent $label"
  [ "$DRY_RUN" = 1 ] && { printf '    [dry-run] write %s\n' "$plist"; return; }
  cat > "$plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>$label</string>
  <key>ProgramArguments</key>
  <array>
$args  </array>
  <key>EnvironmentVariables</key>
  <dict>
    <key>TAILSCALE</key>
    <string>$TAILSCALE</string>
    <key>HERMES_TS_AUTH_PORT</key>
    <string>$AUTH_PORT</string>
  </dict>
  <key>RunAtLoad</key>
  <true/>
  <key>KeepAlive</key>
  <true/>
  <key>ThrottleInterval</key>
  <integer>30</integer>
  <key>StandardOutPath</key>
  <string>$LOG_DIR/$label.log</string>
  <key>StandardErrorPath</key>
  <string>$LOG_DIR/$label.log</string>
</dict>
</plist>
PLIST
  plutil -lint -s "$plist"
}

reload_agent() { # label
  local domain
  domain="gui/$(id -u)"
  run launchctl bootout "$domain/$1" 2>/dev/null || true
  run launchctl bootstrap "$domain" "$AGENTS/$1.plist"
}

for legacy in $LEGACY_LABELS; do
  if [ -f "$AGENTS/$legacy.plist" ]; then
    say "Retiring the earlier agent $legacy"
    run launchctl bootout "gui/$(id -u)/$legacy" 2>/dev/null || true
    run rm -f "$AGENTS/$legacy.plist"
  fi
done

write_agent "$LABEL_PREFIX.ts-auth" "$PYTHON" -I "$APP_DIR/bin/hermes-ts-auth"
write_agent "$LABEL_PREFIX.serve-sync" /bin/zsh "$APP_DIR/bin/hermes-tailscale-sync"
reload_agent "$LABEL_PREFIX.ts-auth"
reload_agent "$LABEL_PREFIX.serve-sync"

say "Publishing /ts-auth on the tailnet"
run "$TAILSCALE" serve --bg --set-path /ts-auth "http://127.0.0.1:$AUTH_PORT" >/dev/null

# ---- done ------------------------------------------------------------------------------

echo
say "Done."
if [ "$PLUGIN_WAS_ENABLED" = 0 ]; then
  echo "    Restart Hermes Desktop once so the gateway loads the tailscale-auth plugin."
fi
echo "    On the phone (signed in to the same tailnet as $SELF_LOGIN):"
echo "      1. Install the app: $APK_URL"
echo "      2. Enter the server: $PUBLIC_URL"
echo "         or open: hermesmobile://connect?url=$PUBLIC_URL"
echo "    Logs: $LOG_DIR"
