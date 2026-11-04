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
from app.core.errors import ApiError, LlmBadRequest, LlmTruncated, LlmUnavailable
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
    params = build_params(model=model, effort=effort, system=system, messages=messages,
                          max_tokens=max_tokens, tools=tools, json_schema=json_schema, cache=cache)
    async with MeteredStream(owner_id, purpose, model=model, params=params, est=est, priority=priority,
                             job_id=job_id, conversation_id=conversation_id, meta=meta) as ms:
        message = await ms.finish()
    cost = ms.cost
    _, totals = budget.usage_cost(model, message.usage)
    if message.stop_reason == "refusal":
        raise LlmRefusal("Модель отказалась выполнять запрос")
    if message.stop_reason == "max_tokens" and json_schema is not None:
        raise LlmTruncated(f"Структурированный ответ обрезан по лимиту {max_tokens} токенов")
    text = "".join(b.text for b in message.content if b.type == "text")
    result = LlmResult(text=text, model=message.model or model, stop_reason=message.stop_reason, cost_usd=cost,
                       usage=totals, content=[b.model_dump(exclude_none=True) for b in message.content])
    if json_schema is not None:
        try:
            result.parsed = json.loads(text)
        except json.JSONDecodeError as exc:
            raise LlmUnavailable("Модель вернула некорректный JSON") from exc
    return result


class LlmStreamParse(LlmUnavailable):
    """The SDK could not parse what the model streamed (a broken tool-input JSON): billed, retryable once."""
    code = "stream_parse"


RETRYABLE_STATUS = (408, 409, 429, 500, 502, 503, 504, 529)


def translate_error(exc: BaseException) -> BaseException:
    """The API's failure modes as this app's error classes (unknown ones pass through unchanged)."""
    if isinstance(exc, anthropic.APIStatusError):
        if exc.status_code in RETRYABLE_STATUS:
            return LlmUnavailable(f"Claude API вернул ошибку {exc.status_code}")
        return LlmBadRequest(f"Claude API отклонил запрос ({exc.status_code})")
    if isinstance(exc, (anthropic.APIConnectionError, anthropic.APITimeoutError)):
        return LlmUnavailable("Нет связи с Claude API")
    if isinstance(exc, ValueError):
        return LlmStreamParse("Модель сформировала некорректный вызов инструмента")
    return exc


class MeteredStream:
    """Reserve the worst case → open the stream → settle what really happened, in one place for every
    caller (the chat loop iterates ``ms.stream`` itself; ``call`` just waits for the final message).

    Failure policy: a status error before anything streamed is free (and 4xx other than rate limits is not
    retried); a dropped connection or an unparsable stream books the partial usage, else the reservation,
    because the tokens were produced. Exceptions come out translated (see ``translate_error``)."""

    def __init__(self, owner_id: uuid.UUID, purpose: str, *, model: str, params: dict[str, Any], est: Decimal,
                 priority: int = 1, job_id: uuid.UUID | None = None, conversation_id: uuid.UUID | None = None,
                 meta: dict | None = None):
        self.owner_id, self.purpose, self.model, self.params, self.est = owner_id, purpose, model, params, est
        self.priority, self.job_id, self.conversation_id, self.meta = priority, job_id, conversation_id, meta
        self.reservation: uuid.UUID | None = None
        self.stream: Any = None
        self.message: Any = None
        self.cost = Decimal(0)
        self._cm: Any = None

    async def __aenter__(self) -> "MeteredStream":
        self.reservation = await budget.reserve(self.owner_id, self.purpose, self.est, self.priority)
        self._cm = get_client().beta.messages.stream(**self.params)
        try:
            self.stream = await self._cm.__aenter__()
        except BaseException as exc:
            self._cm = None
            await self._settle_failure(exc)
            raise translate_error(exc) from exc
        return self

    async def finish(self) -> Any:
        self.message = await self.stream.get_final_message()
        return self.message

    async def __aexit__(self, et, exc, tb) -> bool:
        if self._cm is not None:
            try:
                await self._cm.__aexit__(et, exc, tb)
            except Exception:
                pass
        if exc is None:
            if self.message is None:
                self.message = await self.stream.get_final_message()
            self.cost = await budget.settle(self.owner_id, self.reservation, model=self.model, purpose=self.purpose,
                                            usage=self.message.usage, job_id=self.job_id,
                                            conversation_id=self.conversation_id,
                                            request_id=getattr(self.message, "_request_id", None), meta=self.meta)
            return False
        await self._settle_failure(exc)
        translated = translate_error(exc)
        if translated is exc:
            return False
        raise translated from exc

    async def _settle_failure(self, exc: BaseException) -> None:
        common = dict(model=self.model, purpose=self.purpose, ok=False, job_id=self.job_id,
                      conversation_id=self.conversation_id)
        if isinstance(exc, anthropic.APIStatusError):
            log.warning("claude api error %s on %s", exc.status_code, self.purpose)
            await budget.settle(self.owner_id, self.reservation, meta={"error": exc.status_code}, **common)
            return
        # Unknown outcome (dropped connection, broken stream, cancellation): the tokens produced so far were
        # billed; book the snapshot, else the reservation.
        kind = ("stream_parse" if isinstance(exc, ValueError)
                else "connection" if isinstance(exc, (anthropic.APIConnectionError, anthropic.APITimeoutError))
                else type(exc).__name__)
        await budget.settle(self.owner_id, self.reservation, usage=partial_usage(self.stream), fallback_cost=self.est,
                            meta={"error": kind}, **common)


def partial_usage(stream: Any) -> Any | None:
    """Usage accumulated so far by a stream that did not finish cleanly (None if nothing arrived)."""
    try:
        snapshot = stream.current_message_snapshot if stream is not None else None
    except Exception:
        return None
    usage = getattr(snapshot, "usage", None)
    if usage is None or not (getattr(usage, "input_tokens", 0) or getattr(usage, "output_tokens", 0)):
        return None
    return usage


async def call_json(owner_id: uuid.UUID, purpose: str, *, system: str, user: str | list, schema: dict,
                    **kw) -> Any:
    # One-shot structured requests (a receipt photo, an extraction prompt) are never repeated within the
    # cache TTL: a cache write would cost 1.25x input for nothing. Callers that do repeat pass cache=True.
    kw.setdefault("cache", False)
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
