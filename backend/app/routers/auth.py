from __future__ import annotations

import uuid
from datetime import timedelta

from fastapi import APIRouter, Depends, Request
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import APP_VERSION, get_settings
from app.core.db import get_db
from app.core.deps import Auth, get_auth
from app.core.errors import ApiError, NotFound, Unauthorized
from app.core.redis import get_redis
from app.core.security import hash_secret, new_device_token, new_pairing_code, normalize_pairing_code
from app.core.timeutil import utcnow
from app.models import Device, PairingCode, User
from app.services.audit import audit
from app.services.crud import serialize

router = APIRouter(prefix="/api/v1/auth", tags=["auth"])


class PairIn(BaseModel):
    code: str = Field(min_length=6, max_length=20)
    device_name: str = Field("Android", max_length=100)
    model: str | None = None
    app_version: str | None = None


@router.post("/pair")
async def pair(body: PairIn, request: Request, db: AsyncSession = Depends(get_db)):
    """Exchange a one-time pairing code (from ``app.cli pair``) for a long-lived device token."""
    ip = request.client.host if request.client else "?"
    redis = get_redis()
    try:
        attempts = await redis.incr(f"pair_attempts:{ip}")
        await redis.expire(f"pair_attempts:{ip}", 900)
    except Exception:
        attempts = 0
    if attempts > 10:
        raise ApiError("Слишком много попыток, подождите 15 минут", code="rate_limited", status_code=429)
    code = (await db.execute(select(PairingCode).where(
        PairingCode.code_hash == hash_secret(normalize_pairing_code(body.code)),
        PairingCode.used_at.is_(None), PairingCode.expires_at > utcnow()))).scalar_one_or_none()
    if code is None:
        raise Unauthorized("Код недействителен или истёк", code="bad_pairing_code")
    token = new_device_token()
    device = Device(owner_id=code.owner_id, name=body.device_name, model=body.model, app_version=body.app_version,
                    token_hash=hash_secret(token), last_seen_at=utcnow())
    db.add(device)
    code.used_at = utcnow()
    await db.flush()
    await audit(db, code.owner_id, f"device:{device.id}", "pair", "device", device.id, {"model": body.model}, ip)
    user = await db.get(User, code.owner_id)
    return {"device_id": str(device.id), "access_token": token, "user": serialize(user),
            "server": {"version": APP_VERSION, "firebase": get_settings().firebase_client_options()}}


@router.get("/me")
async def me(auth: Auth = Depends(get_auth)):
    return {"user": serialize(auth.user), "device": serialize(auth.device, {"token_hash"})}


class PushTokenIn(BaseModel):
    fcm_token: str | None


@router.post("/push-token")
async def push_token(body: PushTokenIn, auth: Auth = Depends(get_auth)):
    auth.device.fcm_token = body.fcm_token
    return {"ok": True}


@router.get("/devices")
async def devices(auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    rows = (await db.execute(select(Device).where(Device.owner_id == auth.owner_id))).scalars().all()
    return {"items": [serialize(d, {"token_hash", "fcm_token"}) | {"current": d.id == auth.device.id} for d in rows]}


@router.post("/devices/{device_id}/revoke")
async def revoke(device_id: uuid.UUID, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    d = await db.get(Device, device_id)
    if d is None or d.owner_id != auth.owner_id:
        raise NotFound("Устройство не найдено")
    d.revoked_at = utcnow()
    await audit(db, auth.owner_id, auth.actor, "revoke_device", "device", device_id)
    return {"ok": True}


@router.post("/pairing-code")
async def pairing_code(auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    """A paired device can issue a code to add or replace a phone (valid 30 minutes)."""
    code = new_pairing_code()
    db.add(PairingCode(owner_id=auth.owner_id, code_hash=hash_secret(code),
                       expires_at=utcnow() + timedelta(minutes=30), note=f"from {auth.device.id}"))
    await audit(db, auth.owner_id, auth.actor, "issue_pairing_code")
    return {"code": code, "expires_in_minutes": 30}
