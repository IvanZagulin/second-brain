"""Owner feedback on the agent's own messages.

``messages.feedback`` = useful | noise | NULL. The initiative prompt sees the recent ratings, so the
agent writes less (or more) depending on what the owner marked.

Revision ID: b2d4f6a8c0e1
Revises: a1c3e5f7b9d2
Create Date: 2026-11-04
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa

revision: str = 'b2d4f6a8c0e1'
down_revision: Union[str, None] = 'a1c3e5f7b9d2'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column('messages', sa.Column('feedback', sa.String(length=16), nullable=True))


def downgrade() -> None:
    op.drop_column('messages', 'feedback')
