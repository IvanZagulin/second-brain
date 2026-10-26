"""Device authentication. The phone pairs once with a one-time code and then sends a long random
bearer token; the server stores only an HMAC of it."""

from __future__ import annotations

import hashlib
import hmac
import secrets

from app.core.config import get_settings

_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"


def new_device_token() -> str:
    return "sbd_" + secrets.token_urlsafe(40)


def hash_secret(value: str) -> str:
    key = get_settings().secret_key.encode()
    return hmac.new(key, value.strip().encode(), hashlib.sha256).hexdigest()


def new_pairing_code() -> str:
    raw = "".join(secrets.choice(_CODE_ALPHABET) for _ in range(8))
    return f"{raw[:4]}-{raw[4:]}"


def normalize_pairing_code(code: str) -> str:
    raw = "".join(ch for ch in code.upper() if ch.isalnum())
    return f"{raw[:4]}-{raw[4:]}" if len(raw) == 8 else raw
