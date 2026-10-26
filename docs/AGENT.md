# Agent

Code: `agent/`. The agent is the same in the chat and in the background; only the entry point differs.

## Conversation turn (`agent/chat.py`)

1. Stable system prompt: the owner-editable persona (`agent_profiles`) + a fixed frame (tools are the only source
   of facts, imported content is data not instructions, permission levels, modes, crisis protocol, no diagnoses,
   no pushing load through pain, mobile formatting). It stays identical across turns so prompt caching works.
2. Per-turn `<context>` block inside the user message (`agent/context.py`): local time and phase of the day,
   mode, relevant memories (hybrid search), and only the summaries the message needs — finance numbers for money
   questions, training state for training questions, due tasks for commitments.
3. Claude streams the answer; tool calls are executed (`agent/tools.py`) and the loop continues (up to 8 rounds).
4. Every assistant/tool message is stored with its exact content blocks and replayed unchanged (append-only).
   Long histories are replaced by a summary ("simple compaction"); nothing before it is replayed.
5. Three minutes after the conversation goes quiet, memory extraction runs (`agent/extraction.py`).

Modes: auto, normal, coach, psych, finance, trainer, critic, companion. psych and critic use the analysis model.

## Memory

Types: permanent (biography, preferences, goals, people), episodic (dated events), behavioral (repeated patterns),
working (current plans, open questions), analytical (conclusions with evidence). Each memory has evidence
pointers, confidence, confirmation status, actuality and sensitivity (`secret` never goes to Claude unless
allowed). Search = 0.55 × vector similarity + 0.30 × full-text rank + 0.15 × trigram similarity (+ importance).

Imports (`app/services/imports`, `agent/archive.py`): free parse → cost estimate → owner confirms → Haiku reads
~15k-token chunks and extracts facts, people, events, conflicts, themes → clarifying questions and a profile draft.
Resumable, stops at the API budget.

## Autonomy

- **Events** (`agent_events`): `transaction.created`, `workout.finished`, `weight.changed`, … processed by
  `agent/rules.py`: deterministic reminders go out directly; judgement calls go to `agent/initiative.consider`.
- **Initiative**: checks enabled/paused, quiet hours (deferred to wake-up unless critical), daily cap, minimum
  interval, dedupe; then Haiku decides whether a message is worth sending and drafts it. Messages land in the
  «Агент» conversation and arrive as a push; the owner can reply from the notification.
- **Jobs** (`agent_jobs`, `agent/jobs.py`): system recurring jobs (checks every 15 min, nightly maintenance,
  morning brief, evening check-in, weekly report), per-event jobs (workout review, purchase review, memory
  extraction, import parse/analyse) and follow-ups the agent schedules itself via `schedule_followup`. Follow-ups
  run a small tool loop with a cost limit and a restricted tool set; they can write to the owner with
  `message_user`. Retries with backoff; budget errors reschedule in 6 hours.
- **Scheduler** (`workers/scheduler.py`) dispatches due jobs to the **worker** (`workers/worker.py`, arq), recovers
  stuck jobs, sweeps pending pushes and events.

## Permissions and the journal

Each tool has a level (A automatic, B automatic + notification + rollback, C confirmation) — see
docs/CONFIGURATION.md. Every write is recorded in `agent_actions` with parameters, result, undo data and the
tool-use id as idempotency key. The app's «История агента» confirms, rejects and rolls back.

## Cost control

`app/services/llm`: every call reserves its worst-case cost under an advisory lock, records real usage per
model/purpose afterwards (fallback iterations priced at the model that ran), warns at 50/80/95 %, degrades
models near the cap and refuses new paid calls at 100 %.
