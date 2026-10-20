package com.olhosdavo.geminilive

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var prefs: PrefsManager
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private val handler = Handler(Looper.getMainLooper())
    private var lastTriggerTime: Long = 0L

    private lateinit var btnGiantStart: LinearLayout
    private lateinit var tvStatusBanner: TextView
    private lateinit var btnFamilySettings: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = PrefsManager(this)
        tts = TextToSpeech(this, this)

        btnGiantStart = findViewById(R.id.btnGiantStart)
        tvStatusBanner = findViewById(R.id.tvStatusBanner)
        btnFamilySettings = findViewById(R.id.btnFamilySettings)

        // Toque em qualquer lugar do botão gigante inicia o Gemini Live + Câmera
        btnGiantStart.setOnClickListener {
            onGiantButtonTapped()
        }

        // Se a avó esbarrar com toque curto no botão do canto, também abre o Gemini Live!
        btnFamilySettings.setOnClickListener {
            onGiantButtonTapped()
        }

        // Apenas SEGURAR (toque longo) no botão do canto abre as Configurações da Família
        btnFamilySettings.setOnLongClickListener {
            vibrateShort()
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
    }

    override fun onResume() {
        super.onResume()
        val enabled = GeminiLiveAccessibilityService.isServiceEnabled(this)
        tvStatusBanner.visibility = if (enabled) View.GONE else View.VISIBLE

        // Se o modo "0 cliques" (Iniciar direto ao abrir o ícone) estiver ativo
        val now = System.currentTimeMillis()
        if (enabled && prefs.autoLaunchOnOpen && (now - lastTriggerTime > 6000L)) {
            lastTriggerTime = now
            handler.postDelayed({
                startGeminiLiveAutomation()
            }, 350L)
        }
    }

    private fun onGiantButtonTapped() {
        vibrateShort()

        if (!GeminiLiveAccessibilityService.isServiceEnabled(this)) {
            speakOut("Peça para alguém da família ativar a permissão de acessibilidade na primeira vez.")
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        startGeminiLiveAutomation()
    }

    private fun startGeminiLiveAutomation() {
        lastTriggerTime = System.currentTimeMillis()

        if (prefs.voiceFeedback) {
            speakOut("Abrindo câmera do Gemini, aguarde.")
        }

        val service = GeminiLiveAccessibilityService.instance
        if (service != null) {
            // Se a voz estiver ativa, dá 600ms para o início do aviso sonoro antes de trocar de app
            val delay = if (prefs.voiceFeedback && isTtsReady) 600L else 100L
            handler.postDelayed({
                service.startAutomationSession()
            }, delay)
        } else {
            Toast.makeText(
                this,
                "Reinicie o Serviço de Acessibilidade nas configurações.",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("pt", "BR"))
            isTtsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    private fun speakOut(message: String) {
        if (isTtsReady) {
            tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "olhos_da_vo_tts")
        }
    }

    private fun vibrateShort() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(90L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(90L)
            }
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
