package com.example.satyaho

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.annotation.RequiresApi

class SatyaAccessibilityService : AccessibilityService() {

    companion object {
        // A global instance so our Overlay Service can talk to this service
        var instance: SatyaAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("SatyaHo", "Accessibility Service Connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Required method, but we don't need to track specific screen events for this prototype
    }

    override fun onInterrupt() {
        // Required method
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun captureScreenSilently(onSuccess: (Bitmap) -> Unit, onError: () -> Unit) {
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                // The screenshot is returned as a HardwareBuffer. We convert it to a Bitmap for your backend.
                val bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                if (bitmap != null) {
                    onSuccess(bitmap)
                } else {
                    onError()
                }
            }

            override fun onFailure(errorCode: Int) {
                Log.e("SatyaHo", "Screenshot failed with error code: $errorCode")
                onError()
            }
        })
    }
}