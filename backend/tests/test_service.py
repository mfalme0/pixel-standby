"""Service orchestration: cache policy and the stale fallback.

This is where the "works offline" promise is actually kept, so the tests are written around
what the phone sees rather than around internal calls.
"""

from __future__ import annotations

import pytest

from app.adapters.base import UpstreamError
from app.service import QuoteService

from .conftest import FakeClock, StubProvider, make_quote


def build(settings, clock, providers) -> QuoteService:
    return QuoteService(settings, providers, clock=clock)


async def test_fresh_cache_avoids_the_upstream_entirely(settings, clock) -> None:
    provider = StubProvider(quotes={"AAPL": make_quote("AAPL", price=100.0)})
    service = build(settings, clock, [provider])

    first = await service.quotes(["AAPL"])
    assert provider.calls == [["AAPL"]]
    assert first[0].stale is False

    clock.advance(30)  # still inside the 60s TTL
    second = await service.quotes(["AAPL"])

    assert provider.calls == [["AAPL"]], "upstream must not be called inside the TTL"
    assert second[0].price == first[0].price
    assert second[0].stale is False


async def test_expiry_triggers_a_refresh(settings, clock) -> None:
    provider = StubProvider(quotes={"AAPL": make_quote("AAPL", price=100.0)})
    service = build(settings, clock, [provider])

    await service.quotes(["AAPL"])
    clock.advance(61)
    provider.quotes["AAPL"] = make_quote("AAPL", price=105.0)

    refreshed = await service.quotes(["AAPL"])

    assert len(provider.calls) == 2
    assert refreshed[0].price == pytest.approx(105.0)
    assert refreshed[0].stale is False


async def test_upstream_failure_serves_the_cached_value_as_stale(settings, clock) -> None:
    provider = StubProvider(quotes={"AAPL": make_quote("AAPL", price=100.0)})
    service = build(settings, clock, [provider])

    await service.quotes(["AAPL"])
    clock.advance(3600)
    provider.fail_with = UpstreamError("upstream exploded")

    degraded = await service.quotes(["AAPL"])

    assert len(degraded) == 1
    assert degraded[0].symbol == "AAPL"
    assert degraded[0].price == pytest.approx(100.0), "last known price must survive"
    assert degraded[0].stale is True, "caller has to know it is not fresh"
    assert degraded[0].asOf is not None, "the app renders a timestamp from this"


async def test_a_very_old_cached_value_is_still_better_than_nothing(
    settings, clock
) -> None:
    provider = StubProvider(quotes={"AAPL": make_quote("AAPL", price=100.0)})
    service = build(settings, clock, [provider])

    await service.quotes(["AAPL"])
    clock.advance(60 * 60 * 24)  # a day
    provider.fail_with = UpstreamError("still down")

    degraded = await service.quotes(["AAPL"])

    assert degraded[0].stale is True
    assert degraded[0].price == pytest.approx(100.0)


async def test_failure_with_no_cache_omits_the_symbol(settings, clock) -> None:
    provider = StubProvider(fail_with=UpstreamError("never worked"))
    service = build(settings, clock, [provider])

    result = await service.quotes(["AAPL", "MSFT"])

    assert result == [], "no cached value means no truthful answer"


async def test_partial_upstream_failure_leaves_the_other_symbols_fresh(
    settings, clock
) -> None:
    provider = StubProvider(quotes={"AAPL": make_quote("AAPL", price=100.0)})
    service = build(settings, clock, [provider])

    await service.quotes(["AAPL", "MSFT"])
    clock.advance(120)
    provider.fail_with = UpstreamError("down")

    result = {q.symbol: q for q in await service.quotes(["AAPL", "MSFT"])}

    assert set(result) == {"AAPL"}
    assert result["AAPL"].stale is True


async def test_an_adapter_bug_does_not_take_the_service_down(settings, clock) -> None:
    provider = StubProvider(fail_with=RuntimeError("adapter bug"))
    service = build(settings, clock, [provider])

    result = await service.quotes(["AAPL"])

    assert result == []
    assert service.last_error is not None
    assert "RuntimeError" in service.last_error


async def test_the_second_provider_takes_the_symbols_the_first_declines(
    settings, clock
) -> None:
    class GoldOnly(StubProvider):
        def supports(self, symbol: str) -> bool:
            return symbol == "XAUUSD"

    stocks = StubProvider(
        name="stocks",
        quotes={"AAPL": make_quote("AAPL", price=100.0)},
        supports_all=False,
    )
    gold = GoldOnly(name="gold", quotes={"XAUUSD": make_quote("XAUUSD", price=2412.0)})
    service = build(settings, clock, [stocks, gold])

    result = {q.symbol: q for q in await service.quotes(["AAPL", "XAUUSD"])}

    assert stocks.calls == [["AAPL"]]
    assert gold.calls == [["XAUUSD"]]
    assert result["XAUUSD"].price == pytest.approx(2412.0)


async def test_a_failing_first_provider_falls_through_to_the_next(
    settings, clock
) -> None:
    stocks = StubProvider(name="stocks", fail_with=UpstreamError("429"))
    gold = StubProvider(name="gold", quotes={"XAUUSD": make_quote("XAUUSD", price=2412.0)})
    service = build(settings, clock, [stocks, gold])

    result = await service.quotes(["XAUUSD"])

    assert result[0].symbol == "XAUUSD"
    assert result[0].price == pytest.approx(2412.0)


async def test_symbols_are_normalised_and_deduplicated(settings, clock) -> None:
    provider = StubProvider(
        quotes={"AAPL": make_quote("AAPL")},
        supports_all=False,
    )
    service = build(settings, clock, [provider])

    result = await service.quotes(["aapl", " AAPL ", "Msft"])

    assert [q.symbol for q in result] == ["AAPL"]
    # "Msft" is not supported by the provider, so it is never dispatched.
    assert provider.calls == [["AAPL"]]


async def test_empty_input_returns_nothing_without_touching_upstream(
    settings, clock
) -> None:
    provider = StubProvider()
    service = build(settings, clock, [provider])

    assert await service.quotes([]) == []
    assert provider.calls == []


# --- health -------------------------------------------------------------------


async def test_health_starts_degraded_and_recovers(settings, clock) -> None:
    provider = StubProvider(
        quotes={"AAPL": make_quote("AAPL")},
        supports_all=False,
    )
    service = build(settings, clock, [provider])

    assert service.health_status() == "degraded"
    assert service.last_success_at is None
    assert service.last_success_age_seconds() is None

    await service.quotes(["AAPL"])
    assert service.health_status() == "ok"
    assert service.last_success_at is not None
    assert service.last_success_age_seconds() == pytest.approx(0.0)


async def test_health_reports_degraded_while_the_upstream_is_failing(
    settings, clock
) -> None:
    provider = StubProvider(
        quotes={"AAPL": make_quote("AAPL")},
        supports_all=False,
    )
    service = build(settings, clock, [provider])

    await service.quotes(["AAPL"])
    clock.advance(120)
    provider.fail_with = UpstreamError("down")

    await service.quotes(["AAPL"])

    assert service.health_status() == "degraded"
    assert service.last_error is not None
    assert service.last_success_age_seconds() == pytest.approx(120.0)


async def test_recovery_clears_the_error(settings, clock) -> None:
    provider = StubProvider(
        quotes={"AAPL": make_quote("AAPL")},
        supports_all=False,
    )
    service = build(settings, clock, [provider])

    await service.quotes(["AAPL"])
    clock.advance(120)
    provider.fail_with = UpstreamError("blip")
    await service.quotes(["AAPL"])
    assert service.health_status() == "degraded"

    clock.advance(120)
    provider.fail_with = None
    await service.quotes(["AAPL"])

    assert service.health_status() == "ok"
    assert service.last_error is None


async def test_cached_symbol_count_is_visible(settings, clock) -> None:
    provider = StubProvider(
        quotes={"AAPL": make_quote("AAPL"), "MSFT": make_quote("MSFT")}
    )
    service = build(settings, clock, [provider])

    assert service.cached_symbol_count == 0
    await service.quotes(["AAPL", "MSFT"])
    assert service.cached_symbol_count == 2