"""Adapters for Yahoo Finance.

Yahoo's chart endpoint is unofficial: there is no contract, no SLA, and it has changed shape
before. Two things follow from that and are load-bearing rather than defensive padding:

- the parser walks the payload defensively and raises [UpstreamError] on anything
  unexpected, so a shape change surfaces as "serve stale data" instead of a wrong number on
  a bedside display;
- the stock and metals adapters are separate classes even though they share one HTTP call
  and one parser, so the metals path can diverge (a different unit, a different currency, a
  different vendor) without touching the equities path.

No API key is required. If a deployment does set `YAHOO_API_KEY` it is attached as a header
and never logged.
"""

from __future__ import annotations

import logging

import httpx
from collections.abc import Sequence
from datetime import datetime, timezone
from typing import Any

from ..models import Quote
from .base import QuoteProvider, SymbolNotSupported, UpstreamError, normalize_symbol

logger = logging.getLogger(__name__)

CHART_PATH = "/v8/finance/chart/{symbol}"
USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
)

#: The symbol Yahoo uses for spot gold priced in US dollars.
GOLD_SYMBOL = "XAUUSD=X"

#: Error codes Yahoo returns for "I have never heard of this instrument".
_NOT_FOUND_CODES = {"Not Found", "Quote Not Found For Symbol", "Bad Request"}


class _YahooChartAdapter(QuoteProvider):
    """Shared transport and parsing. Subclasses decide what they are willing to quote."""

    name = "yahoo"

    def __init__(
        self,
        client: Any,
        *,
        interval: str = "1d",
        range_: str = "5d",
    ) -> None:
        self._client = client
        self._interval = interval
        self._range = range_

    def supports(self, symbol: str) -> bool:
        return self.accepts(normalize_symbol(symbol))

    def accepts(self, symbol: str) -> bool:
        raise NotImplementedError

    def to_upstream_symbol(self, symbol: str) -> str:
        """Map a canonical symbol onto whatever this provider calls it."""
        return symbol

    async def fetch(self, symbols: Sequence[str]) -> dict[str, Quote]:
        results: dict[str, Quote] = {}
        for raw in symbols:
            symbol = normalize_symbol(raw)
            if not self.accepts(symbol):
                logger.debug("provider %s declines symbol %s", self.name, symbol)
                continue
            try:
                results[symbol] = await self._fetch_one(symbol)
            except SymbolNotSupported:
                # One bad symbol must not fail the whole request; the app renders a gap.
                logger.info("provider %s has no data for %s", self.name, symbol)
        return results

    async def _fetch_one(self, symbol: str) -> Quote:
        upstream_symbol = self.to_upstream_symbol(symbol)
        payload = await self._get_json(CHART_PATH.format(symbol=upstream_symbol), symbol)

        chart = payload.get("chart")
        if not isinstance(chart, dict):
            raise UpstreamError(
                f"unexpected chart payload for {symbol}", symbol=symbol
            )

        error = chart.get("error")
        if error:
            code = str(error.get("code", ""))
            description = str(error.get("description", "upstream error"))
            if code in _NOT_FOUND_CODES:
                raise SymbolNotSupported(symbol, description)
            raise UpstreamError(
                f"upstream error for {symbol}: {code} {description}", symbol=symbol
            )

        results = chart.get("result")
        if not isinstance(results, list) or not results:
            raise UpstreamError(f"no result for {symbol}", symbol=symbol)

        first = results[0]
        if not isinstance(first, dict):
            raise UpstreamError(f"malformed result for {symbol}", symbol=symbol)

        return self.build_quote(symbol, first)

    async def _get_json(self, path: str, symbol: str) -> dict[str, Any]:
        # UpstreamClient.get() already decodes JSON; a raw httpx.AsyncClient (used in
        # tests) returns a Response that still needs parsing. Accept both.
        try:
            result = await self._client.get(
                path, params={"interval": self._interval, "range": self._range}
            )
        except UpstreamError:
            raise
        except Exception as exc:  # noqa: BLE001 - transport errors are all "upstream down"
            raise UpstreamError(
                f"request failed for {symbol}: {type(exc).__name__}", symbol=symbol
            ) from exc
        if isinstance(result, httpx.Response):
            try:
                return result.json()
            except ValueError as exc:
                raise UpstreamError(
                    f"upstream returned non-JSON for {symbol}", retryable=False
                ) from exc
        return result

    def build_quote(self, symbol: str, result: dict[str, Any]) -> Quote:
        meta = result.get("meta")
        if not isinstance(meta, dict):
            raise UpstreamError(f"missing meta for {symbol}", symbol=symbol)

        price = _as_float(meta.get("regularMarketPrice"))
        if price is None:
            raise UpstreamError(f"no current price for {symbol}", symbol=symbol)

        previous = _first_of(
            meta.get("chartPreviousClose"),
            meta.get("previousClose"),
            _last_close(result),
        )
        if previous is None or previous == 0:
            raise UpstreamError(f"no previous close for {symbol}", symbol=symbol)

        change = price - previous
        as_of = _as_epoch(meta.get("regularMarketTime")) or _last_timestamp(result)
        if as_of is None:
            # No upstream timestamp is better than a fabricated one, but the app shows
            # "updated N min ago", so fall back to now and let the caller's staleness flag
            # carry the honesty.
            as_of = datetime.now(timezone.utc)

        currency = str(meta.get("currency") or "USD").upper()

        return Quote(
            symbol=symbol,
            price=round(price, 6),
            change=round(change, 6),
            change_percent=round(change / previous * 100.0, 4),
            currency=currency,
            as_of=as_of,
            source=self.name,
        )


class YahooStockAdapter(_YahooChartAdapter):
    """Equities. Anything that is not the gold pair is treated as a ticker."""

    name = "yahoo"

    def accepts(self, symbol: str) -> bool:
        if symbol == GOLD_SYMBOL:
            return False
        # Yahoo suffix conventions: =X pairs, ^ indices, -USD crypto/fx pairs.
        return bool(symbol) and not symbol.startswith("^") and len(symbol) <= 32


class YahooGoldAdapter(_YahooChartAdapter):
    """Spot gold priced in USD (XAU/USD).

    Deliberately narrow: one symbol, USD-denominated, reported to the client as `XAUUSD` so
    the app does not have to know Yahoo's `=X` suffix convention.
    """

    name = "yahoo-gold"

    def accepts(self, symbol: str) -> bool:
        return symbol == "XAUUSD"

    def to_upstream_symbol(self, symbol: str) -> str:
        return GOLD_SYMBOL

    def build_quote(self, symbol: str, result: dict[str, Any]) -> Quote:
        quote = super().build_quote(symbol, result)
        # The whole point of this adapter is that the client sees USD per troy ounce.
        return Quote(
            symbol=symbol,
            price=round(quote.price, 2),
            change=round(quote.change, 2),
            change_percent=round(quote.change_percent, 4),
            currency="USD",
            as_of=quote.as_of,
            source=self.name,
        )


def _as_float(value: Any) -> float | None:
    if isinstance(value, bool) or value is None:
        return None
    if isinstance(value, (int, float)):
        return float(value)
    if isinstance(value, str):
        try:
            return float(value)
        except ValueError:
            return None
    return None


def _first_of(*values: Any) -> float | None:
    for value in values:
        parsed = _as_float(value)
        if parsed is not None:
            return parsed
    return None


def _closes(result: dict[str, Any]) -> list[Any]:
    indicators = result.get("indicators")
    if not isinstance(indicators, dict):
        return []
    quote_blocks = indicators.get("quote")
    if not isinstance(quote_blocks, list) or not quote_blocks:
        return []
    first_block = quote_blocks[0]
    if not isinstance(first_block, dict):
        return []
    closes = first_block.get("close")
    return closes if isinstance(closes, list) else []


def _last_close(result: dict[str, Any]) -> float | None:
    """Last non-null close. Used when Yahoo omits chartPreviousClose."""
    for value in reversed(_closes(result)):
        parsed = _as_float(value)
        if parsed is not None:
            return parsed
    return None


def _last_timestamp(result: dict[str, Any]) -> datetime | None:
    timestamps = result.get("timestamp")
    if not isinstance(timestamps, list):
        return None
    for value in reversed(timestamps):
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            return datetime.fromtimestamp(float(value), tz=timezone.utc)
    return None


def _as_epoch(value: Any) -> datetime | None:
    if isinstance(value, bool) or value is None:
        return None
    if isinstance(value, (int, float)):
        try:
            return datetime.fromtimestamp(float(value), tz=timezone.utc)
        except (OverflowError, OSError, ValueError):
            return None
    return None