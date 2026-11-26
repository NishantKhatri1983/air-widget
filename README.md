# Air Widget

Live temperature, humidity and AQI for a **Xiaomi (Mi Home) air purifier** as an Android home-screen widget, with a
bottom-sheet control panel: power, Auto / Sleep / Favorite and a fan-speed slider, plus an animated "filtering air" view.

Built and tested with a Mi Air Purifier 2 (`zhimi.airpurifier.m1`) on Android 17.

## How it works

- **Login** – a WebView shows Xiaomi's own sign-in page; the password never touches this app. Only the resulting
  `passToken` is kept (app-private storage) and used to ask Xiaomi's cloud for an API session.
- **Device list** – the cloud returns the purifier's `did`, local IP and 16-byte token.
- **Live data** – the widget talks to the purifier directly over the LAN using the miIO UDP protocol (port 54321,
  AES-128-CBC keyed from the token), about every 4 s while the screen is on. If the purifier isn't reachable
  (e.g. on mobile data) it falls back to the Mi cloud (`/miotspec/prop/get|set`, RC4-signed requests).
- **AQI** – this purifier reports PM2.5; Mi Home's "AQI" is that same number, so the default scale is "Same as Mi Home".
  India (CPCB) and US EPA conversions are selectable in the app.
- **Pastel colours** – the widget card and the control sheet tint with the air quality (mint, lime, yellow-peach,
  orange, rose, lavender for PM2.5 0-30 / 31-60 / 61-90 / 91-120 / 121-250 / 251+), blending smoothly between bands;
  neutral white-grey when the purifier is off.
- **Air-quality history** – the control sheet has an *Air quality history* graph (1 h / 6 h / 24 h / 7 d). The AQI line is
  coloured by band; underneath, a "fan" lane shows what the purifier was doing (Auto / Sleep / Favorite colour, height =
  fan speed in rpm, grey = off). Touch or drag on the graph to read the time, AQI, mode and rpm at that moment. A short
  summary says when the air rose above "Good", where it peaked, when the purifier switched on and how fast the fan ran,
  and when it got back under "Good". Readings are logged to a private CSV (`history.csv`, 7 days, about one line a minute,
  sooner when something changes). While the screen is off the widget still takes one reading every 2 minutes so the log
  has no holes.
- **Smart fan** – optional (off by default, switch in the control sheet). The app sets the Favorite-mode fan level from the
  AQI shown in the widget: by default level 2 from AQI 50, one level more for every 25 AQI (3 at 75, 4 at 100, 5 at 125 ...
  up to the purifier's top level), and level 1 below 50. All four numbers are editable (*Edit rule*). It averages the last
  readings, needs two in a row to go up, waits 90 s with the air clearly (5 AQI) under a step before going down (wait and extra margin are editable) and changes the fan at most
  every 45 s. If you change the mode or fan level yourself (the sheet, Mi Home, the purifier) it switches itself off
  instead of fighting you. It never turns the purifier on. The history graph and summary mark the periods the Smart fan
  was steering the fan (green) separately from Auto, Sleep and manual Favorite. The purifier's beep on every change is
  switched off by default (a checkbox in *Edit rule* turns it back on).
  An optional **Fast ramp-up** checkbox (*Edit rule*, off by default) makes it quicker going up only: the fan is raised on
  the first reading that calls for it (latest reading, not the average) with just 10 s between raises, and with the screen
  off it reads every 20 s while the air is at or above the start AQI or jumped 5+ AQI. Lowering is unchanged. The
  **⚡ Turbo** button on the Smart fan row is a one-tap switch for the same setting (it also starts the Smart fan).
  The purifier sometimes flips itself to Auto a few seconds after the Smart fan raises the fan in a sharp spike. If the mode
  changes within 60 s of the Smart fan's own command, it puts Favorite and its level back (at most twice in 10 minutes,
  then it switches off as for any other outside change).
- **Very poor air alert** – when the AQI shown in the widget goes above 225 a notification ("Air quality very poor · AQI 262")
  appears as a Live Update (Android 16+: a status-bar pill showing "AQI 262"), with a short heads-up and sound the first time, then updates its number quietly. Swipe it away to dismiss it;
  it comes back only after the AQI has dropped below 100 and gone above 225 again. It closes by itself when the AQI falls
  below 100. Tapping it opens Mi Home if installed, otherwise the control sheet. Needs the notification permission.
- **Debug event log** – the app quietly writes `events.log` (about 1 MB at most) to its folder on shared storage
  (`Android/data/com.nishant.airwidget/files/`): commands sent and by whom (sheet, widget, Smart fan), power / mode / level
  changes seen in the readings, Smart fan on/off with the reason, gaps when the purifier stops answering, service
  start/stop. One short line per event, nothing per reading. The history is mirrored to `history.csv` in the same
  folder (with temperature and humidity columns) so it can be pulled with adb.
- **Control sheet** – tap the widget (everything fits on one screen; the animated purifier shrinks to make room). Fan speed is shown on the same 1-10 scale as the slider, including Auto, where the speed is derived from the measured rpm using what each level produced in Favorite mode. Old models such as the m1 only understand the classic miIO calls
  (`set_power`, `set_mode`, `set_level_favorite`); newer MIoT models are handled through `set_properties`.

## Download

Latest prebuilt APK (debug-signed): [apk/air-widget-v1.6.apk](apk/air-widget-v1.6.apk), also attached to the [v1.6 release](https://github.com/NishantKhatri1983/air-widget/releases/tag/v1.6). Older: [v1.2](apk/air-widget-v1.2.apk), [v1.1](apk/air-widget-v1.1.apk), [v1.0](apk/air-widget-v1.0.apk). Don't install an older APK over a newer one.

## Build

JDK 17+, Android SDK (platform 36), Gradle 9.1 and Android Gradle Plugin 9.0.1:

```
gradle assembleDebug
```

Create `local.properties` with `sdk.dir=...` first. The APK is `app/build/outputs/apk/debug/app-debug.apk`.

## Setup on the phone

1. Install the APK and open **Air Widget**.
2. Log in to Xiaomi → Find my devices → tap your purifier.
3. Allow background running (battery) and add the **Air Widget** 2×2 widget.

## Notes

- Unofficial; not affiliated with Xiaomi. It uses undocumented Mi cloud / miIO behaviour that may change.
- The product photo in `app/src/main/res/drawable-nodpi/mi_purifier.png` is a Xiaomi product image used for
  illustration only.
- Release APKs are debug-signed.
