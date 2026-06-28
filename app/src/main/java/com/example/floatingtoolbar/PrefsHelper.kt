package com.example.floatingtoolbar

import android.content.Context

/**
 * All small bits of state the overlay needs to remember between sessions:
 * which app shortcuts the user picked (and in what order), where the
 * panel/bubble was last left on screen, the panel's last manual size, and
 * whether the user opted in to auto-starting after a reboot.
 */
object PrefsHelper {
    private const val PREFS_NAME = "floating_toolbar_prefs"

    private const val KEY_SELECTED_APPS = "selected_apps"
    private const val KEY_OVERLAY_X = "overlay_x"
    private const val KEY_OVERLAY_Y = "overlay_y"
    private const val KEY_PANEL_WIDTH = "panel_width"
    private const val KEY_PANEL_HEIGHT = "panel_height"
    private const val KEY_AUTO_START = "auto_start"

    const val MAX_APPS = 9

    // ---------- App shortcuts ----------

    fun getSelectedApps(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_SELECTED_APPS, "") ?: ""
        return if (raw.isBlank()) emptyList() else raw.split(",")
    }

    fun setSelectedApps(context: Context, packages: List<String>) {
        prefs(context).edit().putString(KEY_SELECTED_APPS, packages.joinToString(",")).apply()
    }

    // ---------- Overlay position (shared by bubble and panel) ----------

    fun getOverlayPosition(context: Context): Pair<Int, Int> {
        val p = prefs(context)
        return p.getInt(KEY_OVERLAY_X, 20) to p.getInt(KEY_OVERLAY_Y, 120)
    }

    fun setOverlayPosition(context: Context, x: Int, y: Int) {
        prefs(context).edit().putInt(KEY_OVERLAY_X, x).putInt(KEY_OVERLAY_Y, y).apply()
    }

    // ---------- Panel size (resizable panel) ----------

    fun getPanelSize(context: Context, defaultWidthPx: Int, defaultHeightPx: Int): Pair<Int, Int> {
        val p = prefs(context)
        return p.getInt(KEY_PANEL_WIDTH, defaultWidthPx) to p.getInt(KEY_PANEL_HEIGHT, defaultHeightPx)
    }

    fun setPanelSize(context: Context, width: Int, height: Int) {
        prefs(context).edit().putInt(KEY_PANEL_WIDTH, width).putInt(KEY_PANEL_HEIGHT, height).apply()
    }

    // ---------- Auto-start on boot ----------

    fun isAutoStartEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_START, false)

    fun setAutoStartEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_START, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
