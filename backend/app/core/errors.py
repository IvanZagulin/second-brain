"""API error model: every error response is {"error": {"code", "message", "details"}}."""

from __future__ import annotations

from typing import Any

from fastapi import Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import ORJSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException


class ApiError(Exception):
    status_code = 400
    code = "bad_request"

    def __init__(self, message: str, *, code: str | None = None, status_code: int | None = None,
                 details: Any = None):
        super().__init__(message)
        self.message = message
        if code:
            self.code = code
        if status_code:
            self.status_code = status_code
        self.details = details


class NotFound(ApiError):
    status_code = 404
    code = "not_found"


class Unauthorized(ApiError):
    status_code = 401
    code = "unauthorized"


class Forbidden(ApiError):
    status_code = 403
    code = "forbidden"


class Conflict(ApiError):
    status_code = 409
    code = "conflict"


class BudgetExceeded(ApiError):
    status_code = 402
    code = "api_budget_exceeded"


class LlmUnavailable(ApiError):
    status_code = 503
    code = "llm_unavailable"


def _body(code: str, message: str, details: Any = None) -> dict:
    return {"error": {"code": code, "message": message, "details": details}}


async def api_error_handler(_: Request, exc: ApiError) -> ORJSONResponse:
    return ORJSONResponse(_body(exc.code, exc.message, exc.details), status_code=exc.status_code)


async def http_error_handler(_: Request, exc: StarletteHTTPException) -> ORJSONResponse:
    return ORJSONResponse(_body("http_error", str(exc.detail)), status_code=exc.status_code)


async def validation_error_handler(_: Request, exc: RequestValidationError) -> ORJSONResponse:
    details = [{"loc": list(e.get("loc", [])), "msg": e.get("msg")} for e in exc.errors()]
    return ORJSONResponse(_body("validation_error", "Некорректные данные запроса", details), status_code=422)
