from __future__ import annotations

import asyncio
import hashlib
import logging
import uuid
from datetime import datetime
from pathlib import Path

import orjson
from fastapi import APIRouter, Depends, File, Form, Query, UploadFile
from fastapi.responses import FileResponse, StreamingResponse
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from agent.chat import public_message, run_turn
from app.core.config import get_settings
from app.core.db import get_db
from app.core.deps import Auth, get_auth
from app.core.errors import ApiError, NotFound
from app.core.timeutil import utcnow
from app.models import Attachment, Conversation, Message
from app.services.crud import serialize

log = logging.getLogger(__name__)
router = APIRouter(prefix="/api/v1/chat", tags=["chat"])
_running: set[asyncio.Task] = set()

MODES = {"auto", "normal", "coach", "psych", "finance", "trainer", "critic", "companion"}


class ConversationIn(BaseModel):
    id: uuid.UUID | None = None
    title: str | None = None
    mode: str = "auto"


class ConversationPatch(BaseModel):
    title: str | None = None
    mode: str | None = None
    archived: bool | None = None
    pinned: bool | None = None


@router.get("/conversations")
async def conversations(include_archived: bool = False, auth: Auth = Depends(get_auth),
                        db: AsyncSession = Depends(get_db)):
    q = select(Conversation).where(Conversation.owner_id == auth.owner_id, Conversation.deleted_at.is_(None))
    if not include_archived:
        q = q.where(Conversation.archived.is_(False))
    rows = (await db.execute(q.order_by(Conversation.pinned.desc(),
                                        Conversation.last_message_at.desc().nulls_last()))).scalars().all()
    return {"items": [serialize(c) for c in rows]}


@router.post("/conversations", status_code=201)
async def create_conversation(body: ConversationIn, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    if body.id:
        existing = await db.get(Conversation, body.id)
        if existing is not None and existing.owner_id == auth.owner_id:
            return serialize(existing)
    c = Conversation(id=body.id or uuid.uuid4(), owner_id=auth.owner_id, title=body.title or "Новый диалог",
                     mode=body.mode if body.mode in MODES else "auto", last_message_at=utcnow())
    db.add(c)
    await db.flush()
    await db.refresh(c)
    return serialize(c)


async def _conv(db: AsyncSession, auth: Auth, conv_id: uuid.UUID) -> Conversation:
    c = await db.get(Conversation, conv_id)
    if c is None or c.owner_id != auth.owner_id or c.deleted_at is not None:
        raise NotFound("Диалог не найден")
    return c


async def _conv_or_create(db: AsyncSession, auth: Auth, conv_id: uuid.UUID, mode: str | None) -> Conversation:
    """The phone opens a new conversation before the server knows it: the first message creates it."""
    c = await db.get(Conversation, conv_id)
    if c is None:
        c = Conversation(id=conv_id, owner_id=auth.owner_id, title="Новый диалог",
                         mode=mode if mode in MODES else "auto", last_message_at=utcnow())
        db.add(c)
        await db.flush()
        return c
    return await _conv(db, auth, conv_id)


@router.patch("/conversations/{conv_id}")
async def patch_conversation(conv_id: uuid.UUID, body: ConversationPatch, auth: Auth = Depends(get_auth),
                             db: AsyncSession = Depends(get_db)):
    c = await _conv(db, auth, conv_id)
    data = body.model_dump(exclude_none=True)
    if "mode" in data and data["mode"] not in MODES:
        raise ApiError("Неизвестный режим")
    for k, v in data.items():
        setattr(c, k, v)
    c.version += 1
    return serialize(c)


@router.delete("/conversations/{conv_id}")
async def delete_conversation(conv_id: uuid.UUID, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    c = await _conv(db, auth, conv_id)
    c.deleted_at = utcnow()
    c.version += 1
    msgs = (await db.execute(select(Message).where(Message.conversation_id == conv_id))).scalars().all()
    for m in msgs:
        m.deleted_at = utcnow()
        m.version += 1
    return {"ok": True}


@router.get("/conversations/{conv_id}/messages")
async def messages(conv_id: uuid.UUID, before: datetime | None = None, limit: int = Query(50, le=200),
                   auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    await _conv(db, auth, conv_id)
    q = select(Message).where(Message.conversation_id == conv_id, Message.deleted_at.is_(None),
                              Message.role.in_(("user", "assistant", "trigger")))
    if before:
        q = q.where(Message.created_at < before)
    rows = (await db.execute(q.order_by(Message.created_at.desc()).limit(limit))).scalars().all()
    return {"items": [public_message(m) for m in reversed(rows) if m.role != "assistant" or m.text]}


class SendIn(BaseModel):
    text: str = Field("", max_length=30000)
    attachment_ids: list[uuid.UUID] = Field(default_factory=list)
    client_message_id: str | None = Field(None, max_length=64)
    mode: str | None = None
    source: str = "app"


@router.post("/conversations/{conv_id}/messages")
async def send(conv_id: uuid.UUID, body: SendIn, stream: bool = True, auth: Auth = Depends(get_auth),
               db: AsyncSession = Depends(get_db)):
    """Streams Server-Sent Events: start, delta, tool, done, error. The turn runs in a background task,
    so a dropped connection never loses the answer (it arrives with the next sync)."""
    await _conv_or_create(db, auth, conv_id, body.mode)
    if not body.text.strip() and not body.attachment_ids:
        raise ApiError("Пустое сообщение")
    if body.mode and body.mode not in MODES:
        raise ApiError("Неизвестный режим")
    await db.commit()
    owner_id = auth.owner_id
    queue: asyncio.Queue = asyncio.Queue()

    async def produce() -> None:
        try:
            async for event in run_turn(owner_id, conv_id, body.text, attachment_ids=body.attachment_ids,
                                        client_message_id=body.client_message_id, source=body.source,
                                        mode=body.mode):
                await queue.put(event)
        except ApiError as exc:
            await queue.put({"type": "error", "code": exc.code, "message": exc.message})
        except Exception:
            log.exception("chat turn failed")
            await queue.put({"type": "error", "code": "internal", "message": "Внутренняя ошибка сервера"})
        finally:
            await queue.put(None)

    task = asyncio.create_task(produce())
    _running.add(task)
    task.add_done_callback(_running.discard)

    if not stream:
        events = []
        while (ev := await queue.get()) is not None:
            if ev["type"] != "delta":
                events.append(ev)
        done = next((e for e in events if e["type"] == "done"), None)
        error = next((e for e in events if e["type"] == "error"), None)
        return {"message": done["message"] if done else None, "error": error,
                "tool_events": done.get("tool_events", []) if done else []}

    async def sse():
        while True:
            try:
                ev = await asyncio.wait_for(queue.get(), timeout=15)
            except asyncio.TimeoutError:
                yield b": ping\n\n"
                continue
            if ev is None:
                break
            yield b"data: " + orjson.dumps(ev) + b"\n\n"

    return StreamingResponse(sse(), media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


ALLOWED_MIME_PREFIX = ("image/", "application/pdf", "text/", "audio/")


@router.post("/attachments", status_code=201)
async def upload(file: UploadFile = File(...), purpose: str = Form(""), auth: Auth = Depends(get_auth),
                 db: AsyncSession = Depends(get_db)):
    """``purpose=progress``: a body/form photo — governed by its own privacy switch, not the general one."""
    mime = file.content_type or "application/octet-stream"
    if not mime.startswith(ALLOWED_MIME_PREFIX):
        raise ApiError("Этот тип файла не поддерживается", code="unsupported_type")
    now = utcnow()
    folder = get_settings().uploads_dir / f"{now:%Y/%m}"
    folder.mkdir(parents=True, exist_ok=True)
    att_id = uuid.uuid4()
    suffix = Path(file.filename or "").suffix[:10]
    path = folder / f"{att_id}{suffix}"
    digest, size = hashlib.sha256(), 0
    with path.open("wb") as out:
        while chunk := await file.read(1 << 20):
            size += len(chunk)
            if size > 30 * 1024 * 1024:
                out.close()
                path.unlink(missing_ok=True)
                raise ApiError("Файл больше 30 МБ", code="too_large")
            digest.update(chunk)
            out.write(chunk)
    kind = "image" if mime.startswith("image/") else "pdf" if mime == "application/pdf" else \
        "audio" if mime.startswith("audio/") else "document"
    if kind == "image" and purpose == "progress":
        kind = "progress_photo"
    att = Attachment(id=att_id, owner_id=auth.owner_id, kind=kind, mime=mime, file_name=file.filename, path=str(path),
                     size=size, sha256=digest.hexdigest())
    db.add(att)
    await db.flush()
    return serialize(att, {"path"})


@router.get("/attachments/{att_id}")
async def download(att_id: uuid.UUID, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    att = await db.get(Attachment, att_id)
    if att is None or att.owner_id != auth.owner_id:
        raise NotFound("Файл не найден")
    return FileResponse(att.path, media_type=att.mime, filename=att.file_name)
