"""Provider adapters.

Adding a vendor means adding a module here that subclasses `QuoteProvider`, then listing it
in `main.build_service`. Nothing above this package changes.
"""

from .base import QuoteProvider, SymbolNotSupported, UpstreamError, normalize_symbol
from .yahoo import YahooGoldAdapter, YahooStockAdapter

__all__ = [
    "QuoteProvider",
    "SymbolNotSupported",
    "UpstreamError",
    "normalize_symbol",
    "YahooGoldAdapter",
    "YahooStockAdapter",
]