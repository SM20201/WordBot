package me.evil.wordbot

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_OVERLAY = 1001
        private const val REQUEST_CAPTURE = 1002

        // Store MediaProjection token result to share with background OverlayService
        var captureResultCode: Int = 0
        var captureIntentData: Intent? = null

        fun clearCaptureToken() {
            captureResultCode = 0
            captureIntentData = null
        }
    }

    private lateinit var tvOverlayStatus: TextView
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var tvCaptureStatus: TextView

    private lateinit var btnGrantOverlay: Button
    private lateinit var btnGrantAccessibility: Button
    private lateinit var btnInitCapture: Button
    private lateinit var btnLaunchOverlay: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI Elements
        tvOverlayStatus = findViewById(R.id.tv_overlay_status)
        tvAccessibilityStatus = findViewById(R.id.tv_accessibility_status)
        tvCaptureStatus = findViewById(R.id.tv_capture_status)

        btnGrantOverlay = findViewById(R.id.btn_grant_overlay)
        btnGrantAccessibility = findViewById(R.id.btn_grant_accessibility)
        btnInitCapture = findViewById(R.id.btn_init_capture)
        btnLaunchOverlay = findViewById(R.id.btn_launch_overlay)

        // Set Click Listeners
        btnGrantOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, REQUEST_OVERLAY)
            } else {
                Toast.makeText(this, "Overlay permission already granted!", Toast.LENGTH_SHORT).show()
            }
        }

        btnGrantAccessibility.setOnClickListener {
            if (!WordSolverAccessibilityService.isRunning) {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
                Toast.makeText(this, "Please find & enable 'WordBot Auto-Swipe Service'", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Accessibility Service is already running!", Toast.LENGTH_SHORT).show()
            }
        }

        btnInitCapture.setOnClickListener {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CAPTURE)
        }

        btnLaunchOverlay.setOnClickListener {
            if (validateAllPermissions()) {
                startSolverOverlayService()
            } else {
                Toast.makeText(this, "Please resolve all setup steps first!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatusUI()
        // Auto-trigger capture prompt if overlay & accessibility are ready but capture isn't
        autoRequestCaptureIfNeeded()
    }

    /**
     * Checks whether our AccessibilityService is actually enabled in system settings.
     * This works correctly across process deaths unlike the in-memory isRunning flag.
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponent = ComponentName(this, WordSolverAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (ComponentName.unflattenFromString(componentName) == expectedComponent) return true
        }
        return false
    }

    /**
     * If overlay + accessibility are already granted but we don't have a capture token yet,
     * silently kick off the system capture permission dialog so the user only taps "Allow"
     * without having to find the button themselves.
     */
    private fun autoRequestCaptureIfNeeded() {
        if (Settings.canDrawOverlays(this)
            && isAccessibilityServiceEnabled()
            && captureIntentData == null
        ) {
            val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CAPTURE)
        }
    }

    private fun updateStatusUI() {
        // 1. Overlay Permission Status
        val hasOverlay = Settings.canDrawOverlays(this)
        if (hasOverlay) {
            tvOverlayStatus.text = "GRANTED"
            tvOverlayStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green))
            btnGrantOverlay.isEnabled = false
            btnGrantOverlay.alpha = 0.5f
        } else {
            tvOverlayStatus.text = "REJECTED"
            tvOverlayStatus.setTextColor(ContextCompat.getColor(this, R.color.status_red))
            btnGrantOverlay.isEnabled = true
            btnGrantOverlay.alpha = 1.0f
        }

        // 2. Accessibility Status — read from OS settings, not in-memory flag
        val hasAccessibility = isAccessibilityServiceEnabled()
        if (hasAccessibility) {
            tvAccessibilityStatus.text = "ACTIVE"
            tvAccessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green))
            btnGrantAccessibility.isEnabled = false
            btnGrantAccessibility.alpha = 0.5f
        } else {
            tvAccessibilityStatus.text = "INACTIVE"
            tvAccessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.status_red))
            btnGrantAccessibility.isEnabled = true
            btnGrantAccessibility.alpha = 1.0f
        }

        // 3. Capture Token Status
        val hasCapture = captureIntentData != null
        if (hasCapture) {
            tvCaptureStatus.text = "READY"
            tvCaptureStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green))
            btnInitCapture.isEnabled = false
            btnInitCapture.alpha = 0.5f
        } else {
            tvCaptureStatus.text = "NOT READY"
            tvCaptureStatus.setTextColor(ContextCompat.getColor(this, R.color.status_red))
            btnInitCapture.isEnabled = true
            btnInitCapture.alpha = 1.0f
        }

        // 4. Update Launch Button state
        val allReady = hasOverlay && hasAccessibility && hasCapture
        btnLaunchOverlay.isEnabled = allReady
        btnLaunchOverlay.alpha = if (allReady) 1.0f else 0.5f
    }

    private fun validateAllPermissions(): Boolean {
        return Settings.canDrawOverlays(this) &&
                isAccessibilityServiceEnabled() &&
                captureIntentData != null
    }

    private fun startSolverOverlayService() {
        val serviceIntent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        // Minimize the activity
        moveTaskToBack(true)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OVERLAY) {
            updateStatusUI()
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Overlay permission granted!", Toast.LENGTH_SHORT).show()
            }
        } else if (requestCode == REQUEST_CAPTURE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                captureResultCode = resultCode
                captureIntentData = data
                updateStatusUI()
                // Auto-launch the service now that we have everything we need
                if (validateAllPermissions()) {
                    startSolverOverlayService()
                } else {
                    Toast.makeText(this, "Screen capture ready!", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Screen capture denied — tap the button to retry.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
