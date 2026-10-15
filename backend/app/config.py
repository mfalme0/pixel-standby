"""Configuration, read once from the environment.

Every secret lives here and nowhere else. Values are never logged, never echoed in error
messages, and never included in the API surface.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field

DEFAULT_BASE_URL = "https://query1.finance.yahoo.com"


class ConfigurationError(RuntimeError):
    """Raised at startup when the environment is not usable."""


def _int_env(name: str, default: int) -> int:
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    try:
        return int(raw)
    except ValueError as exc:
        raise ConfigurationError(f"{name} must be an integer, got {raw!r}") from exc


def _float_env(name: str, default: float) -> float:
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    try:
        return float(raw)
    except ValueError as exc:
        raise ConfigurationError(f"{name} must be a number, got {raw!r}") from exc


def _bool_env(name: str, default: bool) -> bool:
    raw = os.environ.get(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


@dataclass(frozen=True)
class Settings:
    """Immutable runtime settings."""

    bearer_token: str

    upstream_base_url: str = DEFAULT_BASE_URL
    upstream_timeout_seconds: float = 10.0

    # Token bucket: at most `rate_limit_capacity` requests, refilled at
    # `rate_limit_capacity / rate_limit_window_seconds` per second.
    rate_limit_capacity: int = 30
    rate_limit_window_seconds: float = 60.0

    cache_ttl_seconds: float = 60.0
    max_symbols_per_request: int = 25
    upstream_retries: int = 2

    # Optional. Only needed for endpoints that require cookie/crumb auth. Never logged.
    yahoo_api_key: str | None = field(default=None, repr=False)

    log_level: str = "INFO"

    @classmethod
    def from_env(cls) -> "Settings":
        token = os.environ.get("PIXEL_BEARER_TOKEN", "").strip()
        if not token:
            raise ConfigurationError(
                "PIXEL_BEARER_TOKEN is required. Generate one with "
                "`python -c \"import secrets; print(secrets.token_urlsafe(32))\"`."
            )
        return cls(
            bearer_token=token,
            upstream_base_url=os.environ.get(
                "UPSTREAM_BASE_URL", DEFAULT_BASE_URL
            ).rstrip("/"),
            upstream_timeout_seconds=_float_env("UPSTREAM_TIMEOUT_SECONDS", 10.0),
            rate_limit_capacity=_int_env("UPSTREAM_RATE_CAPACITY", 30),
            rate_limit_window_seconds=_float_env("UPSTREAM_RATE_WINDOW_SECONDS", 60.0),
            cache_ttl_seconds=_float_env("CACHE_TTL_SECONDS", 60.0),
            max_symbols_per_request=_int_env("MAX_SYMBOLS_PER_REQUEST", 25),
            upstream_retries=_int_env("UPSTREAM_RETRIES", 2),
            yahoo_api_key=os.environ.get("YAHOO_API_KEY") or None,
            log_level=os.environ.get("LOG_LEVEL", "INFO"),
        )

    def __repr__(self) -> str:  # pragma: no cover - defensive
        # dataclass repr would include the bearer token; never let one reach a log line.
        return (
            f"Settings(bearer_token=***, upstream_base_url={self.upstream_base_url!r}, "
            f"cache_ttl_seconds={self.cache_ttl_seconds!r}, "
            f"rate_limit_capacity={self.rate_limit_capacity!r}, "
            f"rate_limit_window_seconds={self.rate_limit_window_seconds!r})"
        )