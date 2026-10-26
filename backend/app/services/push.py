"""Firebase Cloud Messaging (HTTP v1). Data-only, high-priority messages: the app builds the
notification itself so it can add reply/snooze/confirm actions and hide sensitive text."""

from __future__ import annotations

import asyncio
import logging
import time

import httpx
from google.auth.transport.requests import Request as GoogleRequest
from google.oauth2 import service_account

from app.core.config import get_settings

log = logging.getLogger(__name__)

SCOPE = "https://www.googleapis.com/auth/firebase.messaging"


class FcmSender:
    def __init__(self) -> None:
        self._creds = None
        self._project_id: str | None = None
        self._expires = 0.0

    @property
    def available(self) -> bool:
        return get_settings().fcm_service_account_path().exists()

    def _load(self) -> None:
        path = get_settings().fcm_service_account_path()
        self._creds = service_account.Credentials.from_service_account_file(str(path), scopes=[SCOPE])
        self._project_id = self._creds.project_id

    async def _token(self) -> str:
        if self._creds is None:
            self._load()
        if not self._creds.valid or time.time() > self._expires - 120:
            await asyncio.to_thread(self._creds.refresh, GoogleRequest())
            self._expires = self._creds.expiry.timestamp() if self._creds.expiry else time.time() + 3000
        return self._creds.token

    async def send(self, device_token: str, data: dict[str, str], high_priority: bool = True) -> str:
        """Returns "sent", "unregistered" (token is dead) or "failed"."""
        if not self.available:
            return "failed"
        s = get_settings()
        token = await self._token()
        body = {
            "message": {
                "token": device_token,
                "data": {k: ("" if v is None else str(v)) for k, v in data.items()},
                "android": {"priority": "HIGH" if high_priority else "NORMAL", "ttl": "86400s"},
            }
        }
        proxy = s.anthropic_proxy if s.fcm_use_proxy else None
        async with httpx.AsyncClient(timeout=20, proxy=proxy) as client:
            resp = await client.post(
                f"https://fcm.googleapis.com/v1/projects/{self._project_id}/messages:send",
                headers={"Authorization": f"Bearer {token}"}, json=body)
        if resp.status_code == 200:
            return "sent"
        if resp.status_code in (400, 404) and ("UNREGISTERED" in resp.text or "not-registered" in resp.text):
            return "unregistered"
        log.warning("fcm send failed: %s", resp.status_code)
        return "failed"


fcm = FcmSender()
