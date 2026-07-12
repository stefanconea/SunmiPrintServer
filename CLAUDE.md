# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An Android app (Kotlin) for the Sunmi V2 Pro's built-in thermal printer, plus an optional
Python desktop companion GUI. The app turns every print request — plain text, JSON, raw
ESC/POS bytes, images, barcodes/QR — into a 384px-wide bitmap and prints that bitmap via the
Sunmi SDK, so output always matches the on-screen preview exactly regardless of source protocol.

## Repository layout

- `app/` — Android app (single module, package `com.example.sunmiprinttest`). Almost all logic
  lives in `app/src/main/java/com/example/sunmiprinttest/MainActivity.kt` (~800 lines); this is
  a monolithic single-Activity app, not a multi-file/MVVM structure.
- `server.py` — standalone Tkinter desktop GUI that acts as a TCP *server* the Android app
  dials into (see Protocols below). `server.spec` is its PyInstaller build spec.
- `custom_components/sunmi_printer/` — a Home Assistant custom integration (Python) that
  talks to the app's HTTP `/print` endpoint (see `custom_components/sunmi_printer/README.md`
  for its own architecture: config flow, notify entity, `print_*` services). It intentionally
  lives at the repo root, not nested, because HACS requires `custom_components/<domain>/` to
  be at the repository root to install a repo as an "Integration." `hacs.json` at the repo
  root is what makes this installable as a HACS custom repository.
- `dist_old/`, `new_server/`, `de bloat backup/`, `build/`, `.gradle/` — build artifacts /
  scratch output, not source. Ignore unless specifically asked to touch packaging.
- `SunmiMobiControl.apk` — a prebuilt APK artifact checked into the repo root.

## Build & run

Android app (from repo root, uses the Gradle wrapper):
```
./gradlew assembleDebug      # build debug APK
./gradlew installDebug       # build + install on a connected/USB-debug Sunmi device
./gradlew build              # full build
```
There is no test source set in this project (no `app/src/test` or `app/src/androidTest`) and
no lint/CI config — don't assume `./gradlew test` will find anything.

- `compileSdk 34`, `targetSdk 33`, `minSdk 23` (Sunmi V2 Pro ships Android 7.1/8.1 depending on
  batch — avoid APIs beyond minSdk 23 without a version check).
- The Sunmi printer SDK (`com.sunmi:printerlibrary`) wraps the `woyou.aidlservice.jiuiv5` AIDL
  system service; if the Maven coordinate ever fails to resolve, see the comment in
  `app/build.gradle` for the manual `.aar` fallback from developer.sunmi.com.

Desktop server:
```
python server.py             # run directly (Tkinter GUI)
pyinstaller server.spec      # produce a standalone server.exe
```

## Architecture

### Bitmap-first rendering pipeline

Every input format converges on the same path in `MainActivity.kt`:

```
PrintJob (data class) → processJob() → renderJobToBitmap() → thresholdBitmap()
    → printExecutor (single-thread) → renderAndPrintBitmap() → SunmiPrinterService.printBitmap()
```

- `PrintJob` is the universal internal representation — every protocol (HTTP JSON, TCP JSON
  from the desktop server, MQTT payload, raw ESC/POS bytes) gets normalized into one before
  rendering. `job.type` selects the layout: `plain`, `centered`, `boxed`, `header_body`,
  `banner`, `list`, `barcode`, `qr`, `image`, `alert`.
- Text jobs go through `generateStyledBuilder()` (builds a `SpannableStringBuilder` with size/
  alignment/style spans) → `renderTextToBitmap()` (lays out with `StaticLayout` at fixed width
  384px) → `thresholdBitmap()` (converts to pure black/white with a simple edge-aware threshold,
  since the printer has no real greyscale).
- All print jobs are serialized through a single-thread `Executors.newSingleThreadExecutor()`
  (`printExecutor`) to keep long receipts from interleaving or dropping rows.
- The live preview (`updatePreview()`) renders the same `PrintJob` → bitmap/styled-text path on
  a 200ms debounce so the on-screen preview always matches what will physically print.

### `alert` / B.A.N.U.S.U.G.E mode

A specialized high-visibility layout (`generateBanuSugeAlertBuilder`) for security/alert
notifications — large centered text with a fixed banner format. Treated as its own `PrintJob`
type, not a variant of `plain`.

### Three inbound protocols + one outbound

The app runs three servers concurrently plus one outbound client, all started from
`onCreate()`:

1. **HTTP** (`AppHttpServer`, NanoHTTPD, port 8081) — serves a minimal web UI at `/` and accepts
   `POST /print` with a `PrintJob`-shaped JSON body. (README also documents `/image` and
   `/print_url` endpoints for raw image POST/URL-pull — implement alongside `AppHttpServer` if
   extending.)
2. **Raw ESC/POS** (`EscPosServer`, port 9100) — a byte-level ESC/POS command interpreter for
   things like Home Assistant's generic ESC/POS printer integration. It hand-parses the byte
   stream (`ESC @`, `ESC a` for alignment, `GS v 0` for bit images, `GS ( k` for QR codes, etc.),
   tracks alignment state (`currentAlign`) across the whole connection, and buffers plain text
   until a newline before flushing it as a `PrintJob`. Bit-image and QR payloads are rendered
   directly to a bitmap and injected via `processJob(job, overrideBitmap)`.
3. **MQTT** (`connectMqtt()`, Eclipse Paho) — subscribes to a configurable broker/topic
   (Settings screen) and treats each message payload as a `PrintJob` JSON (or falls back to
   plain text content if it doesn't parse).
4. **Desktop TCP client** (`performTcpConnect()`) — the Android app is the *client* here, not
   the server: it dials out to the IP:port configured in Settings (default
   `192.168.1.241:8080`) and retries every 5s if disconnected. `server.py` is the matching TCP
   *server*; its GUI builds a `PrintJob` JSON and pushes it down the open socket.

When adding a new inbound source, normalize into a `PrintJob` and call `processJob()` — don't
bypass the bitmap pipeline or the print executor.

### Settings

User-configurable values (desktop server URL, MQTT broker/topic, default lines-after) live in
`SharedPreferences` via `SettingsActivity`/`root_preferences.xml`, keyed by `desktop_server_url`,
`mqtt_broker`, `mqtt_topic`, `default_lines_after`.

### Logging

`LogManager` is a tiny in-memory ring buffer (last 200 entries) with a listener callback, viewed
via `LogsActivity`. Use `LogManager.addLog(...)` for anything a user would want to see in the
in-app Logs screen (connection events, server errors) — not for routine debug output.

`processJob()`/`renderAndPrintBitmap()` take an optional `source` string identifying which
inbound protocol submitted the job (`"HTTP Server"`, `"ESC/POS Server"`, `"MQTT"`,
`"Desktop Server (Python)"`, or the default `"Local"` for in-app UI prints), and log a
`[source] Print job succeeded/failed: ...` line for every job's outcome (including "printer not
connected"). When adding a new inbound source, pass a `source` string through to `processJob()`
so its jobs are attributable in the Logs screen.

## Technical constraints

- Native print width is fixed at **384px**; all rendering targets this width.
- Text rendering forces a monospace typeface for `plain` type or content containing double
  spaces (table-like content), specifically to keep ASCII table borders aligned on the printer's
  fixed character grid.
- ESC/POS text bytes are decoded as `ISO-8859-1` for hardware symbol-set compatibility.
