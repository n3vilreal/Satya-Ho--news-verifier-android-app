package com.example.satyaho

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.core.net.toUri

class MainActivity : Activity() {

    private val overlayPermissionReqCode = 1234
    private val screenshotPermissionReqCode = 5678
    private lateinit var mediaProjectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // CRITICAL FIX: Load our new beautiful XML screen instead of the simple button
        setContentView(R.layout.activity_main)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        if (intent.getBooleanExtra("REQUEST_SCREENSHOT", false)) {
            requestScreenshotPermission()
            return
        }

        // Find the new styled button from our XML and attach the click logic
        val btnStart = findViewById<Button>(R.id.btn_start_overlay)
        btnStart.setOnClickListener { checkPermissionAndStart() }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra("REQUEST_SCREENSHOT", false) == true) {
            requestScreenshotPermission()
        }
    }

    private fun requestScreenshotPermission() {
        startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), screenshotPermissionReqCode)
    }

    private fun checkPermissionAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri()
            )
            startActivityForResult(intent, overlayPermissionReqCode)
        } else {
            startService(Intent(this, SatyaOverlayService::class.java))
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == overlayPermissionReqCode) {
            if (Settings.canDrawOverlays(this)) {
                startService(Intent(this, SatyaOverlayService::class.java))
                finish()
            }
        }
        else if (requestCode == screenshotPermissionReqCode) {
            if (resultCode == RESULT_OK && data != null) {
                // Safely access the running service instance
                val serviceInstance = SatyaOverlayService.instance
                if (serviceInstance != null) {
                    serviceInstance.onScreenshotPermissionGranted(resultCode, data)
                } else {
                    Toast.makeText(this, "Service not running.", Toast.LENGTH_SHORT).show()
                }
            }
            finish()
        }
    }
}