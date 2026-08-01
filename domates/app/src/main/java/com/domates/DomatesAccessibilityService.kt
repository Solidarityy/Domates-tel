package com.domates

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.util.DisplayMetrics
import android.util.Log
import android.view.accessibility.AccessibilityEvent

private const val TAG = "DomatesService"
private const val KANAL_ID = "domates_aktif"
private const val BILDIRIM_ID = 1

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
    }

    override fun onServiceConnected() {
        Log.i(TAG, "onServiceConnected başladı")
        try {
            super.onServiceConnected()
            instance = this

            val info = AccessibilityServiceInfo().apply {
                eventTypes = AccessibilityEvent.TYPES_ALL_MASK
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                        AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                notificationTimeout = 100
            }
            serviceInfo = info
            Log.i(TAG, "serviceInfo ayarlandı")

            collector = ScreenStateCollector(metrics.widthPixels, metrics.heightPixels)
            executor = ActionExecutor(this, metrics.widthPixels, metrics.heightPixels)
            Log.i(TAG, "DOMATES bağlandı — ${metrics.widthPixels}x${metrics.heightPixels}")

            try {
                bildirimiGoster()
                Log.i(TAG, "Bildirim gösterildi")
            } catch (e: Exception) {
                Log.e(TAG, "Bildirim hatası", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "onServiceConnected crash", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        Log.w(TAG, "Servis kesintiye uğradı")
    }

    override fun onDestroy() {
        bildirimiGizle()
        instance = null
        super.onDestroy()
    }

    fun captureAndReply(requestId: String) {
        val client = MainActivity.menemenClient ?: return
        val root = rootInActiveWindow
        val tree = collector.collectTree(root)
        root?.recycle()
        client.sendScreenState(requestId, tree, screenshotB64 = null)
    }

    // ─── Bildirim ────────────────────────────────────────────────────────

    private fun bildirimiGoster() {
        val nm = getSystemService(NotificationManager::class.java)

        val kanal = NotificationChannel(
            KANAL_ID, "DOMATES Servisi",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "DOMATES erişilebilirlik servisi aktif"
            setShowBadge(false)
        }
        nm.createNotificationChannel(kanal)

        val sesliIntent = Intent(this, VoiceCommandActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sesliPending = PendingIntent.getActivity(
            this, 0, sesliIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val sesliAksiyon = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_btn_speak_now),
            "🎤  Sesli Komut",
            sesliPending
        ).build()

        val bildirim = Notification.Builder(this, KANAL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("🍅 DOMATES Aktif")
            .setContentText("Sesli komut göndermek için dokunun")
            .setOngoing(true)
            .setContentIntent(sesliPending)
            .addAction(sesliAksiyon)
            .build()

        nm.notify(BILDIRIM_ID, bildirim)
    }

    private fun bildirimiGizle() {
        getSystemService(NotificationManager::class.java).cancel(BILDIRIM_ID)
    }
}
