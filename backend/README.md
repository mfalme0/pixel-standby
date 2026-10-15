# Pixel Standby backend

Python FastAPI market data service for the Pixel Standby Android app.

## Provider adapters (Phase 2 requirement)

| Adapter       | Provider        | Rate limit           | Delay               | Notes                                               |
| ------------- | --------------- | -------------------- | ------------------- | ----------------------------------------------------- |
| `yahoo`       | Yahoo Finance   | No published limit   | Unofficial endpoint | Equities, FX. No API key. Tolerant parser.            |
| `yahoo-gold`  | Yahoo Finance   | No published limit   | Unofficial endpoint | Spot gold (`XAUUSD=X`), reported as `XAUUSD` / USD.   |

Yahoo Finance is unofficial: there is no contract, no SLA, and the response shape has
changed before. Both adapters use a defensive parser that raises on anything unexpected,
so a shape change surfaces as "serve stale data" rather than a wrong number on the
bedside display.

## Rate limiting

A token-bucket rate limiter fronts all upstream calls. Defaults: 30 tokens per 60-second
window (one request every 2 seconds on average). Requests wait for a token rather than
firing and eating a 429. The bucket is shared across both adapters.

## Running locally

```powershell
# Create the venv (one time)
python -m venv .venv
.\.venv\Scripts\pip install -e ".[dev]"

# Generate a bearer token
$env:PIXEL_BEARER_TOKEN = "..."  # or: python -c "import secrets; print(secrets.token_urlsafe(32))"

# Run
.\.venv\Scripts\python -m uvicorn app.main:create_app --factory --reload
```

The service listens on `http://127.0.0.1:8000`. In production, Caddy terminates HTTPS in
front of it.

## Running with Docker

```powershell
$env:PIXEL_BEARER_TOKEN = "..."
docker compose up
```

Caddy listens on `https://127.0.0.1:8443` and forwards to the backend.

## Tests

```powershell
.\.venv\Scripts\python -m pytest
```

102 tests, all upstream HTTP mocked via `httpx.MockTransport`. No test touches the
network.

## Endpoints

See `docs/api-contract.md` for the full contract.

## Security

- `PIXEL_BEARER_TOKEN` is required. The app sends it as `Authorization: Bearer ...`.
- The token is compared in constant time and never logged.
- `YAHOO_API_KEY` (optional) is attached as a request header and never logged.
- Interactive docs (`/docs`, `/redoc`, `/openapi.json`) are disabled.