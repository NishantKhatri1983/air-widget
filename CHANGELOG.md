# Changelog

Every version is tagged (`vX.Y`). To go back to one: `git checkout vX.Y`. Prebuilt APKs: `apk/` and the GitHub releases
(not every version has one; any version can be rebuilt from its tag). Newest first.

## v1.7 (2026-11-27)
- The widget's state line now reads `On · Turbo`, `On · Smart`, `On · Auto`, `On · Sleep` or `Off`. A manual Favorite shows
  plain `On`.
- The widget redraws at once when Turbo / Smart fan is switched on or off, or Smart fan switches itself off.

## v1.6 (2026-11-26)
- The very-poor-air alert is a Live Update: a status-bar pill showing `AQI 262` on Android 16+ (needs "Live updates"
  allowed for the app in notification settings). Older Android shows an ordinary notification.
- New permission `POST_PROMOTED_NOTIFICATIONS`.

## v1.5 (2026-11-26)
- Very-poor-air alert: appears above AQI 225, swipe to dismiss, closes itself below 100, tap opens Mi Home (or the control sheet).
- Smart fan restores Favorite and its fan level when the purifier flips itself to Auto within 60 s of the app's own command
  (at most twice in 10 minutes).
- Debug event log (`events.log`, 1 MB cap): commands and who sent them, power / mode / level changes, Smart fan on/off with the
  reason, purifier restarts, gaps in contact.
- History is mirrored to shared storage with temperature and humidity columns.

## v1.4 (2026-11-24)
- Smart fan "Fast ramp-up" option: raises the fan on the first reading that calls for it, 10 s between raises, faster
  screen-off polling while the air is high or rising.
- ⚡ Turbo button on the Smart fan row: one tap switches Fast ramp-up on or off (and starts Smart fan).

## v1.3 (2026-11-23)
- Air-quality history graph (1 h / 6 h / 24 h / 7 d) with a fan lane and a summary.
- Smart fan: the app sets the Favorite fan level from the AQI.
- One-screen control sheet.

## v1.2 (2026-11-21)
- Pastel widget and sheet colours that follow the air quality.

## v1.1 (2026-11-17)
- Fixed battery drain: stopped a busy loop while the screen is off.

## v1.0 (2026-11-13)
- First release: live Mi air purifier widget (temperature, humidity, AQI) with a control sheet (power, modes, fan speed).
