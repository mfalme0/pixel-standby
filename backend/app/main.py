"""FastAPI application.

Wiring only. All policy lives in `service.py`, all outbound HTTP lives in `http.py`.
"""

from __future__ import annotations

import logging
import time
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import httpx
from fastapi import Depends, FastAPI, HTTPException, Query, Request, status
from fastapi.responses import JSONResponse

from .adapters.yahoo import YahooGoldAdapter, YahooStockAdapter
from .auth import get_settings, require_token
from .cache import TokenBucket
from .config import ConfigurationError, Settings
from .http import UpstreamClient
from .models import HealthResponse, QuoteResponse
from .service import QuoteService

VERSION = "0.1.0"

logger = logging.getLogger(__name__)


def configure_logging(level: str) -> None:
    logging.basicConfig(
        level=getattr(logging, level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)-8s %(name)s %(message)s",
    )


def build_service(
    settings: Settings,
    *,
    transport: httpx.AsyncBaseTransport | None = None,
) -> tuple[QuoteService, UpstreamClient]:
    """Wire the rate limiter, the single HTTP client, and the adapters together."""
    bucket = TokenBucket(
        capacity=settings.rate_limit_capacity,
        window_seconds=settings.rate_limit_window_seconds,
        clock=time.monotonic,
    )
    client = UpstreamClient(settings, bucket, transport=transport)
    providers: list = [
        YahooStockAdapter(client),
        YahooGoldAdapter(client),
    ]
    return QuoteService(settings, providers), client


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    settings: Settings = app.state.settings
    service, client = build_service(settings, transport=app.state.transport)
    app.state.quote_service = service
    app.state.upstream_client = client
    logger.info(
        "pixel-standby backend %s ready (upstream=%s, ttl=%.0fs, rate=%d per %.0fs)",
        VERSION,
        settings.upstream_base_url,
        settings.cache_ttl_seconds,
        settings.rate_limit_capacity,
        settings.rate_limit_window_seconds,
    )
    try:
        yield
    finally:
        await client.aclose()


def create_app(
    settings: Settings | None = None,
    *,
    transport: httpx.AsyncBaseTransport | None = None,
) -> FastAPI:
    resolved = settings or Settings.from_env()
    configure_logging(resolved.log_level)

    app = FastAPI(
        title="Pixel Standby backend",
        version=VERSION,
        lifespan=lifespan,
        # No interactive docs on a service that fronts a token-protected endpoint.
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )
    app.state.settings = resolved
    app.state.transport = transport

    @app.exception_handler(ConfigurationError)
    async def _config_error(_: Request, exc: ConfigurationError) -> JSONResponse:
        # The message can name the offending variable but never its value.
        logger.error("configuration error: %s", exc)
        return JSONResponse(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            content={"detail": "Backend is misconfigured"},
        )

    @app.get("/api/health", response_model=HealthResponse)
    async def health(
        request: Request,
        settings_: Settings = Depends(get_settings),
    ) -> HealthResponse:
        service: QuoteService = request.app.state.quote_service
        return HealthResponse(
            status=service.health_status(),
            lastSuccessAt=service.last_success_at,
            lastSuccessAgeSeconds=service.last_success_age_seconds(),
            cacheTtlSeconds=settings_.cache_ttl_seconds,
            cachedSymbols=service.cached_symbol_count,
            upstreamBaseUrl=settings_.upstream_base_url,
            version=VERSION,
        )

    @app.get("/api/quotes", response_model=list[QuoteResponse])
    async def quotes(
        request: Request,
        symbols: str = Query(
            ...,
            description="Comma-separated symbols, for example AAPL,MSFT,XAUUSD",
            min_length=1,
        ),
        settings_: Settings = Depends(get_settings),
        _: None = Depends(require_token),
    ) -> list[QuoteResponse]:
        service: QuoteService = request.app.state.quote_service

        parsed = [part.strip() for part in symbols.split(",") if part.strip()]
        if not parsed:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="No symbols supplied",
            )
        if len(parsed) > settings_.max_symbols_per_request:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail=(
                    "Too many symbols; the limit is "
                    f"{settings_.max_symbols_per_request} per request"
                ),
            )

        return await service.quotes(parsed)

    return app