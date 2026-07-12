package com.example.sunmiprinttest

import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.preference.PreferenceManager
import java.util.Locale

class EntranceReceiptsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.entrance_receipts_activity)

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        toolbar.title = "Entrance Receipts"
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        container = findViewById(R.id.receiptsContainer)

        findViewById<Button>(R.id.btnClearReceipts).setOnClickListener {
            EntranceReceiptManager.clear(prefs)
            renderList()
        }
        findViewById<Button>(R.id.btnRefreshReceipts).setOnClickListener { renderList() }

        renderList()
    }

    private fun renderList() {
        container.removeAllViews()
        val receipts = EntranceReceiptManager.getReceipts(prefs)

        if (receipts.isEmpty()) {
            val empty = TextView(this)
            empty.text = "No entrance receipts yet."
            empty.setPadding(32, 32, 32, 32)
            container.addView(empty)
            return
        }

        for (receipt in receipts) {
            val row = layoutInflater.inflate(R.layout.entrance_receipt_row, container, false)
            val amountText = String.format(Locale.getDefault(), "%.2f", receipt.amount).replace('.', ',') + " lei"
            row.findViewById<TextView>(R.id.receiptInfo).text =
                "${receipt.receiptNumber}   ${receipt.timestamp}   $amountText"
            row.setBackgroundColor(if (receipt.paid) Color.parseColor("#E8F5E9") else Color.parseColor("#FFEBEE"))

            val checkBox = row.findViewById<CheckBox>(R.id.paidCheckBox)
            checkBox.setOnCheckedChangeListener(null)
            checkBox.isChecked = receipt.paid
            checkBox.setOnCheckedChangeListener { _, isChecked ->
                EntranceReceiptManager.setPaid(prefs, receipt.id, isChecked)
                row.setBackgroundColor(if (isChecked) Color.parseColor("#E8F5E9") else Color.parseColor("#FFEBEE"))
            }
            container.addView(row)

            val divider = View(this)
            divider.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2)
            divider.setBackgroundColor(Color.parseColor("#DDDDDD"))
            container.addView(divider)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
