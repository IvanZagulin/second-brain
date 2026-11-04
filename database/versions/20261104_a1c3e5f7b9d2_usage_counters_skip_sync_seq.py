"""Usage counters don't bump server_seq.

Every memory search bumps ``use_count``/``last_used_at`` on the memories it returns. The sync trigger
treated that like any other write, so each chat turn re-sent up to eight memories to the phone and
moved the pull cursor for nothing. The trigger now leaves ``server_seq`` alone when nothing but the
usage columns (and ``updated_at``) changed.

Revision ID: a1c3e5f7b9d2
Revises: 87b20f7929b9
Create Date: 2026-11-04
"""
from typing import Sequence, Union

from alembic import op

revision: str = 'a1c3e5f7b9d2'
down_revision: Union[str, None] = '87b20f7929b9'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

NEW_FUNCTION = """
    CREATE OR REPLACE FUNCTION bump_server_seq() RETURNS trigger AS $$
    BEGIN
        IF to_jsonb(NEW) - 'use_count' - 'last_used_at' - 'updated_at' - 'server_seq'
           = to_jsonb(OLD) - 'use_count' - 'last_used_at' - 'updated_at' - 'server_seq' THEN
            NEW.server_seq := OLD.server_seq;
            RETURN NEW;
        END IF;
        NEW.server_seq := nextval('sync_seq');
        RETURN NEW;
    END;
    $$ LANGUAGE plpgsql
"""

OLD_FUNCTION = """
    CREATE OR REPLACE FUNCTION bump_server_seq() RETURNS trigger AS $$
    BEGIN
        NEW.server_seq := nextval('sync_seq');
        RETURN NEW;
    END;
    $$ LANGUAGE plpgsql
"""


def upgrade() -> None:
    op.execute(NEW_FUNCTION)


def downgrade() -> None:
    op.execute(OLD_FUNCTION)
