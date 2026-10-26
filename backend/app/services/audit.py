from __future__ import annotations

import uuid
from typing import Any

from sqlalchemy.ext.asyncio import AsyncSession

from app.models import AuditLog


async def audit(db: AsyncSession, owner_id: uuid.UUID, actor: str, action: str,
                entity: str | None = None, entity_id: Any = None, details: dict | None = None,
                ip: str | None = None) -> None:
    db.add(AuditLog(owner_id=owner_id, actor=actor, action=action, entity=entity,
                    entity_id=str(entity_id) if entity_id is not None else None,
                    details=details or {}, ip=ip))
