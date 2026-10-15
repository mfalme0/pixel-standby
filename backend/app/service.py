"""Orchestration: cache lookup, upstream fetch, stale fallback.

This is the piece with the actual policy in it, so it is worth stating plainly:

1. Anything inside the TTL is served straight from cache. No upstream call, no latency.
2. Anything outside the TTL triggers a fetch.
3. If the fetch succeeds, cache is refreshed and the quote is marked fresh.
4. If the fetch fails but we hold *any* cached value, that value is served with
   `stale: true` and the upstream error is swallowed. A bedside display showing a slightly
   old price is strictly better than one showing an error or a blank panel.
5. If the fetch fails and we hold nothing, the symbol is omitted. There is nothing truthful
   to return, and inventing a zero would be worse than a gap.
"""

from __future__ import annotations

import logging
import time
from collections.abc import Sequence
from datetime import datetime, timezone

from .adapters.base import QuoteProvider, UpstreamError, normalize_symbol
from .cache import Clock, TTLCache
from .config import Settings
from .models import Quote, QuoteResponse

logger = logging.getLogger(__name__)


class QuoteService:
    def __init__(
        self,
        settings: Settings,
        providers: Sequence[QuoteProvider],
        clock: Clock = time.monotonic,
    ) -> None:
        self._settings = settings
        self._providers = list(providers)
        self._clock = clock
        self._cache: TTLCache[Quote] = TTLCache(settings.cache_ttl_seconds, clock)
        self._last_success_wall: datetime | None = None
        self._last_success_mono: float | None = None
        self._last_error: str | None = None

    @property
    def cached_symbol_count(self) -> int:
        return len(self._cache)

    @property
    def last_success_at(self) -> datetime | None:
        return self._last_success_wall

    @property
    def last_error(self) -> str | None:
        return self._last_error

    def health_status(self) -> str:
        if self._last_success_wall is None:
            return "degraded"
        if self._last_error is not None:
            return "degraded"
        return "ok"

    def last_success_age_seconds(self) -> float | None:
        if self._last_success_mono is None:
            return None
        return max(self._clock() - self._last_success_mono, 0.0)

    async def quotes(self, symbols: Sequence[str]) -> list[QuoteResponse]:
        wanted: list[str] = []
        for raw in symbols:
            canonical = normalize_symbol(raw)
            if canonical and canonical not in wanted:
                wanted.append(canonical)
        if not wanted:
            return []

        now = self._clock()

        fresh: dict[str, Quote] = {}
        needs_fetch: list[str] = []
        for symbol in wanted:
            entry = self._cache.get(symbol)
            if entry is not None and entry.is_fresh(now, self._settings.cache_ttl_seconds):
                fresh[symbol] = entry.value
            else:
                needs_fetch.append(symbol)

        if needs_fetch:
            await self._refresh(needs_fetch)

        # Rebuild from the cache after the refresh so that one authoritative read decides
        # both the value and its staleness.
        responses: list[QuoteResponse] = []
        for symbol in wanted:
            entry = self._cache.get(symbol)
            if entry is None:
                continue
            is_fresh = entry.is_fresh(self._clock(), self._settings.cache_ttl_seconds)
            responses.append(
                QuoteResponse.from_quote(entry.value, stale=not is_fresh)
            )
        return responses

    async def _refresh(self, symbols: Sequence[str]) -> None:
        now = self._clock()
        remaining = set(symbols)

        for provider in self._providers:
            if not remaining:
                break
            accepted = [s for s in remaining if provider.supports(s)]
            if not accepted:
                continue

            try:
                fetched = await provider.fetch(accepted)
            except UpstreamError as exc:
                logger.warning(
                    "provider %s failed for %d symbol(s): %s",
                    provider.name,
                    len(accepted),
                    exc,
                )
                self._last_error = f"{provider.name}: {exc}"
                continue
            except Exception as exc:  # noqa: BLE001 - an adapter bug must not kill the dream
                logger.exception("provider %s raised unexpectedly", provider.name)
                self._last_error = f"{provider.name}: {type(exc).__name__}"
                continue

            for symbol, quote in fetched.items():
                self._cache.put(symbol, quote)
                remaining.discard(symbol)

            if fetched:
                self._mark_success(now)
            # Providers decline symbols they cannot quote; the next provider in the list
            # gets them. Anything still missing after the loop stays on its old cached
            # value, which the caller now sees as stale — that is the intended outcome.

        if remaining:
            logger.info(
                "no provider had data for: %s", ",".join(sorted(remaining))
            )

    def _mark_success(self, now: float) -> None:
        self._last_success_wall = datetime.now(timezone.utc)
        self._last_success_mono = now
        self._last_error = None