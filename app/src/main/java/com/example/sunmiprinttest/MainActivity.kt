package com.example.sunmiprinttest

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
import android.view.View
import android.widget.AdapterView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.sunmi.peripheral.printer.InnerPrinterCallback
import com.sunmi.peripheral.printer.InnerPrinterException
import com.sunmi.peripheral.printer.InnerPrinterManager
import com.sunmi.peripheral.printer.InnerResultCallback
import com.sunmi.peripheral.printer.SunmiPrinterService
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import fi.iki.elonen.NanoHTTPD
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.NetworkInterface
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
    private val gson = Gson()

    private lateinit var statusText: TextView
    private lateinit var serverUrlField: EditText
    private lateinit var btnConnect: Button
    private lateinit var httpServerInfo: TextView
    private lateinit var mqttBrokerField: EditText
    private lateinit var mqttTopicField: EditText
    private lateinit var btnMqttConnect: Button
    private lateinit var printButton: Button
    private lateinit var titleField: EditText
    private lateinit var titleSizeField: EditText
    private lateinit var centerTitleCheckBox: CheckBox
    private lateinit var inputField: EditText
    private lateinit var textSizeField: EditText
    private lateinit var printModeSpinner: Spinner
    private lateinit var alignmentSpinner: Spinner
    private lateinit var linesAfterField: EditText
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
        val titleSize: Int? = null,
        val contentSize: Int? = null,
        val centerTitle: Boolean? = null,
        val alignment: Int? = null,
        val linesAfter: Int? = null,
        val timestamp: String? = null
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        serverUrlField = findViewById(R.id.serverUrlField)
        btnConnect = findViewById(R.id.btnConnect)
        httpServerInfo = findViewById(R.id.httpServerInfo)
        mqttBrokerField = findViewById(R.id.mqttBrokerField)
        mqttTopicField = findViewById(R.id.mqttTopicField)
        btnMqttConnect = findViewById(R.id.btnMqttConnect)
        printButton = findViewById(R.id.btnPrint)
        titleField = findViewById(R.id.titleField)
        titleSizeField = findViewById(R.id.titleSizeField)
        centerTitleCheckBox = findViewById(R.id.centerTitleCheckBox)
        inputField = findViewById(R.id.inputField)
        textSizeField = findViewById(R.id.textSizeField)
        printModeSpinner = findViewById(R.id.printModeSpinner)
        alignmentSpinner = findViewById(R.id.alignmentSpinner)
        linesAfterField = findViewById(R.id.linesAfterField)
        livePreview = findViewById(R.id.livePreview)
        livePreview.movementMethod = ScrollingMovementMethod()
        livePreview.setOnTouchListener { v, event ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            false
        }
        testEmojiButton = findViewById(R.id.btnTestEmoji)

        btnConnect.setOnClickListener { toggleConnection() }
        btnMqttConnect.setOnClickListener { toggleMqtt() }
        setupLivePreview()
        printButton.setOnClickListener { printFromFields() }
        testEmojiButton.setOnClickListener { printThreeSmileyFaces() }

        startHttpServer()

        try {
            InnerPrinterManager.getInstance().bindService(this, printerCallback)
        } catch (e: InnerPrinterException) {
            statusText.text = getString(R.string.status_error, e.message)
        }
    }

    private fun startHttpServer() {
        val ip = getLocalIpAddress()
        httpServerInfo.text = "HTTP Server: http://$ip:8081/print"
        httpServer = AppHttpServer(8081)
        try {
            httpServer?.start()
        } catch (e: Exception) {
            httpServerInfo.text = "HTTP Server Error: ${e.message}"
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
        } catch (ex: Exception) { }
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
                    try {
                        val body = session.inputStream.bufferedReader().readText()
                        val job = gson.fromJson(body, PrintJob::class.java)
                        runOnUiThread { remotePrint(job) }
                        return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"ok\"}")
                    } catch (e2: Exception) {
                        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Error: ${e.message}")
                    }
                }
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found. Use POST /print")
        }
    }

    private fun toggleMqtt() {
        if (mqttClient == null || !mqttClient!!.isConnected) {
            val broker = mqttBrokerField.text.toString()
            val topic = mqttTopicField.text.toString()
            if (broker.isEmpty()) return

            thread {
                try {
                    val clientId = "SunmiPrinter_" + System.currentTimeMillis()
                    mqttClient = MqttClient(broker, clientId, MemoryPersistence())
                    val options = MqttConnectOptions()
                    options.isCleanSession = true
                    
                    mqttClient?.setCallback(object : MqttCallback {
                        override fun connectionLost(cause: Throwable?) {
                            runOnUiThread { 
                                btnMqttConnect.text = "M-Conn"
                                Toast.makeText(this@MainActivity, "MQTT Lost", Toast.LENGTH_SHORT).show()
                            }
                        }

                        override fun messageArrived(topic: String?, message: MqttMessage?) {
                            val payload = message?.toString() ?: return
                            try {
                                val job = gson.fromJson(payload, PrintJob::class.java)
                                runOnUiThread { remotePrint(job) }
                            } catch (e: Exception) {
                                runOnUiThread { remotePrint(PrintJob(content = payload)) }
                            }
                        }

                        override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                    })

                    mqttClient?.connect(options)
                    mqttClient?.subscribe(topic)

                    runOnUiThread {
                        btnMqttConnect.text = "M-Disc"
                        Toast.makeText(this@MainActivity, "MQTT Connected", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    runOnUiThread { Toast.makeText(this@MainActivity, "MQTT Error: ${e.message}", Toast.LENGTH_LONG).show() }
                }
            }
        } else {
            thread {
                mqttClient?.disconnect()
                runOnUiThread { btnMqttConnect.text = "M-Conn" }
            }
        }
    }

    private fun toggleConnection() {
        if (tcpSocket == null || tcpSocket?.isClosed == true) {
            val input = serverUrlField.text.toString()
            if (input.isEmpty()) return
            
            val parts = input.split(":")
            val ip = parts[0]
            val port = if (parts.size > 1) parts[1].toInt() else 8080

            thread {
                try {
                    val socket = Socket(ip, port)
                    tcpSocket = socket
                    runOnUiThread { 
                        btnConnect.text = "Disconnect"
                        Toast.makeText(this, "Connected to $ip", Toast.LENGTH_SHORT).show() 
                    }

                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    while (true) {
                        val line = reader.readLine() ?: break
                        try {
                            val job = gson.fromJson(line, PrintJob::class.java)
                            runOnUiThread { remotePrint(job) }
                        } catch (e: Exception) {
                            runOnUiThread { remotePrint(PrintJob(content = line)) }
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread { 
                        Toast.makeText(this, "Connection failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                } finally {
                    runOnUiThread {
                        tcpSocket = null
                        btnConnect.text = "Connect"
                    }
                }
            }
        } else {
            thread {
                tcpSocket?.close()
                tcpSocket = null
                runOnUiThread { btnConnect.text = "Connect" }
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
            val linesAfter = linesAfterField.text.toString().toIntOrNull() ?: 3
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

        // Header
        appendCentered("ALERT\n", 60, true)
        appendCentered("WARNING\n", 32)
        appendCentered("\n", 20) 

        // First Dash
        appendCentered("- - - - - - - - - - - - - - - - - - - -\n", 20)
        appendCentered("\n", 5) // Symmetric vertical padding
        appendCentered("${content.trim()}\n", 50, true)
        appendCentered("\n", 5) // Symmetric vertical padding
        appendCentered("- - - - - - - - - - - - - - - - - - - -\n", 20)
        
        appendCentered("\n", 15) // Spacer
        
        // Info Section between asterisks
        appendCentered("* * * * * * *\n", 20)
        appendCentered("\n", 5) // Symmetric vertical padding
        appendCentered("unknown\n", 26)
        appendCentered("sent: $sent\nrecv: $now\n", 22)
        appendCentered("\n", 5) // Symmetric vertical padding
        appendCentered("* * * * * * *\n", 20)
        
        appendCentered("\n", 15) // Spacer
        
        // Footer section
        appendCentered("Thank you for using B.A.N.U.S.U.G.E\n", 26)
        appendCentered("(Background Alert Notification Utility for Security Updates & General Events)\n", 14)
        
        return builder
    }

    private fun remotePrint(job: PrintJob) {
        if (job.type == "alert") {
            val builder = generateBanuSugeAlertBuilder(job.content ?: "6666", job.timestamp)
            renderAndPrint(builder, 3)
            return
        }

        val builder = SpannableStringBuilder()
        val title = job.title ?: ""
        val content = job.content ?: ""
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
            
            // Explicitly force center alignment for alerts if detected in spans
            val alignmentSpans = builder.getSpans(0, builder.length, AlignmentSpan::class.java)
            val baseAlignment = if (alignmentSpans.any { it.alignment == Layout.Alignment.ALIGN_CENTER }) {
                Layout.Alignment.ALIGN_CENTER
            } else {
                Layout.Alignment.ALIGN_NORMAL
            }

            val staticLayout = StaticLayout.Builder.obtain(builder, 0, builder.length, textPaint, width)
                .setAlignment(baseAlignment)
                .build()

            val rawBitmap = Bitmap.createBitmap(width, staticLayout.height, Bitmap.Config.ARGB_8888)
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
        
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(resultPixels, 0, width, 0, 0, width, height)
        return out
    }

    override fun onDestroy() {
        tcpSocket?.close()
        mqttClient?.disconnect()
        httpServer?.stop()
        try {
            InnerPrinterManager.getInstance().unBindService(this, printerCallback)
        } catch (e: InnerPrinterException) {
            // ignore
        }
        super.onDestroy()
    }
}
