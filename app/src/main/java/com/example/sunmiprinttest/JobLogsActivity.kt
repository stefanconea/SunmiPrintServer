package com.example.sunmiprinttest

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.MenuItem
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar

class JobLogsActivity : AppCompatActivity() {

    private lateinit var jobLogTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.job_logs_activity)

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        toolbar.title = "Job Logs"
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        jobLogTextView = findViewById(R.id.jobLogText)
        jobLogTextView.movementMethod = ScrollingMovementMethod()

        val btnClear: Button = findViewById(R.id.btnClearJobLogs)
        val btnRefresh: Button = findViewById(R.id.btnRefreshJobLogs)

        btnClear.setOnClickListener {
            JobLogManager.clearJobs()
        }

        btnRefresh.setOnClickListener {
            updateJobLogs()
        }

        updateJobLogs()
        JobLogManager.setListener {
            runOnUiThread { updateJobLogs() }
        }
    }

    private fun updateJobLogs() {
        jobLogTextView.text = JobLogManager.getFormattedJobs()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        JobLogManager.setListener(null)
        super.onDestroy()
    }
}
