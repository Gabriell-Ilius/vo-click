package com.olhosdavo.geminilive.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.olhosdavo.geminilive.R
import com.olhosdavo.geminilive.data.PrefsManager
import com.olhosdavo.geminilive.service.GeminiLiveAccessibilityService
import com.olhosdavo.geminilive.util.FeedbackHelper

/**
 * Tela Principal (Alto Contraste Preto/Amarelo):
 * Transforma 100% da tela do celular em um único botão gigante para a avó.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: PrefsManager
    private lateinit var feedbackHelper: FeedbackHelper
    private val handler = Handler(Looper.getMainLooper())
    private var lastTriggerTime: Long = 0L

    private lateinit var btnGiantStart: LinearLayout
    private lateinit var tvStatusBanner: TextView
    private lateinit var btnFamilySettings: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = PrefsManager(this)
        feedbackHelper = FeedbackHelper(this)

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
            FeedbackHelper.vibrateShort(this)
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
        FeedbackHelper.vibrateShort(this)

        if (!GeminiLiveAccessibilityService.isServiceEnabled(this)) {
            feedbackHelper.speak(
                "Peça para alguém da família ativar a permissão de acessibilidade na primeira vez."
            )
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        startGeminiLiveAutomation()
    }

    private fun startGeminiLiveAutomation() {
        lastTriggerTime = System.currentTimeMillis()

        if (prefs.voiceFeedback) {
            feedbackHelper.speak("Abrindo câmera do Gemini, aguarde.")
        }

        val service = GeminiLiveAccessibilityService.instance
        if (service != null) {
            val delay = if (prefs.voiceFeedback && feedbackHelper.isTtsReady) 600L else 100L
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

    override fun onDestroy() {
        feedbackHelper.shutdown()
        super.onDestroy()
    }
}
