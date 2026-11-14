#!/bin/bash
# Hermes Mobile — Linux side.
#
# Lets the Android app reach this machine's Hermes over Tailscale and sign in with the phone's
# Tailscale identity, no password. The same pieces as the Mac side, run by systemd:
#   * the `tailscale-auth` Hermes plugin (dashboard auth provider),
#   * hermes-ts-auth: a loopback endpoint at <url>/ts-auth that turns Tailscale's identity header
#     into a short-lived sign-in code,
#   * hermes-tailscale-sync: keeps `tailscale serve` pointed at the Hermes gateway, whose port
#     changes on every restart,
#   * two systemd user services that start both at boot and restart them if they stop.
#
# Safe to run again: the shared secret is kept, files and services are replaced.
#
#   ./install.sh [--allow you@example.com ...] [--dry-run]
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
# Released tarballs carry the shared files next to this script; in the repository they live under mac/.
SHARED="$HERE"; [ -d "$SHARED/plugin" ] || SHARED="$HERE/../mac"
HERMES_HOME="${HERMES_HOME:-$HOME/.hermes}"
PYTHON="$HERMES_HOME/hermes-agent/venv/bin/python"
HERMES="$HERMES_HOME/hermes-agent/venv/bin/hermes"
TAILSCALE="${TAILSCALE:-$(command -v tailscale || true)}"
APP_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/hermes-mobile"
UNITS="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
UNIT_PREFIX="hermes-mobile"
AUTH_PORT=18766
AUTH_CONFIG="$HERMES_HOME/tailscale-auth.json"
APK_URL="https://github.com/k02miu/hermes-client/releases/latest"

DRY_RUN=0
EXTRA_LOGINS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --allow) EXTRA_LOGINS+=("$2"); shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

say() { printf '\033[1m==>\033[0m %s\n' "$*"; }
die() { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }
run() {
  if [ "$DRY_RUN" = 1 ]; then printf '    [dry-run] %s\n' "$*" >&2; else "$@"; fi
}

# ---- preconditions ----------------------------------------------------------

[ "$(uname -s)" = Linux ] || die "Linux only (on a Mac use mac/install.sh)"
[ -x "$PYTHON" ] && [ -x "$HERMES" ] || die "Hermes Agent not found under $HERMES_HOME"
[ -n "$TAILSCALE" ] && [ -x "$TAILSCALE" ] || die "tailscale not found (set TAILSCALE=/path/to/tailscale)"
command -v systemctl >/dev/null && systemctl --user show-environment >/dev/null 2>&1 \
  || die "systemd user services are not available in this session"
[ -f "$SHARED/plugin/tailscale-auth/__init__.py" ] && [ -f "$SHARED/bin/hermes-ts-auth" ] \
  || die "plugin files not found next to this script"

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
[ "$DNS_NAME" != - ] || die "this machine has no Tailscale DNS name; turn on MagicDNS in the Tailscale admin console"
[ "$SELF_LOGIN" != - ] || die "could not read the signed-in Tailscale account"
PUBLIC_URL="https://$DNS_NAME"

# `tailscale serve` is refused for anyone but root unless this user is the operator. Checked before
# anything is changed, so a refusal does not leave a half-installed setup behind.
OPERATOR="$("$TAILSCALE" debug prefs 2>/dev/null | "$PYTHON" -I -c '
import json, sys
try:
    print(json.load(sys.stdin).get("OperatorUser") or "")
except Exception:
    print("")
')"
if [ "$(id -u)" != 0 ] && [ "$OPERATOR" != "$(id -un)" ]; then
  die "this user may not change \`tailscale serve\`. Run once:  sudo tailscale set --operator=$(id -un)"
fi

say "Hermes:    $HERMES_HOME"
say "Tailscale: $PUBLIC_URL (allowing $SELF_LOGIN${EXTRA_LOGINS[*]:+ ${EXTRA_LOGINS[*]}})"
[ "$DRY_RUN" = 1 ] && say "dry run: nothing will be changed"

# ---- files --------------------------------------------------------------------

say "Installing helpers to $APP_DIR"
run mkdir -p "$APP_DIR/bin" "$UNITS"
run install -m 0644 "$HERE/bin/hermes-tailscale-sync" "$APP_DIR/bin/hermes-tailscale-sync"
run install -m 0644 "$SHARED/bin/hermes-ts-auth" "$APP_DIR/bin/hermes-ts-auth"

PLUGIN_DIR="$HERMES_HOME/plugins/tailscale-auth"
PLUGIN_WAS_ENABLED=0
"$HERMES" config get plugins.enabled 2>/dev/null | grep -q 'tailscale-auth' && PLUGIN_WAS_ENABLED=1
say "Installing the tailscale-auth plugin to $PLUGIN_DIR"
run mkdir -p "$PLUGIN_DIR"
run install -m 0644 "$SHARED/plugin/tailscale-auth/__init__.py" "$SHARED/plugin/tailscale-auth/plugin.yaml" "$PLUGIN_DIR/"

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

# ---- systemd user services ------------------------------------------------------

write_unit() { # name, description, script
  local unit="$UNITS/$UNIT_PREFIX-$1.service"
  say "systemd user service $UNIT_PREFIX-$1"
  [ "$DRY_RUN" = 1 ] && { printf '    [dry-run] write %s\n' "$unit"; return; }
  cat > "$unit" <<UNIT
[Unit]
Description=Hermes Mobile: $2
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
Environment=HERMES_HOME=$HERMES_HOME
Environment=TAILSCALE=$TAILSCALE
Environment=HERMES_TS_AUTH_PORT=$AUTH_PORT
ExecStart=$PYTHON -I $APP_DIR/bin/$3
Restart=always
RestartSec=30

[Install]
WantedBy=default.target
UNIT
}

write_unit ts-auth "Tailscale sign-in endpoint" hermes-ts-auth
write_unit serve-sync "keep tailscale serve pointed at Hermes" hermes-tailscale-sync
run systemctl --user daemon-reload
run systemctl --user enable "$UNIT_PREFIX-ts-auth.service" "$UNIT_PREFIX-serve-sync.service"
run systemctl --user restart "$UNIT_PREFIX-ts-auth.service" "$UNIT_PREFIX-serve-sync.service"

say "Publishing /ts-auth on the tailnet"
run "$TAILSCALE" serve --bg --set-path /ts-auth "http://127.0.0.1:$AUTH_PORT" >/dev/null

# ---- done ------------------------------------------------------------------------------

echo
say "Done."
if [ "$(loginctl show-user "$(id -un)" -p Linger --value 2>/dev/null)" != yes ]; then
  echo "    These services stop when you log out. To keep them running:  sudo loginctl enable-linger $(id -un)"
fi
if [ "$PLUGIN_WAS_ENABLED" = 0 ]; then
  echo "    Restart the Hermes gateway once so it loads the tailscale-auth plugin:"
  echo "      systemctl --user restart hermes-gateway"
fi
echo "    On the phone (signed in to the same tailnet as $SELF_LOGIN):"
echo "      1. Install the app: $APK_URL"
echo "      2. Add the server: $PUBLIC_URL"
echo "         or open: hermesmobile://connect?url=$PUBLIC_URL"
echo "    Logs: journalctl --user -u '$UNIT_PREFIX-*'"
