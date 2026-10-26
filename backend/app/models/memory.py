"""Long-term memory. Raw imported data (import_*) stays separate from derived knowledge (memories,
people, life_events). Deleting a source cascades to everything derived from it."""

from __future__ import annotations

import uuid
from datetime import datetime
from typing import Any

from pgvector.sqlalchemy import Vector
from sqlalchemy import BigInteger, Computed, ForeignKey, Index, UniqueConstraint, func
from sqlalchemy.dialects.postgresql import TSVECTOR
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, OwnedMixin, SyncMixin, TimestampMixin, UUIDPkMixin

MEMORY_TYPES = ("permanent", "episodic", "behavioral", "working", "analytical")


class MemorySource(UUIDPkMixin, OwnedMixin, TimestampMixin, Base):
    __tablename__ = "memory_sources"
    __table_args__ = (UniqueConstraint("owner_id", "kind", "external_id"),)

    kind: Mapped[str]  # telegram | chatgpt | claude | gemini | chat | note | document | manual
    title: Mapped[str]
    external_id: Mapped[str]
    import_job_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("import_jobs.id", ondelete="SET NULL"))
    meta: Mapped[dict[str, Any]] = mapped_column(default=dict)
    excluded: Mapped[bool] = mapped_column(default=False)
    # Fragments of this source may be sent to the Claude API only when allowed.
    allow_llm: Mapped[bool] = mapped_column(default=True)


class Memory(UUIDPkMixin, OwnedMixin, SyncMixin, TimestampMixin, Base):
    __tablename__ = "memories"
    __sync__ = True
    __sync_sort__ = "created_at"
    __table_args__ = (
        Index("ix_memories_tsv", "tsv", postgresql_using="gin"),
        Index("ix_memories_content_trgm", "content", postgresql_using="gin",
              postgresql_ops={"content": "gin_trgm_ops"}),
        Index("ix_memories_owner_type", "owner_id", "type"),
    )

    type: Mapped[str]  # see MEMORY_TYPES
    category: Mapped[str] = mapped_column(default="general")
    title: Mapped[str | None]
    content: Mapped[str]
    data: Mapped[dict[str, Any]] = mapped_column(default=dict)
    event_at: Mapped[datetime | None]
    source_kind: Mapped[str] = mapped_column(default="chat")  # chat | import | manual | agent | finance | workout | note
    source_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("memory_sources.id", ondelete="CASCADE"), index=True)
    # Pointers to the evidence: [{"kind": "message"|"import_message"|"transaction"|..., "id": ..., "excerpt": ...}]
    evidence: Mapped[list[Any]] = mapped_column(default=list)
    confidence: Mapped[float] = mapped_column(default=0.7)
    status: Mapped[str] = mapped_column(default="unconfirmed")  # unconfirmed | confirmed | rejected
    is_actual: Mapped[bool] = mapped_column(default=True)
    sensitivity: Mapped[str] = mapped_column(default="normal")  # normal | private | secret
    importance: Mapped[int] = mapped_column(default=3)  # 1..5
    dedupe_key: Mapped[str | None] = mapped_column(index=True)
    use_count: Mapped[int] = mapped_column(default=0)
    last_used_at: Mapped[datetime | None]
    tsv: Mapped[Any] = mapped_column(
        TSVECTOR, Computed("to_tsvector('russian', coalesce(title, '') || ' ' || coalesce(content, ''))", persisted=True))


class MemoryLink(UUIDPkMixin, OwnedMixin, Base):
    __tablename__ = "memory_links"
    __table_args__ = (Index("ix_memory_links_entity", "entity_type", "entity_id"),)

    memory_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("memories.id", ondelete="CASCADE"), index=True)
    entity_type: Mapped[str]  # person | life_event | memory | transaction | workout | task | conversation
    entity_id: Mapped[str]
    relation: Mapped[str] = mapped_column(default="mentions")
    created_at: Mapped[datetime] = mapped_column(server_default=func.now())


class MemoryEmbedding(OwnedMixin, Base):
    __tablename__ = "memory_embeddings"

    memory_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("memories.id", ondelete="CASCADE"), primary_key=True)
    model: Mapped[str]
    embedding: Mapped[Any] = mapped_column(Vector(384))
    created_at: Mapped[datetime] = mapped_column(server_default=func.now())


class Person(UUIDPkMixin, OwnedMixin, SyncMixin, TimestampMixin, Base):
    """A card for a significant person. Observations are subjective notes, not assessments."""

    __tablename__ = "people"
    __sync__ = True
    __sync_push__ = True
    __sync_sort__ = "updated_at"

    name: Mapped[str]
    aliases: Mapped[list[Any]] = mapped_column(default=list)
    relation: Mapped[str | None]
    description: Mapped[str | None]
    observations: Mapped[list[Any]] = mapped_column(default=list)
    unresolved: Mapped[list[Any]] = mapped_column(default=list)
    importance: Mapped[int] = mapped_column(default=3)
    last_mentioned_at: Mapped[datetime | None]
    source_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("memory_sources.id", ondelete="SET NULL"))


class LifeEvent(UUIDPkMixin, OwnedMixin, SyncMixin, TimestampMixin, Base):
    __tablename__ = "life_events"
    __sync__ = True
    __sync_push__ = True
    __sync_sort__ = "occurred_at"

    title: Mapped[str]
    description: Mapped[str | None]
    occurred_at: Mapped[datetime | None]
    kind: Mapped[str] = mapped_column(default="event")  # event | conflict | achievement | decision | change
    sentiment: Mapped[int | None]  # -2..2
    importance: Mapped[int] = mapped_column(default=3)
    people: Mapped[list[Any]] = mapped_column(default=list)
    evidence: Mapped[list[Any]] = mapped_column(default=list)
    source_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("memory_sources.id", ondelete="CASCADE"))


class MoodEntry(UUIDPkMixin, OwnedMixin, SyncMixin, TimestampMixin, Base):
    __tablename__ = "mood_entries"
    __sync__ = True
    __sync_push__ = True
    __sync_sort__ = "occurred_at"

    occurred_at: Mapped[datetime]
    score: Mapped[int]  # 1..10
    energy: Mapped[int | None]  # 1..10
    emotions: Mapped[list[Any]] = mapped_column(default=list)
    cause: Mapped[str | None]
    note: Mapped[str | None]
    voice_attachment_id: Mapped[uuid.UUID | None]


class ClarifyingQuestion(UUIDPkMixin, OwnedMixin, SyncMixin, TimestampMixin, Base):
    __tablename__ = "clarifying_questions"
    __sync__ = True
    __sync_sort__ = "created_at"

    question: Mapped[str]
    context: Mapped[str | None]
    memory_ids: Mapped[list[Any]] = mapped_column(default=list)
    status: Mapped[str] = mapped_column(default="open")  # open | answered | dismissed
    answer: Mapped[str | None]
    priority: Mapped[int] = mapped_column(default=2)
    answered_at: Mapped[datetime | None]


class ImportJob(UUIDPkMixin, OwnedMixin, TimestampMixin, Base):
    __tablename__ = "import_jobs"

    kind: Mapped[str]  # telegram | chatgpt | claude | gemini | statement | unknown
    status: Mapped[str] = mapped_column(default="uploaded")
    # uploaded → parsing → parsed (awaiting confirmation, cost estimated) → analyzing → done | failed | cancelled
    file_path: Mapped[str]
    file_name: Mapped[str]
    file_size: Mapped[int] = mapped_column(BigInteger)
    file_sha256: Mapped[str]
    stats: Mapped[dict[str, Any]] = mapped_column(default=dict)
    options: Mapped[dict[str, Any]] = mapped_column(default=dict)
    progress: Mapped[float] = mapped_column(default=0.0)
    error: Mapped[str | None]
    finished_at: Mapped[datetime | None]


class ImportChat(UUIDPkMixin, OwnedMixin, Base):
    __tablename__ = "import_chats"
    __table_args__ = (UniqueConstraint("source_id"),)

    import_job_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("import_jobs.id", ondelete="SET NULL"), index=True)
    source_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("memory_sources.id", ondelete="CASCADE"))
    title: Mapped[str]
    chat_type: Mapped[str | None]
    participants: Mapped[list[Any]] = mapped_column(default=list)
    message_count: Mapped[int] = mapped_column(default=0)
    first_at: Mapped[datetime | None]
    last_at: Mapped[datetime | None]
    excluded: Mapped[bool] = mapped_column(default=False)
    analyzed_until: Mapped[datetime | None]
    # Resume cursor for the paid analysis (message ids are assigned in chronological export order).
    analyzed_upto_id: Mapped[int | None] = mapped_column(BigInteger)


class ImportMessage(OwnedMixin, Base):
    __tablename__ = "import_messages"
    __table_args__ = (
        UniqueConstraint("source_id", "external_id"),
        Index("ix_import_messages_chat_sent", "chat_id", "sent_at"),
        Index("ix_import_messages_tsv", "tsv", postgresql_using="gin"),
    )

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    chat_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("import_chats.id", ondelete="CASCADE"))
    source_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("memory_sources.id", ondelete="CASCADE"))
    external_id: Mapped[str]
    author: Mapped[str | None]
    author_id: Mapped[str | None]
    is_self: Mapped[bool] = mapped_column(default=False)
    sent_at: Mapped[datetime | None]
    text: Mapped[str]
    reply_to: Mapped[str | None]
    excluded: Mapped[bool] = mapped_column(default=False)
    tsv: Mapped[Any] = mapped_column(TSVECTOR, Computed("to_tsvector('russian', coalesce(text, ''))", persisted=True))
