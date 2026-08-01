package com.domates

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*

private const val PREFS_NAME = "domates_prefs"
private const val KEY_SERVER_URL = "server_url"
private const val REQ_AUDIO = 3001

class MainActivity : AppCompatActivity() {

    companion object {
        @Volatile
        var menemenClient: MenemenClient? = null
    }

    // ── Renkler ──────────────────────────────────────────────────────────
    private val C_KIRMIZI     = Color.parseColor("#C62828")
    private val C_KIRMIZI_ORT = Color.parseColor("#E53935")
    private val C_KIRMIZI_ACI = Color.parseColor("#FFEBEE")
    private val C_ARKA_PLAN   = Color.parseColor("#F2F2F2")
    private val C_KART        = Color.WHITE
    private val C_YAZI        = Color.parseColor("#212121")
    private val C_YAZI_GERI   = Color.parseColor("#757575")
    private val C_YESIL       = Color.parseColor("#2E7D32")
    private val C_MAVI        = Color.parseColor("#283593")
    private val C_MAVI_ACI    = Color.parseColor("#E8EAF6")

    // ── Widget referansları ───────────────────────────────────────────────
    private lateinit var sunucuAlani: EditText
    private lateinit var gorevAlani: EditText
    private lateinit var calistirBtn: Button
    private lateinit var sesliBtn: Button
    private lateinit var izinBtn: Button
    private lateinit var durumNokta: View
    private lateinit var durumEtiket: TextView
    private lateinit var logKutu: TextView
    private lateinit var baglantiIcerik: LinearLayout
    private lateinit var logIcerik: LinearLayout

    private var speechRecognizer: SpeechRecognizer? = null
    private val logSatirlar = ArrayDeque<String>()

    // ─────────────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val kayitliUrl = prefs.getString(KEY_SERVER_URL, "ws://127.0.0.1:8765") ?: ""

        val kaydirma = ScrollView(this).apply { setBackgroundColor(C_ARKA_PLAN) }
        val kok = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(32))
        }

        kok.addView(ustBaslik())
        kok.addView(baglantiKarti(kayitliUrl))
        kok.addView(gorevKarti())
        kok.addView(logKarti())

        kaydirma.addView(kok)
        setContentView(kaydirma)
        durumYenile()
    }

    // ─── ÜST BAŞLIK ──────────────────────────────────────────────────────
    private fun ustBaslik(): View {
        val satir = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(C_KIRMIZI)
            setPadding(dp(20), dp(40), dp(20), dp(20))
            gravity = Gravity.CENTER_VERTICAL
        }

        val sol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, lp_wrap, 1f)
        }
        sol.addView(TextView(this).apply {
            text = "DOMATES"
            textSize = 32f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.1f
        })
        sol.addView(TextView(this).apply {
            text = "Mobil Ajan — Çalışma Katmanı"
            textSize = 12f
            setTextColor(Color.parseColor("#FFCDD2"))
        })
        satir.addView(sol)

        val sag = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, 0, 0)
        }
        durumNokta = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).also {
                it.gravity = Gravity.CENTER_HORIZONTAL
                it.bottomMargin = dp(4)
            }
            background = daire(C_YAZI_GERI)
        }
        sag.addView(durumNokta)
        durumEtiket = TextView(this).apply {
            text = "Pasif"
            textSize = 10f
            setTextColor(Color.parseColor("#FFCDD2"))
            gravity = Gravity.CENTER
        }
        sag.addView(durumEtiket)
        satir.addView(sag)

        return satir
    }

    // ─── BAĞLANTI KARTI ──────────────────────────────────────────────────
    private fun baglantiKarti(kayitliUrl: String): View {
        val icerik = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        var acik = true
        val chevron = TextView(this).apply {
            text = "▲"; setTextColor(C_YAZI_GERI); textSize = 13f
        }

        val baslikSatir = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        baslikSatir.addView(TextView(this).apply {
            text = "📡  Sunucu Bağlantısı"
            textSize = 15f; setTextColor(C_YAZI); setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, lp_wrap, 1f)
        })
        baslikSatir.addView(chevron)
        icerik.addView(baslikSatir)

        baglantiIcerik = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        baglantiIcerik.addView(ayrac())
        baglantiIcerik.addView(etiket("MENEMEN Sunucu Adresi"))
        sunucuAlani = girdiBol(kayitliUrl, "ws://127.0.0.1:8765")
        baglantiIcerik.addView(sunucuAlani)

        baglantiIcerik.addView(Space(this).apply { minimumHeight = dp(12) })
        izinBtn = buton("⚙️  Erişilebilirlik Servisini Aç", C_KIRMIZI_ACI, C_KIRMIZI).apply {
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        baglantiIcerik.addView(izinBtn)

        icerik.addView(baglantiIcerik)

        baslikSatir.setOnClickListener {
            acik = !acik
            baglantiIcerik.visibility = if (acik) View.VISIBLE else View.GONE
            chevron.text = if (acik) "▲" else "▼"
        }

        return kartSar(icerik)
    }

    // ─── GÖREV KARTI ─────────────────────────────────────────────────────
    private fun gorevKarti(): View {
        val icerik = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        icerik.addView(TextView(this).apply {
            text = "🎯  Görev"
            textSize = 15f; setTextColor(C_YAZI); setTypeface(null, Typeface.BOLD)
        })
        icerik.addView(Space(this).apply { minimumHeight = dp(12) })

        gorevAlani = EditText(this).apply {
            hint = "Ne yapmamı istiyorsunuz? (Türkçe yazabilirsiniz)"
            textSize = 14f; setTextColor(C_YAZI); minLines = 3; maxLines = 6
            background = girdiArkaplan()
            setPadding(dp(12), dp(10), dp(12), dp(10))
            gravity = Gravity.TOP
            layoutParams = LinearLayout.LayoutParams(lp_match, lp_wrap)
        }
        icerik.addView(gorevAlani)

        icerik.addView(Space(this).apply { minimumHeight = dp(14) })

        val butonSatir = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        sesliBtn = buton("🎤  Sesli Komut", C_MAVI_ACI, C_MAVI).apply {
            layoutParams = LinearLayout.LayoutParams(0, lp_wrap, 1f).also { it.rightMargin = dp(8) }
            setOnClickListener { sesliKomutBaslat() }
        }
        butonSatir.addView(sesliBtn)

        calistirBtn = buton("▶  Çalıştır", C_KIRMIZI, Color.WHITE).apply {
            layoutParams = LinearLayout.LayoutParams(0, lp_wrap, 2f)
            setTypeface(null, Typeface.BOLD)
            setOnClickListener { goreviCalistir() }
        }
        butonSatir.addView(calistirBtn)

        icerik.addView(butonSatir)
        return kartSar(icerik)
    }

    // ─── LOG / DURUM KARTI ───────────────────────────────────────────────
    private fun logKarti(): View {
        val icerik = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        var acik = true
        val chevron = TextView(this).apply {
            text = "▲"; setTextColor(C_YAZI_GERI); textSize = 13f
        }

        val baslikSatir = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        baslikSatir.addView(TextView(this).apply {
            text = "📋  Durum & Günlük"
            textSize = 15f; setTextColor(C_YAZI); setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, lp_wrap, 1f)
        })
        baslikSatir.addView(chevron)
        icerik.addView(baslikSatir)

        logIcerik = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        logIcerik.addView(ayrac())

        logKutu = TextView(this).apply {
            text = "Bekleniyor..."
            textSize = 12f; setTextColor(C_YAZI_GERI)
            setTypeface(Typeface.MONOSPACE)
            setBackgroundColor(Color.parseColor("#FAFAFA"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(lp_match, lp_wrap)
        }
        logIcerik.addView(logKutu)

        logIcerik.addView(Space(this).apply { minimumHeight = dp(6) })
        val temizleBtn = buton("🗑  Günlüğü Temizle", Color.parseColor("#FAFAFA"), C_YAZI_GERI).apply {
            textSize = 12f
            setOnClickListener { logSatirlar.clear(); logKutu.text = "Günlük temizlendi." }
        }
        logIcerik.addView(temizleBtn)

        icerik.addView(logIcerik)

        baslikSatir.setOnClickListener {
            acik = !acik
            logIcerik.visibility = if (acik) View.VISIBLE else View.GONE
            chevron.text = if (acik) "▲" else "▼"
        }

        return kartSar(icerik)
    }

    // ─── GÖREV ÇALIŞTIR ──────────────────────────────────────────────────
    private fun goreviCalistir() {
        val url = sunucuAlani.text.toString().trim()
        val gorev = gorevAlani.text.toString().trim()

        if (url.isEmpty()) { log("❌ Sunucu adresi boş olamaz."); return }
        if (gorev.isEmpty()) { log("❌ Görev alanı boş olamaz."); return }
        if (DomatesAccessibilityService.instance == null) {
            log("❌ Erişilebilirlik servisi aktif değil!")
            log("   Lütfen 'Erişilebilirlik Servisini Aç' butonuna basın.")
            durumYenile()
            return
        }

        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SERVER_URL, url).apply()

        log("🔗 Bağlanılıyor: $url")
        log("🎯 Görev başlatıldı: $gorev")
        calistirBtn.isEnabled = false

        menemenClient?.disconnect()

        val svc = DomatesAccessibilityService.instance!!
        val genislik = resources.displayMetrics.widthPixels
        val yukseklik = resources.displayMetrics.heightPixels
        val toplayici = ScreenStateCollector(genislik, yukseklik)
        val yurutucuy = ActionExecutor(svc, genislik, yukseklik)

        menemenClient = MenemenClient(
            serverUrl = url,
            collector = toplayici,
            executor = yurutucuy,
            onTaskResult = { tamam, sonuc ->
                runOnUiThread {
                    if (tamam) log("✅ Görev tamamlandı: $sonuc")
                    else log("❌ Hata: $sonuc")
                    calistirBtn.isEnabled = true
                }
            }
        ).also { it.connect(gorev) }
    }

    // ─── SESLİ KOMUT ─────────────────────────────────────────────────────
    private fun sesliKomutBaslat() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            log("⚠️ Bu cihazda ses tanıma desteklenmiyor.")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            return
        }

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {
                runOnUiThread { sesliBtn.text = "🔴  Dinliyor..." }
            }
            override fun onResults(sonuclar: Bundle?) {
                val eslesme = sonuclar?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!eslesme.isNullOrEmpty()) {
                    runOnUiThread {
                        gorevAlani.setText(eslesme[0])
                        log("🎤 Algılandı: ${eslesme[0]}")
                    }
                }
                runOnUiThread { sesliBtn.text = "🎤  Sesli Komut" }
            }
            override fun onError(hata: Int) {
                val mesaj = when (hata) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "Ses anlaşılamadı, tekrar deneyin."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Ses algılanamadı."
                    SpeechRecognizer.ERROR_NETWORK -> "Ağ hatası."
                    else -> "Hata kodu: $hata"
                }
                runOnUiThread { log("🎤 ⚠️ $mesaj"); sesliBtn.text = "🎤  Sesli Komut" }
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
        speechRecognizer?.startListening(intent)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO
            && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            sesliKomutBaslat()
        } else if (requestCode == REQ_AUDIO) {
            log("⚠️ Mikrofon izni reddedildi.")
        }
    }

    // ─── DURUM YENİLE ────────────────────────────────────────────────────
    private fun durumYenile() {
        val aktif = DomatesAccessibilityService.instance != null
        durumNokta.background = daire(if (aktif) C_YESIL else C_KIRMIZI_ORT)
        durumEtiket.text = if (aktif) "Aktif" else "Pasif"
        if (::izinBtn.isInitialized) {
            izinBtn.visibility = if (aktif) View.GONE else View.VISIBLE
        }
    }

    private fun log(mesaj: String) {
        val saat = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logSatirlar.addLast("[$saat] $mesaj")
        if (logSatirlar.size > 80) logSatirlar.removeFirst()
        logKutu.text = logSatirlar.takeLast(12).joinToString("\n")
    }

    override fun onResume() {
        super.onResume()
        durumYenile()
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        super.onDestroy()
    }

    // ─── UI YARDIMCILARI ─────────────────────────────────────────────────

    private fun kartSar(icerik: LinearLayout): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(lp_match, lp_wrap).also {
                it.setMargins(dp(16), dp(8), dp(16), dp(8))
            }
            setBackgroundColor(C_KART)
            elevation = dp(2).toFloat()
            addView(icerik)
        }
    }

    private fun buton(metin: String, arkaplan: Int, yazRenk: Int) = Button(this).apply {
        text = metin
        setBackgroundColor(arkaplan)
        setTextColor(yazRenk)
        textSize = 13f
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(lp_match, lp_wrap)
    }

    private fun girdiBol(deger: String, ipucu: String) = EditText(this).apply {
        setText(deger); hint = ipucu
        textSize = 14f; setTextColor(C_YAZI)
        background = girdiArkaplan()
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(lp_match, lp_wrap).also { it.topMargin = dp(6) }
    }

    private fun etiket(metin: String) = TextView(this).apply {
        text = metin; textSize = 12f; setTextColor(C_YAZI_GERI)
        layoutParams = LinearLayout.LayoutParams(lp_match, lp_wrap).also { it.topMargin = dp(10) }
    }

    private fun ayrac() = View(this).apply {
        setBackgroundColor(Color.parseColor("#EEEEEE"))
        layoutParams = LinearLayout.LayoutParams(lp_match, dp(1)).also {
            it.topMargin = dp(8); it.bottomMargin = dp(8)
        }
    }

    private fun daire(renk: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(renk)
    }

    private fun girdiArkaplan() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = dp(6).toFloat()
        setColor(Color.parseColor("#F8F8F8"))
        setStroke(dp(1), Color.parseColor("#DDDDDD"))
    }

    private fun dp(deger: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, deger.toFloat(), resources.displayMetrics).toInt()

    private val lp_match = LinearLayout.LayoutParams.MATCH_PARENT
    private val lp_wrap  = LinearLayout.LayoutParams.WRAP_CONTENT
}
