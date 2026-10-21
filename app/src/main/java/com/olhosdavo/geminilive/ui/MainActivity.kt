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
 * Tela Principal (Visual Moderno Escuro e Alto Contraste):
 * Card gigante de 1 toque para a avó + suporte ao Widget 2x2 na tela inicial.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: PrefsManager
    private lateinit var feedbackHelper: FeedbackHelper
    private val handler = Handler(Looper.getMainLooper())
    private var lastTriggerTime: Long = 0L
    private var launchedFromWidget: Boolean = false

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

        checkWidgetIntent(intent)

        // Toque em qualquer lugar do botão gigante inicia o Gemini Live + Câmera
        btnGiantStart.setOnClickListener {
            onGiantButtonTapped()
        }

        // Botão Configurar no topo abre as configurações
        btnFamilySettings.setOnClickListener {
            FeedbackHelper.vibrateShort(this)
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        checkWidgetIntent(intent)
    }

    private fun checkWidgetIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(OlhosDaVoWidgetProvider.EXTRA_FROM_WIDGET, false) == true) {
            launchedFromWidget = true
            intent.removeExtra(OlhosDaVoWidgetProvider.EXTRA_FROM_WIDGET)
        }
    }

    override fun onResume() {
        super.onResume()
        val enabled = GeminiLiveAccessibilityService.isServiceEnabled(this)
        tvStatusBanner.visibility = if (enabled) View.GONE else View.VISIBLE

        // Se ela clicou no Widget 2x2 na tela inicial, já dispara direto!
        if (launchedFromWidget) {
            launchedFromWidget = false
            handler.postDelayed({
                onGiantButtonTapped()
            }, 150L)
            return
        }

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
            val delay = if (prefs.voiceFeedback) 550L else 100L
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
