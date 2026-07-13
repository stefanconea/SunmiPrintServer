package com.example.sunmiprinttest

import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, SettingsFragment())
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)
            findPreference<Preference>("exit_app")?.setOnPreferenceClickListener {
                confirmExit()
                true
            }
            findPreference<Preference>("open_print_settings")?.setOnPreferenceClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_PRINT_SETTINGS))
                } catch (_: Exception) {
                    Toast.makeText(requireContext(), "Could not open Android's print settings", Toast.LENGTH_SHORT).show()
                }
                true
            }
        }

        // Stops the foreground PrintService (closing every socket/server it owns) then kills
        // this process outright -- finish() alone would leave the process (and PrintService,
        // since it's already been told to stop) in a torn-down-but-not-fully-gone state, and
        // this is the one place in the app meant to guarantee a completely closed app on request.
        private fun confirmExit() {
            val ctx = requireContext()
            AlertDialog.Builder(ctx)
                .setTitle("Exit App")
                .setMessage("This stops all print servers (HTTP, ESC/POS, MQTT, desktop connection) until you reopen the app. Continue?")
                .setPositiveButton("Exit") { _, _ ->
                    ctx.stopService(Intent(ctx, PrintService::class.java))
                    requireActivity().finishAffinity()
                    Process.killProcess(Process.myPid())
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
}
