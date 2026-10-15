"""Provider adapter interface.

The rest of the service only ever sees a [QuoteProvider]. Adding a keyed vendor later means
adding a class in this package and registering it; nothing above this line changes.
"""

from __future__ import annotations

import abc
from collections.abc import Sequence

from ..models import Quote


class UpstreamError(RuntimeError):
    """Any failure talking to or parsing an upstream provider.

    Carries no credentials and no request headers, so it is safe to log.
    """

    def __init__(self, message: str, *, symbol: str | None = None, retryable: bool = True):
        super().__init__(message)
        self.symbol = symbol
        self.retryable = retryable


class SymbolNotSupported(UpstreamError):
    """The provider does not know this symbol. Not worth retrying."""

    def __init__(self, symbol: str, message: str | None = None) -> None:
        super().__init__(
            message or f"symbol not supported by provider: {symbol}",
            symbol=symbol,
            retryable=False,
        )


class QuoteProvider(abc.ABC):
    """One upstream source of last prices."""

    #: Short identifier reported to the client in `QuoteResponse.source`.
    name: str

    @abc.abstractmethod
    def supports(self, symbol: str) -> bool:
        """True if this provider is willing to quote `symbol`."""

    @abc.abstractmethod
    async def fetch(self, symbols: Sequence[str]) -> dict[str, Quote]:
        """Return a quote per requested symbol.

        Symbols the provider cannot resolve are simply absent from the result. Raising is
        reserved for whole-upstream failures, which the service treats as "fall back to
        cache" rather than "this one symbol is bad".
        """


def normalize_symbol(symbol: str) -> str:
    """Canonical form used as the cache key.

    Yahoo is case-insensitive but `aapl` and `AAPL` must not occupy two cache slots, and the
    app should not have to care.
    """
    return symbol.strip().upper()