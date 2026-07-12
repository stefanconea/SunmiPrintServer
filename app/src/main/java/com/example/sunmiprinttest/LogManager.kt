package com.example.sunmiprinttest

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogManager {
    private val logs = mutableListOf<String>()
    private var listener: (() -> Unit)? = null

    fun addLog(message: String) {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val time = sdf.format(Date())
        val formattedLog = "[$time] $message"
        logs.add(0, formattedLog)
        if (logs.size > 200) {
            logs.removeAt(logs.size - 1)
        }
        listener?.invoke()
    }

    fun getLogs(): String = logs.joinToString("\n")

    fun clearLogs() {
        logs.clear()
        listener?.invoke()
    }

    fun setListener(l: (() -> Unit)?) {
        listener = l
    }
}
