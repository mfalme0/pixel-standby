# Plan

Sequential delivery. Each phase is built and tested before the next begins, and each
ends at a checkpoint that waits for explicit confirmation.

## Phase 1 — Standby shell (Android)

- `StandbyDreamService : DreamService`, declared with `BIND_DREAM_SERVICE` and the
  `android.service.dreams.DreamService` intent filter.
- `onAttachedToWindow`: `setFullscreen(true)`, `setInteractive(false)`,
  `setScreenBright(false)`. Dim content, not the screen, so the clock stays readable.
- Charging gate: `BatteryManager` / `ACTION_BATTERY_CHANGED` with `EXTRA_PLUGGED`.
  Not charging → "Connect to power to activate", no dashboard. Live-updates on change.
- Orientation gate: checked on attach and on configuration change. Portrait → "Rotate
  to landscape". Dreams cannot lock orientation reliably, so we adapt instead.
- Burn-in protection:
  - pixel-shift all content by a few px on a slow random pattern every 60s
  - dim text after 30 min without interaction
  - near-black after 2 h
  - brighten on tap
- CPU: target under 3% battery per hour while charging. Second-precision clock updates
  only while visible; no polling.

**Checkpoint 1** — screen-saver entry visible on the Pixel; charging, orientation, and
burn-in behaviour confirmed on device; list of anything unverifiable without hardware.

## Phase 2 — Market data

Backend (FastAPI):
- `GET /api/quotes?symbols=AAPL,MSFT,XAUUSD` → `symbol, price, change, changePercent,
  currency, asOf, source, stale`
- `GET /api/health` → status + timestamp of last successful upstream fetch
- Provider adapters: one stock adapter (`{{STOCK_PROVIDER}}`) and one metals adapter for
  XAU/USD (`{{GOLD_PROVIDER}}`). Rate limits and delay per provider documented.
- In-memory cache, 60s TTL. Token-bucket rate limiter for upstream calls. Upstream failure
  → serve cached value with `stale: true`.
- Keys from environment variables, never logged.
- Bearer-token auth; HTTPS via Caddy. Dockerfile + docker-compose.yml.
- pytest for cache, rate limiter, adapter parsing, stale fallback. All upstream HTTP
  mocked.

Android:
- Watchlist in DataStore, editable in settings. Default `{{STOCK_TICKERS}}` + `XAUUSD`.
- Refresh every 60s while the dream is visible via a coroutine loop tied to the dream
  lifecycle; cancel on detach.
- Price with change coloured green/red, "updated N min ago". Gold labelled XAU/USD.
- Network failure → keep last values, mark stale.

**Checkpoint 2** — backend test results, a curl example, a device screenshot of prices,
and a decompile proving no secrets in the APK.

## Phase 3 — Media mode (Android)

- `MediaNotificationListener : NotificationListenerService`. Exists only to grant
  `MediaSessionManager` access. Never reads notification content.
- Settings screen explaining how to enable notification access, and checking whether it
  is on.
- `MediaSessionManager.getActiveSessions(componentName)`; prefer PLAYING, else most recent.
- Read `MediaMetadata` (title, artist, album art, duration) and `PlaybackState`
  (play/pause, position).
- Transport controls: play, pause, skip next, skip previous.
- Subscribe to session and metadata callbacks. No polling.
- Media card beside the clock while playing; hidden when nothing is active.
- README documents that apps without a media session cannot be controlled.

**Checkpoint 3** — YouTube and Spotify on device; what worked, what didn't, and how fast
the card updates on a track change.

## Phase 4 — CLI notification bridge

Phone (ntfy):
- Subscribe to topic `{{NTFY_TOPIC}}` over ntfy JSON/SSE. Exponential-backoff reconnect.
- Banner for 15s, then into a history strip. Last 20 messages in memory.
- Also post a standard Android notification so events are recorded with the screen off.
- Priority colours: low grey, default white, high amber, urgent red.

CLI (`/cli-bridge/notify_run.py`):
- `notify-run --title "opencode" -- <command and args>`
- Runs the command, streams output to the terminal unchanged, records exit code and
  duration, posts e.g. "opencode finished in 4m12s, exit 0".
- Failures are high priority.
- Server and topic from env vars or a config file. Supports a token header.
- `--on-prompt` mode posts a message and waits for a reply. Reply path is a stub.
- Investigate whether opencode exposes hooks, server mode, or an event API. If so, add an
  adapter using it instead of wrapping the command, and document the findings.

**Checkpoint 4** — `notify-run -- sleep 5` shows a banner on the phone within 5 seconds.

## Phase 5 — Hardening

- Overnight charging test: battery drain, temperature, dropped updates.
- Survive airplane mode, Wi-Fi changes, and API outages without crashing. Small
  connection indicator.
- Settings toggle to disable the dashboard without disabling the dream.
- 12/24h toggle and dim-level setting.
- README: setup, permissions, enabling the screen saver on the Pixel, selecting
  "While charging", granting notification access.

**Checkpoint 5** — final acceptance report.

## Acceptance criteria

- Plugged in + landscape: clock, prices, active media visible. Prices refresh within 60s.
- Unplugged: "Connect to power" screen.
- Portrait: "Rotate" hint.
- Media controls work with Spotify and YouTube; card updates within 1s of a track change.
- `notify-run -- sleep 5` → phone banner within 5s.
- Backend down → stale prices with timestamps, no crash.
- No secrets in the APK.

## Decisions

1. `{{STOCK_PROVIDER}}` — **Yahoo Finance (unofficial)**. No API key, and it covers both
   equities and `XAUUSD=X`, so one HTTP shape covers both adapters. Trade-off: unofficial
   and ToS-grey, so it needs a tolerant parser and must never be the only path. A Finnhub
   adapter remains a drop-in if the unofficial route proves flaky.
2. `{{GOLD_PROVIDER}}` — **Yahoo Finance**, `XAUUSD=X`, behind its own metals adapter so
   the stock and metals paths can diverge later without touching the caller.
3. `{{STOCK_TICKERS}}` — **`AAPL, MSFT, GOOGL, AMZN, NVDA`**, plus `XAUUSD`.
4. `{{NTFY_TOPIC}}` — **public topic, unguessable random name**, no token. Anyone who
   guesses the topic can post to the phone, so the name has to be long and never reused.
5. Market data is free-tier and delayed by at least 15 minutes. Accepted for a bedside
   display. If intraday accuracy ever matters, the adapter interface is the swap point.

## Phase status

- Phase 1 (standby shell): built and unit tested locally; device verification pending.
- Phase 2 (market data): backend built and unit tested (102 tests); Android-side built and unit tested (33 tests).
- Phase 3 (media mode): built and unit tested (33 tests); device verification pending.
- Phase 4 (CLI notification bridge): built and tested locally; device verification pending.
- Phase 5: not started.

## Environment gaps, resolved

Resolved in this session: no JDK, no Android SDK, no gradle, no adb. All installed or
recovered — see `progress.md`. Still open: Python is 3.14.6 while the backend targets 3.12.
The backend runs fine on 3.14.6; the `>=3.12` constraint in pyproject.toml is satisfied.