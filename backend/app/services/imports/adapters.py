"""Format adapters for personal archives. Each adapter streams ParsedChat objects so multi-year,
multi-gigabyte exports never have to fit in memory. Media is skipped; only text is kept.

Supported:
* Telegram Desktop JSON export — a single chat (result.json with "messages") or a full export
  ("chats.list"), plain or zipped;
* ChatGPT data export (zip with conversations.json, tree "mapping");
* Claude data export (zip/json with "chat_messages");
* Gemini via Google Takeout (My Activity/Gemini Apps/MyActivity.json).

When an export format changes only the matching adapter needs updating."""

from __future__ import annotations

import html
import io
import json
import re
import zipfile
from collections.abc import Iterator
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import IO, Any

import ijson


@dataclass
class ParsedMessage:
    external_id: str
    author: str | None
    author_id: str | None
    sent_at: datetime | None
    text: str
    reply_to: str | None = None
    is_self: bool | None = None


@dataclass
class ParsedChat:
    kind: str  # telegram | chatgpt | claude | gemini
    external_id: str
    title: str
    chat_type: str | None
    messages: Iterator[ParsedMessage]
    meta: dict[str, Any] = field(default_factory=dict)


class UnknownFormat(ValueError):
    pass


def _ts(value: Any) -> datetime | None:
    if value in (None, ""):
        return None
    if isinstance(value, (int, float)):
        return datetime.fromtimestamp(float(value), tz=timezone.utc)
    s = str(value)
    try:
        dt = datetime.fromisoformat(s.replace("Z", "+00:00"))
    except ValueError:
        try:
            return datetime.fromtimestamp(float(s), tz=timezone.utc)
        except ValueError:
            return None
    return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)


def _tg_text(value: Any) -> str:
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        return "".join(p if isinstance(p, str) else str(p.get("text", "")) for p in value)
    return ""


# ------------------------------------------------------------------ detection

def detect(path: Path) -> str:
    name = path.name.lower()
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as zf:
            names = [n.lower() for n in zf.namelist()]
            if any("gemini" in n and n.endswith("myactivity.json") for n in names):
                return "gemini"
            if any(n.endswith("result.json") for n in names):
                return "telegram"
            conv = next((n for n in zf.namelist() if n.lower().endswith("conversations.json")), None)
            if conv:
                with zf.open(conv) as f:
                    head = f.read(200_000).decode("utf-8", errors="ignore")
                return "claude" if "chat_messages" in head else "chatgpt"
        raise UnknownFormat("В архиве не найден известный формат экспорта")
    with path.open("rb") as f:
        head = f.read(200_000).decode("utf-8", errors="ignore")
    if '"chat_messages"' in head:
        return "claude"
    if '"mapping"' in head and '"current_node"' in head:
        return "chatgpt"
    if '"header"' in head and "Gemini" in head:
        return "gemini"
    if '"messages"' in head and ('"from_id"' in head or '"personal_information"' in head or '"chats"' in head
                                 or '"type": "personal_chat"' in head or '"date_unixtime"' in head):
        return "telegram"
    if name.endswith(".json") and '"chats"' in head:
        return "telegram"
    raise UnknownFormat("Не удалось определить формат файла")


def open_member(path: Path, suffix: str) -> IO[bytes]:
    if zipfile.is_zipfile(path):
        zf = zipfile.ZipFile(path)
        member = next((n for n in zf.namelist() if n.lower().endswith(suffix)), None)
        if member is None:
            raise UnknownFormat(f"В архиве нет {suffix}")
        return zf.open(member)
    return path.open("rb")


# ------------------------------------------------------------------ Telegram

def telegram_chats(path: Path) -> tuple[dict[str, Any], Iterator[ParsedChat]]:
    """Returns (export info, chats). Chats are produced lazily from a single streaming pass, so a
    chat's messages must be consumed before asking for the next chat."""
    info: dict[str, Any] = {"self_id": None}

    def generate() -> Iterator[ParsedChat]:
        f = open_member(path, "result.json")
        events = ijson.parse(f, use_float=True)
        chat_prefixes = {"chats.list.item", "left_chats.list.item"}
        state: dict[str, Any] = {}
        pending: list[ParsedMessage] = []

        def flush_chat() -> ParsedChat | None:
            if not state:
                return None
            msgs = list(pending)
            pending.clear()
            chat = ParsedChat("telegram", f"tg:{state.get('id', state.get('name', 'chat'))}",
                              state.get("name") or "Без названия", state.get("type"), iter(msgs),
                              {"telegram_id": state.get("id")})
            state.clear()
            return chat

        builder: ijson.ObjectBuilder | None = None
        msg_prefix = None
        for prefix, event, value in events:
            if builder is not None:
                builder.event(event, value)
                if prefix == msg_prefix and event == "end_map":
                    m = builder.value
                    builder = None
                    if m.get("type") != "message":
                        continue
                    text = _tg_text(m.get("text")).strip()
                    if not text:
                        continue  # media-only messages are skipped in the first pass
                    pending.append(ParsedMessage(
                        external_id=str(m.get("id")), author=m.get("from") or m.get("actor"),
                        author_id=m.get("from_id") or m.get("actor_id"),
                        sent_at=_ts(m.get("date_unixtime") and int(m["date_unixtime"])) or _ts(m.get("date")),
                        text=text, reply_to=str(m["reply_to_message_id"]) if m.get("reply_to_message_id") else None))
                continue
            if prefix == "personal_information.user_id":
                info["self_id"] = f"user{value}"
                continue
            parent, _, key = prefix.rpartition(".")
            if (parent in chat_prefixes or parent == "") and key in ("name", "type", "id") and event in (
                    "string", "number", "null"):
                if parent == "" and key == "id" and not state.get("_root"):
                    state["_root"] = True
                state[key] = value
                continue
            if prefix.endswith("messages.item") and event == "start_map":
                msg_prefix = prefix
                builder = ijson.ObjectBuilder()
                builder.event(event, value)
                continue
            if prefix in chat_prefixes and event == "end_map":
                chat = flush_chat()
                if chat:
                    yield chat
        chat = flush_chat()  # single-chat export: the root object
        if chat and (chat.title or chat.external_id):
            yield chat
        f.close()

    return info, generate()


# ------------------------------------------------------------------ ChatGPT

def chatgpt_chats(path: Path) -> Iterator[ParsedChat]:
    f = open_member(path, "conversations.json")
    for conv in ijson.items(f, "item", use_float=True):
        mapping = conv.get("mapping") or {}
        node_id = conv.get("current_node")
        chain = []
        guard = 0
        while node_id and node_id in mapping and guard < 20000:
            chain.append(mapping[node_id])
            node_id = mapping[node_id].get("parent")
            guard += 1
        msgs = []
        for node in reversed(chain):
            msg = node.get("message") or {}
            role = (msg.get("author") or {}).get("role")
            if role not in ("user", "assistant"):
                continue
            parts = (msg.get("content") or {}).get("parts") or []
            text = "\n".join(p for p in parts if isinstance(p, str)).strip()
            if not text:
                continue
            msgs.append(ParsedMessage(external_id=str(msg.get("id") or node.get("id")),
                                      author="Я" if role == "user" else "ChatGPT", author_id=role,
                                      sent_at=_ts(msg.get("create_time")), text=text, is_self=role == "user"))
        cid = conv.get("conversation_id") or conv.get("id") or str(conv.get("create_time"))
        yield ParsedChat("chatgpt", f"chatgpt:{cid}", conv.get("title") or "ChatGPT", "ai", iter(msgs))
    f.close()


# ------------------------------------------------------------------ Claude

def claude_chats(path: Path) -> Iterator[ParsedChat]:
    f = open_member(path, "conversations.json")
    for conv in ijson.items(f, "item", use_float=True):
        msgs = []
        for m in conv.get("chat_messages") or []:
            sender = m.get("sender")
            text = (m.get("text") or "").strip()
            if not text:
                text = "\n".join(c.get("text", "") for c in m.get("content") or [] if c.get("type") == "text").strip()
            if not text or sender not in ("human", "assistant"):
                continue
            msgs.append(ParsedMessage(external_id=str(m.get("uuid")), author="Я" if sender == "human" else "Claude",
                                      author_id=sender, sent_at=_ts(m.get("created_at")), text=text,
                                      is_self=sender == "human"))
        yield ParsedChat("claude", f"claude:{conv.get('uuid')}", conv.get("name") or "Claude", "ai", iter(msgs))
    f.close()


# ------------------------------------------------------------------ Gemini (Google Takeout)

_TAGS = re.compile(r"<[^>]+>")


def gemini_chats(path: Path) -> Iterator[ParsedChat]:
    f = open_member(path, "myactivity.json")
    data = json.load(io.TextIOWrapper(f, encoding="utf-8"))
    msgs = []
    for i, item in enumerate(sorted(data, key=lambda x: x.get("time", ""))):
        title = item.get("title") or ""
        prompt = re.sub(r"^(Prompted|Запрос:|Вы спросили:?)\s*", "", title).strip()
        when = _ts(item.get("time"))
        if prompt:
            msgs.append(ParsedMessage(external_id=f"{i}:q", author="Я", author_id="user", sent_at=when, text=prompt,
                                      is_self=True))
        for j, block in enumerate(item.get("safeHtmlItem") or []):
            answer = html.unescape(_TAGS.sub(" ", block.get("html", ""))).strip()
            if answer:
                msgs.append(ParsedMessage(external_id=f"{i}:a{j}", author="Gemini", author_id="model", sent_at=when,
                                          text=re.sub(r"\s+", " ", answer)[:20000], is_self=False))
    f.close()
    yield ParsedChat("gemini", "gemini:activity", "Gemini", "ai", iter(msgs))
