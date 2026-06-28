package com.example.floatingtoolbar

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast

/**
 * Android requires screen-capture consent to be requested from an Activity
 * (not a Service), via a system dialog. This activity is fully transparent -
 * it exists only to show that dialog, then hands the result to
 * [ScreenRecordService] and immediately finishes.
 */
class ScreenCaptureActivity : Activity() {

    private lateinit var projectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            val serviceIntent = Intent(this, ScreenRecordService::class.java).apply {
                putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenRecordService.EXTRA_RESULT_DATA, data)
            }
            startForegroundService(serviceIntent)
            Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Screen recording permission denied", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    companion object {
        private const val REQUEST_CODE = 42
    }
}
