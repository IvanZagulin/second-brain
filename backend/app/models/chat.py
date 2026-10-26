from __future__ import annotations

import uuid
from datetime import datetime
from decimal import Decimal
from typing import Any

from sqlalchemy import Computed, ForeignKey, Index, UniqueConstraint, func
from sqlalchemy.dialects.postgresql import TSVECTOR
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, OwnedMixin, SyncMixin, TimestampMixin, UUIDPkMixin


class Conversation(UUIDPkMixin, OwnedMixin, SyncMixin, TimestampMixin, Base):
    __tablename__ = "conversations"
    __sync__ = True
    __sync_push__ = True
    __sync_sort__ = "last_message_at"

    title: Mapped[str] = mapped_column(default="Новый диалог")
    mode: Mapped[str] = mapped_column(default="auto")  # auto | normal | coach | psych | finance | trainer | critic | companion
    kind: Mapped[str] = mapped_column(default="chat")  # chat | agent (initiative thread) | system
    # Client-side "simple compaction": when history grows, everything before
    # summary_until_seq is replaced by ``summary`` and not replayed.
    summary: Mapped[str | None]
    summary_until: Mapped[datetime | None]
    archived: Mapped[bool] = mapped_column(default=False)
    pinned: Mapped[bool] = mapped_column(default=False)
    last_message_at: Mapped[datetime | None]
    last_extracted_at: Mapped[datetime | None]


class Message(UUIDPkMixin, OwnedMixin, SyncMixin, Base):
    __tablename__ = "messages"
    __sync__ = True
    __sync_parent__ = "conversation_id"
    __sync_sort__ = "created_at"
    __table_args__ = (
        UniqueConstraint("owner_id", "client_message_id"),
        Index("ix_messages_conv_created", "conversation_id", "created_at"),
        Index("ix_messages_tsv", "tsv", postgresql_using="gin"),
    )

    conversation_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("conversations.id", ondelete="CASCADE"))
    role: Mapped[str]  # user | assistant | event
    text: Mapped[str] = mapped_column(default="")
    # Exact content blocks as sent to / returned by the Messages API. Replayed unchanged so
    # thinking blocks stay valid (append-only history).
    blocks: Mapped[list[Any]] = mapped_column(default=list)
    attachments: Mapped[list[Any]] = mapped_column(default=list)
    tool_events: Mapped[list[Any]] = mapped_column(default=list)
    model: Mapped[str | None]
    usage: Mapped[dict[str, Any]] = mapped_column(default=dict)
    cost_usd: Mapped[Decimal | None]
    status: Mapped[str] = mapped_column(default="complete")  # complete | streaming | error | pending
    client_message_id: Mapped[str | None]
    source: Mapped[str] = mapped_column(default="app")  # app | notification_reply | agent | share
    created_at: Mapped[datetime] = mapped_column(server_default=func.now())
    tsv: Mapped[Any] = mapped_column(TSVECTOR, Computed("to_tsvector('russian', coalesce(text, ''))", persisted=True))
