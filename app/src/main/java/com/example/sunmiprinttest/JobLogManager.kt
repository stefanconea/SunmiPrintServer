package com.example.sunmiprinttest

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object JobLogManager {

    data class JobEntry(
        val id: Int,
        val timestamp: String,
        val source: String,
        val type: String,
        var status: String = "Pending"
    )

    private val jobs = mutableListOf<JobEntry>()
    private var counter = 0
    private var listener: (() -> Unit)? = null
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun startJob(source: String, type: String): Int {
        val id = ++counter
        jobs.add(0, JobEntry(id, sdf.format(Date()), source, type))
        if (jobs.size > 200) {
            jobs.removeAt(jobs.size - 1)
        }
        listener?.invoke()
        return id
    }

    @Synchronized
    fun completeJob(id: Int, success: Boolean, message: String? = null) {
        val entry = jobs.find { it.id == id } ?: return
        entry.status = if (success) "Success" else "Failed" + (message?.let { ": $it" } ?: "")
        listener?.invoke()
    }

    @Synchronized
    fun getFormattedJobs(): String = jobs.joinToString("\n") {
        "#%04d [%s] %-24s %-12s %s".format(it.id, it.timestamp, it.source, it.type, it.status)
    }

    @Synchronized
    fun clearJobs() {
        jobs.clear()
        listener?.invoke()
    }

    fun setListener(l: (() -> Unit)?) {
        listener = l
    }
}
