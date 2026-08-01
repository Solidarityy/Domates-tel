package com.domates

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

private const val REQ_AUDIO = 4001

/**
 * Bildirimden açılan sesli komut overlay'i.
 * Açılır açılmaz dinlemeye başlar, sonuç gelince kapanır.
 */
class VoiceCommandActivity : AppCompatActivity() {

    private var taniyici: SpeechRecognizer? = null
    private lateinit var durum: TextView
    private lateinit var ikon: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Dialog gibi davransın ama tam ekran değil
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.also { it.dimAmount = 0.6f }

        val kok = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }

        val kart = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(36), dp(32), dp(28))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(Color.WHITE)
            }
            elevation = dp(8).toFloat()
        }

        ikon = TextView(this).apply {
            text = "🎤"; textSize = 52f; gravity = Gravity.CENTER
        }
        kart.addView(ikon)

        kart.addView(Space(this).apply { minimumHeight = dp(12) })

        durum = TextView(this).apply {
            text = "Hazırlanıyor..."
            textSize = 20f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#212121"))
            setTypeface(null, Typeface.BOLD)
        }
        kart.addView(durum)

        kart.addView(Space(this).apply { minimumHeight = dp(6) })

        TextView(this).apply {
            text = "Türkçe komutunuzu söyleyin"
            textSize = 13f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#9E9E9E"))
            kart.addView(this)
        }

        kart.addView(Space(this).apply { minimumHeight = dp(24) })

        Button(this).apply {
            text = "İptal"
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            setTextColor(Color.parseColor("#9E9E9E"))
            setOnClickListener { finish() }
            kart.addView(this)
        }

        val kartParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
            leftMargin = dp(40); rightMargin = dp(40)
        }
        kok.addView(kart, kartParams)

        // Kartın dışına basınca kapat
        kok.setOnClickListener { if (it == kok) finish() }

        setContentView(kok)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            dinlemeBaslat()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
        }
    }

    private fun dinlemeBaslat() {
        taniyici = SpeechRecognizer.createSpeechRecognizer(this)
        taniyici?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) = runOnUiThread {
                durum.text = "Dinliyorum..."; ikon.text = "🔴"
            }
            override fun onResults(sonuclar: Bundle?) {
                val eslesme = sonuclar?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!eslesme.isNullOrEmpty()) {
                    val gorev = eslesme[0]
                    runOnUiThread { durum.text = gorev; ikon.text = "✅" }
                    goreviBaslat(gorev)
                    window.decorView.postDelayed({ finish() }, 1800)
                } else {
                    finish()
                }
            }
            override fun onError(hata: Int) {
                val mesaj = when (hata) {
                    SpeechRecognizer.ERROR_NO_MATCH      -> "Anlaşılamadı"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Ses algılanamadı"
                    SpeechRecognizer.ERROR_NETWORK        -> "Ağ hatası"
                    else                                  -> "Hata ($hata)"
                }
                runOnUiThread { durum.text = mesaj; ikon.text = "❌" }
                window.decorView.postDelayed({ finish() }, 1800)
            }
            override fun onBeginningOfSpeech() {}
            override fun onEndOfSpeech() {}
            override fun onBufferReceived(p: ByteArray?) {}
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(p: Int, p1: Bundle?) {}
            override fun onRmsChanged(p: Float) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        taniyici?.startListening(intent)
    }

    private fun goreviBaslat(gorev: String) {
        val svc = DomatesAccessibilityService.instance ?: return
        val prefs = getSharedPreferences("domates_prefs", MODE_PRIVATE)
        val url = prefs.getString("server_url", "ws://127.0.0.1:8765") ?: return

        val genislik = resources.displayMetrics.widthPixels
        val yukseklik = resources.displayMetrics.heightPixels

        MainActivity.menemenClient?.disconnect()
        MainActivity.menemenClient = MenemenClient(
            serverUrl = url,
            collector = ScreenStateCollector(genislik, yukseklik),
            executor = ActionExecutor(svc, genislik, yukseklik),
            onTaskResult = { tamam, sonuc ->
                val appCtx = applicationContext
                val nm = appCtx.getSystemService(NotificationManager::class.java)
                val bildirim = Notification.Builder(appCtx, "domates_aktif")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(if (tamam) "✅ Görev Tamamlandı" else "❌ Görev Başarısız")
                    .setContentText(sonuc)
                    .setAutoCancel(true)
                    .build()
                nm.notify(99, bildirim)
            }
        ).also { it.connect(gorev) }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO
            && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            dinlemeBaslat()
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        taniyici?.destroy()
        super.onDestroy()
    }

    private fun dp(v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
