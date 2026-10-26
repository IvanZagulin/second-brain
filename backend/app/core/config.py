"""Runtime configuration. Everything comes from environment variables (see deploy/.env.example)."""

from __future__ import annotations

import json
from functools import lru_cache
from pathlib import Path

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict

APP_VERSION = "1.0.0"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    environment: str = "production"
    log_level: str = "INFO"
    # Logs never contain message bodies, notification texts or keys unless this is on.
    debug_log_bodies: bool = False

    database_url: str = "postgresql+asyncpg://brain:brain@db:5432/brain"
    redis_url: str = "redis://redis:6379/0"

    anthropic_api_key: str = ""
    # Outbound HTTP proxy for api.anthropic.com (the VPS is in a region the API blocks).
    anthropic_proxy: str | None = None
    anthropic_timeout_s: float = 300.0

    embedder_url: str = "http://embedder:8000"
    embedding_dim: int = 384

    data_dir: Path = Path("/data")
    secrets_dir: Path = Path("/secrets")

    # Pepper mixed into device-token hashes.
    secret_key: str = Field(default="change-me", min_length=8)

    public_base_url: str = "https://brain.quasar-x.ru"
    default_timezone: str = "Europe/Moscow"

    # Firebase: service account for sending, google-services.json for the client's runtime init.
    fcm_service_account_file: str = "fcm-service-account.json"
    fcm_client_config_file: str = "google-services.json"
    fcm_use_proxy: bool = False

    github_repo: str = "IvanZagulin/second-brain"

    max_upload_mb: int = 1024
    rate_limit_per_minute: int = 240

    @property
    def uploads_dir(self) -> Path:
        return self.data_dir / "uploads"

    @property
    def imports_dir(self) -> Path:
        return self.data_dir / "imports"

    def fcm_service_account_path(self) -> Path:
        return self.secrets_dir / self.fcm_service_account_file

    def firebase_client_options(self) -> dict | None:
        """Options the Android app passes to FirebaseOptions.Builder at runtime."""
        path = self.secrets_dir / self.fcm_client_config_file
        if not path.exists():
            return None
        data = json.loads(path.read_text(encoding="utf-8"))
        client = next(
            (c for c in data.get("client", [])
             if c["client_info"]["android_client_info"]["package_name"] == "ru.secondbrain.app"),
            None,
        )
        if client is None:
            return None
        return {
            "project_id": data["project_info"]["project_id"],
            "sender_id": data["project_info"]["project_number"],
            "storage_bucket": data["project_info"].get("storage_bucket"),
            "application_id": client["client_info"]["mobilesdk_app_id"],
            "api_key": client["api_key"][0]["current_key"],
        }


@lru_cache
def get_settings() -> Settings:
    return Settings()
