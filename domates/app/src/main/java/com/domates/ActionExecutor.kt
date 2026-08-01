package com.domates

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.media.AudioManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import org.json.JSONObject

/**
 * Translates MENEMEN action JSON into real Android Accessibility gestures/actions.
 *
 * All public methods are suspend-friendly; long operations use callbacks
 * internally and wrap them with CompletableDeferred.
 */
class ActionExecutor(
    private val service: DomatesAccessibilityService,
    private val screenWidth: Int,
    private val screenHeight: Int,
) {

    /** Execute an action dict. Returns true on success. */
    fun execute(action: JSONObject): Boolean {
        return when (action.getString("type")) {
            ActionType.CLICK -> executeClick(action)
            ActionType.TYPE -> executeType(action)
            ActionType.SCROLL -> executeScroll(action)
            ActionType.SWIPE -> executeSwipe(action)
            ActionType.PRESS -> executePress(action)
            ActionType.OPEN_APP -> executeOpenApp(action)
            ActionType.WAIT -> { Thread.sleep(action.optLong("ms", 500)); true }
            else -> false
        }
    }

    // ------------------------------------------------------------------ //
    // Click                                                                //
    // ------------------------------------------------------------------ //

    private fun executeClick(action: JSONObject): Boolean {
        val nodeId = action.optString("node_id").takeIf { it.isNotEmpty() }
        if (nodeId != null) {
            val root = service.rootInActiveWindow ?: return fallbackClick(action)
            val node = findNodeById(root, nodeId)
            if (node != null) {
                val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node.recycle()
                root.recycle()
                if (ok) return true
            }
            root.recycle()
        }
        return fallbackClick(action)
    }

    private fun fallbackClick(action: JSONObject): Boolean {
        val x = (action.optDouble("x", -1.0) * screenWidth).toFloat()
        val y = (action.optDouble("y", -1.0) * screenHeight).toFloat()
        if (x < 0 || y < 0) return false
        return tap(x, y)
    }

    // ------------------------------------------------------------------ //
    // Type                                                                 //
    // ------------------------------------------------------------------ //

    private fun executeType(action: JSONObject): Boolean {
        val text = action.getString("text")
        val clearFirst = action.optBoolean("clear_first", false)

        val focused = findFocusedEditText() ?: return false
        if (clearFirst) {
            val args = android.os.Bundle()
            args.putInt(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0
            )
            args.putInt(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                focused.text?.length ?: 0
            )
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
        }
        val args = android.os.Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        val ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        focused.recycle()
        return ok
    }

    private fun findFocusedEditText(): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    }

    // ------------------------------------------------------------------ //
    // Scroll                                                               //
    // ------------------------------------------------------------------ //

    private fun executeScroll(action: JSONObject): Boolean {
        val direction = action.optString("direction", "down")
        val amount = action.optDouble("amount", 0.5)

        // Try AccessibilityAction scroll first (battery-friendly)
        val nodeId = action.optString("node_id").takeIf { it.isNotEmpty() }
        val scrollNode = if (nodeId != null) {
            val root = service.rootInActiveWindow
            root?.let { findNodeById(it, nodeId) }
        } else {
            findFirstScrollable()
        }

        if (scrollNode != null) {
            val scrollAction = when (direction) {
                "down" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                "up" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                "right" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                "left" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
            val ok = scrollNode.performAction(scrollAction)
            scrollNode.recycle()
            if (ok) return true
        }

        // Fallback to gesture swipe
        val cx = screenWidth / 2f
        val cy = screenHeight / 2f
        val delta = (screenHeight * amount).toFloat()
        return when (direction) {
            "down" -> swipeGesture(cx, cy + delta / 2, cx, cy - delta / 2)
            "up" -> swipeGesture(cx, cy - delta / 2, cx, cy + delta / 2)
            "right" -> swipeGesture(cx - delta / 2, cy, cx + delta / 2, cy)
            "left" -> swipeGesture(cx + delta / 2, cy, cx - delta / 2, cy)
            else -> false
        }
    }

    private fun findFirstScrollable(): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        return findScrollableNode(root)
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findScrollableNode(child)
            if (found != null) { if (found != child) child.recycle(); return found }
            child.recycle()
        }
        return null
    }

    // ------------------------------------------------------------------ //
    // Swipe                                                                //
    // ------------------------------------------------------------------ //

    private fun executeSwipe(action: JSONObject): Boolean {
        val fx = (action.getDouble("from_x") * screenWidth).toFloat()
        val fy = (action.getDouble("from_y") * screenHeight).toFloat()
        val tx = (action.getDouble("to_x") * screenWidth).toFloat()
        val ty = (action.getDouble("to_y") * screenHeight).toFloat()
        val duration = action.optLong("duration_ms", 300)
        return swipeGesture(fx, fy, tx, ty, duration)
    }

    // ------------------------------------------------------------------ //
    // Press                                                                //
    // ------------------------------------------------------------------ //

    private fun executePress(action: JSONObject): Boolean {
        return when (action.optString("key", "")) {
            "back" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            "home" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            "recents" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            "volume_up" -> adjustVolume(AudioManager.ADJUST_RAISE)
            "volume_down" -> adjustVolume(AudioManager.ADJUST_LOWER)
            "mute" -> adjustVolume(AudioManager.ADJUST_MUTE)
            "enter" -> {
                val focused = service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                val ok = focused?.performAction(AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY) ?: false
                focused?.recycle()
                ok
            }
            else -> false
        }
    }

    private fun adjustVolume(direction: Int): Boolean {
        val audio = service.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        return true
    }

    // ------------------------------------------------------------------ //
    // Open App                                                             //
    // ------------------------------------------------------------------ //

    private fun executeOpenApp(action: JSONObject): Boolean {
        val pkg = action.getString("package")
        val intent = service.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        service.startActivity(intent)
        return true
    }

    // ------------------------------------------------------------------ //
    // Gesture helpers                                                      //
    // ------------------------------------------------------------------ //

    private fun tap(x: Float, y: Float): Boolean {
        return swipeGesture(x, y, x, y, 50)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun swipeGesture(
        fromX: Float, fromY: Float,
        toX: Float, toY: Float,
        durationMs: Long = 300,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val desc = GestureDescription.Builder().addStroke(stroke).build()
        var result = false
        val latch = java.util.concurrent.CountDownLatch(1)
        service.dispatchGesture(desc, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gesture: GestureDescription?) {
                result = true; latch.countDown()
            }
            override fun onCancelled(gesture: GestureDescription?) { latch.countDown() }
        }, null)
        latch.await(durationMs + 500, java.util.concurrent.TimeUnit.MILLISECONDS)
        return result
    }

    // ------------------------------------------------------------------ //
    // Node search                                                          //
    // ------------------------------------------------------------------ //

    private fun findNodeById(root: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
        if (nodeIdOf(root) == id) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNodeById(child, id)
            if (found != null) { if (found != child) child.recycle(); return found }
            child.recycle()
        }
        return null
    }

    private fun nodeIdOf(node: AccessibilityNodeInfo): String {
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        val resId = node.viewIdResourceName ?: ""
        return "${resId}_${bounds.left}_${bounds.top}_${bounds.right}_${bounds.bottom}"
    }
}
