"""The only place that talks to the Claude API.

Every request:
* goes through the outbound proxy (the VPS region is blocked by the API);
* reserves its worst-case cost first and records real usage after;
* uses adaptive thinking with ``block_binding: drop_block`` — histories are append-only, and if a
  prefix ever changes (edited persona, model switch) stale thinking blocks are dropped instead of
  failing the request;
* opts into server-side refusal fallbacks on models that support them;
* enables top-level prompt caching.
"""

from __future__ import annotations

import json
import logging
import uuid
from dataclasses import dataclass, field
from decimal import Decimal
from typing import Any

import anthropic
from anthropic import AsyncAnthropic, DefaultAsyncHttpxClient

from app.core.config import get_settings
from app.core.db import session_scope
from app.core.errors import ApiError, LlmUnavailable
from app.services.llm import budget
from app.services.llm.pricing import FALLBACK_MODELS, MODERN_MODELS, estimate_tokens, estimate_usd
from app.services.settings import get_all_settings

log = logging.getLogger(__name__)

BETA_BLOCK_BINDING = "thinking-binding-controls-2026-08-01"
BETA_FALLBACK = "server-side-fallback-2026-07-01"

_client: AsyncAnthropic | None = None


class LlmRefusal(ApiError):
    status_code = 422
    code = "llm_refusal"


def get_client() -> AsyncAnthropic:
    global _client
    if _client is None:
        s = get_settings()
        http_client = DefaultAsyncHttpxClient(proxy=s.anthropic_proxy) if s.anthropic_proxy else None
        _client = AsyncAnthropic(api_key=s.anthropic_api_key, http_client=http_client,
                                 timeout=s.anthropic_timeout_s, max_retries=2)
    return _client


def build_params(*, model: str, effort: str, system: str | list, messages: list, max_tokens: int,
                 tools: list | None = None, json_schema: dict | None = None,
                 cache: bool = True) -> dict[str, Any]:
    params: dict[str, Any] = {"model": model, "max_tokens": max_tokens, "messages": messages}
    params["system"] = [{"type": "text", "text": system}] if isinstance(system, str) else system
    betas: list[str] = []
    if model in MODERN_MODELS:
        params["thinking"] = {"type": "adaptive", "block_binding": {"prefix_mismatch_behavior": "drop_block"}}
        betas.append(BETA_BLOCK_BINDING)
        params["output_config"] = {"effort": effort}
    if json_schema is not None:
        params.setdefault("output_config", {})["format"] = {"type": "json_schema", "schema": json_schema}
    if model in FALLBACK_MODELS:
        betas.append(BETA_FALLBACK)
        params["fallbacks"] = "default"
    if tools:
        params["tools"] = tools
    if cache:
        params["cache_control"] = {"type": "ephemeral"}
    if betas:
        params["betas"] = betas
    return params


def estimate_request_tokens(system: Any, messages: list, tools: list | None = None) -> int:
    total = estimate_tokens(json.dumps(system, ensure_ascii=False, default=str))
    if tools:
        total += estimate_tokens(json.dumps(tools, ensure_ascii=False))
    for msg in messages:
        content = msg.get("content")
        if isinstance(content, str):
            total += estimate_tokens(content)
            continue
        for block in content or []:
            btype = block.get("type") if isinstance(block, dict) else getattr(block, "type", None)
            if btype in ("image", "document"):
                total += 1800 if btype == "image" else 4000
            else:
                total += estimate_tokens(json.dumps(block, ensure_ascii=False, default=str))
    return total


@dataclass
class LlmResult:
    text: str
    model: str
    stop_reason: str | None
    cost_usd: Decimal
    usage: dict[str, int]
    content: list[dict] = field(default_factory=list)
    parsed: Any = None


async def resolve_route(owner_id: uuid.UUID, purpose: str, model: str | None = None,
                        effort: str | None = None) -> tuple[str, str]:
    async with session_scope() as db:
        settings = await get_all_settings(db, owner_id)
        state = await budget.budget_state(db, owner_id)
    return budget.choose_model(settings["models"], settings["effort"], settings["api_budget"],
                               purpose, state["ratio"], model, effort)


async def call(owner_id: uuid.UUID, purpose: str, *, system: str | list, messages: list,
               model: str | None = None, effort: str | None = None, max_tokens: int = 8000,
               tools: list | None = None, json_schema: dict | None = None, priority: int = 1,
               job_id: uuid.UUID | None = None, conversation_id: uuid.UUID | None = None,
               meta: dict | None = None, cache: bool = True) -> LlmResult:
    """One non-interactive request (streamed under the hood for timeout safety).

    ``cache=False`` for one-off prompts (archive periods): a cache write costs 1.25x input and is never read."""
    model, effort = await resolve_route(owner_id, purpose, model, effort)
    est = estimate_usd(model, estimate_request_tokens(system, messages, tools), max_tokens)
    reservation = await budget.reserve(owner_id, purpose, est, priority)
    params = build_params(model=model, effort=effort, system=system, messages=messages,
                          max_tokens=max_tokens, tools=tools, json_schema=json_schema, cache=cache)
    try:
        async with get_client().beta.messages.stream(**params) as stream:
            message = await stream.get_final_message()
    except anthropic.APIStatusError as exc:
        await budget.settle(owner_id, reservation, model=model, purpose=purpose, ok=False, job_id=job_id,
                            conversation_id=conversation_id, meta={"error": exc.status_code})
        log.warning("claude api error %s on %s", exc.status_code, purpose)
        raise LlmUnavailable(f"Claude API вернул ошибку {exc.status_code}") from exc
    except (anthropic.APIConnectionError, anthropic.APITimeoutError) as exc:
        await budget.settle(owner_id, reservation, model=model, purpose=purpose, ok=False, job_id=job_id,
                            conversation_id=conversation_id, meta={"error": "connection"})
        raise LlmUnavailable("Нет связи с Claude API") from exc

    cost = await budget.settle(owner_id, reservation, model=model, purpose=purpose, usage=message.usage,
                               job_id=job_id, conversation_id=conversation_id,
                               request_id=getattr(message, "_request_id", None), meta=meta)
    _, totals = budget.usage_cost(model, message.usage)
    if message.stop_reason == "refusal":
        raise LlmRefusal("Модель отказалась выполнять запрос")
    text = "".join(b.text for b in message.content if b.type == "text")
    result = LlmResult(text=text, model=message.model or model, stop_reason=message.stop_reason, cost_usd=cost,
                       usage=totals, content=[b.model_dump(exclude_none=True) for b in message.content])
    if json_schema is not None:
        try:
            result.parsed = json.loads(text)
        except json.JSONDecodeError as exc:
            raise LlmUnavailable("Модель вернула некорректный JSON") from exc
    return result


async def call_json(owner_id: uuid.UUID, purpose: str, *, system: str, user: str | list, schema: dict,
                    **kw) -> Any:
    content = user if isinstance(user, list) else [{"type": "text", "text": user}]
    result = await call(owner_id, purpose, system=system, messages=[{"role": "user", "content": content}],
                        json_schema=schema, **kw)
    return result.parsed


async def list_models() -> list[dict[str, Any]]:
    page = await get_client().models.list(limit=100)
    out = []
    for m in page.data:
        out.append({"id": m.id, "display_name": m.display_name,
                    "max_input_tokens": getattr(m, "max_input_tokens", None),
                    "max_tokens": getattr(m, "max_tokens", None),
                    "supported": m.id in MODERN_MODELS})
    return out
