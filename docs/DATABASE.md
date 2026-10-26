# Database

PostgreSQL 16 with `vector` (pgvector) and `pg_trgm`. Models: `backend/app/models/`; migrations:
`database/versions/` (Alembic, run automatically when the `api` container starts).

## Conventions

- Every table has `owner_id` (single user today, multi-user ready).
- Money: `BIGINT` minor units (kopecks) + `currency CHAR(3)`; never floats.
- Mirrored (offline) tables have `SyncMixin`: client-generated UUID `id`, `version` (optimistic concurrency),
  `deleted_at` tombstone, `client_updated_at`, and `server_seq` — taken from the global sequence `sync_seq` on
  insert and bumped by the `bump_server_seq()` trigger on every update. `server_seq` is the pull cursor.
- Raw imported data (`import_*`, `transaction_sources`) is kept apart from derived knowledge (`memories`,
  `people`, `life_events`, `transactions`).

## Tables

| Area | Tables |
|---|---|
| Core | `users`, `devices`, `pairing_codes`, `app_settings`, `agent_profiles`, `attachments`, `notes`, `audit_log`, `sync_events` |
| Chat | `conversations`, `messages` (`blocks` keeps the exact API content for append-only replay; `tsv` full-text) |
| Memory | `memories` (type, category, content, `evidence`, confidence, status, sensitivity, `tsv`, trigram index), `memory_sources`, `memory_links`, `memory_embeddings` (vector 384), `people`, `life_events`, `mood_entries`, `clarifying_questions` |
| Imports | `import_jobs`, `import_chats`, `import_messages` (unique `(source_id, external_id)` → re-import never duplicates) |
| Finance | `accounts`, `budget_categories`, `category_rules`, `transactions`, `transaction_sources` (unique fingerprint per evidence), `transaction_matches`, `receipts`, `receipt_items`, `budgets`, `subscriptions`, `debts`, `debt_payments`, `financial_goals`, `goal_contributions`, `tax_payments`, `wishlist_items` |
| Training & health | `exercises`, `workout_plans`, `plan_exercises`, `plan_revisions`, `workouts`, `workout_sets`, `body_metrics`, `progress_photos`, `health_records`, `meals` |
| Tasks | `tasks` (subtasks via `parent_id`, commitment firm/tentative/idea, `postponed_count`), `habits`, `habit_logs`, `experiments` |
| Agent | `agent_events`, `agent_jobs`, `agent_job_runs`, `agent_actions` (journal with `rollback` data and idempotency key), `notifications`, `api_usage`, `api_reservations` |

## Deletion

- Deleting a memory source (`DELETE /api/v1/memories/sources/{id}`) cascades to its raw messages, memories
  (and their embeddings), life events and links; memories that only *cite* it lose that evidence and are deleted
  if nothing is left.
- Deleting an import deletes the uploaded file and all its sources.
- Rows deleted on the phone are tombstoned (`deleted_at`) so the deletion syncs to other devices.
- Backups follow the retention window (`BACKUP_KEEP_DAYS`); data deleted today disappears from backups after it.

## Sync protocol

- `POST /api/v1/sync/push` — outbox entries `{entity, id, op, data, base_version, client_updated_at}`, applied in
  foreign-key order; on a version conflict the later `client_updated_at` wins and the loser receives the server
  copy (`conflict_server_wins`).
- `GET /api/v1/sync/pull?cursor=N` — rows with `server_seq > N` across all mirrored tables, in global order.

The phone stores mirrored rows as JSON documents in a SQLCipher-encrypted Room database (`records`), with an
`outbox` for local changes, `pending_messages` for chat written offline and `bank_notifications` for captured
payment notifications.
