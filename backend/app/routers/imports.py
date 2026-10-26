from __future__ import annotations

import hashlib
import shutil
import uuid

from fastapi import APIRouter, Depends, File, Form, UploadFile
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import get_settings
from app.core.db import get_db
from app.core.deps import Auth, get_auth
from app.core.errors import ApiError, NotFound
from app.core.timeutil import utcnow
from app.models import AgentJob, ImportChat, ImportJob, MemorySource
from app.services.audit import audit
from app.services.crud import serialize
from app.services.imports.pipeline import mark_self
from app.services.memory import delete_source

router = APIRouter(prefix="/api/v1/imports", tags=["imports"])
KINDS = {"auto", "telegram", "chatgpt", "claude", "gemini"}


@router.post("", status_code=201)
async def upload(file: UploadFile = File(...), kind: str = Form("auto"), auth: Auth = Depends(get_auth),
                 db: AsyncSession = Depends(get_db)):
    if kind not in KINDS:
        raise ApiError("Неизвестный тип импорта")
    job_id = uuid.uuid4()
    folder = get_settings().imports_dir / str(job_id)
    folder.mkdir(parents=True, exist_ok=True)
    name = (file.filename or "export").replace("/", "_").replace("\\", "_")[:120]
    path = folder / name
    digest, size = hashlib.sha256(), 0
    limit = get_settings().max_upload_mb * 1024 * 1024
    with path.open("wb") as out:
        while chunk := await file.read(4 << 20):
            size += len(chunk)
            if size > limit:
                out.close()
                shutil.rmtree(folder, ignore_errors=True)
                raise ApiError("Файл слишком большой", code="too_large")
            digest.update(chunk)
            out.write(chunk)
    job = ImportJob(id=job_id, owner_id=auth.owner_id, kind=kind, file_path=str(path), file_name=name, file_size=size,
                    file_sha256=digest.hexdigest())
    db.add(job)
    db.add(AgentJob(owner_id=auth.owner_id, kind="import_parse", goal=f"Разбор импорта {name}",
                    payload={"import_job_id": str(job_id)}, next_run_at=utcnow(), priority=2, max_attempts=1,
                    dedupe_key=f"import_parse:{job_id}"))
    await db.flush()
    return serialize(job)


@router.get("")
async def list_imports(auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    rows = (await db.execute(select(ImportJob).where(ImportJob.owner_id == auth.owner_id)
                             .order_by(ImportJob.created_at.desc()))).scalars().all()
    return {"items": [serialize(j, {"file_path"}) for j in rows]}


async def _job(db: AsyncSession, auth: Auth, job_id: uuid.UUID) -> ImportJob:
    j = await db.get(ImportJob, job_id)
    if j is None or j.owner_id != auth.owner_id:
        raise NotFound("Импорт не найден")
    return j


@router.get("/{job_id}")
async def get_import(job_id: uuid.UUID, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    j = await _job(db, auth, job_id)
    chats = (await db.execute(select(ImportChat).where(ImportChat.import_job_id == job_id)
                              .order_by(ImportChat.message_count.desc()))).scalars().all()
    return serialize(j, {"file_path"}) | {"chats": [serialize(c) for c in chats]}


class ChatPatch(BaseModel):
    excluded: bool


@router.patch("/{job_id}/chats/{chat_id}")
async def patch_chat(job_id: uuid.UUID, chat_id: uuid.UUID, body: ChatPatch, auth: Auth = Depends(get_auth),
                     db: AsyncSession = Depends(get_db)):
    await _job(db, auth, job_id)
    c = await db.get(ImportChat, chat_id)
    if c is None or c.owner_id != auth.owner_id:
        raise NotFound("Чат не найден")
    c.excluded = body.excluded
    return serialize(c)


class SelfIn(BaseModel):
    self_ids: list[str]


@router.post("/{job_id}/self")
async def set_self(job_id: uuid.UUID, body: SelfIn, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    j = await _job(db, auth, job_id)
    await db.commit()
    count = await mark_self(auth.owner_id, job_id, body.self_ids)
    j.options = {**(j.options or {}), "self_ids": body.self_ids}
    return {"chats": count}


class AnalyzeIn(BaseModel):
    allow_llm: bool = True


@router.post("/{job_id}/analyze")
async def analyze(job_id: uuid.UUID, body: AnalyzeIn, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    """The owner confirms the estimated cost and allows sending fragments to Claude."""
    j = await _job(db, auth, job_id)
    if j.status not in ("parsed", "paused", "done"):
        raise ApiError(f"Импорт в состоянии {j.status}", code="bad_state")
    if not body.allow_llm:
        raise ApiError("Для анализа нужно разрешение отправлять фрагменты в Claude API", code="llm_not_allowed")
    j.status, j.error = "analyzing", None
    db.add(AgentJob(owner_id=auth.owner_id, kind="import_analyze", goal=f"Анализ импорта {j.file_name}",
                    payload={"import_job_id": str(job_id)}, next_run_at=utcnow(), priority=2, max_attempts=3))
    await audit(db, auth.owner_id, auth.actor, "allow_import_llm", "import_jobs", job_id)
    return serialize(j, {"file_path"})


@router.post("/{job_id}/cancel")
async def cancel(job_id: uuid.UUID, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    j = await _job(db, auth, job_id)
    if j.status in ("analyzing", "parsing", "uploaded", "parsed", "paused"):
        j.status = "cancelled"
    return serialize(j, {"file_path"})


@router.delete("/{job_id}")
async def remove(job_id: uuid.UUID, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    """Deletes the uploaded archive and everything derived from it (messages, memories, events)."""
    j = await _job(db, auth, job_id)
    sources = (await db.execute(select(MemorySource.id).join(ImportChat, ImportChat.source_id == MemorySource.id)
                                .where(ImportChat.import_job_id == job_id))).scalars().all()
    totals = {"sources": 0, "derived_deleted": 0}
    for sid in sources:
        r = await delete_source(db, auth.owner_id, sid)
        totals["sources"] += 1
        totals["derived_deleted"] += r["derived_deleted"]
    shutil.rmtree(get_settings().imports_dir / str(job_id), ignore_errors=True)
    await db.delete(j)
    await audit(db, auth.owner_id, auth.actor, "delete_import", "import_jobs", job_id, totals)
    return totals
