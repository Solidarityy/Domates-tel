package com.domates

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Serialises the AccessibilityNodeInfo tree to the JSON format expected by MENEMEN.
 * Also captures screenshots via MediaProjection when available.
 */
class ScreenStateCollector(
    private val screenWidth: Int,
    private val screenHeight: Int,
) {

    fun collectTree(root: AccessibilityNodeInfo?): JSONObject {
        val tree = if (root != null) nodeToJson(root, Rect(0, 0, screenWidth, screenHeight)) else JSONObject()
        tree.put("screenSize", JSONObject().apply {
            put("width", screenWidth)
            put("height", screenHeight)
        })
        return tree
    }

    private fun nodeToJson(node: AccessibilityNodeInfo, screenBounds: Rect): JSONObject {
        val obj = JSONObject()

        obj.put("className", node.className?.toString() ?: "")
        obj.put("nodeId", nodeId(node))

        val resId = node.viewIdResourceName
        if (!resId.isNullOrEmpty()) obj.put("viewIdResourceName", resId)

        val text = node.text?.toString()
        if (!text.isNullOrEmpty()) obj.put("text", text)

        val cd = node.contentDescription?.toString()
        if (!cd.isNullOrEmpty()) obj.put("contentDescription", cd)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val hint = node.hintText?.toString()
            if (!hint.isNullOrEmpty()) obj.put("hintText", hint)
        }

        obj.put("clickable", node.isClickable)
        obj.put("longClickable", node.isLongClickable)
        obj.put("editable", node.isEditable)
        obj.put("scrollable", node.isScrollable)
        obj.put("checkable", node.isCheckable)
        obj.put("checked", node.isChecked)
        obj.put("enabled", node.isEnabled)
        obj.put("focusable", node.isFocusable)
        obj.put("selected", node.isSelected)
        obj.put("password", node.isPassword)

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        obj.put("bounds", JSONObject().apply {
            put("left", bounds.left)
            put("top", bounds.top)
            put("right", bounds.right)
            put("bottom", bounds.bottom)
        })
        obj.put("screenSize", JSONObject().apply {
            put("width", screenWidth)
            put("height", screenHeight)
        })

        val childCount = node.childCount
        if (childCount > 0) {
            val children = JSONArray()
            for (i in 0 until childCount) {
                val child = node.getChild(i) ?: continue
                children.put(nodeToJson(child, screenBounds))
                child.recycle()
            }
            obj.put("children", children)
        }

        return obj
    }

    /** Stable pseudo-id derived from position and resource name. */
    private fun nodeId(node: AccessibilityNodeInfo): String {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val resId = node.viewIdResourceName ?: ""
        return "${resId}_${bounds.left}_${bounds.top}_${bounds.right}_${bounds.bottom}"
    }

    /** Encode a Bitmap as base64 PNG. Returns null on failure. */
    fun encodeBitmap(bitmap: Bitmap?): String? {
        bitmap ?: return null
        return try {
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 80, out)
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            null
        }
    }
}
