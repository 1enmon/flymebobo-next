# flymebobo 2.0

LSPosed module that shows notifications as floating bubbles (capsules) on the
left screen edge — music, downloads, verification / pickup codes, calls and
live activities included.

Package `com.chenan.flymebobo`, versionName `2.0`. Verified on Flyme
(MEIZU 20 Pro); the core is generic Android, but small-window opening uses
Flyme WindowManagerExt and some hooks target SystemUI internals.

## What it does

- Every notification pops as a 180x44dp capsule, tinted from its app icon,
  flush on the left edge. Notifications never auto-expand.
- Tap a capsule to expand it into a card; tap the card to open its app
  (small window by default, fullscreen if the switch is off).
- Pin per notification; pinned capsules and cards stay on top.
- Music capsule with live lyrics, album art, progress and controls.
- Download capsule with real-time progress, speed and ETA; install progress
  from app stores included.
- Verification / pickup codes are recognised, enlarged, auto-pinned, and
  verification codes are auto-copied.
- Call capsule with answer / hangup / speaker via the system's own call
  notification actions.
- A settings app (MainActivity) with real-time feature switches — no
  SystemUI restart needed after flipping one.

## Build

Requires JDK 17+, Android SDK (android-34), apktool, D8 (r8) and the
Xposed API jar. See `build/build20.sh`.

## License

GPL-3.0-only. Some implementations reference Flyme Island (灵动岛).
