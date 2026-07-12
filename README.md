# Sunmi Print Test

A feature-rich utility for the Sunmi V2 Pro built-in thermal printer. This app allows for pixel-perfect bitmap rendering and robust remote printing via multiple protocols.

## Features

- **Bitmap Rendering Engine**: Renders all content to a 384px bitmap before printing, ensuring output matches the preview exactly.
- **Enhanced ESC/POS Support**: Robust binary parsing with state-aware alignment, CP437 character set for clean table borders, and fixed 12px hardware grid locking for perfect column alignment.
- **B.A.N.U.S.U.G.E Alert Mode**: Specialized high-importance alert layout (Background Alert Notification Utility for Security Updates & General Events).
- **Multiple Remote Protocols**: 
  - **HTTP API**: Specialized endpoints for JSON, raw images, and URL pulling.
  - **MQTT Client**: Connect to Home Assistant or any broker to receive automated print jobs.
  - **TCP Socket Server**: Connect to a desktop GUI for manual jobs or raw ESC/POS streaming on port 9100.
- **Live Preview**: A fixed-width 384px preview box that accurately shows text wrapping and font sizes as they will appear on the receipt.

## How it works

- **Printer Binding**: Connects to the `woyou.aidlservice.jiuiv5` system service.
- **Print Queue**: Uses a single-threaded executor to process jobs sequentially, preventing race conditions or dropped rows in long receipts.
- **Image-Based Printing**: Bypasses traditional proportional font issues by rendering text to a bitmap using a fixed horizontal grid.

## Getting Started

### 1. Build & Run the Android App
1. Open this folder in Android Studio.
2. Let Gradle sync.
3. Connect your Sunmi V2 Pro via USB and enable Developer Options / USB Debugging.
4. Press **Run**. The app will automatically bind to the printer service and start the internal servers.

### 2. Remote Printing (Home Assistant / CURL)

#### Print an Image (Recommended for Tables/Receipts)
Send a raw PNG/JPG to `http://<device-ip>:8081/image`:
```bash
curl -X POST -H "Content-Type: image/png" --data-binary "@receipt.png" http://<device-ip>:8081/image
```

#### Pull from URL
Tell the printer to fetch and print an image:
`http://<device-ip>:8081/print_url?url=http://ha-ip:8123/local/receipt.png`

#### Print Styled JSON
POST a request to `http://<device-ip>:8081/print`:
```json
{
  "title": "HA Notification",
  "content": "Front door opened",
  "linesAfter": 3,
  "contentSize": 22
}
```

#### Home Assistant Integration (Recommended)
For a more reliable integration than the raw ESC/POS socket, install the custom
`custom_components/sunmi_printer/` integration via HACS (or manually) — it talks to the
JSON `/print` endpoint above and gives you a notify entity plus dedicated services
(`sunmi_printer.print_text`, `print_qr`, `print_image`, etc.) with a proper config flow.
See [`custom_components/sunmi_printer/README.md`](custom_components/sunmi_printer/README.md).

### 3. Desktop GUI Server
1. Run the server: `python server.py`
2. Enter the IP shown in the GUI into the Android app and tap **Connect**.

## Technical Notes

- **Native Width**: 384 pixels.
- **Character Spacing**: Fixed 12px grid (fits 32 characters per line).
- **ESC/POS Port**: `9100` for standard raw socket printing.
- **HTTP Port**: `8081`.
- **Encoding**: Uses `ISO-8859-1` / `Cp437` for hardware symbol compatibility.
