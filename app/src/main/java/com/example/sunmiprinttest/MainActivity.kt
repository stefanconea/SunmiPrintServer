package com.example.sunmiprinttest

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.preference.PreferenceManager
import androidx.appcompat.widget.Toolbar
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), PrintService.Listener {

    private var printService: PrintService? = null
    private var isServiceBound = false
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
    private lateinit var boldTitleCheckBox: CheckBox
    private lateinit var inputField: EditText
    private lateinit var textSizeField: EditText
    private lateinit var boldContentCheckBox: CheckBox
    private lateinit var printModeSpinner: Spinner
    private lateinit var alignmentSpinner: Spinner
    private lateinit var livePreview: TextView
    private lateinit var livePreviewImage: ImageView
    private lateinit var hwStatusText: TextView
    private lateinit var entranceQuantityField: EditText
    private lateinit var entranceButton: Button

    private val previewHandler = Handler(Looper.getMainLooper())
    private var previewRunnable: Runnable? = null
    private var previewCounter = 0

    private val importBackupLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) importBackup(uri)
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = (binder as PrintService.LocalBinder).getService()
            printService = service
            isServiceBound = true
            service.listener = this@MainActivity
            syncUiWithServiceState()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            printService = null
            isServiceBound = false
        }
    }

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
        boldTitleCheckBox = findViewById(R.id.boldTitleCheckBox)
        inputField = findViewById(R.id.inputField)
        textSizeField = findViewById(R.id.textSizeField)
        boldContentCheckBox = findViewById(R.id.boldContentCheckBox)
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

        btnConnect.setOnClickListener { printService?.toggleConnection() }
        setupLivePreview()
        printButton.setOnClickListener { printFromFields() }
        entranceButton.setOnClickListener { printEntranceReceipt() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Started (not just bound) so the servers and printer connection keep running in the
        // background -- home screen, screen off, this Activity destroyed -- until the user
        // explicitly stops it via Settings -> Exit App.
        ContextCompat.startForegroundService(this, Intent(this, PrintService::class.java))
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, PrintService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (isServiceBound) {
            printService?.listener = null
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }

    private fun syncUiWithServiceState() {
        val service = printService ?: return
        httpServerInfo.text = service.httpServerInfoText
        tcpServerInfo.text = service.tcpServerInfoText
        onPrinterConnectionChanged(service.printerService != null)
        onHwStatusChanged(service.currentPrinterStatus)
        onTcpConnectionChanged(service.isTcpConnected)
    }

    override fun onPrinterConnectionChanged(connected: Boolean) {
        statusText.text = if (connected) getString(R.string.status_connected) else getString(R.string.status_disconnected)
        printButton.isEnabled = connected
        entranceButton.isEnabled = connected
    }

    override fun onHwStatusChanged(status: Int) {
        val statusStr = printerStatusLabel(status)
        val colorStr = if (status == 1) "#388E3C" else "#D32F2F"
        hwStatusText.text = "[$statusStr]"
        hwStatusText.setTextColor(Color.parseColor(colorStr))
    }

    override fun onTcpConnectionChanged(connected: Boolean) {
        btnConnect.text = if (connected) getString(R.string.btn_disconnect) else getString(R.string.btn_connect)
    }

    override fun onStatusText(text: String) {
        statusText.text = text
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
            R.id.action_backup -> {
                exportBackup()
                return true
            }
            R.id.action_restore -> {
                importBackupLauncher.launch(arrayOf("application/json"))
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    // Every SharedPreferences value this app stores (Settings + the guard_receipt counter
    // + the full entrance receipts history) lives in app-private storage that Android wipes
    // on uninstall -- this dumps it all to one JSON file and hands it to the share sheet so
    // the user can save it somewhere that survives (Drive, email, a file manager, etc.).
    private fun exportBackup() {
        try {
            val jsonObject = JsonObject()
            for ((key, value) in prefs.all) {
                when (value) {
                    is String -> jsonObject.addProperty(key, value)
                    is Boolean -> jsonObject.addProperty(key, value)
                    is Int -> jsonObject.addProperty(key, value)
                    is Long -> jsonObject.addProperty(key, value)
                    is Float -> jsonObject.addProperty(key, value)
                }
            }
            val backupDir = File(cacheDir, "backups").apply { mkdirs() }
            val file = File(backupDir, "sunmi_print_backup.json")
            file.writeText(gson.toJson(jsonObject))
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Save Sunmi Print backup"))
        } catch (e: Exception) {
            Toast.makeText(this, "Backup failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // Restores every key from a previously exported backup. Values already correctly typed
    // as JSON strings/booleans/numbers round-trip cleanly; numbers are restored as Int since
    // every numeric preference this app stores (guard_receipt_counter, etc.) is an Int.
    private fun importBackup(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                ?: throw IllegalStateException("Could not read the selected file")
            val jsonObject = gson.fromJson(text, JsonObject::class.java)
            val editor = prefs.edit()
            for ((key, element) in jsonObject.entrySet()) {
                val primitive = element.asJsonPrimitive
                when {
                    primitive.isBoolean -> editor.putBoolean(key, primitive.asBoolean)
                    primitive.isNumber -> editor.putInt(key, primitive.asInt)
                    primitive.isString -> editor.putString(key, primitive.asString)
                }
            }
            editor.apply()
            Toast.makeText(this, "Backup restored. Restart the app to fully apply.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Restore failed: ${e.message}", Toast.LENGTH_LONG).show()
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
        boldTitleCheckBox.setOnCheckedChangeListener { _, _ -> updatePreview() }
        boldContentCheckBox.setOnCheckedChangeListener { _, _ -> updatePreview() }
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
            contentSize = textSizeField.text.toString().toIntOrNull() ?: 26, centerTitle = isTitleCentered,
            boldTitle = boldTitleCheckBox.isChecked, boldContent = boldContentCheckBox.isChecked, alignment = alignment)
        previewRunnable = Runnable { thread {
            val service = printService ?: return@thread
            val bitmap: Bitmap = service.renderJobToBitmap(job)
            runOnUiThread {
                if (myId != previewCounter) return@runOnUiThread
                if (titleField.text.toString().trim().isEmpty() && inputField.text.toString().trim().isEmpty() && mode != "alert") {
                    livePreviewImage.visibility = View.GONE; livePreview.visibility = View.VISIBLE; livePreview.text = ""; return@runOnUiThread
                }
                if (mode in listOf("barcode", "qr", "image", "boxed", "banner")) {
                    livePreview.visibility = View.GONE; livePreviewImage.visibility = View.VISIBLE; livePreviewImage.setImageBitmap(bitmap)
                } else {
                    livePreviewImage.visibility = View.GONE; livePreview.visibility = View.VISIBLE
                    livePreview.text = service.generateStyledBuilder(job)
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
        printService?.processJob(PrintJob(type = "guard_receipt", quantity = quantity))
    }

    private fun printFromFields() {
        val job = PrintJob(type = getModeKey(printModeSpinner.selectedItemPosition),
            title = titleField.text.toString(), content = inputField.text.toString(), titleSize = titleSizeField.text.toString().toIntOrNull() ?: 32,
            contentSize = textSizeField.text.toString().toIntOrNull() ?: 26, centerTitle = centerTitleCheckBox.isChecked,
            boldTitle = boldTitleCheckBox.isChecked, boldContent = boldContentCheckBox.isChecked,
            alignment = alignmentSpinner.selectedItemPosition, linesAfter = prefs.getString("default_lines_after", "3")?.toIntOrNull() ?: 3)
        printService?.processJob(job)
    }
}
