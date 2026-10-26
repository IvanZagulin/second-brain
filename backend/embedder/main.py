"""Local multilingual embedding service (paraphrase-multilingual-MiniLM-L12-v2, 384 dims, ONNX).
Runs as its own container so the model is loaded once for the API and the worker. Internal only."""

from __future__ import annotations

import os
import threading

from fastapi import FastAPI
from pydantic import BaseModel, Field

MODEL = os.getenv("EMBED_MODEL", "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2")
app = FastAPI(title="embedder")
_model = None
_lock = threading.Lock()


def model():
    global _model
    with _lock:
        if _model is None:
            from fastembed import TextEmbedding
            _model = TextEmbedding(MODEL, cache_dir=os.getenv("FASTEMBED_CACHE", "/models"), threads=2)
    return _model


class EmbedIn(BaseModel):
    texts: list[str] = Field(max_length=256)
    kind: str = "passage"


@app.post("/embed")
def embed(body: EmbedIn) -> dict:
    vectors = [v.tolist() for v in model().embed(body.texts, batch_size=32)]
    return {"model": MODEL, "dim": len(vectors[0]) if vectors else 0, "vectors": vectors}


@app.get("/health")
def health() -> dict:
    return {"ok": True, "loaded": _model is not None}
