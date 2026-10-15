# Pixel 6 Pro setup

## Prerequisites

- Pixel 6 Pro on Android 14 or newer.
- USB debugging on (**Settings → About phone → Build number** ×7, then
  **Settings → Developer options → USB debugging**).
- JDK 17 and the Android SDK with platform 35. On Windows, set `JAVA_HOME` and let
  `android-app/local.properties` point at the SDK:

  ```properties
  sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk
  ```

## Enable the screen saver

1. Install: `adb install -r android-app/app/build/outputs/apk/debug/app-debug.apk`
2. **Settings → Display → Screen saver → Pixel Standby**
3. **Settings → Display → Screen saver → Start screen saver → While charging**
4. Plug the phone in and rotate it to landscape.

## Preview without the charging trigger

Handy while iterating, because it skips the plug/unplug dance:

```powershell
adb shell am start -n com.pixelstandby/.StandbyDreamService
adb shell am stopservice com.pixelstandby/.StandbyDreamService
```

Charging state still applies in the preview, so a launch while unplugged shows the
"Connect to power" screen. That is the real gate, not a preview artefact.

## Screenshot

```powershell
adb exec-out screencap -p > dream.png
```

---

## Per-phase checklist

### Phase 1 — standby shell

- [ ] "Pixel Standby" appears in **Settings → Display → Screen saver**
- [ ] Charging + landscape: large clock, readable at arm's length
- [ ] Unplug: "Connect to power to activate", no dashboard behind it
- [ ] Re-plug: dashboard returns without restarting the dream
- [ ] Rotate to portrait: "Rotate to landscape", no clipped or stretched layout
- [ ] Rotate back to landscape: clock returns
- [ ] Wait 2+ minutes: content visibly shifts a few pixels, roughly once a minute
- [ ] Touching the screen wakes it; after 30 min the clock visibly dims, after 2 h it is
      nearly gone
- [ ] `adb shell dumpsys battery | findstr AC` confirms the OS agrees it is charging
- [ ] Battery drain while charging overnight: target under 3% per hour

Commands worth having open:

```powershell
adb logcat -s StandbyDream
adb shell dumpsys dreams
adb shell dumpsys battery
```

### Phase 2 — market data

- [ ] Prices visible next to the clock
- [ ] Change is green or red
- [ ] "updated N min ago" appears and ages
- [ ] XAU/USD labelled XAU/USD
- [ ] Stop the backend: prices freeze, are marked stale, show a timestamp, no crash

### Phase 3 — media mode

- [ ] Grant notification access, confirm the settings screen flips to "enabled"
- [ ] Spotify: card appears, play/pause/next/previous work
- [ ] YouTube: same
- [ ] Change track: card updates within 1 second
- [ ] Stop playback: card disappears

### Phase 4 — CLI bridge

- [ ] `notify-run -- sleep 5` → banner on the phone within 5 seconds
- [ ] Banner disappears after 15 s and moves to the history strip
- [ ] Banner is still recorded as a notification with the screen off
- [ ] A failing command produces an amber or red banner