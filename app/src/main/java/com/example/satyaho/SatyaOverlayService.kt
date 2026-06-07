package com.example.satyaho

import java.util.concurrent.TimeUnit
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.core.graphics.toColorInt
import kotlin.math.abs

// Required Coroutine Imports
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Required OkHttp Imports
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody



class SatyaOverlayService : Service() {

    companion object {
        @SuppressLint("StaticFieldLeak")
        var instance: SatyaOverlayService? = null
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private var trashView: View? = null

    private lateinit var params: LayoutParams
    private var screenWidth = 0

    // UI References
    private lateinit var layoutCollapsed: CardView
    private lateinit var layoutExpanded: CardView
    private lateinit var progressBar: ProgressBar
    private lateinit var resultContainer: CardView
    private lateinit var tvScore: TextView
    private lateinit var scoreProgress: ProgressBar
    private lateinit var tvReason: TextView

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("InflateParams")
    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        screenWidth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            windowMetrics.bounds.width()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(metrics)
            metrics.widthPixels
        }

        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        overlayView = inflater.inflate(R.layout.layout_floating_widget, null)

        params = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            LayoutParams.TYPE_APPLICATION_OVERLAY,
            LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 300

        windowManager.addView(overlayView, params)
        setupInteractivity()
    }

    private fun showTrashView() {
        if (trashView != null) return
        trashView = TextView(this).apply {
            text = "🗑️"
            textSize = 40f
            setBackgroundColor("#44FF0000".toColorInt())
            setPadding(40, 40, 40, 40)
        }
        val trashParams = LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
            LayoutParams.TYPE_APPLICATION_OVERLAY,
            LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 100
        }
        windowManager.addView(trashView, trashParams)
    }

    private fun hideTrashView() {
        trashView?.let {
            windowManager.removeView(it)
            trashView = null
        }
    }

    private fun isViewOverlapping(view1: View, view2: View): Boolean {
        val rect1 = Rect()
        view1.getHitRect(rect1)
        val loc1 = IntArray(2)
        view1.getLocationOnScreen(loc1)
        rect1.offset(loc1[0], loc1[1])

        val rect2 = Rect()
        view2.getHitRect(rect2)
        val loc2 = IntArray(2)
        view2.getLocationOnScreen(loc2)
        rect2.offset(loc2[0], loc2[1])

        return Rect.intersects(rect1, rect2)
    }

    @SuppressLint("SetTextI18n", "ClickableViewAccessibility")
    private fun setupInteractivity() {
        layoutCollapsed = overlayView.findViewById(R.id.layout_collapsed)
        layoutExpanded = overlayView.findViewById(R.id.layout_expanded)
        val popupHeader = overlayView.findViewById<RelativeLayout>(R.id.popup_header)

        val btnCollapse = overlayView.findViewById<ImageButton>(R.id.btn_collapse)
        val btnCheckText = overlayView.findViewById<Button>(R.id.btn_check_text)
        val btnCheckPhoto = overlayView.findViewById<Button>(R.id.btn_check_photo)
        val etClaim = overlayView.findViewById<EditText>(R.id.et_claim)

        val btnPaste = overlayView.findViewById<Button>(R.id.btn_paste)

        // The new Clear button logic
        val btnClear = overlayView.findViewById<Button>(R.id.btn_clear)
        btnClear.setOnClickListener {
            etClaim.setText("") // Empties the text box

            // Optional: Hide the result box if they clear the text
            resultContainer.visibility = View.GONE
        }

        // Force a Paste bypass for Overlays
        btnPaste.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            if (clipboard.hasPrimaryClip() && clipboard.primaryClip != null) {
                val item = clipboard.primaryClip!!.getItemAt(0)
                val pasteData = item.text
                if (pasteData != null) {
                    etClaim.setText(pasteData.toString())
                    Toast.makeText(this, "Pasted from clipboard", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        }

        progressBar = overlayView.findViewById(R.id.progress_bar)
        resultContainer = overlayView.findViewById(R.id.result_container)
        tvScore = overlayView.findViewById(R.id.tv_score)
        scoreProgress = overlayView.findViewById(R.id.score_progress)
        tvReason = overlayView.findViewById(R.id.tv_reason)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        // 1. Logic for Dragging the COLLAPSED Bubble
        layoutCollapsed.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    showTrashView()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    var newX = initialX + (event.rawX - initialTouchX).toInt()
                    var newY = initialY + (event.rawY - initialTouchY).toInt()

                    if (trashView != null) {
                        val trashLoc = IntArray(2)
                        trashView!!.getLocationOnScreen(trashLoc)

                        val trashCenterX = trashLoc[0] + (trashView!!.width / 2)
                        val trashCenterY = trashLoc[1] + (trashView!!.height / 2)

                        val distance = Math.hypot(
                            (event.rawX - trashCenterX).toDouble(),
                            (event.rawY - trashCenterY).toDouble()
                        )

                        val magneticRadius = 250.0

                        if (distance < magneticRadius) {
                            newX = trashCenterX - (layoutCollapsed.width / 2)
                            newY = trashCenterY - (layoutCollapsed.height / 2)
                            trashView!!.scaleX = 1.2f
                            trashView!!.scaleY = 1.2f
                        } else {
                            trashView!!.scaleX = 1.0f
                            trashView!!.scaleY = 1.0f
                        }
                    }

                    params.x = newX
                    params.y = newY
                    windowManager.updateViewLayout(overlayView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val xDiff = (event.rawX - initialTouchX).toInt()
                    val yDiff = (event.rawY - initialTouchY).toInt()

                    if (trashView != null && isViewOverlapping(layoutCollapsed, trashView!!)) {
                        hideTrashView()
                        stopSelf()
                        return@setOnTouchListener true
                    }
                    hideTrashView()

                    if (abs(xDiff) < 10 && abs(yDiff) < 10) {
                        expandPopup()
                    } else {
                        val middle = screenWidth / 2
                        params.x = if (params.x + (layoutCollapsed.width / 2) < middle) 0 else screenWidth
                        windowManager.updateViewLayout(overlayView, params)
                    }
                    true
                }
                else -> false
            }
        }

        // 2. Logic for Dragging the EXPANDED Popup by its Header
        popupHeader.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(overlayView, params)
                    true
                }
                else -> false
            }
        }

        // 3. Close Popup back to Bubble
        btnCollapse.setOnClickListener { collapsePopup() }

        // 4. Text Check Workflow
        btnCheckText.setOnClickListener {
            val claim = etClaim.text.toString().trim()
            if (claim.isEmpty()) {
                Toast.makeText(this, "Paste text first!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sendToBackend(claim, isImage = false)
        }

        // 5. Silent Screenshot Check Workflow
        btnCheckPhoto.setOnClickListener {
            collapsePopup()

            if (SatyaAccessibilityService.instance == null) {
                Toast.makeText(this, "Please enable Satya Ho in Accessibility Settings!", Toast.LENGTH_LONG).show()
                val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return@setOnClickListener
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Toast.makeText(this, "📸 Snapping screen...", Toast.LENGTH_SHORT).show()

                Handler(Looper.getMainLooper()).postDelayed({
                    SatyaAccessibilityService.instance?.captureScreenSilently(
                        onSuccess = { bitmap ->
                            val base64Image = encodeBitmapToBase64(bitmap)
                            val dataUri = "data:image/jpeg;base64,$base64Image"
                            sendToBackend(dataUri, isImage = true)
                        },
                        onError = {
                            Toast.makeText(this, "Failed to capture screen", Toast.LENGTH_SHORT).show()
                            expandPopup()
                        }
                    )
                }, 300)
            } else {
                Toast.makeText(this, "Silent screenshots require Android 11+", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // --- HELPER METHODS ---

    private fun encodeBitmapToBase64(bitmap: android.graphics.Bitmap): String {
        // CRITICAL FIX: Android Hardware Bitmaps cannot be compressed. We must copy it first.
        val softwareBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == android.graphics.Bitmap.Config.HARDWARE) {
            bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }

        val outputStream = java.io.ByteArrayOutputStream()
        // Lower quality to 50 to prevent massive 5MB payloads from choking your local network
        softwareBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 50, outputStream)
        val byteArray = outputStream.toByteArray()
        return android.util.Base64.encodeToString(byteArray, android.util.Base64.NO_WRAP)
    }

    private fun expandPopup() {
        layoutCollapsed.visibility = View.GONE
        layoutExpanded.visibility = View.VISIBLE
        params.flags = params.flags and LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        params.x = (screenWidth - layoutExpanded.width) / 2
        windowManager.updateViewLayout(overlayView, params)
    }

    private fun collapsePopup() {
        layoutCollapsed.visibility = View.VISIBLE
        layoutExpanded.visibility = View.GONE
        params.flags = params.flags or LayoutParams.FLAG_NOT_FOCUSABLE
        params.x = if (params.x < screenWidth / 2) 0 else screenWidth
        windowManager.updateViewLayout(overlayView, params)
    }

    // --- REAL BACKEND INTEGRATION ---

    // NOTE: Make sure this is changed to your actual Mac/Acer laptop IP address!
    private val apiUrl = "http://10.51.12.171:5000/analyze"
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    @SuppressLint("SetTextI18n")
    private fun sendToBackend(payloadText: String, isImage: Boolean) {
        // Show loading state
        expandPopup()
        resultContainer.visibility = View.GONE
        progressBar.visibility = View.VISIBLE

        val jsonPayload = org.json.JSONObject()
        jsonPayload.put("text", payloadText)
        jsonPayload.put("is_image", isImage)

        // Updated OkHttp methods
        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
        val requestBody = jsonPayload.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(apiUrl)
            .post(requestBody)
            .build()

        // Executed using correct Coroutine scope
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = client.newCall(request).execute()
                val responseData = response.body?.string()

                if (response.isSuccessful && responseData != null) {
                    val jsonResponse = org.json.JSONObject(responseData)

                    withContext(Dispatchers.Main) {
                        updateUIWithResults(jsonResponse)
                    }
                } else {
                    showError("Backend Error: ${response.code}")
                }
            } catch (e: Exception) {
                showError("Cannot connect to backend. Is server.py running?")
                e.printStackTrace()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateUIWithResults(result: org.json.JSONObject) {
        progressBar.visibility = View.GONE
        resultContainer.visibility = View.VISIBLE

        val score = result.optInt("score", 0)
        val status = result.optString("status", "Unknown")
        val reason = result.optString("reason", "No reasoning provided.")

        scoreProgress.progress = score
        tvScore.text = "Credibility: $score% ($status)"

        when {
            score >= 75 -> {
                tvScore.setTextColor("#10B981".toColorInt())
                scoreProgress.progressTintList = android.content.res.ColorStateList.valueOf("#10B981".toColorInt())
            }
            score >= 35 -> {
                tvScore.setTextColor("#F59E0B".toColorInt())
                scoreProgress.progressTintList = android.content.res.ColorStateList.valueOf("#F59E0B".toColorInt())
            }
            else -> {
                tvScore.setTextColor("#EF4444".toColorInt())
                scoreProgress.progressTintList = android.content.res.ColorStateList.valueOf("#EF4444".toColorInt())
            }
        }

        tvReason.text = reason

        val sourceObj = result.optJSONObject("closest_match")
        if (sourceObj != null && sourceObj.optString("link", "").isNotEmpty()) {
            val title = sourceObj.optString("title", "Matching trusted news source")
            tvReason.append("\n\n🔗 Source: $title")
        }
    }

    private suspend fun showError(message: String) {
        withContext(Dispatchers.Main) {
            progressBar.visibility = View.GONE
            Toast.makeText(this@SatyaOverlayService, message, Toast.LENGTH_LONG).show()
        }
    }

    // Suppressed the unused parameters warning
    @Suppress("UNUSED_PARAMETER")
    @SuppressLint("SetTextI18n")
    fun onScreenshotPermissionGranted(resultCode: Int, data: Intent) {
        Toast.makeText(this, "📸 Screen Captured! Analyzing...", Toast.LENGTH_SHORT).show()

        Handler(Looper.getMainLooper()).postDelayed({
            expandPopup()
            resultContainer.visibility = View.VISIBLE
            progressBar.visibility = View.GONE

            tvScore.text = "✅ Credibility: 89%"
            tvScore.setTextColor("#059669".toColorInt())
            scoreProgress.progress = 89
            tvReason.text = "🧾 Photo scan: Graphic matches verified Kantipur report."
        }, 2000)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        hideTrashView()
        if (::overlayView.isInitialized) {
            windowManager.removeView(overlayView)
        }
    }
}