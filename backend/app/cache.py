"""A TTL cache and the token-bucket rate limiter that fronts the upstream.

Both take an injected clock, so the tests drive them deterministically instead of sleeping.
"""

from __future__ import annotations

import asyncio
from collections.abc import Callable, Iterator
from dataclasses import dataclass
from typing import Generic, TypeVar

from .models import Quote

T = TypeVar("T")

Clock = Callable[[], float]


@dataclass(frozen=True, slots=True)
class CacheEntry(Generic[T]):
    value: T
    stored_at: float

    def age(self, now: float) -> float:
        return now - self.stored_at

    def is_fresh(self, now: float, ttl_seconds: float) -> bool:
        return self.age(now) <= ttl_seconds


class TTLCache(Generic[T]):
    """Per-key cache with a shared TTL.

    Expired entries are kept rather than dropped: they are exactly what the stale fallback
    serves when the upstream is down, and throwing them away would defeat the point.
    """

    def __init__(self, ttl_seconds: float, clock: Clock) -> None:
        if ttl_seconds <= 0:
            raise ValueError("ttl_seconds must be positive")
        self._ttl = ttl_seconds
        self._clock = clock
        self._entries: dict[str, CacheEntry[T]] = {}

    @property
    def ttl_seconds(self) -> float:
        return self._ttl

    def get(self, key: str) -> CacheEntry[T] | None:
        return self._entries.get(key)

    def get_fresh(self, key: str) -> CacheEntry[Quote] | None:
        entry = self._entries.get(key)
        if entry is None:
            return None
        if not entry.is_fresh(self._clock(), self._ttl):
            return None
        return entry

    def put(self, key: str, value: T) -> CacheEntry[T]:
        entry = CacheEntry(value=value, stored_at=self._clock())
        self._entries[key] = entry
        return entry

    def age_of(self, key: str) -> float | None:
        entry = self._entries.get(key)
        return None if entry is None else entry.age(self._clock())

    def __len__(self) -> int:
        return len(self._entries)

    def __contains__(self, key: object) -> bool:
        return key in self._entries

    def clear(self) -> None:
        self._entries.clear()


class TokenBucket:
    """Async token bucket.

    Upstream providers publish a request budget, and blowing past it gets the whole client
    throttled or blocked. Requests wait here until a token is available rather than firing
    and eating a 429, because a 429 costs a full round trip and pollutes the cache with
    failures.

    `acquire()` returns immediately if a token is free, so the common case adds no latency
    and no lock contention.
    """

    def __init__(
        self,
        capacity: int,
        window_seconds: float,
        clock: Clock,
        *,
        sleep: Callable[[float], object] | None = None,
    ) -> None:
        if capacity <= 0:
            raise ValueError("capacity must be positive")
        if window_seconds <= 0:
            raise ValueError("window_seconds must be positive")
        self._capacity = float(capacity)
        self._window = window_seconds
        self._clock = clock
        self._rate = self._capacity / window_seconds  # tokens per second
        self._tokens = self._capacity
        self._updated_at = clock()
        self._lock = asyncio.Lock()
        self._sleep = sleep if sleep is not None else asyncio.sleep

    @property
    def capacity(self) -> float:
        return self._capacity

    @property
    def rate_per_second(self) -> float:
        return self._rate

    def _refill(self) -> None:
        now = self._clock()
        elapsed = now - self._updated_at
        if elapsed <= 0:
            return
        self._tokens = min(self._capacity, self._tokens + elapsed * self._rate)
        self._updated_at = now

    def _try_consume(self, tokens: float = 1.0) -> bool:
        self._refill()
        if self._tokens >= tokens:
            self._tokens -= tokens
            return True
        return False

    def peek(self) -> float:
        """Current token count, refilled to now. For tests and health output."""
        self._refill()
        return self._tokens

    async def acquire(self, tokens: float = 1.0) -> None:
        """Block until `tokens` are available."""
        if tokens > self._capacity:
            raise ValueError("cannot acquire more than a full bucket")
        while True:
            async with self._lock:
                if self._try_consume(tokens):
                    return
                # Wait for just one token, then loop and re-check.
                deficit = 1.0 - self._tokens
                wait_seconds = max(deficit / self._rate, 0.0)
            await self._sleep(wait_seconds)


def iter_quotes(entries: Iterator[CacheEntry[Quote]]) -> Iterator[Quote]:
    """Convenience for tests and debugging."""
    for entry in entries:
        yield entry.value