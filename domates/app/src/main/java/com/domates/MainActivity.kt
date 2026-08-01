package com.domates

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

private const val PREFS_NAME = "domates_prefs"
private const val KEY_SERVER_URL = "server_url"

class MainActivity : AppCompatActivity() {

    companion object {
        @Volatile
        var menemenClient: MenemenClient? = null
    }

    private lateinit var urlInput: EditText
    private lateinit var taskInput: EditText
    private lateinit var runButton: Button
    private lateinit var statusView: TextView
    private lateinit var permissionButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedUrl = prefs.getString(KEY_SERVER_URL, "ws://192.168.1.100:8765") ?: ""

        // Build UI programmatically — keeps the APK self-contained without a layout file.
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        TextView(this).apply {
            text = "DOMATES"
            textSize = 28f
            root.addView(this)
        }
        TextView(this).apply {
            text = "Mobile Agent — Execution Layer"
            textSize = 14f
            root.addView(this)
        }

        root.addView(Space(this).apply { minimumHeight = 48 })

        root.addView(TextView(this).apply { text = "MENEMEN Server URL:" })
        urlInput = EditText(this).apply {
            setText(savedUrl)
            hint = "ws://IP:8765"
            root.addView(this)
        }

        root.addView(Space(this).apply { minimumHeight = 16 })

        root.addView(TextView(this).apply { text = "Task:" })
        taskInput = EditText(this).apply {
            hint = "What should I do?"
            minLines = 3
            root.addView(this)
        }

        root.addView(Space(this).apply { minimumHeight = 16 })

        permissionButton = Button(this).apply {
            text = "Grant Accessibility Permission"
            setOnClickListener { openAccessibilitySettings() }
            root.addView(this)
        }

        runButton = Button(this).apply {
            text = "Run Task"
            setOnClickListener { onRunTask() }
            root.addView(this)
        }

        root.addView(Space(this).apply { minimumHeight = 16 })

        statusView = TextView(this).apply {
            text = "Status: idle"
            root.addView(this)
        }

        setContentView(root)
        updatePermissionButton()
    }

    private fun updatePermissionButton() {
        val active = DomatesAccessibilityService.instance != null
        permissionButton.text = if (active) "Accessibility: ACTIVE" else "Grant Accessibility Permission"
        permissionButton.isEnabled = !active
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun onRunTask() {
        val url = urlInput.text.toString().trim()
        val task = taskInput.text.toString().trim()

        if (url.isEmpty() || task.isEmpty()) {
            setStatus("Please fill in server URL and task.")
            return
        }

        // Persist URL
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_SERVER_URL, url)
            .apply()

        if (DomatesAccessibilityService.instance == null) {
            setStatus("Accessibility Service not active. Grant permission first.")
            return
        }

        startTask(url, task)
    }

    private fun startTask(url: String, task: String) {
        setStatus("Connecting to MENEMEN...")
        runButton.isEnabled = false

        menemenClient?.disconnect()

        val svc = DomatesAccessibilityService.instance!!
        val collector = svc.let {
            ScreenStateCollector(
                resources.displayMetrics.widthPixels,
                resources.displayMetrics.heightPixels
            )
        }
        val executor = ActionExecutor(
            svc,
            resources.displayMetrics.widthPixels,
            resources.displayMetrics.heightPixels
        )

        menemenClient = MenemenClient(
            serverUrl = url,
            collector = collector,
            executor = executor,
            onTaskResult = { ok, result ->
                runOnUiThread {
                    setStatus(if (ok) "Done: $result" else "Error: $result")
                    runButton.isEnabled = true
                }
            }
        ).also { it.connect(task) }

        setStatus("Task started: $task")
    }

    private fun setStatus(msg: String) {
        statusView.text = "Status: $msg"
    }

    override fun onResume() {
        super.onResume()
        updatePermissionButton()
    }
}
