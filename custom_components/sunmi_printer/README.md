# Sunmi Printer — Home Assistant integration

A custom Home Assistant integration for the Sunmi V2 Pro printer app in this
repo. It talks to the app's existing `POST /print` HTTP endpoint (port 8081)
instead of the raw ESC/POS socket (port 9100) — the ESC/POS path is a
hand-rolled binary parser on the Android side and is more fragile than
plain JSON over HTTP.

## Install

### Via HACS (recommended)

This repository (`stefanconea/SunmiPrintServer`) has `custom_components/sunmi_printer/`
at its root plus a `hacs.json`, so HACS can install it as a custom repository:

1. HACS → the **⋮** menu (top right) → **Custom repositories**.
2. Add `https://github.com/stefanconea/SunmiPrintServer`, category **Integration**.
3. Find **Sunmi Printer** in HACS and click **Download**.
4. Restart Home Assistant.
5. Settings → Devices & Services → Add Integration → search for **Sunmi
   Printer** → enter the device's IP address (port defaults to `8081`).

### Manual

1. Copy `custom_components/sunmi_printer/` into your Home Assistant config's
   `custom_components/` directory, so you end up with
   `<config>/custom_components/sunmi_printer/manifest.json`.
2. Restart Home Assistant.
3. Settings → Devices & Services → Add Integration → search for **Sunmi
   Printer** → enter the device's IP address (port defaults to `8081`).

Either way, adding a device runs a connectivity check (`GET /` on the app's
HTTP server) before the entry is created, so a wrong IP/port is caught
immediately.

## What you get

- A **notify entity** per configured printer, for plain-text pushes via
  `notify.send_message` — the layout/lines-after used are configurable per
  device in the integration's Options.
- Nine **`sunmi_printer.print_*` services**, one per print layout the
  Android app supports: `print_text` (plain/centered/header_body),
  `print_boxed`, `print_banner`, `print_list`, `print_alert` (the
  B.A.N.U.S.U.G.E layout), `print_qr`, `print_barcode`, `print_image`
  (by URL or by an HA `camera`/`image` entity snapshot), and `print_raw` —
  an escape hatch that sends an arbitrary `type` + extra fields verbatim,
  for device print types added after this integration was written.

All services target the printer's notify entity (`target: entity:`), so with
multiple printers configured you just pick which one in the service call.

## Known limitation

`POST /print` returns success as soon as the Android app **enqueues** the job
on its single-thread print executor — it does not wait for the physical
print to finish, and the app has no completion callback, so failures like
"out of paper" or a jam are never reported back to Home Assistant. A
successful service call means "the printer accepted the job," not
"the receipt printed." This is a limitation of the Android app's HTTP server,
not something the Home Assistant side can work around.

## Not implemented (possible future work)

- A connectivity `binary_sensor` (via a polling `DataUpdateCoordinator`
  hitting `GET /`) so an unreachable printer is visible in HA instead of
  silently failing on the next print call.
- `diagnostics.py` for redacted config-entry diagnostics downloads.

## Development notes

- The app's `PrintJob` JSON uses **camelCase** keys (`titleSize`,
  `contentSize`, `centerTitle`, `linesAfter`) because they're serialized
  directly from a Kotlin data class by Gson — the integration's services use
  idiomatic snake_case field names and translate them internally
  (`notify.py`). If you add a new service, don't forget this translation —
  an unrecognized key is silently ignored by Gson rather than erroring.
- `alignment` is an integer on the device (`0/1/2`); the integration exposes
  it as a `left/center/right` select and maps it via `ALIGNMENT_TO_INT` in
  `const.py`.
- Only `GET /` and `POST /print` exist on the device today. The main repo's
  `README.md` also documents `/image` and `/print_url` endpoints, but they
  aren't implemented in `MainActivity.kt` — don't build against them.
