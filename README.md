# Sunmi Print Test

A feature-rich test application for the Sunmi V2 Pro built-in thermal printer. This app allows for local printing with advanced styling, live pixel-perfect previews, and remote printing via multiple protocols.

## Features

- **Bitmap Rendering**: Renders all text to a 384px bitmap before printing, ensuring the output matches the preview exactly.
- **Enhanced Emoji Support**: Includes custom edge-detection logic to create sharp, bold outlines for emojis, making them highly legible on thermal paper.
- **B.A.N.U.S.U.G.E Alert Mode**: A specialized high-importance alert layout (Background Alert Notification Utility for Security Updates & General Events).
- **Multiple Remote Protocols**: 
  - **TCP Socket Server**: Connect to a desktop GUI for manual jobs.
  - **HTTP/Web Server**: Host a local web page on the device for mobile/browser printing.
  - **MQTT Client**: Connect to any broker (Home Assistant, etc.) to receive automated print jobs.
- **Live Preview**: A fixed-width 384px preview box that accurately shows text wrapping and font sizes as they will appear on the receipt.
- **Styling Options**: 
  - Independent Title and Content text sizes (in pixels).
  - Configurable Title centering.
  - Content alignment (Left or Center).
  - Configurable paper feed ("Lines after") setting.

## How it works

- **Printer Binding**: Connects to the `woyou.aidlservice.jiuiv5` system service using the Sunmi `printerlibrary`.
- **Image-Based Printing**: Instead of sending raw characters, the app builds a `SpannableStringBuilder`, renders it onto an Android `Canvas` at a fixed width of 384px, and applies a custom thresholding algorithm to ensure a crisp black-and-white output.
- **Integrations**:
  - **HTTP**: Listens on port `8081`. Access `http://<ip>:8081` in any browser to print.
  - **MQTT**: Subscribes to a configurable topic for JSON or raw text jobs.
  - **TCP**: Maintains a persistent connection to the Python desktop server.

## Getting Started

### 1. Build & Run the Android App
1. Open this folder in Android Studio.
2. Let Gradle sync.
3. Connect your Sunmi V2 Pro via USB and enable Developer Options / USB Debugging.
4. Press **Run**. The app will automatically bind to the printer service and start the internal servers.

### 2. Remote Printing (Home Assistant / CURL)
Send a POST request to `http://<device-ip>:8081/print`:
```json
{
  "type": "alert",
  "content": "6666"
}
```
Or for a normal message:
```json
{
  "title": "HA Notification",
  "content": "Front door opened",
  "linesAfter": 3
}
```

### 3. Desktop GUI Server
1. Run the server: `python server.py`
2. Enter the IP shown in the GUI into the Android app and tap **Connect**.
3. Use **Normal** mode for custom text or **B.A.N.U.S.U.G.E** for stylized alerts.

## Building the Server EXE
If you want to use the server without Python installed:
1. `python -m PyInstaller --onefile --noconsole server.py`
2. Find `server.exe` in the `dist/` folder.

## Technical Notes

- **Resolution**: 384 pixels is the native width for Sunmi V2 Pro.
- **B.A.N.U.S.U.G.E**: Part of the Background Alert Notification Utility for Security Updates & General Events system.
