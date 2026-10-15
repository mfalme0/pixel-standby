"""The single outbound HTTP path.

Everything upstream goes through here so that the rate limiter, the retry policy, the
timeout, and the credential handling live in exactly one place. Nothing else in the service
is allowed to open a socket.
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any

import httpx

from .adapters.base import UpstreamError
from .cache import TokenBucket
from .config import Settings

logger = logging.getLogger(__name__)

#: Backoff between retries. Doubles each attempt.
_RETRY_BASE_DELAY_SECONDS = 0.25
_RETRY_MAX_DELAY_SECONDS = 2.0


class UpstreamClient:
    """Rate-limited, retrying, JSON-returning HTTP client.

    Deliberately not a context manager: one is built at startup and shared for the life of
    the process so the connection pool is reused.
    """

    def __init__(
        self,
        settings: Settings,
        bucket: TokenBucket,
        *,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._settings = settings
        self._bucket = bucket

        headers = {
            "User-Agent": (
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
            ),
            "Accept": "application/json",
        }
        if settings.yahoo_api_key:
            # Attached, never logged, never echoed into an error.
            headers["X-Api-Key"] = settings.yahoo_api_key

        self._client = httpx.AsyncClient(
            base_url=settings.upstream_base_url,
            timeout=httpx.Timeout(settings.upstream_timeout_seconds),
            headers=headers,
            follow_redirects=True,
            transport=transport,
        )

    async def aclose(self) -> None:
        await self._client.aclose()

    async def __aenter__(self) -> "UpstreamClient":
        return self

    async def __aexit__(self, exc_type, exc, tb) -> None:
        await self.aclose()

    async def get(self, path: str, **params: str) -> Any:
        """GET a path and return decoded JSON, honouring the bucket and retrying."""
        last_error: Exception | None = None

        for attempt in range(self._settings.upstream_retries + 1):
            await self._bucket.acquire()
            try:
                response = await self._client.get(path, params=params)
            except httpx.TimeoutException as exc:
                last_error = exc
                logger.warning(
                    "upstream timeout on attempt %d/%d for %s",
                    attempt + 1,
                    self._settings.upstream_retries + 1,
                    path,
                )
            except httpx.HTTPError as exc:
                last_error = exc
                logger.warning(
                    "upstream transport error on attempt %d/%d for %s: %s",
                    attempt + 1,
                    self._settings.upstream_retries + 1,
                    path,
                    type(exc).__name__,
                )
            else:
                if response.status_code in (429, 500, 502, 503, 504):
                    last_error = UpstreamError(
                        f"upstream returned {response.status_code} for {path}",
                        retryable=True,
                    )
                    logger.warning(
                        "upstream %d on attempt %d/%d for %s",
                        response.status_code,
                        attempt + 1,
                        self._settings.upstream_retries + 1,
                        path,
                    )
                elif response.status_code == 404:
                    raise UpstreamError(
                        f"upstream returned 404 for {path}", retryable=False
                    )
                elif response.is_error:
                    raise UpstreamError(
                        f"upstream returned {response.status_code} for {path}",
                        retryable=False,
                    )
                else:
                    try:
                        return response.json()
                    except ValueError as exc:
                        # A 200 with a body we cannot read is a shape change, not a blip.
                        raise UpstreamError(
                            f"upstream returned non-JSON for {path}", retryable=False
                        ) from exc

            if attempt < self._settings.upstream_retries:
                await asyncio.sleep(
                    min(
                        _RETRY_BASE_DELAY_SECONDS * (2**attempt),
                        _RETRY_MAX_DELAY_SECONDS,
                    )
                )

        raise UpstreamError(
            f"upstream failed for {path} after "
            f"{self._settings.upstream_retries + 1} attempts"
        ) from last_error