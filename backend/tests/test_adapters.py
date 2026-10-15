"""Adapter parsing.

The Yahoo endpoint is unofficial and can change shape without notice, so most of these
tests are about what happens when the payload is *not* what we expect: the parser must
raise so the caller serves stale data, never return a wrong number.
"""

from __future__ import annotations

from datetime import datetime, timezone

import pytest

from app.adapters.yahoo import (
    GOLD_SYMBOL,
    YahooGoldAdapter,
    YahooStockAdapter,
)


def make_adapter(client, adapter_cls=YahooStockAdapter):
    return adapter_cls(client)


async def test_parses_a_normal_stock_payload(mock_transport_factory, yahoo_chart_payload):
    handler_calls = []

    def handler(request):
        handler_calls.append(request.url.path)
        return yahoo_chart_payload

    async with mock_transport_factory(handler) as client:
        quote = await make_adapter(client)._fetch_one("AAPL")

    assert handler_calls == ["/v8/finance/chart/AAPL"]
    assert quote.symbol == "AAPL"
    assert quote.price == pytest.approx(231.44)
    assert quote.change == pytest.approx(1.82)
    assert quote.change_percent == pytest.approx(0.7929, abs=1e-3)
    assert quote.currency == "USD"
    assert quote.source == "yahoo"
    assert quote.as_of == datetime.fromtimestamp(1_759_988_100, tz=timezone.utc)


async def test_change_is_computed_against_the_previous_close(
    mock_transport_factory, yahoo_chart_payload
):
    payload = yahoo_chart_payload
    payload["chart"]["result"][0]["meta"]["regularMarketPrice"] = 100.0
    payload["chart"]["result"][0]["meta"]["chartPreviousClose"] = 110.0

    async with mock_transport_factory(lambda r: payload) as client:
        quote = await make_adapter(client)._fetch_one("AAPL")

    assert quote.change == pytest.approx(-10.0)
    assert quote.change_percent == pytest.approx(-9.0909, abs=1e-3)
    assert quote.is_up is False


async def test_falls_back_to_previous_close_field(
    mock_transport_factory, yahoo_chart_payload
):
    meta = yahoo_chart_payload["chart"]["result"][0]["meta"]
    del meta["chartPreviousClose"]

    async with mock_transport_factory(lambda r: yahoo_chart_payload) as client:
        quote = await make_adapter(client)._fetch_one("AAPL")

    assert quote.change == pytest.approx(231.44 - 229.62)


async def test_falls_back_to_the_last_close_in_the_series(
    mock_transport_factory, yahoo_chart_payload
):
    meta = yahoo_chart_payload["chart"]["result"][0]["meta"]
    del meta["chartPreviousClose"]
    del meta["previousClose"]
    # Series ends with a null, which is normal for a live candle.
    yahoo_chart_payload["chart"]["result"][0]["indicators"]["quote"][0]["close"] = [
        230.10,
        None,
        None,
    ]

    async with mock_transport_factory(lambda r: yahoo_chart_payload) as client:
        quote = await make_adapter(client)._fetch_one("AAPL")

    assert quote.change == pytest.approx(1.34)


async def test_accepts_numeric_strings(mock_transport_factory, yahoo_chart_payload):
    meta = yahoo_chart_payload["chart"]["result"][0]["meta"]
    meta["regularMarketPrice"] = "231.44"
    meta["chartPreviousClose"] = "229.62"

    async with mock_transport_factory(lambda r: yahoo_chart_payload) as client:
        quote = await make_adapter(client)._fetch_one("AAPL")

    assert quote.price == pytest.approx(231.44)


async def test_missing_timestamp_falls_back_to_the_series(
    mock_transport_factory, yahoo_chart_payload
):
    del yahoo_chart_payload["chart"]["result"][0]["meta"]["regularMarketTime"]

    async with mock_transport_factory(lambda r: yahoo_chart_payload) as client:
        quote = await make_adapter(client)._fetch_one("AAPL")

    assert quote.as_of == datetime.fromtimestamp(1_759_988_100, tz=timezone.utc)


async def test_upstream_not_found_becomes_symbol_not_supported(
    mock_transport_factory,
):
    payload = {
        "chart": {
            "result": None,
            "error": {"code": "Not Found", "description": "No data found, symbol may be delisted"},
        }
    }
    async with mock_transport_factory(lambda r: payload) as client:
        with pytest.raises(Exception) as excinfo:
            await make_adapter(client)._fetch_one("ZZZZ")

    assert excinfo.value.retryable is False


async def test_other_upstream_errors_are_retryable(mock_transport_factory):
    payload = {"chart": {"result": None, "error": {"code": "Server Error", "description": "oops"}}}
    async with mock_transport_factory(lambda r: payload) as client:
        with pytest.raises(Exception) as excinfo:
            await make_adapter(client)._fetch_one("AAPL")

    assert excinfo.value.retryable is True


@pytest.mark.parametrize(
    "payload",
    [
        {},
        {"chart": None},
        {"chart": {"result": []}},
        {"chart": {"result": [{}]}},
        {"chart": {"result": [{"meta": {}}]}},
        {"chart": {"result": [{"meta": {"regularMarketPrice": 1.0}}]}},
        {"chart": {"result": [{"meta": {"regularMarketPrice": 1.0, "chartPreviousClose": 0}}]}},
        {"chart": {"result": [{"meta": {"regularMarketPrice": None}}]}},
        {"chart": {"result": ["not-a-dict"]}},
    ],
    ids=[
        "empty",
        "chart-null",
        "no-results",
        "no-meta",
        "no-price",
        "no-previous-close",
        "zero-previous-close",
        "null-price",
        "result-not-a-dict",
    ],
)
async def test_malformed_payloads_raise_rather_than_return_garbage(
    mock_transport_factory, payload
):
    async with mock_transport_factory(lambda r: payload) as client:
        with pytest.raises(Exception):
            await make_adapter(client)._fetch_one("AAPL")


async def test_fetch_omits_unknown_symbols_instead_of_failing_the_batch(
    mock_transport_factory, yahoo_chart_payload
):
    def handler(request):
        if "ZZZZ" in request.url.path:
            return {
                "chart": {
                    "result": None,
                    "error": {"code": "Not Found", "description": "no data"},
                }
            }
        return yahoo_chart_payload

    async with mock_transport_factory(handler) as client:
        quotes = await make_adapter(client).fetch(["AAPL", "ZZZZ"])

    assert set(quotes) == {"AAPL"}


# --- gold ---------------------------------------------------------------------


async def test_gold_maps_xauusd_onto_yahoos_symbol(mock_transport_factory, yahoo_chart_payload):
    paths = []

    def handler(request):
        paths.append(request.url.path)
        return yahoo_chart_payload

    async with mock_transport_factory(handler) as client:
        quote = await YahooGoldAdapter(client)._fetch_one("XAUUSD")

    assert paths == [f"/v8/finance/chart/{GOLD_SYMBOL}"]
    assert quote.symbol == "XAUUSD"
    assert quote.currency == "USD"
    assert quote.source == "yahoo-gold"


async def test_gold_rounds_to_cents(mock_transport_factory, yahoo_chart_payload):
    yahoo_chart_payload["chart"]["result"][0]["meta"]["regularMarketPrice"] = 2412.3849
    yahoo_chart_payload["chart"]["result"][0]["meta"]["chartPreviousClose"] = 2400.0

    async with mock_transport_factory(lambda r: yahoo_chart_payload) as client:
        quote = await YahooGoldAdapter(client)._fetch_one("XAUUSD")

    assert quote.price == pytest.approx(2412.38)
    assert quote.change == pytest.approx(12.38)


def test_gold_adapter_only_accepts_xauusd() -> None:
    adapter = YahooGoldAdapter(client=None)  # type: ignore[arg-type]
    assert adapter.supports("XAUUSD") is True
    assert adapter.supports("AAPL") is False


def test_stock_adapter_declines_the_gold_symbol() -> None:
    adapter = YahooStockAdapter(client=None)  # type: ignore[arg-type]
    assert adapter.supports("AAPL") is True
    assert adapter.supports("XAUUSD=X") is False


def test_symbols_are_normalised_before_dispatch() -> None:
    adapter = YahooStockAdapter(client=None)  # type: ignore[arg-type]
    assert adapter.supports("  aapl ") is True