"""Claude API prices (USD per 1M tokens). Kept in code as defaults; the owner can override them in
settings key ``model_prices`` if Anthropic changes pricing."""

from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal

M = Decimal(1_000_000)


@dataclass(frozen=True)
class Price:
    input: Decimal
    output: Decimal
    cache_read: Decimal
    cache_write: Decimal  # 5-minute TTL write


def _p(i: str, o: str, r: str, w: str) -> Price:
    return Price(Decimal(i), Decimal(o), Decimal(r), Decimal(w))


PRICES: dict[str, Price] = {
    "claude-fable-5-1": _p("10", "50", "0.25", "12.5"),
    "claude-fable-5": _p("10", "50", "1.0", "12.5"),
    "claude-opus-5-5": _p("4", "20", "0.20", "5"),
    "claude-opus-5": _p("5", "25", "0.50", "6.25"),
    "claude-opus-4-8": _p("5", "25", "0.50", "6.25"),
    "claude-sonnet-5-5": _p("2", "10", "0.20", "2.5"),
    "claude-sonnet-5": _p("2", "10", "0.20", "2.5"),
    "claude-sonnet-4-6": _p("3", "15", "0.30", "3.75"),
    "claude-haiku-5-5": _p("0.10", "0.50", "0.01", "0.125"),
}
# Claude Haiku 5.5 prompts above 100K input tokens are billed at a higher tier.
HAIKU_55_LONG = _p("0.50", "2.50", "0.05", "0.625")
UNKNOWN = _p("10", "50", "1.0", "12.5")  # conservative: never under-reserve for an unknown id

# Models that take adaptive thinking + effort + thinking block binding (the 5.x family).
MODERN_MODELS = {"claude-fable-5-1", "claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5", "claude-opus-5", "claude-sonnet-5", "claude-fable-5"}
# Models that accept server-side ``fallbacks: "default"``.
FALLBACK_MODELS = {"claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5"}


def price_for(model: str, prompt_tokens: int = 0) -> Price:
    if model == "claude-haiku-5-5" and prompt_tokens > 100_000:
        return HAIKU_55_LONG
    return PRICES.get(model, UNKNOWN)


def cost_usd(model: str, input_tokens: int, output_tokens: int, cache_read: int = 0, cache_write: int = 0) -> Decimal:
    p = price_for(model, input_tokens + cache_read + cache_write)
    total = (
        Decimal(input_tokens) * p.input
        + Decimal(output_tokens) * p.output
        + Decimal(cache_read) * p.cache_read
        + Decimal(cache_write) * p.cache_write
    ) / M
    return total.quantize(Decimal("0.000001"))


def estimate_usd(model: str, input_tokens: int, max_output_tokens: int) -> Decimal:
    """Upper bound used for reservations: everything uncached, full output budget."""
    return cost_usd(model, input_tokens, max_output_tokens)


def estimate_tokens(text: str) -> int:
    # Cyrillic averages ~2.5 characters per token; err on the high side.
    return int(len(text) / 2.5) + 16
