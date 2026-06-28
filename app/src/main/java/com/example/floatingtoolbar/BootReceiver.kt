package com.example.floatingtoolbar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * Only acts if the user explicitly enabled "Start automatically on boot" in
 * MainActivity AND the overlay permission is already granted - otherwise
 * this is a no-op, so the app stays silent unless the user opted in.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!PrefsHelper.isAutoStartEnabled(context)) return

        val hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
        if (!hasOverlayPermission) return

        val serviceIntent = Intent(context, FloatingToolbarService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
