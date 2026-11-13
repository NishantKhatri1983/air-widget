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
- **Control sheet** – tap the widget. Old models such as the m1 only understand the classic miIO calls
  (`set_power`, `set_mode`, `set_level_favorite`); newer MIoT models are handled through `set_properties`.

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
