"""FastAPI application entry point."""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.exceptions import RequestValidationError
from fastapi.responses import ORJSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

import app.services.hooks  # noqa: F401  (registers write hooks)
from app.core.config import APP_VERSION, get_settings
from app.core.errors import ApiError, api_error_handler, http_error_handler, validation_error_handler
from app.core.logging import setup_logging
from app.routers import agent, auth, chat, finance, fitness, imports, memory, misc, settings, system
from app.routers import diagnostics

log = logging.getLogger("api")


@asynccontextmanager
async def lifespan(_: FastAPI):
    s = get_settings()
    setup_logging(s.log_level)
    s.uploads_dir.mkdir(parents=True, exist_ok=True)
    s.imports_dir.mkdir(parents=True, exist_ok=True)
    log.info("api started", extra={"extra_fields": {"version": APP_VERSION}})
    yield


app = FastAPI(title="Second Brain API", version=APP_VERSION, default_response_class=ORJSONResponse,
              lifespan=lifespan, docs_url="/api/docs", openapi_url="/api/openapi.json", redoc_url=None)

app.add_exception_handler(ApiError, api_error_handler)
app.add_exception_handler(StarletteHTTPException, http_error_handler)
app.add_exception_handler(RequestValidationError, validation_error_handler)

for r in (system.router, auth.router, settings.router, agent.router, chat.router, memory.router,
          memory.people_router, memory.events_router, memory.mood_router, imports.router, finance.router,
          *finance.crud_routers, fitness.router, fitness.health_router, *fitness.crud_routers,
          misc.notifications_router, misc.sync_router, misc.usage_router, misc.home_router, *misc.crud_routers,
          diagnostics.router):
    app.include_router(r)
