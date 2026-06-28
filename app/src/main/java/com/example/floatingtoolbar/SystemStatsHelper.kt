package com.example.floatingtoolbar

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Settings
import java.io.RandomAccessFile

/**
 * Small helpers for the stats shown in the floating panel.
 *
 * Two of these (RAM, battery temperature) use stable public APIs and work
 * reliably on every device. CPU usage does NOT have a public, unprivileged
 * API on modern Android - see the comment on getCpuUsagePercent().
 */
object SystemStatsHelper {

    fun getRamUsagePercent(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        if (info.totalMem == 0L) return 0
        val used = info.totalMem - info.availMem
        return ((used * 100) / info.totalMem).toInt()
    }

    fun getBatteryPercent(context: Context): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return runCatching {
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrDefault(-1)
    }

    fun getBatteryTemperature(context: Context): Float {
        val intent = context.applicationContext.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val tenthsOfDegree = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        return tenthsOfDegree / 10f
    }

    fun getBrightnessPercent(context: Context): Int {
        return try {
            val value = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS
            )
            (value * 100) / 255
        } catch (e: Exception) {
            50
        }
    }

    /**
     * Best-effort, unprivileged CPU usage estimate.
     *
     * IMPORTANT LIMITATION: starting with Android 8 (API 26), SELinux policy
     * blocks regular apps from reading /proc/stat on the vast majority of
     * devices. There is no replacement public API for system-wide CPU load -
     * this is a deliberate Android privacy restriction, not a bug. This
     * function will return null on most modern phones; it's kept here for
     * older devices, emulators, or custom ROMs where /proc/stat is still
     * readable. The UI should show "N/A" when this returns null rather than
     * faking a number.
     */
    fun getCpuUsagePercent(): Int? {
        return try {
            val reader = RandomAccessFile("/proc/stat", "r")
            val firstLine = reader.readLine()
            reader.seek(0)
            Thread.sleep(360)
            val secondLine = reader.readLine()
            reader.close()

            val first = firstLine.split(" ").filter { it.isNotBlank() }
            val second = secondLine.split(" ").filter { it.isNotBlank() }

            val idle1 = first[4].toLong()
            val total1 = first.drop(1).take(7).sumOf { it.toLong() }
            val idle2 = second[4].toLong()
            val total2 = second.drop(1).take(7).sumOf { it.toLong() }

            val idleDelta = idle2 - idle1
            val totalDelta = total2 - total1
            if (totalDelta <= 0) null else (100 * (totalDelta - idleDelta) / totalDelta).toInt()
        } catch (e: Exception) {
            null
        }
    }
}
