"""Declarative base and mixins shared by all tables.

Conventions:
* every row belongs to an owner (``owner_id``) even though the app has a single user;
* money is BIGINT minor units + currency;
* tables the phone mirrors offline carry ``SyncMixin``: a client-generated UUID, a ``version``
  for optimistic concurrency, a tombstone ``deleted_at`` and ``server_seq`` — a global
  monotonically increasing number bumped by a trigger on every insert/update, used as the
  pull cursor.
"""

from __future__ import annotations

import uuid
from datetime import date, datetime
from decimal import Decimal
from typing import Any, ClassVar

from sqlalchemy import BigInteger, Date, DateTime, ForeignKey, MetaData, Numeric, Text, func, text
from sqlalchemy.dialects.postgresql import JSONB, UUID as PG_UUID
from sqlalchemy.orm import DeclarativeBase, Mapped, declared_attr, mapped_column

NAMING_CONVENTION = {
    "ix": "ix_%(column_0_label)s",
    "uq": "uq_%(table_name)s_%(column_0_name)s",
    "ck": "ck_%(table_name)s_%(constraint_name)s",
    "fk": "fk_%(table_name)s_%(column_0_name)s_%(referred_table_name)s",
    "pk": "pk_%(table_name)s",
}


class Base(DeclarativeBase):
    metadata = MetaData(naming_convention=NAMING_CONVENTION)
    type_annotation_map = {
        dict[str, Any]: JSONB,
        list[Any]: JSONB,
        datetime: DateTime(timezone=True),
        date: Date,
        uuid.UUID: PG_UUID(as_uuid=True),
        Decimal: Numeric(14, 4),
        str: Text,
    }

    # Sync metadata, read by app.services.sync.
    __sync__: ClassVar[bool] = False          # mirrored to the phone
    __sync_push__: ClassVar[bool] = False     # the phone may create/edit rows
    __sync_parent__: ClassVar[str | None] = None  # column the phone indexes as parent_id
    __sync_sort__: ClassVar[str | None] = None    # column the phone indexes as sort_at


class TimestampMixin:
    created_at: Mapped[datetime] = mapped_column(server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(server_default=func.now(), onupdate=func.now())


class OwnedMixin:
    @declared_attr
    def owner_id(cls) -> Mapped[uuid.UUID]:
        return mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)


class UUIDPkMixin:
    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)


class SyncMixin:
    version: Mapped[int] = mapped_column(default=1, server_default=text("1"))
    server_seq: Mapped[int] = mapped_column(BigInteger, server_default=text("nextval('sync_seq')"), index=True)
    deleted_at: Mapped[datetime | None] = mapped_column(default=None)
    client_updated_at: Mapped[datetime | None] = mapped_column(default=None)


def money_col(**kw) -> Mapped[int]:
    return mapped_column(BigInteger, **kw)
