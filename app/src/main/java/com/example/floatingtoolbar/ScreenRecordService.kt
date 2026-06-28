package com.example.floatingtoolbar

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records the screen to /Movies once the user grants MediaProjection
 * consent via [ScreenCaptureActivity]. While recording, shows a small
 * floating pill (elapsed timer + stop button) so the user always has a
 * visible way to end the recording, independent of the main panel.
 *
 * This is a basic implementation: it always records at the device's
 * current resolution and doesn't handle orientation changes mid-recording.
 */
class ScreenRecordService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null

    private lateinit var windowManager: WindowManager
    private var indicatorView: View? = null
    private var indicatorParams: WindowManager.LayoutParams? = null
    private val indicatorHandler = Handler(Looper.getMainLooper())
    private var startTimeMs = 0L
    private lateinit var tickRunnable: Runnable

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultData != null) {
            val projectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
            startRecording()
        } else {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Screen Recording", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording screen")
            .setSmallIcon(R.drawable.ic_toolbar)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notification)
    }

    private fun startRecording() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val moviesDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        if (moviesDir != null && !moviesDir.exists()) moviesDir.mkdirs()
        val fileName = "recording_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
        val outputFile = File(moviesDir, fileName)

        try {
            mediaRecorder = MediaRecorder().apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(width, height)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(8 * 1024 * 1024)
                setOutputFile(outputFile.absolutePath)
                prepare()
            }

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "FloatingToolbarCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder?.surface, null, null
            )

            mediaRecorder?.start()
            isRecording = true
            showRecordingIndicator()
        } catch (e: Exception) {
            isRecording = false
            stopSelf()
        }
    }

    // ---------- Floating "recording in progress" indicator ----------

    private fun showRecordingIndicator() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val inflated = LayoutInflater.from(this).inflate(R.layout.floating_recording_indicator, null)
        indicatorView = inflated

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

        indicatorParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (16 * resources.displayMetrics.density).toInt()
            y = (100 * resources.displayMetrics.density).toInt()
        }

        inflated.findViewById<View>(R.id.btnStopRecording).setOnClickListener { stopSelf() }
        runCatching { windowManager.addView(inflated, indicatorParams) }

        startTimeMs = System.currentTimeMillis()
        tickRunnable = object : Runnable {
            override fun run() {
                val elapsedSeconds = (System.currentTimeMillis() - startTimeMs) / 1000
                val minutes = elapsedSeconds / 60
                val seconds = elapsedSeconds % 60
                indicatorView?.findViewById<TextView>(R.id.recordingTimer)?.text =
                    String.format(Locale.US, "%02d:%02d", minutes, seconds)
                indicatorHandler.postDelayed(this, 1000)
            }
        }
        indicatorHandler.post(tickRunnable)
    }

    private fun removeRecordingIndicator() {
        indicatorHandler.removeCallbacksAndMessages(null)
        indicatorView?.let { runCatching { windowManager.removeView(it) } }
        indicatorView = null
    }

    override fun onDestroy() {
        super.onDestroy()
        removeRecordingIndicator()
        runCatching {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        }
        virtualDisplay?.release()
        mediaProjection?.stop()
        isRecording = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        var isRecording = false
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val CHANNEL_ID = "screen_record_channel"
        private const val NOTIF_ID = 1002
    }
}
