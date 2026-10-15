# Progress

## Current state

Phase 1 built and unit tested locally. Waiting on device verification for Checkpoint 1.
Phases 2–5 not started.

## Phase log

### Phase 1 — Standby shell

- Status: **built, unit tested, not verified on hardware**
- Checkpoint 1: **not reached** — needs the Pixel
- Tests: 33 passing, 0 failures
- Lint: 0 errors, 26 warnings (24 are "a newer version is available" nags on deliberately
  pinned versions; 1 is the conventional `mipmap-anydpi-v26` folder, 1 is a version nag on a
  transitive dep)

Delivered:

| File | Purpose |
| --- | --- |
| `StandbyDreamService.kt` | `DreamService`; fullscreen, non-interactive, screen brightness off, Compose host |
| `dream/DreamEnvironment.kt` | Battery broadcasts + `ComponentCallbacks2`, pushed not polled |
| `dream/Charging.kt` | `EXTRA_PLUGGED` test |
| `dream/Orientation.kt` | Orientation test |
| `dream/BurnInShift.kt` | Ring walk for pixel shift |
| `dream/DimPolicy.kt` | Idle → brightness levels, as a `Flow` |
| `dream/ClockTicker.kt` | Boundary-aligned clock `Flow` |
| `dream/ClockFormatter.kt` | 12/24 hour formatting |
| `data/SettingsRepository.kt` | DataStore-backed settings |
| `ui/StandbyScreen.kt` | Gates, dim, shift, tap handling |
| `ui/ClockPane.kt`, `ui/StandbyMessage.kt`, `ui/Theme.kt` | Presentation |

Verified here without a device:

- Debug and release (R8 + resource shrinking) both assemble.
- Merged and packaged manifest carry the service, `BIND_DREAM_SERVICE`, and the
  `android.service.dreams.DreamService` filter. Confirmed with `aapt2 dump xmltree` against
  the built APK.
- 33 unit tests over charging, orientation, burn-in, dim policy, clock scheduling, and
  formatting.

Two of those tests earned their keep: the original burn-in implementation could re-roll its
direction onto the same offset at a lap boundary, so content would not move that cycle. The
test caught it and the walk was reworked into a proper step-at-a-time walk.

### Phase 2 — Market data

- Status: **backend built + unit tested (102 tests); Android-side built + unit tested (33 tests)**
- Checkpoint 2: **not reached** — needs device screenshot + APK decompile for secrets
- Backend: 102 tests passing, verified with curl (auth, health, quotes endpoints all work)
- Android: 33 tests passing, debug APK builds successfully
- Provider decided: Yahoo Finance (unofficial). Two adapters: `yahoo` (equities) and
  `yahoo-gold` (XAUUSD=X → reported as XAUUSD/USD).
- Default watchlist decided: `AAPL, MSFT, GOOGL, AMZN, NVDA` + `XAUUSD`

Android delivered (Phase 2):

| File | Purpose |
| --- | --- |
| `data/Quote.kt` | Domain model for a price shown in the dream |
| `data/QuoteResponseDto.kt` | (in QuotesClient.kt) JSON DTO matching backend shape |
| `data/QuotesClient.kt` | HTTP client using java.net.HttpURLConnection, bearer auth |
| `data/QuotesRepository.kt` | Cache + 60s refresh loop, stale fallback on failure |
| `data/SettingsRepository.kt` | DataStore: clock format, dim, dashboard toggle, backend URL, bearer token |
| `data/WatchlistRepository.kt` | DataStore: watchlist, canonicalised, default AAPL/MSFT/GOOGL/AMZN/NVDA/XAUUSD |
| `ui/PriceCard.kt` | Price rows with green/red change, updated timestamp |
| `ui/StandbyScreen.kt` | Updated to show prices beside clock |
| `StandbyDreamService.kt` | Wires quotes refresh loop into dream lifecycle |

Verified here without a device:

- Debug APK builds successfully (release/R8 not yet re-tested after Kotlin changes)
- 33 unit tests pass
- Live quotes can't be fetched from this machine because Yahoo rate-limits the IP
  ("Edge: Too Many Requests"). The stale fallback path is fully covered by backend unit
  tests.
- Backend URL and bearer token are stored in DataStore, not hardcoded — no secrets in APK.

Backend README at `backend/README.md`; API contract at `docs/api-contract.md`.

### Phase 3 — Media mode

- Status: not started
- Checkpoint 3: not reached

### Phase 4 — CLI notification bridge

- Status: not started
- Checkpoint 4: not reached
- ntfy decided: public topic with an unguessable name, no token

### Phase 5 — Hardening

- Status: not started
- Checkpoint 5: not reached

## Environment

Resolved at the start of this session:

| Tool              | Before                                    | Now                                |
| ----------------- | ----------------------------------------- | ---------------------------------- |
| `java`            | not found                                 | Temurin 17.0.20.1, user `JAVA_HOME` |
| Android SDK       | absent                                    | platform 35, build-tools 35.0.0, platform-tools 37.0.1 |
| Gradle            | not found, but 8.10.2 was already cached  | wrapper generated and used         |
| `adb`             | not found                                 | shipped with platform-tools        |

Machine-level env writes are blocked (no admin), so `JAVA_HOME` and the JDK were added at
user level instead. A new shell will pick them up; shells opened before the install need
`JAVA_HOME` set manually.

Pinned deliberately to what was already in the Gradle cache: AGP 8.8.0, Kotlin 2.1.0,
Gradle 8.10.2. Newer versions exist and lint nags about them; upgrading is a deliberate
step, not a freebie.

Still open: Python is 3.14.6 while the backend targets 3.12. Dependency pins need checking
against 3.14 before Phase 2.

## Unverifiable without the physical Pixel

- Screen-saver entry appears in the Pixel's screen-saver picker
- "While charging" trigger behaviour
- Charging detection on real hardware
- Rotation to landscape while the dream runs
- Burn-in pixel-shift and the 30 min / 2 h fades
- Battery drain per hour while charging
- Whether the 150sp clock is actually readable at arm's length across a room — this is a
  judgement call, not something unit tests can settle
- Media controls against YouTube and Spotify, and update latency on a track change
- Notification access grant flow and resulting media session visibility
- ntfy banner latency with the screen on and off
- Overnight soak: temperature, dropped updates

Full checklist in `docs/device-setup.md`.

## Build and test commands

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
cd android-app
.\gradlew.bat testDebugUnitTest     # unit tests
.\gradlew.bat lintDebug             # static analysis
.\gradlew.bat assembleDebug         # debug APK
.\gradlew.bat assembleRelease       # minified, unsigned APK
```