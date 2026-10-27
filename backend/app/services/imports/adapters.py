"""Format adapters for personal archives. Each adapter streams ParsedChat objects so multi-year,
multi-gigabyte exports never have to fit in memory. Media is skipped; only text is kept.

Supported:
* Telegram Desktop JSON export — a single chat (result.json with "messages") or a full export
  ("chats.list"), plain or zipped;
* ChatGPT data export (zip with conversations.json, tree "mapping");
* Claude data export (zip/json with "chat_messages");
* Gemini via Google Takeout (My Activity/Gemini Apps/MyActivity.json);
* Telegram Desktop HTML export of one chat (messages.html, messages2.html, … zipped);
* Claude memory export (zip of Markdown notes: profile.md, preferences.md, areas/, projects/, topics/).

When an export format changes only the matching adapter needs updating."""

from __future__ import annotations

import html
import io
import json
import re
import zipfile
from collections.abc import Iterator
from html.parser import HTMLParser
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
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
            if any(n.rsplit("/", 1)[-1] == "messages.html" for n in names):
                return "telegram_html"
            if any(n.rsplit("/", 1)[-1] in ("profile.md", "preferences.md") for n in names) or \
                    any(n.startswith("memories/") and n.endswith(".json") for n in names):
                return "claude_memory"
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


# ------------------------------------------------------------------ Telegram (HTML export)

_TG_DATE = re.compile(r"(\d{2})\.(\d{2})\.(\d{4}) (\d{2}):(\d{2}):(\d{2})(?:\s*(?:UTC|GMT)([+-])(\d{1,2}):?(\d{2}))?")


def _tg_html_date(title: str | None) -> datetime | None:
    m = _TG_DATE.search(title or "")
    if not m:
        return None
    d, mo, y, h, mi, s, sign, oh, om = m.groups()
    tz = timezone.utc
    if sign:
        off = timedelta(hours=int(oh), minutes=int(om))
        tz = timezone(off if sign == "+" else -off)
    return datetime(int(y), int(mo), int(d), int(h), int(mi), int(s), tzinfo=tz)


class _TgHtmlPage(HTMLParser):
    """One messagesN.html page → messages. Text only: media blocks are ignored, service messages skipped."""

    def __init__(self, last_author: str | None) -> None:
        super().__init__(convert_charrefs=True)
        self.out: list[ParsedMessage] = []
        self.last_author = last_author
        self.title: str | None = None
        self._stack: list[set[str]] = []
        self._msg: dict[str, Any] | None = None
        self._msg_depth = 0
        self._capture: str | None = None  # "from" | "text" | "title"
        self._capture_depth = 0
        self._buf: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag == "br" and self._capture == "text":
            self._buf.append("\n")
            return
        if tag != "div":
            return
        a = dict(attrs)
        classes = set((a.get("class") or "").split())
        self._stack.append(classes)
        depth = len(self._stack)
        if {"message", "default"} <= classes:
            self._msg = {"id": (a.get("id") or "").removeprefix("message"), "joined": "joined" in classes,
                         "author": None, "date": None, "text": [], "forwarded": False}
            self._msg_depth = depth
            return
        if self._capture is None and self.title is None and classes == {"text", "bold"} and \
                any("page_header" in c for c in self._stack[:-1]):
            self._capture, self._capture_depth, self._buf = "title", depth, []
            return
        if self._msg is None:
            return
        if "forwarded" in classes:
            self._msg["forwarded"] = True
        if "date" in classes and "details" in classes and self._msg["date"] is None:
            self._msg["date"] = _tg_html_date(a.get("title"))
        elif "from_name" in classes and self._capture is None:
            self._capture, self._capture_depth, self._buf = "from", depth, []
        elif classes == {"text"} and self._capture is None:
            self._capture, self._capture_depth, self._buf = "text", depth, []

    def handle_endtag(self, tag: str) -> None:
        if tag != "div" or not self._stack:
            return
        depth = len(self._stack)
        if self._capture and depth == self._capture_depth:
            value = "".join(self._buf).strip()
            if self._capture == "title":
                self.title = value or None
            elif self._capture == "from" and self._msg is not None:
                # A from_name inside a forwarded block names the original author, not the sender.
                if not self._msg["forwarded"] and self._msg["author"] is None:
                    self._msg["author"] = value.split("\n")[0].strip() or None
            elif self._capture == "text" and self._msg is not None and value:
                self._msg["text"].append(value)
            self._capture = None
        if self._msg is not None and depth == self._msg_depth:
            m = self._msg
            self._msg = None
            # "joined" messages continue the previous sender and carry no from_name.
            author = m["author"] or self.last_author
            if m["author"]:
                self.last_author = m["author"]
            text = "\n".join(m["text"]).strip()
            if text and m["id"]:
                self.out.append(ParsedMessage(external_id=m["id"], author=author,
                                              author_id=f"name:{author}" if author else None,
                                              sent_at=m["date"], text=text))
        self._stack.pop()

    def handle_data(self, data: str) -> None:
        if self._capture:
            self._buf.append(data)


def _page_no(name: str) -> int:
    m = re.search(r"messages(\d*)\.html$", name)
    return int(m.group(1) or 1) if m else 10**9


def _tg_html_page(zf: zipfile.ZipFile, name: str, last_author: str | None) -> _TgHtmlPage:
    page = _TgHtmlPage(last_author)
    with zf.open(name) as f:
        page.feed(f.read().decode("utf-8", errors="replace"))
    page.close()
    return page


def telegram_html_chats(path: Path) -> Iterator[ParsedChat]:
    """A single-chat HTML export. Pages are read in order and streamed, so huge chats stay cheap on memory.
    Authors carry no ids in HTML, so participants are keyed by name; the owner picks which name is theirs."""
    zf = zipfile.ZipFile(path)
    pages = sorted((n for n in zf.namelist() if re.search(r"(^|/)messages\d*\.html$", n)), key=_page_no)
    if not pages:
        raise UnknownFormat("В архиве нет messages.html")
    first = _tg_html_page(zf, pages[0], None)
    title = first.title or "Чат Telegram"

    def messages() -> Iterator[ParsedMessage]:
        yield from first.out
        last = first.last_author
        for name in pages[1:]:
            page = _tg_html_page(zf, name, last)
            last = page.last_author
            yield from page.out
        zf.close()

    yield ParsedChat("telegram", f"tg:html:{title}", title, "personal_chat", messages(), {"format": "html"})


# ------------------------------------------------------------------ Claude memory export

def _zip_name(info: zipfile.ZipInfo) -> str:
    """Archivers that do not set the UTF-8 flag make zipfile decode names as cp437."""
    if info.flag_bits & 0x800:
        return info.filename
    try:
        return info.filename.encode("cp437").decode("utf-8")
    except (UnicodeEncodeError, UnicodeDecodeError):
        return info.filename


def claude_memory_chats(path: Path) -> Iterator[ParsedChat]:
    """Claude's memory export is already a digest about the owner: each note becomes one fragment to analyse.

    Two shapes: the memory tool's Markdown notes, or the account data export's ``memories/<id>.json`` with the
    overall conversations memory, per-project memories and the same notes as ``memory_files``."""
    with zipfile.ZipFile(path) as zf:
        msgs = []
        for info in zf.infolist():
            name = _zip_name(info)
            if not (name.startswith("memories/") and name.endswith(".json")):
                continue
            data = json.loads(zf.read(info).decode("utf-8", errors="replace"))
            when = datetime(*info.date_time, tzinfo=timezone.utc)

            def note(ext_id: str, label: str, text: str | None) -> None:
                if text and text.strip():
                    msgs.append(ParsedMessage(external_id=ext_id, author="Claude (память)", author_id="claude_memory",
                                              sent_at=when, text=f"[{label}]\n{text.strip()}", is_self=False))

            note("conversations_memory", "Память Claude по всем разговорам", data.get("conversations_memory"))
            for pid, text in (data.get("project_memories") or {}).items():
                note(f"project:{pid}", f"Память Claude о проекте {pid[:8]}", text)
            for f in data.get("memory_files") or []:
                rel = (f.get("path") or "").lstrip("/")
                note(rel, f"Заметка памяти Claude: {rel}", f.get("content"))
        for info in sorted(zf.infolist(), key=_zip_name):
            name = _zip_name(info)
            if info.is_dir() or not name.lower().endswith(".md"):
                continue
            text = zf.read(info).decode("utf-8", errors="replace").strip()
            if not text:
                continue
            rel = name.split("/", 1)[1] if "/" in name else name
            msgs.append(ParsedMessage(external_id=rel, author="Claude (память)", author_id="claude_memory",
                                      sent_at=datetime(*info.date_time, tzinfo=timezone.utc),
                                      text=f"[Заметка памяти Claude: {rel}]\n{text}", is_self=False))
    yield ParsedChat("claude", "claude:memory-export", "Память Claude", "ai", iter(msgs), {"format": "memory"})
