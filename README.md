# Pixel Standby

A bedside dashboard for a Google Pixel 6 Pro. When the phone is charging and held in
landscape, it shows a large clock, live market prices, media controls for whatever is
playing, and notification banners pushed from command-line tools.

Built as three pieces:

| Path           | What it is                                                    |
| -------------- | ------------------------------------------------------------- |
| `/android-app` | Kotlin + Jetpack Compose `DreamService` (Min SDK 26)            |
| `/backend`     | Python FastAPI market-data service behind Caddy/HTTPS           |
| `/cli-bridge`  | `notify-run`, a notify-and-run wrapper for shell commands      |
| `/docs`        | API contract and setup notes                                   |

## Status

All 5 phases built and unit tested. Waiting on device verification for all checkpoints.

- Phase 1: Standby shell — clock, power/orientation gates, burn-in protection
- Phase 2: Market data — backend + prices in dream
- Phase 3: Media mode — playback controls for Spotify, YouTube, etc.
- Phase 4: CLI notification bridge — ntfy banners from `notify-run`
- Phase 5: Hardening — settings screen, connection indicator, dim level

---

## Quick start

### 1. Install the APK

```powershell
adb install -r android-app\app\build\outputs\apk\debug\app-debug.apk
```

### 2. Enable the screen saver on the Pixel

1. **Settings → Display → Screen saver → Pixel Standby**
2. **Settings → Display → Screen saver → Start screen saver → While charging**
3. Plug the phone in and rotate to landscape.

### 3. Open the settings screen

The settings activity is available from the app launcher or via notification pending intents.
Configure:

| Setting | What it does |
| ------- | ------------ |
| Enable dashboard | Turn the dashboard off without disabling the screen saver |
| 24-hour clock | 12-hour vs 24-hour time format |
| Show seconds | Display seconds (uses more power) |
| Auto-dim | Dim after 30 min idle, near-black after 2 h |
| Dim level | Never / Medium / Aggressive dimming |
| Media controls | Control Spotify, YouTube and other media sessions |
| Backend URL | Where prices are fetched from (required for prices) |
| Bearer token | Auth token for the backend |
| ntfy server / topic / token | CLI notification bridge config |
| Stock tickers | Comma-separated watchlist (default: AAPL,MSFT,GOOGL,AMZN,NVDA,XAUUSD) |

### 4. Deploy the backend (for prices)

```powershell
cd backend
$env:PIXEL_BEARER_TOKEN = "<generate with: python -c "import secrets; print(secrets.token_urlsafe(32))">"
python -m uvicorn app.main:create_app --factory --host 0.0.0.0 --port 8000
```

Then enter the URL and bearer token in the app settings.

### 5. Configure the CLI bridge (for ntfy banners)

```powershell
cd cli-bridge
$env:NTFY_TOPIC = "your-topic"
python notify_run.py --title "opencode" -- sleep 5
```

Enter the ntfy server/topic/token in the app settings. Banners appear for 15 seconds, then
move to a history strip (last 20 kept).

---

## Build

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
cd android-app
.\gradlew.bat testDebugUnitTest   # 34 unit tests
.\gradlew.bat assembleDebug      # debug APK
.\gradlew.bat assembleRelease    # minified APK
```

Backend tests:

```powershell
cd backend
.\.venv\Scripts\python.exe -m pytest   # 102 tests
```

## Acceptance criteria

- [ ] Plugged in + landscape: clock, prices, active media visible. Prices refresh within 60s.
- [ ] Unplugged: "Connect to power" screen.
- [ ] Portrait: "Rotate" hint.
- [ ] Media controls work with Spotify and YouTube; card updates within 1s of track change.
- [ ] `notify-run -- sleep 5` → phone banner within 5s.
- [ ] Backend down → stale prices with timestamps, no crash.
- [ ] No secrets in the APK.

## Documentation map

- `plan.md` — phase plan, acceptance criteria, decisions
- `progress.md` — running log of what is done, tested, and blocked
- `docs/device-setup.md` — Pixel setup and per-phase device checklist
- `docs/api-contract.md` — backend endpoints and payloads