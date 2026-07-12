package com.example.sunmiprinttest

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.MenuItem
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar

class LogsActivity : AppCompatActivity() {

    private lateinit var logTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.logs_activity)

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        toolbar.title = "Server Logs"
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        logTextView = findViewById(R.id.logText)
        logTextView.movementMethod = ScrollingMovementMethod()

        val btnClear: Button = findViewById(R.id.btnClearLogs)
        val btnRefresh: Button = findViewById(R.id.btnRefreshLogs)

        btnClear.setOnClickListener {
            LogManager.clearLogs()
        }

        btnRefresh.setOnClickListener {
            updateLogs()
        }

        updateLogs()
        LogManager.setListener {
            runOnUiThread { updateLogs() }
        }
    }

    private fun updateLogs() {
        logTextView.text = LogManager.getLogs()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        LogManager.setListener(null)
        super.onDestroy()
    }
}
