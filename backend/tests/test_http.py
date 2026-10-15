"""The retrying HTTP client: rate limiting, backoff, and error classification."""

from __future__ import annotations

import httpx
import pytest

from app.adapters.base import UpstreamError
from app.cache import TokenBucket
from app.config import Settings
from app.http import UpstreamClient

from .conftest import FakeClock, RecordingSleeper, TOKEN


def build(
    settings: Settings,
    clock: FakeClock,
    sleeper: RecordingSleeper,
    handler,
) -> UpstreamClient:
    bucket = TokenBucket(
        capacity=settings.rate_limit_capacity,
        window_seconds=settings.rate_limit_window_seconds,
        clock=clock,
        sleep=sleeper,
    )
    return UpstreamClient(
        settings,
        bucket,
        transport=httpx.MockTransport(handler),
    )


async def test_returns_decoded_json(clock, sleeper) -> None:
    async def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"ok": True})

    client = build(
        Settings(rate_limit_capacity=5, rate_limit_window_seconds=60.0, bearer_token=TOKEN),
        clock,
        sleeper,
        handler,
    )

    async with client:
        payload = await client.get("/v8/finance/chart/AAPL", interval="1d")

    assert payload == {"ok": True}


async def test_query_parameters_are_forwarded(clock, sleeper) -> None:
    captured: dict[str, str] = {}

    async def handler(request: httpx.Request) -> httpx.Response:
        captured.update(dict(request.url.params))
        return httpx.Response(200, json={"ok": True})

    client = build(
        Settings(rate_limit_capacity=5, rate_limit_window_seconds=60.0, bearer_token=TOKEN),
        clock,
        sleeper,
        handler,
    )

    async with client:
        await client.get("/v8/finance/chart/AAPL", interval="1d", range="5d")

    assert captured == {"interval": "1d", "range": "5d"}


async def test_api_key_header_is_attached_when_configured(clock, sleeper) -> None:
    captured: dict[str, str] = {}

    async def handler(request: httpx.Request) -> httpx.Response:
        captured.update(dict(request.headers))
        return httpx.Response(200, json={"ok": True})

    client = build(
        Settings(
            rate_limit_capacity=5,
            rate_limit_window_seconds=60.0,
            bearer_token=TOKEN,
            yahoo_api_key="secret-key-value",
        ),
        clock,
        sleeper,
        handler,
    )

    async with client:
        await client.get("/anything")

    assert any(k.lower() == "x-api-key" and v == "secret-key-value" for k, v in captured.items())


async def test_rate_limiter_blocks_the_overflow_request(clock, sleeper) -> None:
    # One token, refilling slowly. The third call has to wait.
    call_count = 0

    async def handler(request: httpx.Request) -> httpx.Response:
        nonlocal call_count
        call_count += 1
        return httpx.Response(200, json={"ok": True})

    client = build(
        Settings(rate_limit_capacity=1, rate_limit_window_seconds=10.0, upstream_retries=0, bearer_token=TOKEN),
        clock,
        sleeper,
        handler,
    )

    async with client:
        await client.get("/a")
        await client.get("/b")
        await client.get("/c")

    assert call_count == 3
    assert sleeper.slept, "the third request should have waited for a token"


@pytest.mark.parametrize("status", [429, 500, 502, 503, 504])
async def test_transient_statuses_are_retried_then_raise(clock, sleeper, status) -> None:
    call_count = 0

    async def handler(request: httpx.Request) -> httpx.Response:
        nonlocal call_count
        call_count += 1
        return httpx.Response(status, json={})

    client = build(
        Settings(rate_limit_capacity=20, rate_limit_window_seconds=60.0, upstream_retries=2, bearer_token=TOKEN),
        clock,
        sleeper,
        handler,
    )

    async with client:
        with pytest.raises(UpstreamError):
            await client.get("/v8/finance/chart/AAPL")

    # Initial attempt plus two retries.
    assert call_count == 3


async def test_a_retry_can_succeed(clock, sleeper) -> None:
    responses = [
        httpx.Response(503, json={}),
        httpx.Response(200, json={"recovered": True}),
    ]

    async def handler(request: httpx.Request) -> httpx.Response:
        return responses.pop(0)

    client = build(
        Settings(rate_limit_capacity=20, rate_limit_window_seconds=60.0, upstream_retries=2, bearer_token=TOKEN),
        clock,
        sleeper,
        handler,
    )

    async with client:
        payload = await client.get("/v8/finance/chart/AAPL")

    assert payload == {"recovered": True}


@pytest.mark.parametrize("status", [400, 401, 403, 404])
async def test_client_errors_are_not_retried(clock, sleeper, status) -> None:
    call_count = 0

    async def handler(request: httpx.Request) -> httpx.Response:
        nonlocal call_count
        call_count += 1
        return httpx.Response(status, json={})

    client = build(
        Settings(rate_limit_capacity=20, rate_limit_window_seconds=60.0, upstream_retries=3, bearer_token=TOKEN),
        clock,
        sleeper,
        handler,
    )

    async with client:
        with pytest.raises(UpstreamError):
            await client.get("/v8/finance/chart/AAPL")

    assert call_count == 1, "a 4xx will not fix itself"


async def test_a_404_is_marked_non_retryable(clock, sleeper) -> None:
    async def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(404, json={})

    client = build(Settings(rate_limit_capacity=20, rate_limit_window_seconds=60.0, bearer_token=TOKEN), clock, sleeper, handler)

    async with client:
        with pytest.raises(UpstreamError) as excinfo:
            await client.get("/nope")

    assert excinfo.value.retryable is False


async def test_non_json_200_raises_rather_than_crashing_the_parser(clock, sleeper) -> None:
    async def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, text="<html>maintenance</html>")

    client = build(Settings(rate_limit_capacity=20, rate_limit_window_seconds=60.0, bearer_token=TOKEN), clock, sleeper, handler)

    async with client:
        with pytest.raises(UpstreamError) as excinfo:
            await client.get("/v8/finance/chart/AAPL")

    assert "non-JSON" in str(excinfo.value)


async def test_timeouts_are_retried(clock, sleeper) -> None:
    call_count = 0

    async def handler(request: httpx.Request) -> httpx.Response:
        nonlocal call_count
        call_count += 1
        raise httpx.ReadTimeout("too slow")

    client = build(
        Settings(rate_limit_capacity=20, rate_limit_window_seconds=60.0, upstream_retries=2, bearer_token=TOKEN),
        clock,
        sleeper,
        handler,
    )

    async with client:
        with pytest.raises(UpstreamError):
            await client.get("/v8/finance/chart/AAPL")

    assert call_count == 3


async def test_errors_never_mention_credentials(clock, sleeper) -> None:
    async def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(401, json={"detail": "bad creds"})

    client = build(
        Settings(
            rate_limit_capacity=20,
            rate_limit_window_seconds=60.0,
            upstream_retries=0,
            yahoo_api_key="secret-key-value",
            bearer_token=TOKEN,
        ),
        clock,
        sleeper,
        handler,
    )

    async with client:
        with pytest.raises(UpstreamError) as excinfo:
            await client.get("/v8/finance/chart/AAPL")

    assert "secret-key-value" not in str(excinfo.value)


def test_sleeper_stub_is_recording(clock, sleeper) -> None:
    assert isinstance(sleeper, RecordingSleeper)