package com.domates

import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val TAG = "MenemenClient"

/**
 * WebSocket client that connects DOMATES (Android) to MENEMEN (Python server).
 *
 * Thread model: OkHttp delivers callbacks on its own thread. The ActionExecutor
 * is called directly from those callbacks (blocking). Screen captures are
 * dispatched from the AccessibilityService thread via [onScreenRequest].
 */
class MenemenClient(
    private val serverUrl: String,          // e.g. "ws://192.168.1.100:8765"
    private val collector: ScreenStateCollector,
    private val executor: ActionExecutor,
    private val onTaskResult: (ok: Boolean, result: String) -> Unit,
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)   // no read timeout on WS
        .build()

    private var ws: WebSocket? = null

    // Pending screen capture requests: requestId → callback
    private val screenRequests = ConcurrentHashMap<String, (JSONObject) -> Unit>()

    // ------------------------------------------------------------------ //
    // Lifecycle                                                            //
    // ------------------------------------------------------------------ //

    fun connect(task: String) {
        val request = Request.Builder().url(serverUrl).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Connected to MENEMEN at $serverUrl")
                // First message must be the task
                webSocket.send(JSONObject().apply {
                    put("type", MessageType.TASK)
                    put("task", task)
                }.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(webSocket, JSONObject(text))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
                onTaskResult(false, t.message ?: "Connection error")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $reason")
            }
        })
    }

    fun disconnect() {
        ws?.close(1000, "Client disconnect")
        ws = null
    }

    // ------------------------------------------------------------------ //
    // Incoming message handling                                            //
    // ------------------------------------------------------------------ //

    private fun handleMessage(webSocket: WebSocket, msg: JSONObject) {
        when (msg.getString("type")) {

            MessageType.SCREEN_REQUEST -> {
                val reqId = msg.getString("request_id")
                Log.d(TAG, "Screen request $reqId")
                // CollectorService posts a screen capture; we reply when ready.
                // For now capture synchronously on this callback thread.
                // The AccessibilityService will call fulfillScreenRequest() when ready.
                DomatesAccessibilityService.instance?.captureAndReply(reqId)
                    ?: run {
                        Log.w(TAG, "AccessibilityService not running, sending empty tree")
                        sendScreenState(reqId, JSONObject(), null)
                    }
            }

            MessageType.ACTION -> {
                val reqId = msg.getString("request_id")
                Log.d(TAG, "Action $reqId: ${msg.optString("type")}")
                val ok = try {
                    executor.execute(msg)
                } catch (e: Exception) {
                    Log.e(TAG, "Action execution error", e)
                    false
                }
                webSocket.send(JSONObject().apply {
                    put("type", MessageType.ACTION_ACK)
                    put("request_id", reqId)
                    put("ok", ok)
                    if (!ok) put("error", "Execution failed")
                }.toString())
            }

            MessageType.TASK_RESULT -> {
                val ok = msg.optBoolean("ok", false)
                val result = msg.optString("result", "")
                Log.i(TAG, "Task result ok=$ok: $result")
                onTaskResult(ok, result)
            }
        }
    }

    // ------------------------------------------------------------------ //
    // Screen state reply (called by AccessibilityService)                  //
    // ------------------------------------------------------------------ //

    fun sendScreenState(requestId: String, tree: JSONObject, screenshotB64: String?) {
        val msg = JSONObject().apply {
            put("type", MessageType.SCREEN_STATE)
            put("request_id", requestId)
            put("tree", tree)
            if (screenshotB64 != null) put("screenshot", screenshotB64)
        }
        ws?.send(msg.toString()) ?: Log.w(TAG, "Cannot send; no WebSocket")
    }
}
