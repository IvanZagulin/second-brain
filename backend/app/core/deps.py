from __future__ import annotations

import uuid
from dataclasses import dataclass
from datetime import timedelta

from fastapi import Depends, Request
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import get_settings
from app.core.db import get_db
from app.core.errors import ApiError, Unauthorized
from app.core.redis import get_redis
from app.core.security import hash_secret
from app.core.timeutil import utcnow
from app.models import Device, User


@dataclass
class Auth:
    owner_id: uuid.UUID
    device: Device
    user: User

    @property
    def actor(self) -> str:
        return f"device:{self.device.id}"


async def get_auth(request: Request, db: AsyncSession = Depends(get_db)) -> Auth:
    header = request.headers.get("authorization", "")
    if not header.lower().startswith("bearer "):
        raise Unauthorized("Нужна авторизация устройства")
    token = header[7:].strip()
    device = (await db.execute(
        select(Device).where(Device.token_hash == hash_secret(token), Device.revoked_at.is_(None))
    )).scalar_one_or_none()
    if device is None:
        # A distinct code: the app forgets its token only on this, never on a stray 401.
        raise Unauthorized("Устройство не найдено или отозвано", code="device_revoked")
    user = await db.get(User, device.owner_id)
    if user is None:
        raise Unauthorized("Пользователь не найден")

    await _rate_limit(str(device.id))

    now = utcnow()
    if device.last_seen_at is None or now - device.last_seen_at > timedelta(minutes=5):
        device.last_seen_at = now
        app_version = request.headers.get("x-app-version")
        if app_version:
            device.app_version = app_version
    return Auth(owner_id=user.id, device=device, user=user)


async def _rate_limit(key: str) -> None:
    limit = get_settings().rate_limit_per_minute
    try:
        redis = get_redis()
        bucket = f"rl:{key}:{int(utcnow().timestamp() // 60)}"
        count = await redis.incr(bucket)
        if count == 1:
            await redis.expire(bucket, 120)
    except Exception:
        return  # Redis down must not lock the owner out
    if count > limit:
        raise ApiError("Слишком много запросов, подождите минуту", code="rate_limited", status_code=429)
