"""Client for the local embedding service (multilingual MiniLM, 384 dims)."""

from __future__ import annotations

import logging

import httpx

from app.core.config import get_settings

log = logging.getLogger(__name__)
MODEL_NAME = "paraphrase-multilingual-MiniLM-L12-v2"


_client: httpx.AsyncClient | None = None


def _http() -> httpx.AsyncClient:
    """One keep-alive client per process: every memory search embeds the query, a new TCP handshake each
    time was most of the embedder round-trip."""
    global _client
    if _client is None or _client.is_closed:
        _client = httpx.AsyncClient(timeout=120, limits=httpx.Limits(max_connections=4, max_keepalive_connections=2))
    return _client


async def embed(texts: list[str], kind: str = "passage") -> list[list[float]] | None:
    if not texts:
        return []
    try:
        resp = await _http().post(f"{get_settings().embedder_url}/embed",
                                  json={"texts": [t[:2000] for t in texts], "kind": kind})
        resp.raise_for_status()
        return resp.json()["vectors"]
    except Exception as exc:  # search falls back to full-text when the embedder is down
        log.warning("embedder unavailable: %s", type(exc).__name__)
        return None


def to_pgvector(vec: list[float]) -> str:
    return "[" + ",".join(f"{x:.6f}" for x in vec) + "]"
