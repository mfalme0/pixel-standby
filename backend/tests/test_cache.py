"""TTL cache behaviour.

The important property is that expired entries are *kept*. They are the entire stale
fallback path, so an implementation that evicted on expiry would quietly break the feature
the bedside display depends on.
"""

from __future__ import annotations

import pytest

from app.cache import TTLCache

from .conftest import FakeClock


def test_put_then_get_fresh(clock: FakeClock) -> None:
    cache: TTLCache[str] = TTLCache(ttl_seconds=60.0, clock=clock)
    cache.put("AAPL", "first")

    entry = cache.get_fresh("AAPL")
    assert entry is not None
    assert entry.value == "first"
    assert entry.age(clock.now) == 0.0


def test_entry_is_fresh_right_up_to_the_ttl(clock: FakeClock) -> None:
    cache: TTLCache[str] = TTLCache(ttl_seconds=60.0, clock=clock)
    cache.put("AAPL", "first")

    clock.advance(60)
    assert cache.get_fresh("AAPL") is not None

    clock.advance(1)
    assert cache.get_fresh("AAPL") is None


def test_expired_entry_is_retained_for_the_stale_path(clock: FakeClock) -> None:
    cache: TTLCache[str] = TTLCache(ttl_seconds=60.0, clock=clock)
    cache.put("AAPL", "first")
    clock.advance(3600)

    assert cache.get_fresh("AAPL") is None
    # Still readable, just not fresh.
    assert cache.get("AAPL") is not None
    assert "AAPL" in cache
    assert len(cache) == 1


def test_put_overwrites_and_refreshes_the_age(clock: FakeClock) -> None:
    cache: TTLCache[str] = TTLCache(ttl_seconds=60.0, clock=clock)
    cache.put("AAPL", "first")
    clock.advance(50)

    cache.put("AAPL", "second")
    entry = cache.get_fresh("AAPL")
    assert entry is not None
    assert entry.value == "second"
    assert entry.age(clock.now) == 0.0
    assert len(cache) == 1


def test_missing_key_returns_none(clock: FakeClock) -> None:
    cache: TTLCache[str] = TTLCache(ttl_seconds=60.0, clock=clock)
    assert cache.get("NOPE") is None
    assert cache.get_fresh("NOPE") is None
    assert cache.age_of("NOPE") is None


def test_keys_are_independent(clock: FakeClock) -> None:
    cache: TTLCache[str] = TTLCache(ttl_seconds=60.0, clock=clock)
    cache.put("AAPL", "a")
    cache.put("MSFT", "m")

    clock.advance(30)
    assert cache.get_fresh("AAPL").value == "a"
    assert cache.get_fresh("MSFT").value == "m"


def test_clear_empties_the_cache(clock: FakeClock) -> None:
    cache: TTLCache[str] = TTLCache(ttl_seconds=60.0, clock=clock)
    cache.put("AAPL", "a")
    cache.clear()
    assert len(cache) == 0
    assert cache.get("AAPL") is None


def test_age_reports_seconds_since_storage(clock: FakeClock) -> None:
    cache: TTLCache[str] = TTLCache(ttl_seconds=60.0, clock=clock)
    cache.put("AAPL", "a")
    clock.advance(12.5)
    assert cache.age_of("AAPL") == 12.5


@pytest.mark.parametrize("ttl", [0, -1, -0.5])
def test_non_positive_ttl_is_rejected(clock: FakeClock, ttl: float) -> None:
    with pytest.raises(ValueError):
        TTLCache(ttl_seconds=ttl, clock=clock)


def test_clock_going_backwards_does_not_break_freshness(clock: FakeClock) -> None:
    cache: TTLCache[str] = TTLCache(ttl_seconds=60.0, clock=clock)
    cache.put("AAPL", "a")
    clock.advance(-10)  # monotonic should never do this, but be forgiving.
    assert cache.get_fresh("AAPL") is not None