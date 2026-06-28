package com.example.floatingtoolbar

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var btnGrantPermission: Button
    private lateinit var btnToggleService: Button
    private lateinit var btnCustomizeApps: Button
    private lateinit var cbAutoStart: CheckBox
    private lateinit var btnBatteryOptimization: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        btnGrantPermission = findViewById(R.id.btnGrantPermission)
        btnToggleService = findViewById(R.id.btnToggleService)
        btnCustomizeApps = findViewById(R.id.btnCustomizeApps)
        cbAutoStart = findViewById(R.id.cbAutoStart)
        btnBatteryOptimization = findViewById(R.id.btnBatteryOptimization)

        btnGrantPermission.setOnClickListener { requestOverlayPermission() }
        btnToggleService.setOnClickListener { toggleService() }
        btnCustomizeApps.setOnClickListener {
            startActivity(Intent(this, AppPickerActivity::class.java))
        }

        cbAutoStart.isChecked = PrefsHelper.isAutoStartEnabled(this)
        cbAutoStart.setOnCheckedChangeListener { _, checked ->
            PrefsHelper.setAutoStartEnabled(this, checked)
        }

        btnBatteryOptimization.setOnClickListener { requestIgnoreBatteryOptimizations() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !hasOverlayPermission()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun toggleService() {
        if (!hasOverlayPermission()) {
            statusText.text = "Grant overlay permission first"
            return
        }
        if (FloatingToolbarService.isRunning) {
            stopService(Intent(this, FloatingToolbarService::class.java))
        } else {
            val intent = Intent(this, FloatingToolbarService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
        // Give the service a moment to flip isRunning before refreshing UI
        statusText.postDelayed({ refreshStatus() }, 300)
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(this, "Not needed on this Android version", Toast.LENGTH_SHORT).show()
            return
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Already exempt from battery optimization", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun refreshStatus() {
        val granted = hasOverlayPermission()
        statusText.text = if (granted) {
            "Overlay permission: granted"
        } else {
            "Overlay permission: NOT granted"
        }
        btnToggleService.text = if (FloatingToolbarService.isRunning) {
            getString(R.string.stop_toolbar)
        } else {
            getString(R.string.start_toolbar)
        }
        btnToggleService.isEnabled = granted
    }
}
