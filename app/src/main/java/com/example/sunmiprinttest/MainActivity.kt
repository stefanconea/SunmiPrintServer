package com.example.sunmiprinttest

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.text.Editable
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextWatcher
import android.text.style.AbsoluteSizeSpan
import android.text.style.AlignmentSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.Base64
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.createBitmap
import androidx.preference.PreferenceManager
import androidx.appcompat.widget.Toolbar
import com.google.gson.Gson
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.sunmi.peripheral.printer.InnerPrinterCallback
import com.sunmi.peripheral.printer.InnerPrinterException
import com.sunmi.peripheral.printer.InnerPrinterManager
import com.sunmi.peripheral.printer.InnerResultCallback
import com.sunmi.peripheral.printer.SunmiPrinterService
import fi.iki.elonen.NanoHTTPD
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    companion object {
        // Heuristics used to approximate how long a physical print takes, since the SDK
        // has no true print-completion callback (see renderAndPrintBitmap).
        private const val MS_PER_PIXEL_ROW = 2
        private const val MIN_WATCH_MS = 1500
        private const val WATCH_STEP_MS = 200
    }

    private var printerService: SunmiPrinterService? = null
    private var tcpSocket: Socket? = null
    private var mqttClient: MqttClient? = null
    private var httpServer: AppHttpServer? = null
    private var escPosServer: EscPosServer? = null
    private val gson = Gson()
    private lateinit var prefs: SharedPreferences

    private lateinit var statusText: TextView
    private lateinit var btnConnect: Button
    private lateinit var httpServerInfo: TextView
    private lateinit var tcpServerInfo: TextView
    private lateinit var printButton: Button
    private lateinit var titleField: EditText
    private lateinit var titleSizeField: EditText
    private lateinit var centerTitleCheckBox: CheckBox
    private lateinit var inputField: EditText
    private lateinit var textSizeField: EditText
    private lateinit var printModeSpinner: Spinner
    private lateinit var alignmentSpinner: Spinner
    private lateinit var livePreview: TextView
    private lateinit var livePreviewImage: ImageView
    private lateinit var hwStatusText: TextView
    private lateinit var entranceQuantityField: EditText
    private lateinit var entranceButton: Button

    private var currentPrinterStatus: Int = 1 
    private var isMonitoringStatus = false

    private val previewHandler = Handler(Looper.getMainLooper())
    private var previewRunnable: Runnable? = null
    private var previewCounter = 0
    private val printExecutor = Executors.newSingleThreadExecutor()

    private val printerCallback = object : InnerPrinterCallback() {
        override fun onConnected(service: SunmiPrinterService) {
            printerService = service
            runOnUiThread {
                statusText.text = getString(R.string.status_connected)
                printButton.isEnabled = true
                entranceButton.isEnabled = true
            }
            startStatusMonitoring()
        }

        override fun onDisconnected() {
            printerService = null
            isMonitoringStatus = false
            runOnUiThread {
                statusText.text = getString(R.string.status_disconnected)
                printButton.isEnabled = false
                entranceButton.isEnabled = false
            }
        }
    }

    data class PrintJob(
        val type: String? = "plain", 
        val title: String? = null,
        val content: String? = null,
        val text: String? = null,
        val message: String? = null,
        val titleSize: Int? = null,
        val contentSize: Int? = null,
        val centerTitle: Boolean? = null,
        val alignment: Int? = null,
        val linesAfter: Int? = null,
        val timestamp: String? = null,
        val quantity: Int? = null
    )

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        prefs = PreferenceManager.getDefaultSharedPreferences(this)

        statusText = findViewById(R.id.statusText)
        hwStatusText = findViewById(R.id.hwStatusText)
        btnConnect = findViewById(R.id.btnConnect)
        httpServerInfo = findViewById(R.id.httpServerInfo)
        tcpServerInfo = findViewById(R.id.tcpServerInfo)
        printButton = findViewById(R.id.btnPrint)
        titleField = findViewById(R.id.titleField)
        titleSizeField = findViewById(R.id.titleSizeField)
        centerTitleCheckBox = findViewById(R.id.centerTitleCheckBox)
        inputField = findViewById(R.id.inputField)
        textSizeField = findViewById(R.id.textSizeField)
        printModeSpinner = findViewById(R.id.printModeSpinner)
        alignmentSpinner = findViewById(R.id.alignmentSpinner)
        livePreview = findViewById(R.id.livePreview)
        livePreviewImage = findViewById(R.id.livePreviewImage)
        entranceQuantityField = findViewById(R.id.entranceQuantityField)
        entranceButton = findViewById(R.id.btnEntrance)

        val previewScrollContainer: View = findViewById(R.id.previewScrollContainer)
        previewScrollContainer.setOnTouchListener { v, _ ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            v.performClick()
            false
        }

        btnConnect.setOnClickListener { toggleConnection() }
        setupLivePreview()
        printButton.setOnClickListener { printFromFields() }
        entranceButton.setOnClickListener { printEntranceReceipt() }

        startHttpServer()
        startEscPosServer()
        autoConnectMqtt()
        autoConnectDesktopServer()

        try {
            InnerPrinterManager.getInstance().bindService(this, printerCallback)
        } catch (e: InnerPrinterException) {
            statusText.text = getString(R.string.status_error, e.message)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                return true
            }
            R.id.action_logs -> {
                startActivity(Intent(this, LogsActivity::class.java))
                return true
            }
            R.id.action_job_logs -> {
                startActivity(Intent(this, JobLogsActivity::class.java))
                return true
            }
            R.id.action_entrance_receipts -> {
                startActivity(Intent(this, EntranceReceiptsActivity::class.java))
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun startHttpServer() {
        val ip = getLocalIpAddress()
        val info = "HTTP Server: http://$ip:8081/print"
        httpServerInfo.text = info
        httpServer = AppHttpServer(8081)
        try {
            httpServer?.start()
        } catch (e: Exception) {
            val err = "HTTP Server Error: ${e.message}"
            httpServerInfo.text = err
        }
    }

    private fun startEscPosServer() {
        val ip = getLocalIpAddress()
        val info = "TCP ESC/POS: $ip:9100"
        tcpServerInfo.text = info
        escPosServer = EscPosServer(9100)
        escPosServer?.start()
    }

    private fun autoConnectMqtt() {
        val broker = prefs.getString("mqtt_broker", "")
        if (!broker.isNullOrEmpty()) {
            thread { connectMqtt() }
        }
    }

    private fun autoConnectDesktopServer() {
        val url = prefs.getString("desktop_server_url", "192.168.1.241:8080") ?: "192.168.1.241:8080"
        thread {
            while (true) {
                if (tcpSocket == null || tcpSocket?.isClosed == true) {
                    performTcpConnect(url)
                }
                Thread.sleep(5000)
            }
        }
    }

    private fun performTcpConnect(url: String) {
        val parts = url.split(":")
        val ip = parts[0]
        val port = if (parts.size > 1) parts[1].toInt() else 8080
        try {
            val socket = Socket(ip, port)
            tcpSocket = socket
            runOnUiThread { btnConnect.text = getString(R.string.btn_disconnect) }
            LogManager.addLog("Connected to desktop server at $ip")
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            while (true) {
                val line = reader.readLine() ?: break
                try {
                    val job = gson.fromJson(line, PrintJob::class.java)
                    processJob(job, source = "Desktop Server (Python)")
                } catch (_: Exception) {
                    processJob(PrintJob(content = line), source = "Desktop Server (Python)")
                }
            }
        } catch (e: Exception) {
            LogManager.addLog("TCP Connect attempt failed: ${e.message}")
        } finally {
            try { tcpSocket?.close() } catch (_: Exception) {}
            tcpSocket = null
            runOnUiThread { btnConnect.text = getString(R.string.btn_connect) }
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress) {
                        val sAddr = addr.hostAddress
                        if (sAddr != null) {
                            val isIPv4 = sAddr.indexOf(':') < 0
                            if (isIPv4) return sAddr
                        }
                    }
                }
            }
        } catch (_: Exception) { }
        return "0.0.0.0"
    }

    private fun printerStatusLabel(status: Int): String = when (status) {
        1 -> "Ready"
        2 -> "Preparing"
        3 -> "Comms Error"
        4 -> "No Paper"
        5 -> "Overheated"
        6 -> "Door Open"
        7 -> "Cutter Error"
        else -> "Error ($status)"
    }

    private fun startStatusMonitoring() {
        if (isMonitoringStatus) return
        isMonitoringStatus = true
        thread {
            while (isMonitoringStatus) {
                val service = printerService ?: break
                try {
                    currentPrinterStatus = service.updatePrinterState()
                    val statusStr = printerStatusLabel(currentPrinterStatus)
                    val colorStr = if (currentPrinterStatus == 1) "#388E3C" else "#D32F2F"
                    runOnUiThread {
                        hwStatusText.text = "[$statusStr]"
                        hwStatusText.setTextColor(Color.parseColor(colorStr))
                    }
                } catch (_: RemoteException) {
                    isMonitoringStatus = false
                }
                Thread.sleep(2000)
            }
        }
    }

    inner class AppHttpServer(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            if (session.method == Method.GET && (session.uri == "/" || session.uri == "")) {
                val html = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta name="viewport" content="width=device-width, initial-scale=1">
                        <title>Sunmi Web Print</title>
                        <style>
                            body { font-family: sans-serif; padding: 20px; text-align: center; }
                            textarea { width: 100%; height: 150px; margin: 10px 0; border-radius: 8px; border: 1px solid #ccc; padding: 10px; }
                            button { background: #4CAF50; color: white; padding: 15px 30px; border: none; border-radius: 8px; font-size: 18px; cursor: pointer; }
                            input { width: 100%; padding: 10px; margin-bottom: 10px; border-radius: 8px; border: 1px solid #ccc; box-sizing: border-box; }
                        </style>
                    </head>
                    <body>
                        <h1>Sunmi Web Print</h1>
                        <input type="text" id="title" placeholder="Title (Optional)">
                        <textarea id="content" placeholder="Enter text or emojis to print..."></textarea>
                        <div style="margin: 10px 0;">
                            <label><input type="checkbox" id="isAlert"> B.A.N.U.S.U.G.E (Alert Mode)</label>
                        </div>
                        <button onclick="doPrint()">PRINT TO SUNMI</button>
                        <p id="status"></p>
                        <script>
                            function doPrint() {
                                const btn = document.querySelector('button');
                                btn.disabled = true;
                                const isAlert = document.getElementById('isAlert').checked;
                                fetch('/print', {
                                    method: 'POST',
                                    headers: { 'Content-Type': 'application/json' },
                                    body: JSON.stringify({
                                        type: isAlert ? 'alert' : 'plain',
                                        title: document.getElementById('title').value,
                                        content: document.getElementById('content').value,
                                        linesAfter: 3
                                    })
                                }).then(r => {
                                    document.getElementById('status').innerText = r.ok ? 'Sent successfully!' : 'Error sending print job';
                                    btn.disabled = false;
                                });
                            }
                        </script>
                    </body>
                    </html>
                """.trimIndent()
                return newFixedLengthResponse(html)
            }

            if (session.method == Method.POST && session.uri == "/print") {
                try {
                    val map = mutableMapOf<String, String>()
                    session.parseBody(map)
                    val json = map["postData"] ?: session.queryParameterString
                    val job = gson.fromJson(json, PrintJob::class.java)
                    processJob(job, source = "HTTP Server")
                    return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"ok\"}")
                } catch (e: Exception) {
                    return try {
                        val body = session.inputStream.bufferedReader().readText()
                        val job = gson.fromJson(body, PrintJob::class.java)
                        processJob(job, source = "HTTP Server")
                        newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"ok\"}")
                    } catch (_: Exception) {
                        newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Error: ${e.message}")
                    }
                }
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found. Use POST /print")
        }
    }

    inner class EscPosServer(private val port: Int) {
        private var serverSocket: ServerSocket? = null
        private var running = false
        private var lastQrContent: String? = null

        fun start() {
            running = true
            thread {
                try {
                    serverSocket = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) }
                    LogManager.addLog("ESC/POS Server listening on port $port")
                    while (running) {
                        val client = serverSocket?.accept() ?: break
                        handleClient(client)
                    }
                } catch (e: Exception) { LogManager.addLog("ESC/POS Server Error: ${e.message}") }
            }
        }

        private fun handleClient(socket: Socket) {
            thread {
                val byteBuffer = ByteArrayOutputStream()
                var currentAlign = 0 // 0=Left, 1=Center, 2=Right
                try {
                    val dis = DataInputStream(socket.getInputStream())
                    val out = socket.getOutputStream()
                    LogManager.addLog("HA Connection opened")
                    printerService?.printerInit(null)
                    
                    while (socket.isConnected && !socket.isClosed) {
                        val b = dis.readUnsignedByte()
                        when (b) {
                            0x10 -> { if (dis.readUnsignedByte() == 0x04) out.write(getStatusResponse(dis.readUnsignedByte())) }
                            0x1B -> {
                                when (val b2 = dis.readUnsignedByte()) {
                                    0x40 -> { currentAlign = 0; printerService?.printerInit(null) }
                                    0x61 -> { 
                                        val n = dis.readUnsignedByte()
                                        currentAlign = when(n) {
                                            1, 49 -> 1
                                            2, 50 -> 2
                                            else -> 0
                                        }
                                    }
                                    0x21, 0x2D, 0x4A, 0x64, 0x74, 0x45, 0x47, 0x4D, 0x56, 0x7B -> { dis.readUnsignedByte() }
                                    0x70 -> { repeat(3) { dis.readUnsignedByte() } }
                                    0x2A -> {
                                        val m = dis.readUnsignedByte(); val nL = dis.readUnsignedByte(); val nH = dis.readUnsignedByte()
                                        val dataLen = (nL + (nH shl 8)) * (if (m == 32 || m == 33) 3 else 1)
                                        dis.readFully(ByteArray(dataLen))
                                    }
                                }
                            }
                            0x1D -> {
                                when (val b2 = dis.readUnsignedByte()) {
                                    0x21, 0x77, 0x68, 0x66, 0x48 -> { dis.readUnsignedByte() }
                                    0x56 -> { if (dis.readUnsignedByte() > 64) dis.readUnsignedByte() }
                                    0x28 -> { // GS (
                                        if (dis.readUnsignedByte() == 0x6B) { // k
                                            val pL = dis.readUnsignedByte(); val pH = dis.readUnsignedByte()
                                            val len = pL + (pH shl 8)
                                            val payload = ByteArray(len)
                                            dis.readFully(payload)
                                            val fn = payload[1].toInt() and 0xFF
                                            if (fn == 80) {
                                                lastQrContent = String(payload.copyOfRange(3, len)).trim()
                                            } else if (fn == 81) {
                                                lastQrContent?.let {
                                                    processJob(PrintJob(type = "qr", content = it, alignment = currentAlign), source = "ESC/POS Server")
                                                }
                                            }
                                        }
                                    }
                                    0x76 -> { // GS v 0 (Bit Image)
                                        if (dis.readUnsignedByte() == 0x30) {
                                            dis.readUnsignedByte() // m
                                            val xL = dis.readUnsignedByte(); val xH = dis.readUnsignedByte()
                                            val yL = dis.readUnsignedByte(); val yH = dis.readUnsignedByte()
                                            val wBytes = xL + (xH shl 8); val hPixels = yL + (yH shl 8)
                                            val data = ByteArray(wBytes * hPixels)
                                            dis.readFully(data)
                                            renderEscPosImage(wBytes, hPixels, data, currentAlign)
                                        }
                                    }
                                }
                            }
                            10, 13 -> {
                                flushTextBytes(byteBuffer, currentAlign)
                            }
                            else -> {
                                byteBuffer.write(b)
                            }
                        }
                    }
                } catch (e: Exception) { } finally {
                    flushTextBytes(byteBuffer, currentAlign)
                    try { 
                        Thread.sleep(500)
                        printerService?.lineWrap(3, null)
                        socket.close() 
                    } catch (_: Exception) {}
                    LogManager.addLog("HA Connection closed")
                }
            }
        }

        private fun renderEscPosImage(wBytes: Int, hPixels: Int, data: ByteArray, align: Int) {
            val imgWidth = wBytes * 8
            val bitmap = createBitmap(imgWidth, hPixels, Bitmap.Config.ARGB_8888)
            for (y in 0 until hPixels) {
                for (xByte in 0 until wBytes) {
                    val b = data[y * wBytes + xByte].toInt() and 0xFF
                    for (bit in 0 until 8) {
                        val color = if ((b shr (7 - bit)) and 1 == 1) Color.BLACK else Color.WHITE
                        bitmap.setPixel(xByte * 8 + bit, y, color)
                    }
                }
            }
            val finalBitmap = createBitmap(384, hPixels, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(finalBitmap)
            canvas.drawColor(Color.WHITE)
            val xOffset = when(align) {
                1 -> ((384 - imgWidth) / 2).toFloat()
                2 -> (384 - imgWidth).toFloat()
                else -> 0f
            }
            canvas.drawBitmap(bitmap, xOffset, 0f, null)
            processJob(PrintJob(type = "bitmap", alignment = align, content = ""), finalBitmap, source = "ESC/POS Server")
        }

        private fun flushTextBytes(os: ByteArrayOutputStream, align: Int) {
            val bytes = os.toByteArray()
            if (bytes.isNotEmpty()) {
                val line = String(bytes, java.nio.charset.Charset.forName("ISO-8859-1"))
                processJob(PrintJob(content = line, alignment = align, linesAfter = 0, contentSize = 22), source = "ESC/POS Server")
            }
            os.reset()
        }

        private fun getStatusResponse(n: Int): Int = when (n) {
            1 -> if (currentPrinterStatus == 1) 0x12 else 0x1E
            2 -> if (currentPrinterStatus == 6) 0x16 else 0x12
            4 -> if (currentPrinterStatus == 4) 0x72 else 0x12
            else -> 0x12
        }

        fun stop() { running = false; try { serverSocket?.close() } catch (_: Exception) {} }
    }

    private fun connectMqtt() {
        val broker = prefs.getString("mqtt_broker", "") ?: return
        val topic = prefs.getString("mqtt_topic", "sunmi/print") ?: "sunmi/print"
        try {
            val clientId = "SunmiPrinter_" + System.currentTimeMillis()
            mqttClient = MqttClient(broker, clientId, MemoryPersistence())
            val options = MqttConnectOptions(); options.isCleanSession = true
            mqttClient?.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    LogManager.addLog("MQTT connection lost: ${cause?.message}")
                    thread { Thread.sleep(5000); connectMqtt() }
                }
                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    val payload = message?.toString() ?: return
                    try { processJob(gson.fromJson(payload, PrintJob::class.java), source = "MQTT") }
                    catch (_: Exception) { processJob(PrintJob(content = payload), source = "MQTT") }
                }
                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })
            mqttClient?.connect(options); mqttClient?.subscribe(topic)
            LogManager.addLog("Connected to MQTT broker: $broker")
        } catch (e: Exception) { LogManager.addLog("MQTT Error: ${e.message}") }
    }

    private fun toggleConnection() {
        if (tcpSocket == null || tcpSocket?.isClosed == true) {
            val url = prefs.getString("desktop_server_url", "192.168.1.241:8080") ?: return
            thread { performTcpConnect(url) }
        } else {
            thread { tcpSocket?.close(); tcpSocket = null
                runOnUiThread { btnConnect.text = getString(R.string.btn_connect) }
                LogManager.addLog("Disconnected from desktop server")
            }
        }
    }

    private fun setupLivePreview() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { updatePreview() }
        }
        titleField.addTextChangedListener(watcher); titleSizeField.addTextChangedListener(watcher)
        inputField.addTextChangedListener(watcher); textSizeField.addTextChangedListener(watcher)
        centerTitleCheckBox.setOnCheckedChangeListener { _, _ -> updatePreview() }
        val spinnerListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long) { updatePreview() }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
        alignmentSpinner.onItemSelectedListener = spinnerListener; printModeSpinner.onItemSelectedListener = spinnerListener
        updatePreview()
    }

    private fun updatePreview() {
        val mode = getModeKey(printModeSpinner.selectedItemPosition)
        val title = titleField.text.toString(); val content = inputField.text.toString()
        val isTitleCentered = centerTitleCheckBox.isChecked; val alignment = alignmentSpinner.selectedItemPosition
        val isEmpty = title.trim().isEmpty() && content.trim().isEmpty(); val myId = ++previewCounter
        if (isEmpty && mode != "alert") {
            previewRunnable?.let { previewHandler.removeCallbacks(it) }
            livePreviewImage.visibility = View.GONE; livePreview.visibility = View.VISIBLE; livePreview.text = ""; return
        }
        previewRunnable?.let { previewHandler.removeCallbacks(it) }
        val job = PrintJob(type = mode, title = title, content = content, titleSize = titleSizeField.text.toString().toIntOrNull() ?: 32,
            contentSize = textSizeField.text.toString().toIntOrNull() ?: 26, centerTitle = isTitleCentered, alignment = alignment)
        previewRunnable = Runnable { thread {
            val bitmap = renderJobToBitmap(job)
            runOnUiThread {
                if (myId != previewCounter) return@runOnUiThread
                if (titleField.text.toString().trim().isEmpty() && inputField.text.toString().trim().isEmpty() && mode != "alert") {
                    livePreviewImage.visibility = View.GONE; livePreview.visibility = View.VISIBLE; livePreview.text = ""; return@runOnUiThread
                }
                if (mode in listOf("barcode", "qr", "image", "boxed", "banner")) {
                    livePreview.visibility = View.GONE; livePreviewImage.visibility = View.VISIBLE; livePreviewImage.setImageBitmap(bitmap)
                } else {
                    livePreviewImage.visibility = View.GONE; livePreview.visibility = View.VISIBLE
                    livePreview.text = generateStyledBuilder(job)
                    livePreview.gravity = if (mode == "centered" || mode == "alert") Gravity.CENTER_HORIZONTAL else Gravity.START
                }
            }
        } }
        previewHandler.postDelayed(previewRunnable!!, 200)
    }

    private fun getModeKey(index: Int): String = when (index) {
        0 -> "plain"; 1 -> "centered"; 2 -> "boxed"; 3 -> "header_body"; 4 -> "banner"; 5 -> "list"; 6 -> "barcode"; 7 -> "qr"; 8 -> "image"; 9 -> "alert"; else -> "plain"
    }

    private fun printEntranceReceipt() {
        val quantity = entranceQuantityField.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1
        processJob(PrintJob(type = "guard_receipt", quantity = quantity))
    }

    private fun printFromFields() = processJob(PrintJob(type = getModeKey(printModeSpinner.selectedItemPosition),
        title = titleField.text.toString(), content = inputField.text.toString(), titleSize = titleSizeField.text.toString().toIntOrNull() ?: 32,
        contentSize = textSizeField.text.toString().toIntOrNull() ?: 26, centerTitle = centerTitleCheckBox.isChecked,
        alignment = alignmentSpinner.selectedItemPosition, linesAfter = prefs.getString("default_lines_after", "3")?.toIntOrNull() ?: 3))

    private fun processJob(job: PrintJob, overrideBitmap: Bitmap? = null, source: String = "Local") {
        val jobId = JobLogManager.startJob(source, job.type ?: "plain")
        printExecutor.submit {
            val bitmap = overrideBitmap ?: renderJobToBitmap(job)
            val linesAfter = job.linesAfter
                ?: prefs.getString("default_lines_after", "3")?.toIntOrNull() ?: 3
            renderAndPrintBitmap(bitmap, linesAfter, jobId)
        }
    }

    private fun generateStyledBuilder(job: PrintJob): SpannableStringBuilder {
        val type = job.type ?: "plain"
        if (type == "alert") return generateBanuSugeAlertBuilder(job.content ?: "6666", job.timestamp)
        val builder = SpannableStringBuilder()
        val title = job.title ?: ""; val content = job.content ?: job.text ?: job.message ?: ""
        val titleSize = job.titleSize ?: 32; val contentSize = job.contentSize ?: 26
        
        val alignment = when {
            job.alignment == 1 || type == "centered" -> Layout.Alignment.ALIGN_CENTER
            job.alignment == 2 -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        }

        if (title.isNotEmpty()) {
            val start = builder.length; builder.append(title).append("\n")
            builder.setSpan(AbsoluteSizeSpan(titleSize), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (job.centerTitle == true || type == "centered") builder.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (type == "header_body") builder.setSpan(StyleSpan(Typeface.BOLD), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (content.isNotEmpty()) {
            val start = builder.length
            if (type == "list") {
                content.split("\n").filter { it.isNotBlank() }.forEach { line ->
                    val lineStart = builder.length; builder.append("• ").append(line).append("\n")
                    builder.setSpan(AbsoluteSizeSpan(contentSize), lineStart, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(AlignmentSpan.Standard(alignment), lineStart, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            } else {
                builder.append(content)
                builder.setSpan(AbsoluteSizeSpan(contentSize), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(AlignmentSpan.Standard(alignment), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                
                // Force Monospace for ESC/POS or table-like content
                if (content.contains("  ") || type == "plain") {
                    builder.setSpan(TypefaceSpan("monospace"), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }

                if (type == "banner") { builder.setSpan(StyleSpan(Typeface.BOLD), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE); builder.setSpan(AbsoluteSizeSpan(80), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
            }
        }
        return builder
    }

    private fun generateBanuSugeAlertBuilder(content: String, sentTime: String? = null): SpannableStringBuilder {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val now = sdf.format(Date()); val sent = sentTime ?: now; val builder = SpannableStringBuilder(); val center = AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER)
        fun appendCentered(text: String, size: Int, bold: Boolean = false) {
            val start = builder.length; builder.append(text)
            builder.setSpan(AbsoluteSizeSpan(size), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (bold) builder.setSpan(StyleSpan(Typeface.BOLD), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(center, start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        appendCentered("ALERT\n", 60, true); appendCentered("WARNING\n", 32); appendCentered("\n", 20) 
        appendCentered("- - - - - - - - - - - - - - - - - - - -\n", 20); appendCentered("\n", 5); appendCentered("${content.trim()}\n", 50, true); appendCentered("\n", 5); appendCentered("- - - - - - - - - - - - - - - - - - - -\n", 20)
        appendCentered("\n", 15); appendCentered("* * * * * * *\n", 20); appendCentered("\n", 5); appendCentered("unknown\n", 26); appendCentered("sent: $sent\nrecv: $now\n", 22); appendCentered("\n", 5); appendCentered("* * * * * * *\n", 20)
        appendCentered("\n", 15); appendCentered("Thank you for using B.A.N.U.S.U.G.E\n", 26); appendCentered("(Background Alert Notification Utility for Security Updates & General Events)\n", 14)
        return builder
    }

    private fun padRow(label: String, value: String, width: Int = 26): String {
        val spaces = (width - label.length - value.length).coerceAtLeast(1)
        return label + " ".repeat(spaces) + value
    }

    /** How many monospace characters at [sizePx] fit across the printable [canvasWidthPx],
     *  measured directly rather than guessed -- character width doesn't scale perfectly
     *  linearly with point size, so a guessed ratio drifts and wraps mid-word. Shaves one
     *  character off as a rounding safety margin. */
    private fun monoCharsPerLine(sizePx: Int, canvasWidthPx: Int = 384): Int {
        val charWidth = TextPaint().apply {
            typeface = Typeface.MONOSPACE
            textSize = sizePx.toFloat()
        }.measureText("0")
        return (canvasWidthPx / charWidth).toInt() - 1
    }

    private fun formatMoney(value: Double): String =
        String.format(Locale.getDefault(), "%.2f", value).replace('.', ',') + " lei"

    private fun generateGuardReceiptBuilder(quantity: Int, receiptNumber: String, now: String): SpannableStringBuilder {
        val companyName = prefs.getString("guard_company_name", "Guard")?.takeIf { it.isNotBlank() } ?: "Guard"
        val unitPrice = prefs.getString("guard_price", "50.00")?.replace(',', '.')?.toDoubleOrNull() ?: 50.0
        val total = unitPrice * quantity

        val bodySize = 22
        val priceRowSize = 24
        val qtyRowSize = 24
        val totalSize = 30
        val ruleWidth = monoCharsPerLine(bodySize)
        val priceRowWidth = monoCharsPerLine(priceRowSize)
        val totalWidth = monoCharsPerLine(totalSize)
        val rule = "-".repeat(ruleWidth)

        val builder = SpannableStringBuilder()
        fun alignNormal() = AlignmentSpan.Standard(Layout.Alignment.ALIGN_NORMAL)

        val headerStart = builder.length
        builder.append(companyName).append("\n")
        val headerEnd = builder.length
        builder.setSpan(AbsoluteSizeSpan(26), headerStart, headerEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(StyleSpan(Typeface.BOLD), headerStart, headerEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(alignNormal(), headerStart, headerEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        val bodyStart = builder.length
        builder.append("Employee: Owner\n")
        builder.append("POS: POS 1\n")
        builder.append(rule).append("\n")
        val priceRowStart = builder.length
        builder.append(padRow("Intrare interzisa", formatMoney(unitPrice), priceRowWidth)).append("\n")
        val priceRowEnd = builder.length
        val qtyRowStart = builder.length
        builder.append("$quantity x ${formatMoney(unitPrice)}\n")
        val qtyRowEnd = builder.length
        builder.append(rule).append("\n")
        val totalStart = builder.length
        builder.append(padRow("Total", formatMoney(total), totalWidth)).append("\n")
        val totalEnd = builder.length
        builder.append(padRow("Cash", formatMoney(total), ruleWidth)).append("\n")
        builder.append(rule).append("\n")
        builder.append(now).append("\n")
        builder.append(receiptNumber)
        val bodyEnd = builder.length

        builder.setSpan(AbsoluteSizeSpan(bodySize), bodyStart, bodyEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(TypefaceSpan("monospace"), bodyStart, bodyEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(alignNormal(), bodyStart, bodyEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(AbsoluteSizeSpan(priceRowSize), priceRowStart, priceRowEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(AbsoluteSizeSpan(qtyRowSize), qtyRowStart, qtyRowEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(AbsoluteSizeSpan(totalSize), totalStart, totalEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(StyleSpan(Typeface.BOLD), totalStart, totalEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        return builder
    }

    private fun renderJobToBitmap(job: PrintJob): Bitmap {
        val width = 384; val type = job.type ?: "plain"
        if (type == "guard_receipt") {
            val quantity = job.quantity ?: 1

            // Persisted across prints so receipts are sequentially numbered like a real POS.
            val counter = prefs.getInt("guard_receipt_counter", 0) + 1
            prefs.edit().putInt("guard_receipt_counter", counter).apply()
            val receiptNumber = "#1-%04d".format(counter)
            val sdf = SimpleDateFormat("dd-MM-yyyy hh:mm a", Locale.getDefault())
            val now = sdf.format(Date())

            val unitPrice = prefs.getString("guard_price", "50.00")?.replace(',', '.')?.toDoubleOrNull() ?: 50.0
            EntranceReceiptManager.addReceipt(prefs, counter, receiptNumber, now, unitPrice * quantity)

            val textBitmap = renderTextToBitmap(generateGuardReceiptBuilder(quantity, receiptNumber, now), width)

            val qrBitmap = try {
                val bitMatrix: BitMatrix = MultiFormatWriter().encode("$receiptNumber $now", BarcodeFormat.QR_CODE, 220, 220)
                val bmp = createBitmap(bitMatrix.width, bitMatrix.height, Bitmap.Config.ARGB_8888)
                for (x in 0 until bitMatrix.width) { for (y in 0 until bitMatrix.height) bmp.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE) }
                bmp
            } catch (_: Exception) { null }

            if (qrBitmap == null) return textBitmap
            val qrTopMargin = -10
            val finalBitmap = createBitmap(width, textBitmap.height + qrBitmap.height + qrTopMargin + 6, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(finalBitmap)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(textBitmap, 0f, 0f, null)
            canvas.drawBitmap(qrBitmap, ((width - qrBitmap.width) / 2).toFloat(), (textBitmap.height + qrTopMargin).toFloat(), null)
            return finalBitmap
        }
        if (type == "image") {
            return try {
                val data = job.content ?: ""; val bitmap = if (data.startsWith("http")) BitmapFactory.decodeStream(URL(data).openConnection().getInputStream()) else { val ds = Base64.decode(data, Base64.DEFAULT); BitmapFactory.decodeByteArray(ds, 0, ds.size) }
                val scale = width.toFloat() / bitmap.width; Bitmap.createScaledBitmap(bitmap, width, (bitmap.height * scale).toInt(), true)
            } catch (e: Exception) { renderTextToBitmap(SpannableStringBuilder("Image Load Error: ${e.message}"), width) }
        }
        if (type == "qr" || type == "barcode") {
            return try {
                val format = if (type == "qr") BarcodeFormat.QR_CODE else BarcodeFormat.CODE_128
                val bitMatrix: BitMatrix = MultiFormatWriter().encode(job.content ?: "0", format, if (type == "qr") 250 else 380, if (type == "qr") 250 else 100)
                val bitmap = createBitmap(bitMatrix.width, bitMatrix.height, Bitmap.Config.ARGB_8888)
                for (x in 0 until bitMatrix.width) { for (y in 0 until bitMatrix.height) bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE) }
                val xOffset = when(job.alignment) {
                    1 -> ((width - bitMatrix.width) / 2).toFloat()
                    2 -> (width - bitMatrix.width).toFloat()
                    else -> 0f
                }
                val finalBitmap = createBitmap(width, bitMatrix.height + 20, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(finalBitmap); canvas.drawColor(Color.WHITE); canvas.drawBitmap(bitmap, xOffset, 10f, null)
                finalBitmap
            } catch (e: Exception) { renderTextToBitmap(SpannableStringBuilder("$type Error: ${e.message}"), width) }
        }
        if (type == "boxed") {
            val padding = 16; val innerWidth = width - (padding * 2); val innerBitmap = renderTextToBitmap(generateStyledBuilder(job), innerWidth)
            val boxedBitmap = createBitmap(width, innerBitmap.height + (padding * 2), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(boxedBitmap); canvas.drawColor(Color.WHITE); val paint = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 4f }
            canvas.drawRect(2f, 2f, (width - 2).toFloat(), (innerBitmap.height + (padding * 2) - 2).toFloat(), paint); canvas.drawBitmap(innerBitmap, padding.toFloat(), padding.toFloat(), null)
            return boxedBitmap
        }
        return renderTextToBitmap(generateStyledBuilder(job), width)
    }

    private fun renderTextToBitmap(builder: SpannableStringBuilder, width: Int): Bitmap {
        val textPaint = TextPaint().apply { color = Color.BLACK; isAntiAlias = true }
        val alignmentSpans = builder.getSpans(0, builder.length, AlignmentSpan::class.java)
        val baseAlignment = when {
            alignmentSpans.any { it.alignment == Layout.Alignment.ALIGN_CENTER } -> Layout.Alignment.ALIGN_CENTER
            alignmentSpans.any { it.alignment == Layout.Alignment.ALIGN_OPPOSITE } -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        }
        val staticLayout = StaticLayout.Builder.obtain(builder, 0, builder.length, textPaint, width).setAlignment(baseAlignment).build()
        val bitmap = createBitmap(width, staticLayout.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE); staticLayout.draw(canvas)
        return thresholdBitmap(bitmap)
    }

    private fun renderAndPrintBitmap(bitmap: Bitmap, linesAfter: Int, jobId: Int = -1) {
        val service = printerService ?: run {
            JobLogManager.completeJob(jobId, false, "printer not connected")
            return
        }
        val sdkFailed = AtomicBoolean(false)
        val sdkFailReason = AtomicReference<String?>(null)
        val resultCallback = object : InnerResultCallback() {
            // isSuccess only means "the print command was accepted" -- it fires almost
            // immediately and does not wait for the physical print to finish, so it can't
            // by itself catch a fault (e.g. door opened) introduced mid-print. Record an
            // explicit SDK-level failure here; the actual pass/fail verdict is decided below
            // after watching live hardware state for a window sized to the content. This
            // callback may fire on a different thread than the watch loop, hence the atomics.
            override fun onRunResult(isSuccess: Boolean) {
                if (!isSuccess) { sdkFailed.set(true); sdkFailReason.set("printer failure") }
            }
            override fun onReturnString(result: String?) {}
            override fun onRaiseException(code: Int, msg: String?) {
                sdkFailed.set(true); sdkFailReason.set(msg)
            }
            override fun onPrintResult(code: Int, msg: String?) {}
        }
        try {
            runOnUiThread { statusText.text = getString(R.string.status_printing) }
            service.printBitmap(bitmap, resultCallback)
            if (linesAfter > 0) service.lineWrap(linesAfter, null)
        } catch (e: RemoteException) {
            JobLogManager.completeJob(jobId, false, e.message)
            runOnUiThread { statusText.text = getString(R.string.status_error, e.message) }
            return
        }

        // There is no true print-completion callback from the SDK, so approximate one:
        // poll hardware state for a window sized to the bitmap's height (taller content
        // takes longer to physically feed through) and flag a failure if a fault (door
        // open, no paper, etc.) shows up at any point during that window.
        val watchMs = (bitmap.height * MS_PER_PIXEL_ROW).coerceAtLeast(MIN_WATCH_MS)
        var hwFault: String? = null
        var elapsed = 0
        while (elapsed < watchMs) {
            Thread.sleep(WATCH_STEP_MS.toLong())
            elapsed += WATCH_STEP_MS
            try {
                val status = service.updatePrinterState()
                if (status != 1) hwFault = printerStatusLabel(status)
            } catch (_: RemoteException) {}
        }

        val success = !sdkFailed.get() && hwFault == null
        val reason = hwFault ?: sdkFailReason.get() ?: "printer failure"
        JobLogManager.completeJob(jobId, success, if (success) null else reason)
        runOnUiThread { statusText.text = if (success) getString(R.string.status_done) else getString(R.string.status_error, reason) }
    }

    private fun thresholdBitmap(bitmap: Bitmap): Bitmap {
        val width = bitmap.width; val height = bitmap.height; val pixels = IntArray(width * height); val resultPixels = IntArray(width * height); bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        for (y in 0 until height) { for (x in 0 until width) {
            val i = y * width + x; val color = pixels[i]; val r = (color shr 16) and 0xff; val g = (color shr 8) and 0xff; val b = color and 0xff; val luminance = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            val isFeature = luminance < 80; var isEdge = false
            if (luminance < 250) { for (dy in -2..2) { for (dx in -2..2) {
                val ny = y + dy; val nx = x + dx
                if (ny in 0 until height && nx in 0 until width) {
                    val nColor = pixels[ny * width + nx]; val nLuminance = (0.299 * ((nColor shr 16) and 0xff) + 0.587 * ((nColor shr 8) and 0xff) + 0.114 * (nColor and 0xff)).toInt()
                    if (nLuminance > 250) { isEdge = true; break }
                }
            } ; if (isEdge) break } }
            resultPixels[i] = if (isFeature || isEdge) Color.BLACK else Color.WHITE
        } }
        val out = createBitmap(width, height, Bitmap.Config.ARGB_8888); out.setPixels(resultPixels, 0, width, 0, 0, width, height); return out
    }

    override fun onDestroy() { tcpSocket?.close(); mqttClient?.disconnect(); httpServer?.stop(); escPosServer?.stop(); try { InnerPrinterManager.getInstance().unBindService(this, printerCallback) } catch (_: InnerPrinterException) {}; super.onDestroy() }
}
