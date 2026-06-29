package com.example.floatingtoolbar

import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlin.math.abs

/**
 * Foreground service that owns the floating overlay. Only one of
 * [bubbleView] (collapsed) or [panelView] (expanded) exists at a time.
 *
 * Window controls:
 *  - Drag the header        -> move the panel anywhere on screen
 *  - drag the corner handle -> resize the panel (ignored while fullscreen)
 *  - minimize icon          -> collapse to a small draggable bubble
 *  - tap the bubble         -> restore the panel
 *  - fullscreen icon        -> toggle between the saved size and full-screen
 *  - close icon             -> stopSelf(), removes the overlay and ends the service
 *
 * The bubble additionally snaps to the nearest screen edge when released,
 * and fades to partial transparency after a few seconds of inactivity.
 */
class FloatingToolbarService : Service() {

    companion object {
        var isRunning = false
        const val ACTION_APPS_UPDATED = "com.example.floatingtoolbar.ACTION_APPS_UPDATED"
        private const val CHANNEL_ID = "floating_toolbar_channel"
        private const val NOTIF_ID = 1001
        private const val DEFAULT_PANEL_WIDTH_DP = 300
        private const val DEFAULT_PANEL_HEIGHT_DP = 420
        private const val MIN_PANEL_WIDTH_DP = 220
        private const val MIN_PANEL_HEIGHT_DP = 200
        private const val BUBBLE_IDLE_FADE_MS = 3000L
        private const val BUBBLE_IDLE_ALPHA = 0.55f
    }

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var panelView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null

    // Remembers where the panel/bubble was, shared between the two states
    private var savedX = 0
    private var savedY = 0
    private var isFullscreen = false

    private var isFlashlightOn = false
    private var torchCameraId: String? = null

    private val statsHandler = Handler(Looper.getMainLooper())
    private lateinit var statsRunnable: Runnable
    private val cpuExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private val fadeHandler = Handler(Looper.getMainLooper())
    private val fadeRunnable = Runnable { bubbleView?.alpha = BUBBLE_IDLE_ALPHA }

    private val appsUpdatedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            panelView?.let { buildToolsGrid(it.findViewById(R.id.toolsGrid)) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val (x, y) = PrefsHelper.getOverlayPosition(this)
        savedX = x
        savedY = y

        val foregroundOk = runCatching { startForegroundNotification() }.isSuccess
        if (!foregroundOk) {
            Toast.makeText(this, "Couldn't start the floating toolbar service", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }

        if (!showPanel()) {
            // showPanel() already toasts the reason and calls stopSelf().
            return
        }
        startStatsUpdates()

        val filter = IntentFilter(ACTION_APPS_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(appsUpdatedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(appsUpdatedReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        statsHandler.removeCallbacks(statsRunnable)
        fadeHandler.removeCallbacks(fadeRunnable)
        cpuExecutor.shutdownNow()
        runCatching { unregisterReceiver(appsUpdatedReceiver) }
        if (isFlashlightOn) turnOffFlashlight()
        removeBubble()
        removePanel()
    }

    // ---------- Foreground notification (required to keep the overlay alive) ----------

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Floating Toolbar", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Floating Toolbar running")
            .setContentText("Tap to manage")
            .setSmallIcon(R.drawable.ic_toolbar)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notification)
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

    // ---------- Bubble (collapsed) ----------

    private fun showBubble(): Boolean {
        removePanel()
        if (bubbleView != null) return true

        val inflated = LayoutInflater.from(this).inflate(R.layout.floating_bubble, null)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }

        attachDragAndTap(
            dragSource = inflated,
            params = params,
            movedView = inflated,
            onTap = { showPanel() },
            snapToEdge = true,
            onDown = { cancelBubbleFade() }
        )

        val result = runCatching { windowManager.addView(inflated, params) }
        if (result.isFailure) {
            // Most commonly: overlay permission was revoked after the service
            // started, or this device/ROM blocks the overlay window type we
            // requested. Fail safely instead of letting the exception crash
            // the whole service.
            Toast.makeText(
                this,
                "Couldn't show the floating bubble - check overlay permission",
                Toast.LENGTH_LONG
            ).show()
            stopSelf()
            return false
        }

        bubbleView = inflated
        bubbleParams = params
        scheduleBubbleFade()
        return true
    }

    private fun removeBubble() {
        fadeHandler.removeCallbacks(fadeRunnable)
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
    }

    private fun scheduleBubbleFade() {
        fadeHandler.removeCallbacks(fadeRunnable)
        fadeHandler.postDelayed(fadeRunnable, BUBBLE_IDLE_FADE_MS)
    }

    private fun cancelBubbleFade() {
        fadeHandler.removeCallbacks(fadeRunnable)
        bubbleView?.alpha = 1f
    }

    // ---------- Panel (normal / fullscreen, resizable) ----------

    private fun showPanel(): Boolean {
        removeBubble()
        if (panelView != null) return true

        val inflated = LayoutInflater.from(this).inflate(R.layout.floating_panel, null)

        val (savedWidth, savedHeight) = PrefsHelper.getPanelSize(
            this, dp(DEFAULT_PANEL_WIDTH_DP), dp(DEFAULT_PANEL_HEIGHT_DP)
        )
        val width = if (isFullscreen) WindowManager.LayoutParams.MATCH_PARENT else savedWidth
        val height = if (isFullscreen) WindowManager.LayoutParams.MATCH_PARENT else savedHeight

        val params = WindowManager.LayoutParams(
            width, height,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (isFullscreen) 0 else savedX
            y = if (isFullscreen) 0 else savedY
        }

        setupPanelContents(inflated)
        attachResizeHandle(inflated)

        val header = inflated.findViewById<View>(R.id.panelHeader)
        attachDragAndTap(dragSource = header, params = params, movedView = inflated, onTap = null)

        val result = runCatching { windowManager.addView(inflated, params) }
        if (result.isFailure) {
            Toast.makeText(
                this,
                "Couldn't show the floating panel - check overlay permission",
                Toast.LENGTH_LONG
            ).show()
            stopSelf()
            return false
        }

        panelView = inflated
        panelParams = params
        return true
    }

    private fun removePanel() {
        panelView?.let { runCatching { windowManager.removeView(it) } }
        panelView = null
    }

    private fun rebuildPanel() {
        removePanel()
        showPanel()
    }

    // ---------- Resize handle ----------

    private fun attachResizeHandle(root: View) {
        val handle = root.findViewById<View>(R.id.resizeHandle)
        handle.visibility = if (isFullscreen) View.GONE else View.VISIBLE
        if (isFullscreen) return

        var startWidth = 0
        var startHeight = 0
        var startTouchX = 0f
        var startTouchY = 0f

        handle.setOnTouchListener { _, event ->
            val params = panelParams ?: return@setOnTouchListener false
            val pv = panelView ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startWidth = params.width
                    startHeight = params.height
                    startTouchX = event.rawX
                    startTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dm = resources.displayMetrics
                    val maxWidth = dm.widthPixels
                    val maxHeight = dm.heightPixels - dp(40)
                    params.width = (startWidth + (event.rawX - startTouchX).toInt())
                        .coerceIn(dp(MIN_PANEL_WIDTH_DP), maxWidth)
                    params.height = (startHeight + (event.rawY - startTouchY).toInt())
                        .coerceIn(dp(MIN_PANEL_HEIGHT_DP), maxHeight)
                    runCatching { windowManager.updateViewLayout(pv, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    PrefsHelper.setPanelSize(this, params.width, params.height)
                    true
                }
                else -> false
            }
        }
    }

    // ---------- Drag, tap, snap-to-edge ----------

    private fun attachDragAndTap(
        dragSource: View,
        params: WindowManager.LayoutParams,
        movedView: View,
        onTap: (() -> Unit)?,
        snapToEdge: Boolean = false,
        onDown: (() -> Unit)? = null
    ) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false

        dragSource.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    onDown?.invoke()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (abs(dx) > 6 || abs(dy) > 6) moved = true
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    runCatching { windowManager.updateViewLayout(movedView, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    savedX = params.x
                    savedY = params.y
                    PrefsHelper.setOverlayPosition(this, savedX, savedY)
                    when {
                        !moved -> onTap?.invoke()
                        snapToEdge -> snapBubbleToEdge(movedView, params)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun snapBubbleToEdge(view: View, params: WindowManager.LayoutParams) {
        val screenWidth = resources.displayMetrics.widthPixels
        val bubbleWidth = view.width.takeIf { it > 0 } ?: dp(56)
        val targetX = if (params.x + bubbleWidth / 2 < screenWidth / 2) 0 else screenWidth - bubbleWidth
        animateBubbleX(view, params, targetX)
    }

    private fun animateBubbleX(view: View, params: WindowManager.LayoutParams, targetX: Int) {
        val animator = ValueAnimator.ofInt(params.x, targetX)
        animator.duration = 200
        animator.addUpdateListener { anim ->
            params.x = anim.animatedValue as Int
            runCatching { windowManager.updateViewLayout(view, params) }
        }
        animator.start()
        savedX = targetX
        PrefsHelper.setOverlayPosition(this, targetX, params.y)
        scheduleBubbleFade()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ---------- Panel UI wiring ----------

    private fun setupPanelContents(root: View) {
        root.findViewById<ImageView>(R.id.btnMinimize).setOnClickListener { showBubble() }
        root.findViewById<ImageView>(R.id.btnFullscreen).apply {
            setImageResource(if (isFullscreen) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
            setOnClickListener { toggleFullscreen() }
        }
        root.findViewById<ImageView>(R.id.btnClose).setOnClickListener { stopSelf() }

        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val volumeSeek = root.findViewById<SeekBar>(R.id.volumeSeek)
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        volumeSeek.progress = (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100) / maxVol
        volumeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (value * maxVol) / 100, 0)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        val brightnessSeek = root.findViewById<SeekBar>(R.id.brightnessSeek)
        brightnessSeek.progress = SystemStatsHelper.getBrightnessPercent(this)
        brightnessSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) setBrightness(value)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        buildToolsGrid(root.findViewById(R.id.toolsGrid))
        refreshStats(root)
    }

    private fun setBrightness(percent: Int) {
        if (!Settings.System.canWrite(this)) {
            Toast.makeText(this, "Grant 'Modify system settings' permission first", Toast.LENGTH_SHORT).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val value = (percent * 255) / 100
        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
    }

    // ---------- Fullscreen toggle ----------

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        rebuildPanel()
    }

    // ---------- Tools grid ----------

    private data class ToolItem(val label: String, val iconRes: Int?, val action: () -> Unit)

    private fun buildToolsGrid(grid: GridLayout) {
        grid.removeAllViews()

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val dndOn = nm.isNotificationPolicyAccessGranted &&
            nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL

        val tools = listOf(
            ToolItem("Calculator", null) { launchAppByCategory(Intent.CATEGORY_APP_CALCULATOR) },
            ToolItem("Browser", null) { launchAppByCategory(Intent.CATEGORY_APP_BROWSER) },
            ToolItem("Clean cache", null) { clearOwnCache() },
            ToolItem(
                if (isFlashlightOn) "Flashlight: On" else "Flashlight",
                R.drawable.ic_flashlight
            ) { toggleFlashlight() },
            ToolItem(if (dndOn) "DND: On" else "DND: Off", null) { toggleDnd() },
            ToolItem(
                if (ScreenRecordService.isRecording) "Stop rec." else "Screen rec.",
                null
            ) { toggleScreenRecording() },
            ToolItem("Customize", null) { openAppPicker() }
        )
        for (tool in tools) {
            val btn = Button(this).apply {
                text = tool.label
                textSize = 10.5f
                isAllCaps = false
                maxLines = 1
                tool.iconRes?.let { res ->
                    val d = androidx.core.content.ContextCompat.getDrawable(this@FloatingToolbarService, res)
                    d?.setBounds(0, 0, dp(18), dp(18))
                    setCompoundDrawables(null, d, null, null)
                    compoundDrawablePadding = dp(2)
                }
                setOnClickListener { tool.action() }
                layoutParams = GridLayout.LayoutParams().apply {
                    width = dp(86)
                    height = dp(48)
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
            }
            grid.addView(btn)
        }
        addUserAppShortcuts(grid)
    }

    /** Renders the apps the user picked in AppPickerActivity, with their real icon + label. */
    private fun addUserAppShortcuts(grid: GridLayout) {
        val pm = packageManager
        for (pkg in PrefsHelper.getSelectedApps(this)) {
            val launchIntent = pm.getLaunchIntentForPackage(pkg) ?: continue
            val label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
            val icon = runCatching { pm.getApplicationIcon(pkg) }.getOrNull()

            val btn = Button(this).apply {
                text = label
                textSize = 10f
                isAllCaps = false
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                icon?.let {
                    it.setBounds(0, 0, dp(22), dp(22))
                    setCompoundDrawables(null, it, null, null)
                    compoundDrawablePadding = dp(2)
                }
                setOnClickListener {
                    startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                layoutParams = GridLayout.LayoutParams().apply {
                    width = dp(86)
                    height = dp(56)
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
            }
            grid.addView(btn)
        }
    }

    private fun launchAppByCategory(category: String) {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(category)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, "No app found for this action", Toast.LENGTH_SHORT).show() }
    }

    private fun clearOwnCache() {
        // NOTE: an unprivileged app can only clear its own cache directory.
        // There is no public API to clear other apps' cache/RAM without root
        // or being a system app - "boost/clean" claims in many Play Store
        // apps are largely cosmetic for this reason.
        runCatching { cacheDir.deleteRecursively() }
        Toast.makeText(this, "App cache cleared", Toast.LENGTH_SHORT).show()
    }

    // ---------- Flashlight ----------

    private fun toggleFlashlight() {
        val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        val cameraId = torchCameraId ?: findTorchCameraId(cameraManager)
        if (cameraId == null) {
            Toast.makeText(this, "No flashlight available on this device", Toast.LENGTH_SHORT).show()
            return
        }
        torchCameraId = cameraId
        runCatching {
            cameraManager.setTorchMode(cameraId, !isFlashlightOn)
            isFlashlightOn = !isFlashlightOn
        }.onFailure {
            Toast.makeText(this, "Couldn't toggle flashlight", Toast.LENGTH_SHORT).show()
        }
        panelView?.let { buildToolsGrid(it.findViewById(R.id.toolsGrid)) }
    }

    private fun findTorchCameraId(cameraManager: CameraManager): String? = runCatching {
        cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }.getOrNull()

    private fun turnOffFlashlight() {
        val id = torchCameraId ?: return
        runCatching {
            (getSystemService(CAMERA_SERVICE) as CameraManager).setTorchMode(id, false)
        }
        isFlashlightOn = false
    }

    // ---------- Do Not Disturb ----------

    private fun toggleDnd() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            startActivity(
                Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val isDndOn = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        nm.setInterruptionFilter(
            if (isDndOn) NotificationManager.INTERRUPTION_FILTER_ALL
            else NotificationManager.INTERRUPTION_FILTER_NONE
        )
        panelView?.let { buildToolsGrid(it.findViewById(R.id.toolsGrid)) }
    }

    // ---------- Screen recording ----------

    private fun toggleScreenRecording() {
        if (ScreenRecordService.isRecording) {
            stopService(Intent(this, ScreenRecordService::class.java))
            Toast.makeText(this, "Recording stopped", Toast.LENGTH_SHORT).show()
        } else {
            startActivity(
                Intent(this, ScreenCaptureActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        panelView?.let { buildToolsGrid(it.findViewById(R.id.toolsGrid)) }
    }

    private fun openAppPicker() {
        startActivity(
            Intent(this, AppPickerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // ---------- Live stats ----------

    private fun startStatsUpdates() {
        statsRunnable = object : Runnable {
            override fun run() {
                panelView?.let { refreshStats(it) }
                statsHandler.postDelayed(this, 2000)
            }
        }
        statsHandler.post(statsRunnable)
    }

    private fun refreshStats(root: View) {
        root.findViewById<TextView>(R.id.ramValue)?.text = "${SystemStatsHelper.getRamUsagePercent(this)}%"
        val batteryPercent = SystemStatsHelper.getBatteryPercent(this)
        root.findViewById<TextView>(R.id.batteryValue)?.text =
            if (batteryPercent >= 0) "$batteryPercent%" else "N/A"
        root.findViewById<TextView>(R.id.tempValue)?.text =
            "${SystemStatsHelper.getBatteryTemperature(this)}°C"

        // getCpuUsagePercent() takes ~360ms (it samples /proc/stat twice with a
        // sleep in between), so it must never run on the main thread - doing so
        // every 2 seconds would repeatedly block the UI and could make the
        // overlay appear frozen or unresponsive.
        cpuExecutor.execute {
            val cpu = SystemStatsHelper.getCpuUsagePercent()
            statsHandler.post {
                panelView?.findViewById<TextView>(R.id.cpuValue)?.text = cpu?.let { "$it%" } ?: "N/A"
            }
        }
    }
}
