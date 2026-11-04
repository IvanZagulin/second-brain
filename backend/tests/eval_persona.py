"""Live eval of the agent's persona and tool routing. Costs money: each case is one real API call
with the production system prompt and tool set, so it is not part of pytest.

    python -m tests.eval_persona --model claude-haiku-5-5      # cheap smoke run of the harness
    python -m tests.eval_persona                               # the chat model (Sonnet), ~$0.05 per run

The API key comes from ANTHROPIC_API_KEY (and ANTHROPIC_PROXY if set). Nothing touches the database:
tools are not executed, only the first turn's tool calls and text are checked."""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import re
import sys
from pathlib import Path

import anthropic
from anthropic import AsyncAnthropic, DefaultAsyncHttpxClient

sys.path[:0] = [str(Path(__file__).resolve().parents[2]), str(Path(__file__).resolve().parents[1])]

from agent.persona import build_system_prompt  # noqa: E402
from agent.tools import tool_definitions  # noqa: E402
from app.services.llm.client import build_params  # noqa: E402

CASES = Path(__file__).parent / "evals" / "persona_cases.json"
# No finance line in the context: with the numbers already there the model answers from them (rightly), and
# the finance case would stop testing tool routing.
CONTEXT = ("<context>\nСейчас: среда, 4 ноября 2026, 19:10 (Europe/Moscow). Фаза дня: вечер.\nРежим: auto (выбери сам).\n"
           "Тренировки: программа — вт: Спина/бицепс/плечи, чт: Грудь/трицепс/ноги. Последняя записанная тренировка: 28.10.\n"
           "</context>")


async def run(model: str, effort: str, only: str | None) -> int:
    client = AsyncAnthropic(api_key=os.environ["ANTHROPIC_API_KEY"],
                            http_client=DefaultAsyncHttpxClient(proxy=os.environ["ANTHROPIC_PROXY"])
                            if os.environ.get("ANTHROPIC_PROXY") else None)
    system = build_system_prompt(None, None, "Иван")
    tools = tool_definitions()
    cases = json.loads(CASES.read_text(encoding="utf-8"))
    failed = 0
    cost_in = cost_out = 0
    for case in cases:
        if only and case["id"] != only:
            continue
        params = build_params(model=model, effort=effort, system=system, max_tokens=2000, tools=tools,
                              messages=[{"role": "user", "content": [{"type": "text", "text": CONTEXT},
                                                                     {"type": "text", "text": case["user"]}]}])
        try:
            async with client.beta.messages.stream(**params) as stream:
                msg = await stream.get_final_message()
        except anthropic.APIError as exc:
            print(f"✗ {case['id']}: API error {exc}")
            failed += 1
            continue
        cost_in += msg.usage.input_tokens
        cost_out += msg.usage.output_tokens
        called = [b.name for b in msg.content if b.type == "tool_use"]
        text = "".join(b.text for b in msg.content if b.type == "text")
        problems = []
        for t in case.get("expect_tools", []):
            if t not in called:
                problems.append(f"ожидался инструмент {t}, вызваны {called or 'ничего'}")
        for t in case.get("forbid_tools", []):
            if t in called:
                problems.append(f"вызван запрещённый {t}")
        if case.get("text_must_match") and not re.search(case["text_must_match"], text, re.I):
            problems.append(f"в тексте нет /{case['text_must_match']}/")
        if case.get("text_must_not_match") and re.search(case["text_must_not_match"], text, re.I):
            problems.append(f"в тексте есть /{case['text_must_not_match']}/")
        mark = "✓" if not problems else "✗"
        failed += bool(problems)
        print(f"{mark} {case['id']}: tools={called} text={text[:90]!r}" + (f"\n    {'; '.join(problems)}" if problems else ""))
    print(f"\n{len(cases) - failed}/{len(cases)} passed; tokens in={cost_in} out={cost_out}; model={model}")
    return failed


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="claude-sonnet-5-5")
    ap.add_argument("--effort", default="medium")
    ap.add_argument("--only")
    a = ap.parse_args()
    sys.exit(1 if asyncio.run(run(a.model, a.effort, a.only)) else 0)
