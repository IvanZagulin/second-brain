"""Client diagnostics: the phone uploads its recent log buffer and crash reports, so problems can be
investigated without a USB cable. Logs are stored as files on the server, never sent anywhere."""

from __future__ import annotations

import uuid

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field

from app.core.config import get_settings
from app.core.deps import Auth, get_auth
from app.core.logging import redact
from app.core.timeutil import utcnow

router = APIRouter(prefix="/api/v1/diagnostics", tags=["diagnostics"])


class ClientLogIn(BaseModel):
    app_version: str | None = None
    device: str | None = None
    android: str | None = None
    kind: str = "log"  # log | crash
    text: str = Field(max_length=2_000_000)


@router.post("/client-log")
async def client_log(body: ClientLogIn, auth: Auth = Depends(get_auth)):
    folder = get_settings().data_dir / "client-logs"
    folder.mkdir(parents=True, exist_ok=True)
    name = f"{utcnow():%Y%m%d-%H%M%S}-{body.kind}-{uuid.uuid4().hex[:6]}.txt"
    header = f"device={body.device} android={body.android} app={body.app_version} device_id={auth.device.id}\n\n"
    (folder / name).write_text(header + redact(body.text), encoding="utf-8")
    return {"ok": True, "file": name}
