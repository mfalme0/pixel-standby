"""Shared fixtures.

Two rules the whole suite follows:

- no test ever touches the network. Upstream is always an `httpx.MockTransport`;
- no test ever sleeps. Both the cache and the rate limiter take an injected clock, and the
  limiter's sleep is a recording stub.
"""

from __future__ import annotations

from collections.abc import Iterator
from dataclasses import dataclass, field

import httpx
import pytest

from app.adapters.base import QuoteProvider, UpstreamError
from app.config import Settings
from app.models import Quote

TOKEN = "test-token-not-a-real-secret"


class FakeClock:
    """Manually advanced monotonic clock."""

    def __init__(self, start: float = 1_000.0) -> None:
        self.now = start

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


class RecordingSleeper:
    """Stands in for asyncio.sleep so rate limiter waits are instantaneous."""

    def __init__(self, clock: FakeClock) -> None:
        self._clock = clock
        self.slept: list[float] = []

    async def __call__(self, seconds: float) -> None:
        self.slept.append(seconds)
        self._clock.advance(seconds)


@dataclass
class StubProvider(QuoteProvider):
    """A provider whose responses the test dictates."""

    name: str = "stub"
    quotes: dict[str, Quote] = field(default_factory=dict)
    fail_with: Exception | None = None
    calls: list[list[str]] = field(default_factory=list)
    supports_all: bool = True

    def supports(self, symbol: str) -> bool:
        if self.supports_all:
            return True
        return symbol in self.quotes

    async def fetch(self, symbols):
        self.calls.append(list(symbols))
        if self.fail_with is not None:
            raise self.fail_with
        return {s: self.quotes[s] for s in symbols if s in self.quotes}


def make_quote(
    symbol: str = "AAPL",
    price: float = 100.0,
    previous: float = 100.0,
    source: str = "stub",
) -> Quote:
    from datetime import datetime, timezone

    change = price - previous
    return Quote(
        symbol=symbol,
        price=price,
        change=change,
        change_percent=(change / previous * 100.0) if previous else 0.0,
        currency="USD",
        as_of=datetime(2026, 10, 15, 20, 0, tzinfo=timezone.utc),
        source=source,
    )


@pytest.fixture
def clock() -> FakeClock:
    return FakeClock()


@pytest.fixture
def sleeper(clock: FakeClock) -> RecordingSleeper:
    return RecordingSleeper(clock)


@pytest.fixture
def settings() -> Settings:
    return Settings(
        bearer_token=TOKEN,
        upstream_base_url="https://upstream.test",
        upstream_timeout_seconds=1.0,
        rate_limit_capacity=5,
        rate_limit_window_seconds=60.0,
        cache_ttl_seconds=60.0,
        max_symbols_per_request=10,
        upstream_retries=1,
    )


@pytest.fixture
def yahoo_chart_payload() -> dict:
    """A realistic Yahoo chart response, trimmed but structurally faithful."""
    return {
        "chart": {
            "result": [
                {
                    "meta": {
                        "currency": "USD",
                        "symbol": "AAPL",
                        "regularMarketPrice": 231.44,
                        "chartPreviousClose": 229.62,
                        "previousClose": 229.62,
                        "regularMarketTime": 1_759_988_100,
                        "exchangeTimezoneName": "America/New_York",
                        "instrumentType": "EQUITY",
                    },
                    "timestamp": [1_759_930_000, 1_759_988_100],
                    "indicators": {
                        "quote": [{"close": [230.10, 231.44, None]}],
                    },
                }
            ],
            "error": None,
        }
    }


@pytest.fixture
def mock_transport_factory():
    """Builds an AsyncClient backed by a routing stub.

    Handlers may return either an httpx.Response or a plain dict (converted to a 200 JSON
    response), because httpx.MockTransport awaits its handler's return value.
    """

    def build(handler) -> httpx.AsyncClient:
        def wrapped(request: httpx.Request):
            result = handler(request)
            if isinstance(result, dict):
                return httpx.Response(200, json=result)
            return result

        return httpx.AsyncClient(
            base_url="https://upstream.test",
            transport=httpx.MockTransport(wrapped),
        )

    return build


@pytest.fixture
def recording_handler():
    """A handler that records requests and replays canned responses."""

    class Recorder:
        def __init__(self) -> None:
            self.requests: list[httpx.Request] = []
            self.responses: list[httpx.Response | Exception] = []
            self.default: httpx.Response | Exception = httpx.Response(200, json={})

        def __call__(self, request: httpx.Request) -> httpx.Response:
            self.requests.append(request)
            if self.responses:
                nxt = self.responses.pop(0)
            else:
                nxt = self.default
            if isinstance(nxt, Exception):
                raise nxt
            return httpx.Response(
                nxt.status_code,
                json=nxt.json() if nxt.content else {},
                headers=nxt.headers,
            )

        @property
        def paths(self) -> list[str]:
            return [r.url.path for r in self.requests]

    return Recorder