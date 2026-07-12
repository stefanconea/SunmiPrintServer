package com.example.sunmiprinttest

import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** Persistent record of every guard_receipt print, so a guard can later confirm which
 *  entries were actually paid. Backed by a JSON blob in the app's default SharedPreferences
 *  (the same file MainActivity already uses for Settings) so no extra plumbing is needed to
 *  share it between MainActivity and EntranceReceiptsActivity. */
object EntranceReceiptManager {

    data class EntranceReceipt(
        val id: Int,
        val receiptNumber: String,
        val timestamp: String,
        val amount: Double,
        val paid: Boolean = false
    )

    private const val KEY_RECEIPTS = "entrance_receipts"
    private val gson = Gson()
    private val listType = object : TypeToken<List<EntranceReceipt>>() {}.type

    fun addReceipt(prefs: SharedPreferences, id: Int, receiptNumber: String, timestamp: String, amount: Double) {
        val list = getReceipts(prefs).toMutableList()
        list.add(0, EntranceReceipt(id, receiptNumber, timestamp, amount))
        save(prefs, list)
    }

    fun setPaid(prefs: SharedPreferences, id: Int, paid: Boolean) {
        val list = getReceipts(prefs).map { if (it.id == id) it.copy(paid = paid) else it }
        save(prefs, list)
    }

    fun getReceipts(prefs: SharedPreferences): List<EntranceReceipt> {
        val json = prefs.getString(KEY_RECEIPTS, null) ?: return emptyList()
        return try { gson.fromJson(json, listType) } catch (_: Exception) { emptyList() }
    }

    fun clear(prefs: SharedPreferences) {
        prefs.edit().remove(KEY_RECEIPTS).apply()
    }

    private fun save(prefs: SharedPreferences, list: List<EntranceReceipt>) {
        prefs.edit().putString(KEY_RECEIPTS, gson.toJson(list)).apply()
    }
}
