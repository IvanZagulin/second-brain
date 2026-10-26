from __future__ import annotations

import json

import httpx
from fastapi import APIRouter, Depends
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import APP_VERSION, get_settings
from app.core.db import get_db
from app.core.deps import Auth, get_auth
from app.core.redis import get_redis
from app.core.timeutil import utcnow
from app.services.llm.budget import usage_summary

router = APIRouter(prefix="/api/v1/system", tags=["system"])


@router.get("/health")
async def health(db: AsyncSession = Depends(get_db)):
    status = {"db": False, "redis": False}
    try:
        await db.execute(text("SELECT 1"))
        status["db"] = True
    except Exception:
        pass
    try:
        status["redis"] = bool(await get_redis().ping())
    except Exception:
        pass
    return {"ok": all(status.values()), "version": APP_VERSION, "time": utcnow().isoformat(), **status}


@router.get("/info")
async def info(auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    usage = await usage_summary(db, auth.owner_id)
    return {"version": APP_VERSION, "time": utcnow().isoformat(), "firebase": get_settings().firebase_client_options(),
            "api_budget": {"spent_usd": usage["spent_usd"], "limit_usd": usage["limit_usd"], "blocked": usage["blocked"]},
            "github_repo": get_settings().github_repo}


@router.get("/updates/latest")
async def latest_release(auth: Auth = Depends(get_auth)):
    """Fallback for the phone when GitHub is unreachable directly: the latest release, cached 10 min."""
    redis = get_redis()
    cached = await redis.get("gh_latest_release")
    if cached:
        return json.loads(cached)
    repo = get_settings().github_repo
    async with httpx.AsyncClient(timeout=20) as client:
        resp = await client.get(f"https://api.github.com/repos/{repo}/releases/latest",
                                headers={"Accept": "application/vnd.github+json"})
    if resp.status_code != 200:
        return {"available": False, "status": resp.status_code}
    rel = resp.json()
    data = {"available": True, "tag": rel.get("tag_name"), "name": rel.get("name"), "body": rel.get("body"),
            "published_at": rel.get("published_at"),
            "assets": [{"name": a["name"], "url": a["browser_download_url"], "size": a["size"]}
                       for a in rel.get("assets", [])]}
    await redis.set("gh_latest_release", json.dumps(data), ex=600)
    return data
