# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An Android app (Kotlin) for the Sunmi V2 Pro's built-in thermal printer, plus an optional
Python desktop companion GUI. The app turns every print request — plain text, JSON, raw
ESC/POS bytes, images, barcodes/QR — into a 384px-wide bitmap and prints that bitmap via the
Sunmi SDK, so output always matches the on-screen preview exactly regardless of source protocol.

## Repository layout

- `app/` — Android app (single module, package `com.example.sunmiprinttest`). The engine (all
  three inbound servers, the outbound desktop client, the printer connection, and the whole
  bitmap-rendering pipeline) lives in `PrintService.kt`, a foreground `Service` — see
  "Background service" below. `MainActivity.kt` is a thin UI layer that binds to it; this is
  not a multi-file/MVVM structure beyond that split.
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
  `banner`, `list`, `barcode`, `qr`, `image`, `alert`, `guard_receipt`.
- Text jobs go through `generateStyledBuilder()` (builds a `SpannableStringBuilder` with size/
  alignment/style spans) → `renderTextToBitmap()` (lays out with `StaticLayout` at fixed width
  384px) → `thresholdBitmap()` (converts to pure black/white with a simple edge-aware threshold,
  since the printer has no real greyscale).
- All print jobs are serialized through a single-thread `Executors.newSingleThreadExecutor()`
  (`printExecutor`) to keep long receipts from interleaving or dropping rows.
- The live preview (`updatePreview()`) renders the same `PrintJob` → bitmap/styled-text path on
  a 200ms debounce so the on-screen preview always matches what will physically print.

### Background service

`PrintService` (a foreground `Service`, not tied to `MainActivity`'s lifecycle) owns every piece
of the engine: `PrintJob` (top-level data class), the three inbound servers, the desktop TCP
client, the Sunmi SDK printer binding, `printExecutor`, and the entire render/print pipeline
described above. It's started with `ContextCompat.startForegroundService()` from
`MainActivity.onCreate()` and shows a low-priority persistent notification (required on Android
8+ for a foreground service) — this is what keeps HTTP/ESC-POS/MQTT/desktop-TCP listening and the
printer bound while the user is on the home screen, in another app, or with the screen off; a
plain unbound Activity gets deprioritized (and eventually killed) by Android well before that.
`MainActivity` only *binds* to it (`bindService`/`unbindService` in `onStart`/`onStop`) to receive
live UI updates through `PrintService.Listener` and to call into `processJob()`/
`renderJobToBitmap()`/`generateStyledBuilder()`/`toggleConnection()` for the on-screen preview and
print buttons — it never starts or stops the service itself. The only way the service stops is the
explicit "Exit App" preference in Settings (`SettingsActivity.SettingsFragment.confirmExit()`),
which calls `stopService()` then `Process.killProcess(Process.myPid())` to guarantee a fully closed
app rather than leaving anything backgrounded. If you add a new field the UI needs to observe
(printer status, TCP status, etc.), add it to `PrintService.Listener` and read the service's
current value once in `MainActivity.syncUiWithServiceState()` too — `Listener` callbacks only
fire on future state changes, so anything that changed while `MainActivity` wasn't bound (e.g. the
Activity was recreated) needs that explicit resync on (re)bind.

### `alert` / B.A.N.U.S.U.G.E mode

A specialized high-visibility layout (`generateBanuSugeAlertBuilder`) for security/alert
notifications — large centered text with a fixed banner format. Treated as its own `PrintJob`
type, not a variant of `plain`.

The alert prints a source label (replacing what used to be a hardcoded `"unknown"` line) derived
from `processJob()`'s existing `source` parameter (the same value used for Job Logs attribution),
threaded through `renderJobToBitmap()` and `generateStyledBuilder()` down to the builder. If you
add a new inbound path, its `source` string needs an entry in `alertSourceLabel()` too, or it'll
fall through and print the raw internal source string verbatim. `AppHttpServer`'s `POST /print`
handler distinguishes the built-in web page from other HTTP callers (curl, Home Assistant's HTTP
integration) by checking for a `Referer` header — present on a same-page browser `fetch()`,
absent from headless clients — rather than requiring any change on the caller's side.

### `guard_receipt` mode

A fixed POS-style entry-ticket layout — company name header, `Employee: Owner`, `POS: POS 1`, a
single `Intrare interzisa` line item, total, `Cash` line, timestamp, a sequential receipt number
(`#1-%04d`), and a QR code (encoding `"<receiptNumber> <timestamp>"`) printed below everything
else. Unlike every other `PrintJob` type, this one is handled by a dedicated branch in
`renderJobToBitmap()` rather than flowing through `generateStyledBuilder()`'s generic dispatch:
the receipt number/timestamp/counter-increment and the `EntranceReceiptManager.addReceipt(...)`
call happen once in that branch, then get passed as parameters into `generateGuardReceiptBuilder()`
(pure text layout) and reused again to build the QR content, so the counter only advances once
and the text and QR always agree on the same receipt number — don't move that bookkeeping back
into `generateGuardReceiptBuilder()` or split it across two call sites. The text bitmap
(`renderTextToBitmap`) and the QR bitmap (built the same way as the standalone `qr` type, via
ZXing's `MultiFormatWriter`) are drawn onto one final `Canvas`, text first, QR centered below it.
The counter is persisted in `SharedPreferences` (`guard_receipt_counter`) so numbering survives
app restarts; company name (`guard_company_name`) and unit price (`guard_price`) are
user-configurable in Settings, not passed per job — only `job.quantity` (default 1) varies the
ticket (multiplies the line total).

Row-level text layout inside `generateGuardReceiptBuilder()` uses `padRow()` (manual space
padding, not `AlignmentSpan`) to position Total/Cash values — an `AlignmentSpan(ALIGN_OPPOSITE)`
per row was tried and reverted because it right-aligns the *entire* padded label+value string as
one block, visibly shoving the "Total"/"Cash" labels away from the left margin instead of just
pinning the value flush right. Two more `SpannableStringBuilder` pitfalls worth knowing before
touching this function again: (1) `renderTextToBitmap` picks one base alignment for the *entire*
layout the moment any `ALIGN_OPPOSITE` span exists anywhere in it, so every paragraph needs its
own explicit alignment span or it'll be swept into that base; (2) reusing a single span object
across multiple `setSpan()` calls at different ranges *moves* its attachment each time rather than
adding a new one — only the last range keeps it; give every row its own freshly-constructed span
instance. Row font sizes (`bodySize`/`priceRowSize`/`qtyRowSize`/`totalSize`) and their monospace
character budgets are reconciled via `monoCharsPerLine()`, which measures real glyph width with
`Paint.measureText()` rather than guessing a linear scale factor — a guessed ratio was just wrong
enough to wrap rows mid-word. The item row's label (`"Intrare interzisa"` + a price) is already
close to the 384px ceiling at the current font size; there is essentially no headroom to make it
bigger without wrapping unless the label itself gets shorter or moves to its own line. If you
change any font size or the QR layout again, verify by temporarily dumping the rendered bitmap to
a file (`bitmap.compress(...)` to `getExternalFilesDir(null)`) and pulling it via `adb pull` —
job-log "Success" only proves the SDK accepted the bitmap, not that the layout is visually
correct.

### Three inbound protocols + one outbound

The app runs three servers concurrently plus one outbound client, all started from
`PrintService.onCreate()` (see "Background service" above):

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

### System Print Service (fifth inbound path)

`SunmiPrintService` (extends `android.printservice.PrintService`, a *different* class from this
app's own `PrintService` foreground service — aliased as `AndroidPrintService` in that file to
avoid a same-name collision) registers this app as a system-level Android Print Service, so
"Sunmi Printer" shows up as a target from any app's native Print action (Gallery, Chrome, PDF
viewers, ...), not just this app's own protocols. `SunmiPrinterDiscoverySession` reports one
fixed virtual printer with a custom `PrintAttributes.MediaSize` — "Receipt Roll (58mm)", the
printer's nominal/marketed paper size (2283×3543 mils, roughly a 1.5:1 aspect) — since there's
nothing to actually discover. The declared width is intentionally the *paper* width (58mm), not
the narrower ~48mm printable strip the 384px render actually uses (see "Technical constraints"
below); `PrintService` always renders at the correct 384px regardless of what width is declared
here. The declared *height* is a deliberate compromise, not a hardware constraint: Android's print
framework needs some finite page size, and apps' default image-print adapters scale a photo to
*fill* the whole declared page and crop whatever overflows, so a page far taller than it is wide
(the original attempt used a ~300mm/6:1 ratio) forces brutal cropping down to a thin sliver of the
photo. A page that's too short isn't free either — it just means longer documents paginate into
more discrete `PdfRenderer` pages, each printed as its own sequential bitmap (which is fine, but
looks like a compromise either way; there's no single page shape that's right for both a tall text
receipt and a landscape photo). Unlike every other inbound path, this one doesn't go through
`processJob()` directly with a `PrintJob`: the system spools whatever the calling app rendered as
a PDF, and `onPrintJobQueued()` rasterizes each page with `PdfRenderer` into a 384px-wide bitmap,
then calls `PrintService.processImageBitmap()` (a raw-bitmap entry point that scales before
handing off to `processJob()`).

Framework gotchas hit during this integration, worth knowing before touching `SunmiPrintService`
again:
1. `PdfRenderer` requires a *seekable* file descriptor, but `printJob.document.data` from the
   spooler is a streamed pipe — passing it straight to `PdfRenderer` throws immediately. Drain it
   into a local cache file first (`ParcelFileDescriptor.AutoCloseInputStream(...).copyTo(...)`),
   then open *that* file with `PdfRenderer`. Skipping this doesn't just fail silently on our side —
   it also EPIPEs the system print spooler process (visible in logcat as
   `PrintSpoolerService: Error writing print job data!`), which can crash and restart the spooler.
2. `PrintJob.start()`/`.complete()`/`.fail()`/`.cancel()` all throw `IllegalAccessError` if called
   off the main thread. `onPrintJobQueued()` itself runs on the main thread (framework contract),
   so `start()` happens there directly; the actual PDF rendering runs on a background thread, which
   hops back via a `Handler(Looper.getMainLooper())` only to report `complete()`/`fail()`.
3. A landscape-oriented PDF page (`page.width > page.height`, e.g. a horizontal photo) has to be
   rotated 90° before printing: the printer only ever has 384px in one fixed physical axis (the
   roll's width), so a page wider than it is tall would otherwise print sideways relative to the
   roll's natural (portrait) feed direction. `onPrintJobQueued()` detects this and rotates the
   rasterized bitmap with `Matrix().postRotate(-90f)` after rendering (`-90`, not `+90` — the two
   directions are geometrically equivalent for "landscape becomes portrait" but only one prints
   right-side-up rather than upside-down; there's no way to derive which from the PDF page alone,
   this was found by testing an actual print), before handing it to `processImageBitmap()`.
4. The Android print dialog's own Portrait/Landscape orientation toggle is a generic system UI
   feature completely outside a print service's control (no `PrinterCapabilitiesInfo` API exists
   to restrict it) — picking "Landscape" there makes the *preview pane* render sideways, which
   looks alarming but is cosmetic only. It doesn't affect the actual printed output: point 3 above
   detects and corrects orientation from the real PDF page dimensions regardless of what the
   dialog's toggle was set to, so the physical print comes out correctly oriented either way. Don't
   try to "fix" the preview — there's no hook available to do so.
5. `processImageBitmap()` runs photos through `ditherBitmap()` (Floyd-Steinberg error-diffusion
   dithering) rather than handing the SDK's `printBitmap()` a raw grayscale/color bitmap directly.
   Two things were tried and rejected first: `thresholdBitmap()` (the edge-aware threshold built
   for text/line-art in `renderTextToBitmap`) posterizes photos into harsh blobs since it decides
   each pixel independently with no error diffusion; and handing the SDK a raw bitmap untouched
   (matching what the pre-existing `image` job type in `renderJobToBitmap()` does for base64/URL
   images) turned out to look little better, since the SDK's own internal conversion isn't well
   tuned for photos either. Proper dithering is what actually reads as a photo on a 1-bit printer.
   If the `image` job type's photo quality ever gets a complaint too, the same fix likely applies
   there.

### Remote printing (no local Sunmi hardware)

`SunmiPrintService` only works installed on the Sunmi V2 Pro itself, since it talks to the
printer through the local `woyou.aidlservice.jiuiv5` AIDL service — there's no such service on an
ordinary phone. To still let "Sunmi Printer" show up in an *ordinary* phone/tablet's own print
menu and actually reach the physical printer, `processJob()` in `PrintService` checks
`printerService` (the local SDK connection) at dispatch time: if it's null (no printer physically
attached to this device) and Settings' `remote_printer_url` is non-empty, the job is relayed
instead of failed — `relayBitmapToRemote()` POSTs the already-fully-rendered bitmap (post
rotation, post dithering) as base64 PNG to the configured device's own `POST /print` endpoint,
using the existing `image` job type (`renderJobToBitmap()`'s `image` branch just base64-decodes
and prints as-is, no re-processing) so nothing gets reprocessed or degraded in transit. This means
the *same APK* serves both roles with no build variant needed: on the Sunmi device itself
`printerService` is always non-null once the SDK binds, so this path is simply never taken there
regardless of what (if anything) `remote_printer_url` is set to; on any other device, printing
always falls through to the relay as long as that setting points at a reachable Sunmi. Leave
`remote_printer_url` empty on the Sunmi device itself.

`MainActivity`'s manual print form (the `Print`/`Entrance` buttons and the "Printer connected" /
"Printer disconnected" status line) is gated on `PrintService.canPrint` (local hardware connected
OR a remote configured), not raw hardware-connected state — otherwise the form would be uselessly
disabled on a phone acting purely as a remote client, even though it can genuinely print via
relay. The status *text* still distinguishes the three real states (`status_connected` /
`status_remote` / `status_disconnected`) rather than collapsing them, so it stays honest about
whether this device has a printer physically attached.

Registered via `AndroidManifest.xml` (`<service android:name=".SunmiPrintService"
android:permission="android.permission.BIND_PRINT_SERVICE">`, a system signature permission so
only the real print spooler can bind it) + `res/xml/printservice.xml`. Same rule as accessibility
services: an app cannot self-enable as a print service — the user has to manually turn it on once
under Android's own Printing settings. Settings → "Enable as Android Print Service" jumps straight
to that system screen (`Settings.ACTION_PRINT_SETTINGS`); note that screen has been observed
rendering completely blank on this device's ODM ROM even though the service registers and works
correctly (confirmed via `adb shell dumpsys print` and an actual print from Chrome) — don't trust
that screen's appearance as a signal of whether registration succeeded.

### Settings

User-configurable values (desktop server URL, MQTT broker/topic, default lines-after, guard
receipt company name/price) live in `SharedPreferences` via `SettingsActivity`/
`root_preferences.xml`, keyed by `desktop_server_url`, `mqtt_broker`, `mqtt_topic`,
`default_lines_after`, `guard_company_name`, `guard_price`.

### Logging

Two separate ring-buffer loggers, each with its own listener-driven screen — don't conflate them:

- `LogManager` (last 200 entries, viewed via `LogsActivity`/"Logs") — free-text server/connection
  events (startup, connect/disconnect, server errors). Use `LogManager.addLog(...)` for anything
  in that category, not for routine debug output.
- `JobLogManager` (last 200 entries, viewed via `JobLogsActivity`/"Job Logs") — one structured,
  numbered, timestamped entry per *print job*, independent of the general log. `processJob()`
  calls `JobLogManager.startJob(source, type)` up front (so a job shows as "Pending" while still
  queued on `printExecutor`) and gets back a `jobId`, which `renderAndPrintBitmap()` later closes
  out via `JobLogManager.completeJob(jobId, success, message)` — including the "printer not
  connected" case that used to silently drop with no trace at all. `source` is one of
  `"HTTP Server"`, `"ESC/POS Server"`, `"MQTT"`, `"Desktop Server (Python)"`, or the default
  `"Local"` for in-app UI prints. When adding a new inbound source, pass a `source` string through
  to `processJob()` so its jobs are attributable in the Job Logs screen.

`renderAndPrintBitmap()`'s success/failure verdict is a heuristic, not a hard guarantee: the Sunmi
SDK has no true print-completion callback — `InnerResultCallback.onRunResult(isSuccess)` fires
almost immediately once the bitmap is *accepted*, well before the physical print (which takes real
time proportional to content length) actually finishes. So a fault introduced mid-print (e.g. the
paper door opened) would still log as Success if only that callback were trusted. To catch it,
`renderAndPrintBitmap()` polls `service.updatePrinterState()` for a window sized to the bitmap's
height (`MS_PER_PIXEL_ROW`/`MIN_WATCH_MS`/`WATCH_STEP_MS` constants) after submitting, and flags
Failed if a fault shows up at any point during that window. This is a best-effort approximation of
"print finished," not an authoritative signal — tune those constants if jobs are logged as
succeeding despite visible physical failures, or failing despite a clean print.

### Entrance receipts

Unlike `LogManager`/`JobLogManager` (in-memory ring buffers, lost on restart), `EntranceReceiptManager`
persists every `guard_receipt` print as JSON (via Gson) in the app's default `SharedPreferences` —
these represent real money collected at a door, so they must survive an app restart and are never
auto-evicted. `generateGuardReceiptBuilder()` calls `EntranceReceiptManager.addReceipt(...)` each
time it prints; `EntranceReceiptsActivity` ("Entrance Receipts" in the toolbar menu) lists every
receipt newest-first with a `Paid` checkbox per row (green background when checked, red when not)
that calls `EntranceReceiptManager.setPaid(...)` immediately on toggle. All functions take the
`SharedPreferences` instance as a parameter rather than the manager holding its own reference, so
both `MainActivity` and `EntranceReceiptsActivity` share the same underlying storage without needing
an explicit `init(context)` call.

### Backup / restore

All SharedPreferences data (every Settings value plus `entrance_receipts`) is app-private storage
that Android deletes on uninstall, with no way to recover it after the fact — `exportBackup()`
(toolbar menu → "Backup Data") dumps `prefs.all` to a JSON file in the app's cache dir and hands
it to the system share sheet via a `FileProvider` (declared in `AndroidManifest.xml` +
`res/xml/file_paths.xml`), so the user picks where it actually lands (Drive, email, a file
manager, etc.) — deliberately avoids `WRITE_EXTERNAL_STORAGE`/scoped-storage handling entirely.
`importBackup()` (toolbar menu → "Restore Backup", backed by
`ActivityResultContracts.OpenDocument()`) reverses this: reads the picked JSON file and replays
every key back into `SharedPreferences`, type-dispatched off each `JsonPrimitive` (string/
boolean/number — numbers always restore as `Int`, since every numeric preference this app stores,
e.g. `guard_receipt_counter`, is one). A restored `EntranceReceiptManager` list only takes effect
after the app restarts (the toast says so) since in-memory state isn't touched.

## Technical constraints

- Native print width is fixed at **384px**; all rendering targets this width.
- Text rendering forces a monospace typeface for `plain` type or content containing double
  spaces (table-like content), specifically to keep ASCII table borders aligned on the printer's
  fixed character grid.
- ESC/POS text bytes are decoded as `ISO-8859-1` for hardware symbol-set compatibility.
