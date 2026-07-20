package me.evil.wordbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent

class WordSolverAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: WordSolverAccessibilityService? = null
            private set

        val isRunning: Boolean
            get() = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No event handling needed for solver gestures
    }

    override fun onInterrupt() {
        // Interrupt handling
    }

    /**
     * Dispatches a swipe gesture following the provided points path.
     * @param points List of Pair(x, y) representing screen coordinates of path points.
     * @param duration Total stroke duration in milliseconds.
     * @param callback Action to trigger once swipe completes.
     */
    fun performSwipeGesture(points: List<Pair<Float, Float>>, duration: Long, callback: (Boolean) -> Unit) {
        if (points.size < 2) {
            callback(false)
            return
        }

        val path = Path()
        path.moveTo(points[0].first, points[0].second)
        for (i in 1 until points.size) {
            path.lineTo(points[i].first, points[i].second)
        }

        val strokeDescription = GestureDescription.StrokeDescription(path, 0, duration)
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(strokeDescription)

        dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                callback(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                callback(false)
            }
        }, null)
    }
}
