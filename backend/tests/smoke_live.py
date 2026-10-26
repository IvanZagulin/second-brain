"""End-to-end smoke test against a running server. Spends a few cents of Claude API.

    BRAIN_URL=http://127.0.0.1:8040 BRAIN_PAIR_CODE=XXXX-XXXX python backend/tests/smoke_live.py [samples_dir]

Pairs a test device, then exercises: home, settings, chat (streaming with tools), bank notification
ingestion, statement import + dedupe on re-import, finance summary, memory search, sync push/pull,
usage accounting. The test device is revoked at the end."""

from __future__ import annotations

import json
import os
import sys
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

import httpx

BASE = os.environ.get("BRAIN_URL", "http://127.0.0.1:8040")
results: list[tuple[str, bool, str]] = []


def check(name: str, ok: bool, info: str = "") -> None:
    results.append((name, ok, info))
    print(f"{'OK ' if ok else 'FAIL'} {name} {info}")


def main() -> int:
    samples = Path(sys.argv[1]) if len(sys.argv) > 1 else None
    c = httpx.Client(base_url=BASE, timeout=180)
    r = c.post("/api/v1/auth/pair", json={"code": os.environ["BRAIN_PAIR_CODE"], "device_name": "smoke-test",
                                          "model": "ci", "app_version": "test"})
    check("pair", r.status_code == 200, str(r.status_code))
    if r.status_code != 200:
        print(r.text)
        return 1
    token = r.json()["access_token"]
    c.headers["Authorization"] = f"Bearer {token}"
    check("firebase options", bool(r.json()["server"]["firebase"]), "")

    r = c.get("/api/v1/home")
    check("home", r.status_code == 200 and "greeting" in r.json(), r.json().get("greeting", "") if r.status_code == 200 else r.text[:200])
    r = c.get("/api/v1/settings")
    check("settings", r.status_code == 200 and r.json()["models"]["chat"].startswith("claude"), "")

    # Bank notifications: two copies of the same push must produce one transaction.
    now = datetime.now(timezone.utc)
    note = {"package": "ru.nspk.mirpay", "title": "Оплата покупки",
            "text": f"Сумма 2 554,30 ₽. {now.astimezone():%d.%m.%Y, %H:%M}. Карта Mir •••• 0617",
            "posted_at": now.isoformat()}
    r = c.post("/api/v1/finance/notifications", json={"items": [note, note]})
    statuses = [x["status"] for x in r.json().get("results", [])] if r.status_code == 200 else []
    cleanup = [f"/api/v1/finance/tx/{x['transaction_id']}" for x in r.json().get("results", []) if x.get("transaction_id")]
    check("notification ingest + dedupe", statuses == ["created", "duplicate"], str(statuses))

    if samples and samples.exists():
        for name in ("sber_statement_26-10-2026_20-58-10.pdf", "Выписка.pdf", "Payment_2686419983_20_56_39.pdf"):
            f = samples / name
            if not f.exists():
                continue
            r = c.post("/api/v1/finance/statements", files={"file": (name, f.read_bytes(), "application/pdf")})
            rep = r.json() if r.status_code == 200 else {"error": r.text[:200]}
            check(f"statement {name}", r.status_code == 200, json.dumps({k: rep.get(k) for k in ("rows", "created", "linked", "duplicate", "ambiguous")}, ensure_ascii=False))
            r2 = c.post("/api/v1/finance/statements", files={"file": (name, f.read_bytes(), "application/pdf")})
            rep2 = r2.json() if r2.status_code == 200 else {}
            check(f"re-import {name} creates nothing", rep2.get("created", 0) == 0 and rep2.get("duplicate", 0) == rep2.get("rows"),
                  json.dumps({k: rep2.get(k) for k in ("rows", "created", "duplicate")}))

    r = c.get("/api/v1/finance/summary")
    s = r.json() if r.status_code == 200 else {}
    check("finance summary", r.status_code == 200 and "free_minor" in s,
          f"free={s.get('free_minor')} daily={s.get('daily_budget_minor')} estimated={s.get('estimated')}")

    r = c.get("/api/v1/memories", params={"q": "собака"})
    check("memory search", r.status_code == 200 and len(r.json()["items"]) > 0, f"{len(r.json().get('items', []))} found")

    # Chat with streaming and tools.
    r = c.post("/api/v1/chat/conversations", json={"title": "smoke", "mode": "auto"})
    conv = r.json()["id"]
    cleanup.append(f"/api/v1/chat/conversations/{conv}")
    events, text = [], ""
    t0 = time.time()
    with c.stream("POST", f"/api/v1/chat/conversations/{conv}/messages",
                  json={"text": "Коротко: сколько у меня свободных денег сейчас и когда следующая тренировка?",
                        "client_message_id": str(uuid.uuid4())}) as resp:
        for line in resp.iter_lines():
            if line.startswith("data: "):
                ev = json.loads(line[6:])
                events.append(ev["type"])
                if ev["type"] == "delta":
                    text += ev["text"]
                if ev["type"] == "error":
                    print("chat error:", ev)
                if ev["type"] == "tool":
                    print("   tool:", ev.get("name"), ev.get("status"))
    check("chat stream", "done" in events and len(text) > 5, f"{time.time() - t0:.1f}s, {len(text)} chars")
    print("   answer:", text[:400].replace("\n", " "))
    check("chat used a tool", "tool" in events, "")

    r = c.post(f"/api/v1/chat/conversations/{conv}/messages", params={"stream": "false"},
               json={"text": "Это технический тест связи, ответь одним словом: ок.", "client_message_id": str(uuid.uuid4())})
    check("chat non-stream", r.status_code == 200 and r.json().get("message"), (r.json().get("message") or {}).get("text", "")[:120])

    # Sync: push an offline-created workout set chain, then pull.
    plan_id, workout_id, ex_id = str(uuid.uuid4()), str(uuid.uuid4()), str(uuid.uuid4())
    changes = [
        {"entity": "workout_sets", "id": str(uuid.uuid4()), "data": {"workout_id": workout_id, "exercise_id": ex_id,
                                                                    "set_no": 1, "reps": 10, "weight": 60.5,
                                                                    "done_at": now.isoformat()}},
        {"entity": "exercises", "id": ex_id, "data": {"name": "Тяга штанги в наклоне", "muscle_group": "back"}},
        {"entity": "workouts", "id": workout_id, "data": {"started_at": now.isoformat(), "status": "in_progress"}},
    ]
    cleanup += [f"/api/v1/workouts/sets/{changes[0]['id']}", f"/api/v1/workouts/sessions/{workout_id}",
                f"/api/v1/workouts/exercises/{ex_id}"]
    r = c.post("/api/v1/sync/push", json={"changes": changes})
    st = [x["status"] for x in r.json().get("results", [])] if r.status_code == 200 else [r.text[:200]]
    check("sync push (dependency order)", all(s == "applied" for s in st), str(st))
    r = c.post("/api/v1/sync/push", json={"changes": [{"entity": "workouts", "id": workout_id, "base_version": 0,
                                                         "data": {"notes": "старая версия"},
                                                         "client_updated_at": "2020-01-01T00:00:00Z"}]})
    check("sync conflict → server wins", r.json()["results"][0]["status"] == "conflict_server_wins", r.json()["results"][0]["status"])
    r = c.get("/api/v1/sync/pull", params={"cursor": 0, "limit": 2000})
    pulled = r.json()
    check("sync pull", r.status_code == 200 and len(pulled["changes"]) > 10, f"{len(pulled['changes'])} changes, cursor {pulled['cursor']}")

    r = c.get("/api/v1/usage/summary")
    u = r.json()
    check("api usage recorded", u["spent_usd"] > 0, f"spent ${u['spent_usd']:.4f} of ${u['limit_usd']}")

    r = c.get("/api/v1/agent/tools")
    check("agent tools", r.status_code == 200 and len(r.json()["items"]) > 20, f"{len(r.json()['items'])} tools")

    for path in cleanup:  # soft-delete everything this test created
        c.delete(path)
    me = c.get("/api/v1/auth/me").json()
    c.post(f"/api/v1/auth/devices/{me['device']['id']}/revoke")
    failed = [n for n, ok, _ in results if not ok]
    print(f"\n{len(results) - len(failed)}/{len(results)} passed" + (f"; failed: {failed}" if failed else ""))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
