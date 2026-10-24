"""TailscaleAuthProvider — sign in with the Tailscale identity of the calling device.

The OAuth leg runs against ``/ts-auth`` (``hermes-ts-auth``, a loopback server published by
``tailscale serve``). Tailscale overwrites ``Tailscale-User-Login`` on every proxied request, so
that endpoint signs a short-lived code for an allowed login; this provider checks the code's
signature, expiry, redirect URI and PKCE challenge, then mints stateless HMAC sessions the same
way the bundled basic provider does. Config and shared secret: ``~/.hermes/tailscale-auth.json``.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import logging
import time
from pathlib import Path
from typing import Optional

from hermes_cli.dashboard_auth import (
    DashboardAuthProvider, InvalidCodeError, LoginStart, RefreshExpiredError, Session)
from plugins.dashboard_auth._shared import pkce_login_start, validate_redirect_uri

logger = logging.getLogger(__name__)

_CONFIG_PATH = Path.home() / ".hermes" / "tailscale-auth.json"
_REFRESH_TTL_SECONDS = 90 * 24 * 60 * 60
_SIG_LEN = hashlib.sha256().digest_size


def _sign(payload: dict, secret: bytes) -> str:
    raw = json.dumps(payload, separators=(",", ":")).encode()
    sig = hmac.new(secret, raw, hashlib.sha256).digest()
    return base64.urlsafe_b64encode(raw + sig).decode()


def _unsign(token: str, secret: bytes, kind: str) -> Optional[dict]:
    try:
        blob = base64.urlsafe_b64decode(token.encode())
        if len(blob) <= _SIG_LEN:
            return None
        raw, sig = blob[:-_SIG_LEN], blob[-_SIG_LEN:]
        if not hmac.compare_digest(sig, hmac.new(secret, raw, hashlib.sha256).digest()):
            return None
        payload = json.loads(raw)
    except Exception:
        return None
    if payload.get("kind") != kind or payload.get("exp", 0) <= int(time.time()):
        return None
    return payload


class TailscaleAuthProvider(DashboardAuthProvider):
    """Tailscale-identity provider with stateless HMAC-signed sessions."""

    name = "tailscale"
    display_name = "Tailscale"

    def __init__(self, *, secret: bytes, allowed_logins: frozenset[str], public_url: str,
                 ttl_seconds: int) -> None:
        if len(secret) < 16:
            raise ValueError("secret must be at least 16 bytes")
        if not allowed_logins:
            raise ValueError("allowed_logins must be non-empty")
        self._secret = secret
        self._allowed = allowed_logins
        self._authorize_url = public_url.rstrip("/") + "/ts-auth/authorize"
        self._ttl = max(60, int(ttl_seconds))

    # ---- OAuth leg -------------------------------------------------------

    def start_login(self, *, redirect_uri: str) -> LoginStart:
        validate_redirect_uri(redirect_uri)
        return pkce_login_start(self._authorize_url, client_id="hermes", scope="login",
                                redirect_uri=redirect_uri)

    def complete_login(self, *, code: str, state: str, code_verifier: str, redirect_uri: str) -> Session:
        payload = _unsign(code, self._secret, "code")
        if payload is None:
            raise InvalidCodeError("code invalid or expired")
        if payload.get("redirect_uri") != redirect_uri:
            raise InvalidCodeError("redirect_uri mismatch")
        challenge = base64.urlsafe_b64encode(
            hashlib.sha256(code_verifier.encode("ascii")).digest()).decode().rstrip("=")
        if not code_verifier or not hmac.compare_digest(challenge, str(payload.get("challenge", ""))):
            raise InvalidCodeError("PKCE verification failed")
        login = str(payload.get("login", ""))
        if login not in self._allowed:
            raise InvalidCodeError("login not allowed")
        return self._mint_session(login, str(payload.get("name") or login))

    # ---- session lifecycle -----------------------------------------------

    def verify_session(self, *, access_token: str) -> Optional[Session]:
        payload = _unsign(access_token, self._secret, "access")
        if payload is None or payload.get("sub") not in self._allowed:
            return None
        return self._session(payload["sub"], str(payload.get("name", "")), int(payload["exp"]),
                             access_token, "")

    def refresh_session(self, *, refresh_token: str) -> Session:
        payload = _unsign(refresh_token, self._secret, "refresh") if refresh_token else None
        if payload is None or payload.get("sub") not in self._allowed:
            raise RefreshExpiredError("refresh token expired or invalid")
        return self._mint_session(payload["sub"], str(payload.get("name", "")))

    def revoke_session(self, *, refresh_token: str) -> None:
        # Stateless tokens: removing the login from allowed_logins (or rotating the secret) revokes.
        return None

    # ---- internals -------------------------------------------------------

    def _mint_session(self, login: str, name: str) -> Session:
        now = int(time.time())
        exp = now + self._ttl
        return self._session(
            login, name, exp,
            _sign({"sub": login, "name": name, "kind": "access", "exp": exp}, self._secret),
            _sign({"sub": login, "name": name, "kind": "refresh", "exp": now + _REFRESH_TTL_SECONDS},
                  self._secret))

    def _session(self, login: str, name: str, exp: int, access_token: str, refresh_token: str) -> Session:
        return Session(
            user_id=login, email=login, display_name=name or login, org_id="", provider=self.name,
            expires_at=exp, access_token=access_token, refresh_token=refresh_token)


def register(ctx) -> None:
    try:
        cfg = json.loads(_CONFIG_PATH.read_text())
        provider = TailscaleAuthProvider(
            secret=cfg["secret"].encode(),
            allowed_logins=frozenset(cfg.get("allowed_logins") or ()),
            public_url=cfg["public_url"],
            ttl_seconds=int(cfg.get("session_ttl_seconds") or 7 * 24 * 60 * 60))
    except FileNotFoundError:
        logger.info("tailscale-auth: %s not found; provider not registered", _CONFIG_PATH)
        return
    except Exception as exc:
        logger.warning("tailscale-auth: invalid config (%s); provider not registered", exc)
        return
    ctx.register_dashboard_auth_provider(provider)
    logger.info("tailscale-auth: registered provider for %d login(s)", len(provider._allowed))


__all__ = ["TailscaleAuthProvider", "register"]
