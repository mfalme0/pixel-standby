# API contract

This document describes the backend HTTP API consumed by the Pixel Standby Android app.

Base URL: `https://<backend-host>:8443` (HTTPS terminated by Caddy, forwarded to port 8000).

All endpoints require HTTPS. All state-changing or data endpoints require a bearer token.

## Authentication

`GET /api/quotes` requires:

```
Authorization: Bearer <PIXEL_BEARER_TOKEN>
```

The token is compared in constant time. A missing or invalid token returns `401` with
`WWW-Authenticate: Bearer`. The token is never logged and never appears in error messages.

`GET /api/health` is intentionally unauthenticated so the phone can show a connection
indicator without knowing the bearer token. It exposes no market data or credentials.

## Endpoints

### GET /api/quotes

Returns last prices for a comma-separated list of symbols.

**Query parameters**

| Parameter | Type   | Required | Description                                         |
| --------- | ------ | -------- | --------------------------------------------------- |
| symbols   | string | Yes      | Comma-separated symbols, e.g. `AAPL,MSFT,XAUUSD`    |

**Rules**

- Whitespace around symbols is tolerated: ` AAPL , MSFT ` works.
- Symbols are case-insensitive and deduplicated: `aapl,AAPL` returns one entry.
- Maximum 25 symbols per request (configurable via `MAX_SYMBOLS_PER_REQUEST`).
- Unknown or unsupported symbols are omitted from the response, not faked.

**Response** — array of objects:

| Field         | Type     | Description                                                                   |
| ------------- | -------- | ----------------------------------------------------------------------------- |
| symbol        | string   | Canonical symbol, e.g. `AAPL`                                                 |
| price         | number   | Last price                                                                    |
| change        | number   | `price - previousClose`                                                       |
| changePercent | number   | `(change / previousClose) * 100`                                              |
| currency      | string   | Quote currency, e.g. `USD`. Gold is always `USD`                              |
| asOf          | string   | ISO-8601 timestamp of the upstream quote (UTC, with `Z` suffix)             |
| source        | string   | Provider identifier, e.g. `yahoo` or `yahoo-gold`                             |
| stale         | boolean  | `true` when the value came from cache because the upstream fetch failed       |

The app renders `change` green when positive, red when negative, and shows
`updated N min ago` derived from `asOf`.

### GET /api/health

Returns service health and cache status. Unauthenticated.

**Response:**

| Field                  | Type     | Description                                                                 |
| ---------------------- | -------- | --------------------------------------------------------------------------- |
| status                 | string   | `ok` or `degraded`                                                          |
| lastSuccessAt          \| null | ISO-8601 timestamp of the last successful upstream fetch                    |
| lastSuccessAgeSeconds  \| null | Seconds since that fetch                                                    |
| cacheTtlSeconds        | number   | Configured TTL (60 by default)                                              |
| cachedSymbols          | integer  | Number of symbols currently held in cache                                   |
| upstreamBaseUrl        | string   | The upstream base URL (no credentials)                                      |
| version                | string   | Backend version                                                             |

`status` is `degraded` when no upstream fetch has ever succeeded, or when the most recent
fetch attempt failed.

## Provider adapters

| Adapter       | Provider        | Symbols          | Notes                                               |
| ------------- | --------------- | ---------------- | ----------------------------------------------------- |
| `yahoo`       | Yahoo Finance   | Equities, FX     | Unofficial endpoint; no API key required              |
| `yahoo-gold`  | Yahoo Finance   | `XAUUSD` only    | Spot gold in USD per troy ounce, mapped from `XAUUSD=X` |

Rate limits and delays are documented in `backend/README.md`. The adapter interface is in
`backend/app/adapters/base.py`; adding a vendor means adding a module and registering it in
`backend/app/main.py`.

## Stale fallback

When the upstream fetch fails, the service serves the last known cached value with
`stale: true` and a timestamp from `asOf`. If there is no cached value at all, the symbol
is omitted. The Android app mirrors this: stale prices are shown with their timestamp
rather than blanking the panel.

## Errors

| Status | Meaning                                           |
| ------ | ------------------------------------------------- |
| 400    | Bad request (no symbols, too many symbols)        |
| 401    | Missing or invalid bearer token                   |
| 422    | Missing query parameter                           |
| 503    | Backend is misconfigured (e.g. no bearer token)   |

## Docker

```
docker compose up
```

Requires `PIXEL_BEARER_TOKEN` to be set in the environment. Caddy terminates HTTPS on
port 8443 and forwards to the backend on port 8000.

## Environment variables

| Variable                  | Default                              | Description                          |
| ------------------------- | ------------------------------------ | ------------------------------------ |
| `PIXEL_BEARER_TOKEN`      | *(required)*                         | Bearer token for `/api/quotes`       |
| `UPSTREAM_BASE_URL`       | `https://query1.finance.yahoo.com`   | Upstream provider base URL           |
| `UPSTREAM_TIMEOUT_SECONDS`| `10`                                 | Upstream request timeout             |
| `UPSTREAM_RATE_CAPACITY`  | `30`                                 | Token-bucket capacity                |
| `UPSTREAM_RATE_WINDOW_SECONDS`| `60`                            | Token-bucket refill window (seconds) |
| `CACHE_TTL_SECONDS`       | `60`                                 | Cache TTL (seconds)                  |
| `MAX_SYMBOLS_PER_REQUEST` | `25`                                 | Max symbols per request              |
| `UPSTREAM_RETRIES`        | `2`                                  | Upstream retry attempts              |
| `YAHOO_API_KEY`           | *(none)*                             | Optional key, attached as header     |
| `LOG_LEVEL`               | `INFO`                               | Logging level                        |

## cURL examples

Generate a bearer token:

```powershell
$env:PIXEL_BEARER_TOKEN = "your-secret-token"
```

Health check (no token required):

```bash
curl https://<backend-host>:8443/api/health
```

Quotes (token required):

```bash
curl -H "Authorization: Bearer your-secret-token" \
  "https://<backend-host>:8443/api/quotes?symbols=AAPL,MSFT,XAUUSD"
```

Example response:

```json
[
  {
    "symbol": "AAPL",
    "price": 231.44,
    "change": 1.82,
    "changePercent": 0.79,
    "currency": "USD",
    "asOf": "2026-10-15T20:15:00Z",
    "source": "yahoo",
    "stale": false
  },
  {
    "symbol": "XAUUSD",
    "price": 2412.38,
    "change": 12.38,
    "changePercent": 0.52,
    "currency": "USD",
    "asOf": "2026-10-15T20:15:00Z",
    "source": "yahoo-gold",
    "stale": false
  }
]
```

When the upstream is unreachable, cached values are returned with `"stale": true` and
their original `asOf` timestamp.

## Market data disclaimer

Yahoo Finance is an unofficial endpoint with no SLA. Free-tier data is delayed (at least
15 minutes). This is acceptable for a bedside dashboard; if intraday accuracy is needed,
swap the adapter at `backend/app/adapters/yahoo.py`.