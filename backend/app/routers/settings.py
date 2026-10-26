from __future__ import annotations

import json
from typing import Any

from fastapi import APIRouter, Body, Depends
from pydantic import BaseModel
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.db import get_db
from app.core.deps import Auth, get_auth
from app.core.errors import ApiError
from app.core.redis import get_redis
from app.services.audit import audit
from app.services.crud import serialize
from app.services.llm.client import list_models
from app.services.settings import DEFAULTS, get_all_settings, set_setting

router = APIRouter(prefix="/api/v1", tags=["profile"])

SECURITY_KEYS = {"privacy", "tool_permissions"}


class ProfileIn(BaseModel):
    name: str | None = None
    timezone: str | None = None
    locale: str | None = None
    profile: dict[str, Any] | None = None


@router.get("/profile")
async def get_profile(auth: Auth = Depends(get_auth)):
    return serialize(auth.user)


@router.put("/profile")
async def put_profile(body: ProfileIn, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    user = auth.user
    for k, v in body.model_dump(exclude_none=True).items():
        setattr(user, k, {**(user.profile or {}), **v} if k == "profile" else v)
    return serialize(user)


@router.get("/settings")
async def get_settings_all(auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    data = await get_all_settings(db, auth.owner_id)
    fin = data.get("finance", {})
    if fin.get("proverkacheka_token"):
        fin["proverkacheka_token"] = "***"
    return data


@router.patch("/settings/{key}")
async def patch_setting(key: str, value: Any = Body(...), auth: Auth = Depends(get_auth),
                        db: AsyncSession = Depends(get_db)):
    if key not in DEFAULTS and key not in ("profile_summary",):
        raise ApiError(f"Неизвестная настройка {key}", code="unknown_setting")
    if key == "finance" and isinstance(value, dict) and value.get("proverkacheka_token") == "***":
        value.pop("proverkacheka_token")
    result = await set_setting(db, auth.owner_id, key, value)
    if key in SECURITY_KEYS:
        await audit(db, auth.owner_id, auth.actor, "change_security_setting", "app_settings", key)
    return {key: result}


@router.get("/settings/models")
async def models(auth: Auth = Depends(get_auth)):
    redis = get_redis()
    cached = await redis.get("anthropic_models")
    if cached:
        return {"items": json.loads(cached)}
    items = await list_models()
    await redis.set("anthropic_models", json.dumps(items), ex=6 * 3600)
    return {"items": items}
