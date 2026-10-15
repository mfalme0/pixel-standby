# Pixel Standby

A bedside dashboard for a Google Pixel 6 Pro. When the phone is charging and held in
landscape, it shows a large clock, live market prices, media controls for whatever is
playing, and notification banners pushed from command-line tools.

| Path           | What it is                                                |
| -------------- | --------------------------------------------------------- |
| `/android-app` | Kotlin + Jetpack Compose `DreamService` (Min SDK 26)       |
| `/backend`     | Python FastAPI market-data service behind Caddy/HTTPS      |
| `/cli-bridge`  | `notify-run`, a notify-and-run wrapper for shell commands  |
| `/docs`        | API contract and device setup notes                        |

## Status

Phase 1 (standby shell) built and unit tested. Waiting on device verification for
Checkpoint 1. Phases 2–5 not started. See `progress.md`.

---

## Phase 1 — Standby shell

The clock, the power and orientation gates, and burn-in protection. No network yet.

### Build

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
.\gradlew.bat assembleDebug          # -> app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat testDebugUnitTest      # 33 unit tests
.\gradlew.bat lintDebug
```

The SDK location comes from `android-app/local.properties` (`sdk.dir`), which is
machine-specific and gitignored.

### Install and enable

1. `adb install -r app\build\outputs\apk\debug\app-debug.apk`
2. On the phone: **Settings → Display → Screen saver → Pixel Standby**
3. **Settings → Display → Screen saver → Start screen saver → While charging**
4. Plug in and rotate to landscape.

To preview the dream without waiting for the trigger:

```powershell
adb shell am start -n com.pixelstandby/.StandbyDreamService
```

Full walkthrough in [docs/device-setup.md](docs/device-setup.md).

### Behaviour

**Power gate.** `ACTION_BATTERY_CHANGED` with `EXTRA_PLUGGED`. Because that broadcast is
sticky it is read synchronously at attach, so there is no flash of the wrong screen and no
guesswork. `ACTION_POWER_CONNECTED` / `DISCONNECTED` keep it live. While unplugged the
dashboard is not drawn at all — just "Connect to power to activate".

**Orientation gate.** Dream services cannot reliably lock orientation, so the dashboard
adapts instead. Orientation comes from the process-wide `ComponentCallbacks2` rather than
from a service callback, which means it fires whether the phone rotates or the dream is
started in the new orientation. Portrait shows "Rotate to landscape".

**Burn-in protection.**

- Every 60 seconds the entire dashboard shifts to the next position on a small ring,
  8dp out from centre in each direction. The walk steps one position at a time so content
  always moves, and every so often it jumps across the ring rather than turning back, so the
  motion never degenerates into a ping-pong that would trace a visible line. Counter is
  aligned to the wall-clock minute so the shift does not drift.
- After 30 minutes without interaction the content fades to 45% alpha.
- After 2 hours it drops to 6%.
- Interaction resets the timer.

**Brightness.** `setScreenBright(false)` knocks the panel down, and the dimming above
fades content on top of that rather than only the panel, so the clock stays readable
across a dark room instead of going straight to black.

**CPU.** Every time-driven loop is a cold `Flow` collected inside the composition, so it
starts when the dream draws and is cancelled when the window detaches — there is no
service-lifetime scope that could leak. The clock aligns to the next second or minute
boundary instead of firing on a fixed interval, so with seconds hidden the dashboard wakes
once a minute rather than sixty times, and it never drifts.

### Known limitation: tap-to-brighten

The spec for this phase asks for both `setInteractive(false)` and "brighten on tap". On
Android these conflict: a non-interactive dream never receives touch events, because the
system consumes them to wake the device. So `detectTapGestures` in `StandbyScreen` is wired
up and ready, but in the default configuration it does not fire.

What does work today is `onWakeUp()`, which resets the idle timer when the system wakes the
dream, so the screen recovers whenever the phone is touched. If tap-to-brighten needs to
respond while the dream stays up, `setInteractive(true)` is the switch — it is a one-line
change and it is intentionally left off here because it also stops the screen blanking,
which is a behaviour decision rather than a technical one. It is flagged for the Phase 5
settings screen.

This is also why the 30-minute and 2-hour fades apply unconditionally: on a non-interactive
dream there is no reliable per-keystroke signal to reset them.

### Settings

Stored in DataStore (`standby_settings`), consumed by the dream and written by the Phase 5
settings screen:

| Key                     | Default | Effect                                  |
| ----------------------- | ------- | --------------------------------------- |
| `clock_24_hour`         | `false` | 24 hour vs 12 hour clock                |
| `clock_show_seconds`    | `false` | Hide seconds; also cuts wakeups to 1/min |
| `auto_dim_enabled`      | `true`  | Master switch for the 30 min / 2 h fade |

Defaulting to 12 hour with no seconds is the "calm bedside clock" choice; both are
one tap away in Phase 5.

---

## Hardware setup (Pixel 6 Pro)

See [docs/device-setup.md](docs/device-setup.md).

## Documentation map

- `plan.md` — phase plan, acceptance criteria, decisions
- `progress.md` — running log of what is done, tested, and blocked
- `docs/device-setup.md` — Pixel setup and per-phase device checklist
- `docs/api-contract.md` — backend endpoints and payloads (Phase 2)