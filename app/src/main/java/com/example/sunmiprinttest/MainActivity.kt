package com.example.sunmiprinttest

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.RemoteException
import android.text.Editable
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextWatcher
import android.text.method.ScrollingMovementMethod
import android.text.style.AbsoluteSizeSpan
import android.text.style.AlignmentSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.createBitmap
import androidx.preference.PreferenceManager
import androidx.appcompat.widget.Toolbar
import com.google.gson.Gson
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
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

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
    private lateinit var testEmojiButton: Button

    private val printerCallback = object : InnerPrinterCallback() {
        override fun onConnected(service: SunmiPrinterService) {
            printerService = service
            runOnUiThread {
                statusText.text = getString(R.string.status_connected)
                printButton.isEnabled = true
                testEmojiButton.isEnabled = true
            }
        }

        override fun onDisconnected() {
            printerService = null
            runOnUiThread {
                statusText.text = getString(R.string.status_disconnected)
                printButton.isEnabled = false
                testEmojiButton.isEnabled = false
            }
        }
    }

    data class PrintJob(
        val type: String? = null,
        val title: String? = null,
        val content: String? = null,
        val text: String? = null,
        val message: String? = null,
        val titleSize: Int? = null,
        val contentSize: Int? = null,
        val centerTitle: Boolean? = null,
        val alignment: Int? = null,
        val linesAfter: Int? = null,
        val timestamp: String? = null
    )

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        prefs = PreferenceManager.getDefaultSharedPreferences(this)

        statusText = findViewById(R.id.statusText)
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
        testEmojiButton = findViewById(R.id.btnTestEmoji)

        livePreview.movementMethod = ScrollingMovementMethod()
        livePreview.setOnTouchListener { v, _ ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            v.performClick()
            false
        }

        btnConnect.setOnClickListener { toggleConnection() }
        setupLivePreview()
        printButton.setOnClickListener { printFromFields() }
        testEmojiButton.setOnClickListener { printThreeSmileyFaces() }

        startHttpServer()
        startEscPosServer()
        autoConnectMqtt()

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
                                        type: isAlert ? 'alert' : 'normal',
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
                    runOnUiThread { remotePrint(job) }
                    return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"ok\"}")
                } catch (e: Exception) {
                    return try {
                        val body = session.inputStream.bufferedReader().readText()
                        val job = gson.fromJson(body, PrintJob::class.java)
                        runOnUiThread { remotePrint(job) }
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

        fun start() {
            running = true
            thread {
                try {
                    serverSocket = ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(port))
                    }
                    LogManager.addLog("ESC/POS Server listening on port $port")
                    while (running) {
                        val client = serverSocket?.accept() ?: break
                        LogManager.addLog("HA Connection established: ${client.inetAddress.hostAddress}")
                        handleClient(client)
                    }
                } catch (e: Exception) {
                    LogManager.addLog("ESC/POS Server Error: ${e.message}")
                }
            }
        }

        private fun handleClient(socket: Socket) {
            thread {
                try {
                    val inputStream = socket.getInputStream()
                    val buffer = ByteArray(16384)
                    
                    while (socket.isConnected && !socket.isClosed) {
                        val bytesRead = inputStream.read(buffer)
                        if (bytesRead == -1) break 
                        
                        val data = buffer.copyOfRange(0, bytesRead)
                        LogManager.addLog("Received $bytesRead bytes from HA")
                        
                        // Smarter extraction: Skip ESC (27) and GS (29) command bytes
                        val cleanOutput = StringBuilder()
                        var i = 0
                        while (i < data.size) {
                            val b = data[i].toInt() and 0xFF
                            if (b == 0x1B || b == 0x1D) {
                                // It's a command prefix (ESC or GS), skip it and the identifier byte
                                i += 2 
                            } else {
                                if (b in 32..126 || b == 10 || b == 13) {
                                    cleanOutput.append(b.toChar())
                                }
                                i++
                            }
                        }
                        
                        val finalContent = cleanOutput.toString().replace(Regex("\\s+"), " ").trim()
                        if (finalContent.isNotEmpty()) {
                            LogManager.addLog("Printing cleaned HA job: ${finalContent.take(20)}...")
                            runOnUiThread { remotePrint(PrintJob(content = finalContent)) }
                        }
                    }
                } catch (e: Exception) {
                    LogManager.addLog("ESC/POS Client Error: ${e.message}")
                } finally {
                    try { socket.close() } catch (_: Exception) {}
                    LogManager.addLog("HA Connection closed")
                }
            }
        }

        fun stop() {
            running = false
            try { serverSocket?.close() } catch (_: Exception) {}
        }
    }

    private fun connectMqtt() {
        val broker = prefs.getString("mqtt_broker", "") ?: return
        val topic = prefs.getString("mqtt_topic", "sunmi/print") ?: "sunmi/print"
        
        try {
            val clientId = "SunmiPrinter_" + System.currentTimeMillis()
            mqttClient = MqttClient(broker, clientId, MemoryPersistence())
            val options = MqttConnectOptions()
            options.isCleanSession = true
            
            mqttClient?.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    LogManager.addLog("MQTT connection lost: ${cause?.message}")
                    thread { Thread.sleep(5000); connectMqtt() }
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    val payload = message?.toString() ?: return
                    LogManager.addLog("MQTT message received on topic $topic")
                    try {
                        val job = gson.fromJson(payload, PrintJob::class.java)
                        runOnUiThread { remotePrint(job) }
                    } catch (_: Exception) {
                        runOnUiThread { remotePrint(PrintJob(content = payload)) }
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            mqttClient?.connect(options)
            mqttClient?.subscribe(topic)
            LogManager.addLog("Connected to MQTT broker: $broker")
        } catch (e: Exception) {
            LogManager.addLog("MQTT Connection Error: ${e.message}")
        }
    }

    private fun toggleConnection() {
        if (tcpSocket == null || tcpSocket?.isClosed == true) {
            val url = prefs.getString("desktop_server_url", "192.168.1.241:8080") ?: return
            val parts = url.split(":")
            val ip = parts[0]
            val port = if (parts.size > 1) parts[1].toInt() else 8080

            thread {
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
                            runOnUiThread { remotePrint(job) }
                        } catch (_: Exception) {
                            runOnUiThread { remotePrint(PrintJob(content = line)) }
                        }
                    }
                } catch (e: Exception) {
                    LogManager.addLog("Desktop server connection failed: ${e.message}")
                    runOnUiThread { 
                        Toast.makeText(this, "Desktop connection failed", Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    runOnUiThread {
                        tcpSocket = null
                        btnConnect.text = getString(R.string.btn_connect)
                    }
                }
            }
        } else {
            thread {
                tcpSocket?.close()
                tcpSocket = null
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

        titleField.addTextChangedListener(watcher)
        titleSizeField.addTextChangedListener(watcher)
        inputField.addTextChangedListener(watcher)
        textSizeField.addTextChangedListener(watcher)
        centerTitleCheckBox.setOnCheckedChangeListener { _, _ -> updatePreview() }

        val spinnerListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updatePreview()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        alignmentSpinner.onItemSelectedListener = spinnerListener
        printModeSpinner.onItemSelectedListener = spinnerListener
        updatePreview()
    }

    private fun updatePreview() {
        val isAlertMode = printModeSpinner.selectedItemPosition == 1
        livePreview.gravity = if (isAlertMode) Gravity.CENTER_HORIZONTAL else Gravity.START

        val builder = if (isAlertMode) {
            val content = inputField.text.toString().ifEmpty { "6666" }
            generateBanuSugeAlertBuilder(content)
        } else {
            val title = titleField.text.toString()
            val content = inputField.text.toString()
            val titleSize = titleSizeField.text.toString().toIntOrNull() ?: 32
            val contentSize = textSizeField.text.toString().toIntOrNull() ?: 26
            val bodyAlignment = if (alignmentSpinner.selectedItemPosition == 1) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL

            val b = SpannableStringBuilder()
            if (title.isNotEmpty()) {
                val start = b.length
                b.append(title).append("\n")
                b.setSpan(AbsoluteSizeSpan(titleSize), start, b.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (centerTitleCheckBox.isChecked) {
                    b.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), start, b.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            if (content.isNotEmpty()) {
                val start = b.length
                b.append(content)
                b.setSpan(AbsoluteSizeSpan(contentSize), start, b.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                b.setSpan(AlignmentSpan.Standard(bodyAlignment), start, b.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            b
        }

        livePreview.setBackgroundColor(Color.WHITE)
        livePreview.setTextColor(Color.BLACK)
        livePreview.text = builder
    }

    private fun printThreeSmileyFaces() {
        val builder = SpannableStringBuilder("😊😊😊")
        builder.setSpan(AbsoluteSizeSpan(60), 0, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        renderAndPrint(builder, 3)
    }

    private fun printFromFields() {
        val isAlertMode = printModeSpinner.selectedItemPosition == 1
        
        if (isAlertMode) {
            val content = inputField.text.toString().ifEmpty { "6666" }
            val builder = generateBanuSugeAlertBuilder(content)
            renderAndPrint(builder, 3)
        } else {
            val title = titleField.text.toString()
            val content = inputField.text.toString()
            val titleSize = titleSizeField.text.toString().toIntOrNull() ?: 32
            val contentSize = textSizeField.text.toString().toIntOrNull() ?: 26
            val linesAfter = prefs.getString("default_lines_after", "3")?.toIntOrNull() ?: 3
            val isTitleCentered = centerTitleCheckBox.isChecked
            val bodyAlignment = if (alignmentSpinner.selectedItemPosition == 1) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL

            if (title.isEmpty() && content.isEmpty()) {
                Toast.makeText(this, "Please enter some text", Toast.LENGTH_SHORT).show()
                return
            }

            val builder = SpannableStringBuilder()
            if (title.isNotEmpty()) {
                val start = builder.length
                builder.append(title).append("\n")
                builder.setSpan(AbsoluteSizeSpan(titleSize), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (isTitleCentered) {
                    builder.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            if (content.isNotEmpty()) {
                val start = builder.length
                builder.append(content)
                builder.setSpan(AbsoluteSizeSpan(contentSize), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(AlignmentSpan.Standard(bodyAlignment), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            renderAndPrint(builder, linesAfter)
        }
    }

    private fun generateBanuSugeAlertBuilder(content: String, sentTime: String? = null): SpannableStringBuilder {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val now = sdf.format(Date())
        val sent = sentTime ?: now
        
        val builder = SpannableStringBuilder()
        val center = AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER)
        
        fun appendCentered(text: String, size: Int, bold: Boolean = false) {
            val start = builder.length
            builder.append(text)
            builder.setSpan(AbsoluteSizeSpan(size), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (bold) builder.setSpan(StyleSpan(Typeface.BOLD), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(center, start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        appendCentered("ALERT\n", 60, true)
        appendCentered("WARNING\n", 32)
        appendCentered("\n", 20) 

        appendCentered("- - - - - - - - - - - - - - - - - - - -\n", 20)
        appendCentered("\n", 5)
        appendCentered("${content.trim()}\n", 50, true)
        appendCentered("\n", 5)
        appendCentered("- - - - - - - - - - - - - - - - - - - -\n", 20)
        
        appendCentered("\n", 15)
        appendCentered("* * * * * * *\n", 20)
        appendCentered("\n", 5)
        appendCentered("unknown\n", 26)
        appendCentered("sent: $sent\nrecv: $now\n", 22)
        appendCentered("\n", 5)
        appendCentered("* * * * * * *\n", 20)
        
        appendCentered("\n", 15)
        appendCentered("Thank you for using B.A.N.U.S.U.G.E\n", 26)
        appendCentered("(Background Alert Notification Utility for Security Updates & General Events)\n", 14)
        
        return builder
    }

    private fun remotePrint(job: PrintJob) {
        if (job.type == "alert") {
            val builder = generateBanuSugeAlertBuilder(job.content ?: job.text ?: job.message ?: "6666", job.timestamp)
            renderAndPrint(builder, 3)
            return
        }

        val builder = SpannableStringBuilder()
        val title = job.title ?: ""
        val content = job.content ?: job.text ?: job.message ?: ""
        val titleSize = job.titleSize ?: 32
        val contentSize = job.contentSize ?: 26
        val linesAfter = job.linesAfter ?: 3
        val centerTitle = job.centerTitle ?: true
        val alignment = if (job.alignment == 1) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL

        if (title.isNotEmpty()) {
            val start = builder.length
            builder.append(title).append("\n")
            builder.setSpan(AbsoluteSizeSpan(titleSize), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (centerTitle) {
                builder.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        if (content.isNotEmpty()) {
            val start = builder.length
            builder.append(content)
            builder.setSpan(AbsoluteSizeSpan(contentSize), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(AlignmentSpan.Standard(alignment), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        renderAndPrint(builder, linesAfter)
    }

    private fun renderAndPrint(builder: SpannableStringBuilder, linesAfter: Int) {
        val service = printerService ?: run {
            Toast.makeText(this, R.string.status_disconnected, Toast.LENGTH_SHORT).show()
            return
        }
        
        val resultCallback = object : InnerResultCallback() {
            override fun onRunResult(isSuccess: Boolean) {
                runOnUiThread {
                    statusText.text = if (isSuccess) getString(R.string.status_done)
                    else getString(R.string.status_error, "printer returned failure")
                }
            }
            override fun onReturnString(result: String?) {}
            override fun onRaiseException(code: Int, msg: String?) {
                runOnUiThread { statusText.text = getString(R.string.status_error, msg) }
            }
            override fun onPrintResult(code: Int, msg: String?) {}
        }

        try {
            statusText.text = getString(R.string.status_printing)

            val width = 384
            val textPaint = TextPaint().apply {
                color = Color.BLACK
                isAntiAlias = true
            }
            
            // Check if any AlignmentSpan is present to decide on base alignment
            val alignmentSpans = builder.getSpans(0, builder.length, AlignmentSpan::class.java)
            val baseAlignment = if (alignmentSpans.any { it.alignment == Layout.Alignment.ALIGN_CENTER }) {
                Layout.Alignment.ALIGN_CENTER
            } else {
                Layout.Alignment.ALIGN_NORMAL
            }

            val staticLayout = StaticLayout.Builder.obtain(builder, 0, builder.length, textPaint, width)
                .setAlignment(baseAlignment)
                .build()

            val rawBitmap = createBitmap(width, staticLayout.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(rawBitmap)
            canvas.drawColor(Color.WHITE)
            staticLayout.draw(canvas)

            val processedBitmap = thresholdBitmap(rawBitmap)

            service.printerInit(null)
            service.printBitmap(processedBitmap, resultCallback)
            service.lineWrap(linesAfter, null)

        } catch (e: RemoteException) {
            statusText.text = getString(R.string.status_error, e.message)
        }
    }

    private fun thresholdBitmap(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        val resultPixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val color = pixels[i]
                val r = (color shr 16) and 0xff
                val g = (color shr 8) and 0xff
                val b = color and 0xff
                val luminance = (0.299 * r + 0.587 * g + 0.114 * b).toInt()

                val isFeature = luminance < 80
                var isEdge = false
                if (luminance < 250) {
                    for (dy in -2..2) {
                        for (dx in -2..2) {
                            val ny = y + dy
                            val nx = x + dx
                            if (ny in 0 until height && nx in 0 until width) {
                                val nColor = pixels[ny * width + nx]
                                val nr = (nColor shr 16) and 0xff
                                val ng = (nColor shr 8) and 0xff
                                val nb = nColor and 0xff
                                val nLuminance = (0.299 * nr + 0.587 * ng + 0.114 * nb).toInt()
                                if (nLuminance > 250) {
                                    isEdge = true
                                    break
                                }
                            }
                        }
                        if (isEdge) break
                    }
                }
                resultPixels[i] = if (isFeature || isEdge) Color.BLACK else Color.WHITE
            }
        }
        
        val out = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(resultPixels, 0, width, 0, 0, width, height)
        return out
    }

    override fun onDestroy() {
        tcpSocket?.close()
        mqttClient?.disconnect()
        httpServer?.stop()
        escPosServer?.stop()
        try {
            InnerPrinterManager.getInstance().unBindService(this, printerCallback)
        } catch (_: InnerPrinterException) {
        }
        super.onDestroy()
    }
}
