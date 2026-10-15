"""Wire models shared by the adapters, the cache, and the HTTP layer."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone

from pydantic import BaseModel, Field


@dataclass(frozen=True, slots=True)
class Quote:
    """A single instrument's last price.

    `as_of` is the instant the *upstream* reported for the price, not the instant we
    fetched it, because a free tier quote is delayed and the app shows that delay to the
    user.
    """

    symbol: str
    price: float
    change: float
    change_percent: float
    currency: str
    as_of: datetime
    source: str

    @property
    def is_up(self) -> bool:
        return self.change > 0


class QuoteResponse(BaseModel):
    """Shape returned by GET /api/quotes."""

    symbol: str = Field(examples=["AAPL"])
    price: float = Field(examples=[231.44])
    change: float = Field(examples=[1.82])
    changePercent: float = Field(examples=[0.79])
    currency: str = Field(examples=["USD"])
    asOf: datetime = Field(examples=["2026-10-15T20:15:00Z"])
    source: str = Field(examples=["yahoo"])
    stale: bool = Field(
        description=(
            "True when the value was served from cache after the upstream fetch failed, or "
            "when the cached value has outlived the TTL."
        )
    )

    @classmethod
    def from_quote(cls, quote: Quote, *, stale: bool) -> "QuoteResponse":
        return cls(
            symbol=quote.symbol,
            price=quote.price,
            change=quote.change,
            changePercent=quote.change_percent,
            currency=quote.currency,
            asOf=quote.as_of.astimezone(timezone.utc),
            source=quote.source,
            stale=stale,
        )


class HealthResponse(BaseModel):
    """Shape returned by GET /api/health."""

    status: str = Field(examples=["ok", "degraded"])
    lastSuccessAt: datetime | None = Field(
        default=None,
        description="When an upstream fetch last succeeded. Null if it never has.",
        examples=["2026-10-15T20:15:00Z"],
    )
    lastSuccessAgeSeconds: float | None = Field(
        default=None,
        description="Seconds since that fetch. Null if there has never been one.",
        examples=[4.2],
    )
    cacheTtlSeconds: float = Field(examples=[60.0])
    cachedSymbols: int = Field(examples=[6])
    upstreamBaseUrl: str = Field(examples=["https://query1.finance.yahoo.com"])
    version: str = Field(examples=["0.1.0"])


class ErrorResponse(BaseModel):
    detail: str