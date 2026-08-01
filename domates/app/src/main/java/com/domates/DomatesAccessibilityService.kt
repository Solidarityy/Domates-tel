package com.domates

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "DomatesService"

/**
 * Core Android Accessibility Service for DOMATES.
 *
 * Responsibilities:
 *  - Maintain a singleton reference so MenemenClient can reach it.
 *  - Respond to screen capture requests by collecting the Accessibility tree
 *    (and optionally a screenshot via MediaProjection).
 *  - Forward actions to ActionExecutor.
 *
 * The service is started/stopped by the OS; MainActivity holds a reference to
 * MenemenClient which this service feeds with screen data.
 */
class DomatesAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: DomatesAccessibilityService? = null
    }

    private lateinit var collector: ScreenStateCollector
    private lateinit var executor: ActionExecutor
    private lateinit var metrics: DisplayMetrics

    // MediaProjection for screenshots (optional, set by MainActivity after user grants)
    @Volatile
    var mediaProjection: MediaProjection? = null

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

        Log.i(TAG, "DOMATES Accessibility Service connected — screen ${metrics.widthPixels}x${metrics.heightPixels}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to react to individual events; MENEMEN drives the loop.
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ //
    // Screen capture                                                       //
    // ------------------------------------------------------------------ //

    /**
     * Captures the current screen state and sends it to [client].
     * Called from MenemenClient on a background thread.
     */
    fun captureAndReply(requestId: String) {
        val client = MainActivity.menemenClient ?: run {
            Log.w(TAG, "No MenemenClient available")
            return
        }

        val root = rootInActiveWindow
        val tree = collector.collectTree(root)
        root?.recycle()

        val screenshotB64 = captureScreenshot()

        client.sendScreenState(requestId, tree, screenshotB64)
    }

    private fun captureScreenshot(): String? {
        val projection = mediaProjection ?: return null
        return try {
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            val imageReader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 1)
            val display = projection.createVirtualDisplay(
                "DomatesCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface, null, null
            )

            Thread.sleep(200)  // allow frame to render

            val image = imageReader.acquireLatestImage()
            val bitmap = if (image != null) {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width
                val bmp = Bitmap.createBitmap(
                    width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
                )
                bmp.copyPixelsFromBuffer(buffer)
                image.close()
                Bitmap.createBitmap(bmp, 0, 0, width, height)
            } else null

            display.release()
            imageReader.close()

            collector.encodeBitmap(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot failed", e)
            null
        }
    }
}
