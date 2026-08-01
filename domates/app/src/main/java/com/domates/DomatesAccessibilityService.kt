package com.domates

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.DisplayMetrics
import android.util.Log
import android.view.accessibility.AccessibilityEvent

private const val TAG = "DomatesService"

class DomatesAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: DomatesAccessibilityService? = null
    }

    private lateinit var collector: ScreenStateCollector
    private lateinit var executor: ActionExecutor
    private lateinit var metrics: DisplayMetrics

    override fun onCreate() {
        super.onCreate()
        instance = this
        metrics = resources.displayMetrics
        Log.i(TAG, "DOMATES Accessibility Service created")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = (
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                or AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY
            )
            notificationTimeout = 100
        }
        serviceInfo = info

        collector = ScreenStateCollector(metrics.widthPixels, metrics.heightPixels)
        executor = ActionExecutor(this, metrics.widthPixels, metrics.heightPixels)

        Log.i(TAG, "DOMATES connected — ${metrics.widthPixels}x${metrics.heightPixels}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun captureAndReply(requestId: String) {
        val client = MainActivity.menemenClient ?: run {
            Log.w(TAG, "No MenemenClient available")
            return
        }

        val root = rootInActiveWindow
        val tree = collector.collectTree(root)
        root?.recycle()

        // Screenshot devre dışı — accessibility tree yeterli
        client.sendScreenState(requestId, tree, screenshotB64 = null)
    }
}
