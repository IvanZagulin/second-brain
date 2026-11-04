from __future__ import annotations

import json
from datetime import datetime, timedelta

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
    """``ok`` covers the API's own dependencies; ``warnings`` lists the background pieces that are off."""
    status = {"db": False, "redis": False}
    warnings: list[str] = []
    try:
        await db.execute(text("SELECT 1"))
        status["db"] = True
    except Exception:
        pass
    redis = get_redis()
    try:
        status["redis"] = bool(await redis.ping())
    except Exception:
        pass
    extra: dict = {}
    if status["redis"]:
        try:
            beat = await redis.get("scheduler:heartbeat")
            age = (utcnow() - datetime.fromisoformat(beat.decode() if isinstance(beat, bytes) else beat)).total_seconds() \
                if beat else None
            extra["scheduler_heartbeat_s"] = round(age) if age is not None else None
            if age is None or age > 120:
                warnings.append("scheduler")
            # arq refreshes this key while a worker is alive.
            if not await redis.exists("arq:queue:health-check"):
                warnings.append("worker")
        except Exception:
            warnings.append("scheduler")
    if status["db"]:
        try:
            since = utcnow() - timedelta(hours=24)
            extra["jobs_failed_24h"] = int((await db.execute(text(
                "SELECT count(*) FROM agent_jobs WHERE status = 'failed' AND updated_at >= :since"), {"since": since})).scalar_one())
            if extra["jobs_failed_24h"]:
                warnings.append("jobs")
        except Exception:
            pass
    try:
        async with httpx.AsyncClient(timeout=3) as client:
            (await client.get(f"{get_settings().embedder_url}/health")).raise_for_status()
        extra["embedder"] = True
    except Exception:
        extra["embedder"] = False
        warnings.append("embedder")
    marker = get_settings().data_dir / "backup-last-ok"
    try:
        last = datetime.fromisoformat(marker.read_text().strip()) if marker.exists() else None
        extra["backup_age_h"] = round((utcnow() - last).total_seconds() / 3600, 1) if last else None
        if last is None or extra["backup_age_h"] > 36:
            warnings.append("backup")
    except Exception:
        warnings.append("backup")
    return {"ok": all(status.values()), "version": APP_VERSION, "time": utcnow().isoformat(), **status,
            **extra, "warnings": warnings}


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
